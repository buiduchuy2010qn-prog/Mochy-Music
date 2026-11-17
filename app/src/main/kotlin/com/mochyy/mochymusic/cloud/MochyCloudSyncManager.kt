package com.mochyy.mochymusic.cloud

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.constants.AudioNormalizationKey
import com.mochyy.mochymusic.constants.AudioQualityKey
import com.mochyy.mochymusic.constants.ProfileCardNumberKey
import com.mochyy.mochymusic.constants.ProfileJoinedEpochDayKey
import com.mochyy.mochymusic.constants.ProfilePicUriKey
import com.mochyy.mochymusic.constants.UsernameKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.AlbumArtistMap
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Event
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.db.entities.PlaylistEntity
import com.mochyy.mochymusic.db.entities.PlaylistSongMap
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.di.AudioLabEntryPoint
import com.mochyy.mochymusic.di.CloudSyncEntryPoint
import com.mochyy.mochymusic.eq.AudioLabStore
import com.mochyy.mochymusic.eq.EqSettings
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.screens.profile.ProfileStore
import com.mochyy.mochymusic.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class CloudPlaybackResume(
    val deviceId: String,
    val deviceName: String,
    val songId: String,
    val title: String,
    val artists: String,
    val albumName: String?,
    val durationSec: Int,
    val thumbnailUrl: String?,
    val positionMs: Long,
    val queueTracks: List<CloudPlaylistTrack>,
    val updatedAtEpochMs: Long,
)

data class MochyCloudState(
    val isConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val avatarLocalPath: String? = null,
    val favoritesCount: Int = 0,
    val playlistsCount: Int = 0,
    val albumsCount: Int = 0,
    val artistsCount: Int = 0,
    val historyCount: Int = 0,
    val syncing: Boolean = false,
    val lastSyncEpochMs: Long = 0L,
    val lastError: String? = null,
    val remoteResume: CloudPlaybackResume? = null,
)

/**
 * Điều phối Mochy Cloud gọn nhẹ:
 * - Đồng bộ hai chiều Yêu thích (`mochy_favorites`), Playlist cá nhân (`user_metadata.playlists_gz`),
 *   Album đã lưu, Nghệ sĩ theo dõi & Equalizer (`user_metadata.library_extra_gz`),
 *   Lịch sử nghe (`mochy_history`), Thẻ ID (`user_metadata`) & Ảnh đại diện WebP.
 * - Hàng đợi gộp lịch sử nghe (debounce 45s hoặc đủ 10 bài, lưu bền vững chống mất dữ liệu).
 */
object MochyCloudSyncManager {

    private const val HistoryBatchFlushCount = 10
    private const val HistoryBatchDebounceMs = 45_000L
    private const val MaxCloudExtraItems = 200

    private val audioLabJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(MochyCloudState())
    val state: StateFlow<MochyCloudState> = _state.asStateFlow()

    private val syncMutex = Mutex()
    private val sessionRefreshMutex = Mutex()
    private val historyFlushMutex = Mutex()
    private val historyPlayCountCache = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val pendingHistoryMap = ConcurrentHashMap<String, CloudHistoryRecord>()
    private var appContext: Context? = null
    private var appScope: CoroutineScope? = null
    private var favoritesObserverJob: Job? = null
    private var playlistsObserverJob: Job? = null
    private var historyFlushJob: Job? = null
    private var favoriteToggleSyncJob: Job? = null
    private var suppressObserverUntilMs: Long = 0L

    @Volatile
    private var lastSessionFailureReason: String? = null

    @Volatile
    private var lastSessionExpired: Boolean = false

    @Volatile
    private var cachedDeviceId: String? = null

    private const val DeviceIdFileName = "mochy_cloud_device_id.txt"

    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        appContext = app
        appScope = scope
        val configured = MochyCloudVault.isConfigured(app)
        val cached = MochyCloudVault.loadSession(app)
        _state.value = _state.value.copy(
            isConfigured = configured,
            signedIn = configured && cached != null,
            username = cached?.username.orEmpty(),
            displayName = cached?.displayName.orEmpty(),
        )
        if (configured && cached != null) {
            MochyCloudVault.loadPendingHistory(app, cached.userId).forEach { rec ->
                pendingHistoryMap[rec.songId] = rec
                historyPlayCountCache[rec.songId] = rec.playCount to rec.totalPlayMs
            }
            startObservers(app, scope)
            scope.launch(Dispatchers.IO) {
                runCatching { database(app).purgeBlankAlbumRows() }
                readLocalAvatarFile(app)?.let { local ->
                    _state.value = _state.value.copy(avatarLocalPath = local.absolutePath)
                }
                refreshLocalStats(app)
                syncNowInternal(app, fromUser = false)
            }
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching { database(app).purgeBlankAlbumRows() }
                refreshLocalStats(app)
            }
        }
    }

    suspend fun signUp(
        context: Context,
        username: String,
        password: String,
        displayName: String,
        avatarUri: Uri? = null,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val session = MochySupabaseClient.signUp(app, username, password, displayName)
            MochyCloudVault.saveSession(app, session)
            var localAvatarPath: String? = null
            if (avatarUri != null) {
                val webpDataUri = MochyAvatarCodec.encodeUriToWebpDataUri(app, avatarUri)
                val localAvatarFile = if (webpDataUri != null) {
                    runCatching {
                        MochySupabaseClient.updateProfile(app, session, session.displayName, webpDataUri)
                    }
                    MochyAvatarCodec.decodeDataUriToFile(app, webpDataUri)
                } else null
                localAvatarPath = localAvatarFile?.absolutePath
                ProfileStore.save(
                    app,
                    session.displayName,
                    localAvatarFile?.let(Uri::fromFile) ?: avatarUri,
                    syncToCloud = false,
                )
            } else {
                ProfileStore.save(app, session.displayName, null, syncToCloud = false)
            }
            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = session.username,
                displayName = session.displayName,
                avatarLocalPath = localAvatarPath,
                lastError = null,
            )
            lastSessionFailureReason = null
            lastSessionExpired = false
            appScope?.let { startObservers(app, it) }
            syncNowInternal(app, fromUser = true)
        }.onFailure { err ->
            _state.value = _state.value.copy(lastError = err.message)
        }
    }

    suspend fun signIn(
        context: Context,
        username: String,
        password: String,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val session = MochySupabaseClient.signIn(app, username, password)
            MochyCloudVault.saveSession(app, session)
            val existingAvatar = readLocalAvatarFile(app)
            ProfileStore.save(app, session.displayName, null, syncToCloud = false)
            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = session.username,
                displayName = session.displayName,
                avatarLocalPath = existingAvatar?.absolutePath ?: _state.value.avatarLocalPath,
                lastError = null,
            )
            lastSessionFailureReason = null
            lastSessionExpired = false
            appScope?.let { startObservers(app, it) }
            syncNowInternal(app, fromUser = true)
        }.onFailure { err ->
            _state.value = _state.value.copy(lastError = err.message)
        }
    }

    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        cancelBackgroundJobs()
        pendingHistoryMap.clear()
        val old = MochyCloudVault.loadSession(app)
        MochyCloudVault.clearSession(app)
        historyPlayCountCache.clear()
        lastSessionFailureReason = null
        lastSessionExpired = false
        if (old != null) {
            runCatching { MochySupabaseClient.signOutRemote(app, old) }
        }
        _state.value = MochyCloudState(isConfigured = MochyCloudVault.isConfigured(app))
        refreshLocalStats(app)
    }

    suspend fun updateProfileAndAvatar(
        context: Context,
        newDisplayName: String?,
        avatarUri: Uri?,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val session = getValidSession(app)
                ?: throw MochyCloudException(lastSessionFailureReason ?: "Bạn chưa đăng nhập Mochy Cloud.")
            var webpDataUri: String? = null
            if (avatarUri != null) {
                webpDataUri = MochyAvatarCodec.encodeUriToWebpDataUri(app, avatarUri)
                    ?: throw MochyCloudException("Không thể nén ảnh đại diện để tải lên.")
            }
            MochySupabaseClient.updateProfile(app, session, newDisplayName, webpDataUri)
            val updatedName = newDisplayName?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: session.displayName
            val latestSession = MochyCloudVault.loadSession(app)?.takeIf { it.userId == session.userId } ?: session
            MochyCloudVault.saveSession(app, latestSession.copy(displayName = updatedName))
            val localFile = if (webpDataUri != null) {
                MochyAvatarCodec.decodeDataUriToFile(app, webpDataUri)
            } else null
            if (newDisplayName != null || localFile != null) {
                ProfileStore.save(app, updatedName, localFile?.let(Uri::fromFile), syncToCloud = false)
            }
            _state.value = _state.value.copy(
                displayName = updatedName,
                avatarLocalPath = localFile?.absolutePath ?: _state.value.avatarLocalPath,
                lastError = null,
            )
        }.onFailure { err ->
            if (err is MochyCloudAuthExpiredException) {
                handleSessionExpired(context.applicationContext, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
            } else {
                _state.value = _state.value.copy(lastError = err.message)
            }
        }
    }

    suspend fun changePassword(
        context: Context,
        newPassword: String,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val session = getValidSession(app)
                ?: throw MochyCloudException(lastSessionFailureReason ?: "Bạn chưa đăng nhập Mochy Cloud.")
            MochySupabaseClient.updatePassword(app, session, newPassword)
            Unit
        }.onFailure { err ->
            if (err is MochyCloudAuthExpiredException) {
                handleSessionExpired(context.applicationContext, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
            }
        }
    }

    suspend fun clearAllCloudData(context: Context): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                val app = context.applicationContext
                val session = getValidSession(app)
                    ?: throw MochyCloudException(lastSessionFailureReason ?: "Bạn chưa đăng nhập Mochy Cloud.")
                historyFlushJob?.cancel()
                pendingHistoryMap.clear()
                historyPlayCountCache.clear()
                MochyCloudVault.savePendingHistory(app, session.userId, emptyList())
                MochyCloudVault.saveSyncedFavoriteIds(app, session.userId, emptySet())
                MochyCloudVault.saveSyncedPlaylistIds(app, session.userId, emptySet())
                MochyCloudVault.saveSyncedPlaylistsDigest(app, session.userId, null)
                MochyCloudVault.saveSyncedAlbumIds(app, session.userId, emptySet())
                MochyCloudVault.saveSyncedArtistIds(app, session.userId, emptySet())
                MochyCloudVault.saveSyncedEqDigest(app, session.userId, null)
                MochySupabaseClient.wipeCloudData(app, session)
                ProfileStore.clearPhoto(app)
                ProfileStore.save(app, session.displayName, null, syncToCloud = false)
                _state.value = _state.value.copy(
                    avatarLocalPath = null,
                    favoritesCount = 0,
                    playlistsCount = 0,
                    albumsCount = 0,
                    artistsCount = 0,
                    historyCount = 0,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    lastError = null,
                    remoteResume = null,
                )
            }.onFailure { err ->
                if (err is MochyCloudAuthExpiredException) {
                    handleSessionExpired(context.applicationContext, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
                }
            }
        }
    }

    fun syncNow(context: Context, fromUser: Boolean = true) {
        val scope = appScope ?: return
        scope.launch(Dispatchers.IO) {
            syncNowInternal(context.applicationContext, fromUser = fromUser)
        }
    }

    fun localDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        val app = context.applicationContext
        val file = File(app.noBackupFilesDir, DeviceIdFileName)
        val persisted = runCatching { file.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (persisted != null) {
            cachedDeviceId = persisted
            return persisted
        }
        val androidId = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim().orEmpty()
        val seed = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            "mochy:$androidId:${Build.MANUFACTURER}:${Build.MODEL}"
        } else {
            "mochy-uuid:${UUID.randomUUID()}"
        }
        val derived = sha256Hex(seed).take(24)
        runCatching { file.writeText(derived, Charsets.UTF_8) }
        cachedDeviceId = derived
        return derived
    }

    fun localDeviceName(): String =
        Build.MODEL?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: "Thiết bị Mochy"

    suspend fun pushPlaybackResume(
        context: Context,
        song: MediaMetadata,
        positionMs: Long,
        queueTracks: List<CloudPlaylistTrack>,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            if (song.localUri != null || !isCloudSafeId(song.id)) {
                throw MochyCloudException("Chỉ hỗ trợ lưu vị trí đang nghe cho bài hát trực tuyến.")
            }
            val session = getValidSession(app)
                ?: throw MochyCloudException(lastSessionFailureReason ?: "Bạn chưa đăng nhập Mochy Cloud.")

            val sanitizedQueue = queueTracks
                .asSequence()
                .filter { isCloudSafeId(it.songId) && it.songId != song.id }
                .distinctBy { it.songId }
                .take(25)
                .map { t ->
                    t.copy(
                        title = t.title.trim().ifBlank { "Bài hát" }.take(120),
                        artists = t.artists.trim().ifBlank { "Nghệ sĩ" }.take(120),
                        albumName = t.albumName?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.take(120),
                        thumbnailUrl = sanitizeHttpUrl(t.thumbnailUrl),
                    )
                }
                .toList()

            val resume = CloudPlaybackResume(
                deviceId = localDeviceId(app),
                deviceName = localDeviceName(),
                songId = song.id,
                title = song.title.trim().ifBlank { "Bài hát" }.take(140),
                artists = song.artists.joinToString(", ") { it.name }.trim().ifBlank { "Nghệ sĩ" }.take(140),
                albumName = song.album?.title?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.take(120),
                durationSec = song.duration.coerceIn(-1, 86_400),
                thumbnailUrl = sanitizeHttpUrl(song.thumbnailUrl),
                positionMs = positionMs.coerceAtLeast(0L),
                queueTracks = sanitizedQueue,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            val encoded = MochyPlaybackResumeCodec.encode(resume)
            if (encoded.isBlank()) {
                throw MochyCloudException("Không thể đóng gói vị trí đang nghe.")
            }
            MochySupabaseClient.updateUserMetadata(
                context = app,
                session = session,
                playbackResumeGz = encoded,
            )
            _state.value = _state.value.copy(
                remoteResume = resume,
                lastError = null,
            )
        }.onFailure { err ->
            if (err is MochyCloudAuthExpiredException) {
                handleSessionExpired(context.applicationContext, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
            } else {
                _state.value = _state.value.copy(lastError = err.message)
            }
        }
    }

    suspend fun refreshRemoteResume(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val session = getValidSession(app) ?: return@withContext
        runCatching {
            val remoteMeta = MochySupabaseClient.fetchUserMetadata(app, session) ?: return@runCatching
            val decoded = MochyPlaybackResumeCodec.decode(remoteMeta.playbackResumeGz)
            val current = _state.value.remoteResume
            val next = when {
                remoteMeta.playbackResumeGz.isNullOrBlank() -> null
                decoded != null && current != null && current.updatedAtEpochMs > decoded.updatedAtEpochMs -> current
                else -> decoded
            }
            _state.value = _state.value.copy(remoteResume = next)
        }.onFailure { err ->
            if (err is MochyCloudAuthExpiredException) {
                handleSessionExpired(app, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
            }
        }
    }

    suspend fun refreshLocalStats(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val db = database(context.applicationContext)
            val favCount = db.likedSongsCount().first()
            val plCount = db.playlistsByCreateDateAsc().first().count { isSyncablePlaylist(it.playlist) }
            val albCount = db.albumsLikedByCreateDateAsc().first().count { !it.album.isLocal && it.album.id.isNotBlank() }
            val artCount = db.artistsBookmarkedByCreateDateAsc().first().count { !it.artist.isLocal && it.artist.id.isNotBlank() }
            val histCount = db.events().first().distinctBy { it.song.id }.size
            _state.value = _state.value.copy(
                favoritesCount = favCount,
                playlistsCount = plCount,
                albumsCount = albCount,
                artistsCount = artCount,
                historyCount = maxOf(_state.value.historyCount, histCount),
            )
        }
    }

    fun onLocalProfileSaved(context: Context, name: String, photoPath: String?) {
        val app = context.applicationContext
        if (!MochyCloudVault.isConfigured(app) || MochyCloudVault.loadSession(app) == null) return
        val cleanName = name.trim().take(40)
        val cleanPhoto = photoPath?.removePrefix("file://")?.takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(
            displayName = cleanName.ifBlank { _state.value.displayName },
            avatarLocalPath = cleanPhoto ?: _state.value.avatarLocalPath,
        )
        val scope = appScope ?: return
        scope.launch(NonCancellable + Dispatchers.IO) {
            val session = getValidSession(app) ?: return@launch
            val webp = cleanPhoto?.let(::File)?.takeIf { it.exists() }?.let { file ->
                MochyAvatarCodec.encodeUriToWebpDataUri(app, Uri.fromFile(file))
            }
            runCatching {
                MochySupabaseClient.ensureProfileRow(app, session, cleanName.ifBlank { session.displayName })
                MochySupabaseClient.updateProfile(
                    context = app,
                    session = session,
                    displayName = cleanName.takeIf { it.isNotBlank() },
                    avatarWebp = webp,
                )
            }.onFailure { err ->
                if (err is MochyCloudAuthExpiredException) {
                    handleSessionExpired(app, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
                }
            }
        }
    }

    fun onFavoriteToggled(context: Context) {
        val app = context.applicationContext
        val scope = appScope ?: return
        if (!MochyCloudVault.isConfigured(app) || MochyCloudVault.loadSession(app) == null) return
        if (favoritesObserverJob?.isActive == true) return
        favoriteToggleSyncJob?.cancel()
        favoriteToggleSyncJob = scope.launch(Dispatchers.IO) {
            delay(1_500L)
            syncNowInternal(app, fromUser = false)
        }
    }

    fun onSongPlayed(context: Context, songId: String, playTimeMs: Long) {
        if (songId.isBlank() || playTimeMs < 5_000L) return
        val app = context.applicationContext
        val scope = appScope ?: return
        val cachedSession = MochyCloudVault.loadSession(app) ?: return
        scope.launch(Dispatchers.IO) {
            val db = database(app)
            val song = db.getSongByIdBlocking(songId) ?: return@launch
            if (!isCloudTrackEligible(song.song)) return@launch
            val existingPending = pendingHistoryMap[songId]
            val prev = historyPlayCountCache[songId]
                ?: existingPending?.let { it.playCount to it.totalPlayMs }
                ?: (0L to 0L)
            val nextCount = (prev.first + 1L).coerceAtLeast(1L)
            val nextMs = (prev.second + playTimeMs).coerceAtLeast(playTimeMs)
            historyPlayCountCache[songId] = nextCount to nextMs

            val record = CloudHistoryRecord(
                songId = song.id,
                title = song.title,
                artists = song.artists.joinToString(", ") { it.name }.ifBlank { "Nghệ sĩ" },
                albumName = song.song.albumName,
                duration = song.song.duration,
                thumbnailUrl = song.thumbnailUrl,
                playCount = nextCount,
                totalPlayMs = nextMs,
                lastPlayedAt = Instant.now().toString(),
            )
            pendingHistoryMap[songId] = record
            MochyCloudVault.savePendingHistory(app, cachedSession.userId, pendingHistoryMap.values.toList())

            if (pendingHistoryMap.size >= HistoryBatchFlushCount) {
                historyFlushJob?.cancel()
                flushPendingHistoryInternal(app)
            } else {
                historyFlushJob?.cancel()
                historyFlushJob = scope.launch(Dispatchers.IO) {
                    delay(HistoryBatchDebounceMs)
                    flushPendingHistoryInternal(app)
                }
            }
        }
    }

    fun flushPendingHistory(context: Context) {
        if (pendingHistoryMap.isEmpty()) return
        val app = context.applicationContext
        val scope = appScope ?: return
        historyFlushJob?.cancel()
        scope.launch(NonCancellable + Dispatchers.IO) {
            flushPendingHistoryInternal(app)
        }
    }

    private suspend fun flushPendingHistoryInternal(app: Context) = historyFlushMutex.withLock {
        if (pendingHistoryMap.isEmpty()) return@withLock
        val session = getValidSession(app) ?: return@withLock
        val snapshot = pendingHistoryMap.values.toList()
        if (snapshot.isEmpty()) return@withLock
        snapshot.forEach { rec -> pendingHistoryMap.remove(rec.songId, rec) }
        val result = runCatching {
            MochySupabaseClient.upsertHistory(app, session, snapshot)
        }
        if (result.isSuccess) {
            MochyCloudVault.savePendingHistory(app, session.userId, pendingHistoryMap.values.toList())
            _state.value = _state.value.copy(
                historyCount = maxOf(_state.value.historyCount, historyPlayCountCache.size),
                lastSyncEpochMs = System.currentTimeMillis(),
            )
        } else {
            val err = result.exceptionOrNull()
            if (err is MochyCloudAuthExpiredException) {
                pendingHistoryMap.clear()
                handleSessionExpired(app, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
                return@withLock
            }
            snapshot.forEach { oldRec ->
                val cur = pendingHistoryMap[oldRec.songId]
                if (cur == null) {
                    pendingHistoryMap[oldRec.songId] = oldRec
                } else {
                    pendingHistoryMap[oldRec.songId] = cur.copy(
                        playCount = maxOf(cur.playCount, oldRec.playCount),
                        totalPlayMs = maxOf(cur.totalPlayMs, oldRec.totalPlayMs),
                    )
                }
            }
            MochyCloudVault.savePendingHistory(app, session.userId, pendingHistoryMap.values.toList())
        }
    }

    private fun startObservers(app: Context, scope: CoroutineScope) {
        favoritesObserverJob?.cancel()
        favoritesObserverJob = scope.launch(Dispatchers.IO) {
            val db = database(app)
            db.likedSongsByCreateDateAsc()
                .map { songs -> songs.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collectLatest { currentIds ->
                    val session = MochyCloudVault.loadSession(app) ?: return@collectLatest
                    if (System.currentTimeMillis() < suppressObserverUntilMs) return@collectLatest
                    val prevSynced = MochyCloudVault.loadSyncedFavoriteIds(app, session.userId) ?: return@collectLatest
                    if (currentIds == prevSynced) return@collectLatest
                    delay(1_200L)
                    if (System.currentTimeMillis() < suppressObserverUntilMs) return@collectLatest
                    val latestSynced = MochyCloudVault.loadSyncedFavoriteIds(app, session.userId) ?: return@collectLatest
                    if (currentIds == latestSynced) return@collectLatest
                    syncNowInternal(app, fromUser = false)
                }
        }

        playlistsObserverJob?.cancel()
        playlistsObserverJob = scope.launch(Dispatchers.IO) {
            val db = database(app)
            db.playlistsByCreateDateAsc()
                .map { list -> buildPlaylistDigestEntries(db, list) }
                .distinctUntilChanged()
                .collectLatest { items ->
                    val session = MochyCloudVault.loadSession(app) ?: return@collectLatest
                    if (System.currentTimeMillis() < suppressObserverUntilMs) return@collectLatest
                    val prevDigest = MochyCloudVault.loadSyncedPlaylistsDigest(app, session.userId)
                        ?: return@collectLatest
                    val currentDigest = computePlaylistsDigest(items)
                    if (currentDigest == prevDigest) return@collectLatest
                    delay(4_000L)
                    if (System.currentTimeMillis() < suppressObserverUntilMs) return@collectLatest
                    val latestDigest = MochyCloudVault.loadSyncedPlaylistsDigest(app, session.userId)
                        ?: return@collectLatest
                    if (currentDigest == latestDigest) return@collectLatest
                    syncNowInternal(app, fromUser = false)
                }
        }
    }

    private fun isSyncablePlaylist(pl: PlaylistEntity): Boolean =
        pl.browseId == null &&
            pl.isEditable &&
            pl.id != PlaylistEntity.LIKED_PLAYLIST_ID &&
            pl.id != PlaylistEntity.DOWNLOADED_PLAYLIST_ID

    private suspend fun syncNowInternal(app: Context, fromUser: Boolean = false) = syncMutex.withLock {
        val session = getValidSession(app)
        if (session == null) {
            if (fromUser && !lastSessionExpired && !lastSessionFailureReason.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    syncing = false,
                    lastError = lastSessionFailureReason,
                )
            }
            return@withLock
        }
        _state.value = _state.value.copy(syncing = true, lastError = null)
        try {
            val db = database(app)

            // Đẩy hàng đợi lịch sử còn tồn trước khi đồng bộ
            flushPendingHistoryInternal(app)
            val activeSession = MochyCloudVault.loadSession(app)
                ?.takeIf { it.userId == session.userId }
                ?: session

            // 1. Hồ sơ & ảnh đại diện nhẹ
            val localAvatarFile = readLocalAvatarFile(app)
            val localUserName = runCatching {
                app.dataStore.data.first()[UsernameKey]?.trim().orEmpty()
            }.getOrDefault("")
            var profile = MochySupabaseClient.fetchProfile(app, activeSession)
            if (profile == null) {
                MochySupabaseClient.ensureProfileRow(
                    app,
                    activeSession,
                    localUserName.ifBlank { activeSession.displayName },
                )
                profile = MochySupabaseClient.fetchProfile(app, activeSession)
            }
            var avatarPath = localAvatarFile?.absolutePath ?: _state.value.avatarLocalPath
            var activeDisplay = activeSession.displayName
            if (profile != null) {
                if (profile.displayName.isNotBlank()) {
                    activeDisplay = profile.displayName
                } else if (localUserName.isNotBlank()) {
                    activeDisplay = localUserName
                }
                val decodedAvatar = profile.avatarWebp?.let { MochyAvatarCodec.decodeDataUriToFile(app, it) }
                if (decodedAvatar != null) {
                    avatarPath = decodedAvatar.absolutePath
                    ProfileStore.save(app, activeDisplay, Uri.fromFile(decodedAvatar), syncToCloud = false)
                } else if (localAvatarFile != null) {
                    val webp = MochyAvatarCodec.encodeUriToWebpDataUri(app, Uri.fromFile(localAvatarFile))
                    if (webp != null) {
                        runCatching {
                            MochySupabaseClient.updateProfile(app, activeSession, activeDisplay, webp)
                        }.onFailure { err ->
                            if (err is MochyCloudAuthExpiredException) throw err
                        }
                    }
                    avatarPath = localAvatarFile.absolutePath
                    ProfileStore.save(app, activeDisplay, null, syncToCloud = false)
                } else {
                    ProfileStore.save(app, activeDisplay, null, syncToCloud = false)
                }
            }

            // 1B. Metadata tài khoản (Thẻ ID, GZIP Playlist & Library Extra)
            val remoteMeta = runCatching { MochySupabaseClient.fetchUserMetadata(app, activeSession) }
                .onFailure { err -> if (err is MochyCloudAuthExpiredException) throw err }
                .getOrNull()
            if (remoteMeta != null) {
                syncIdCardMetadata(app, activeSession, remoteMeta)
            }

            // 2. Yêu thích (hai chiều)
            val remoteFavs = MochySupabaseClient.fetchFavorites(app, activeSession)
            val remoteById = remoteFavs.associateBy { it.songId }
            val localLiked = db.likedSongsByCreateDateAsc().first()
            val localById = localLiked.associateBy { it.id }
            val prevSynced = MochyCloudVault.loadSyncedFavoriteIds(app, activeSession.userId)

            val removedLocally = if (prevSynced != null) prevSynced - localById.keys else emptySet()
            val addedLocally = if (prevSynced != null) localById.keys - prevSynced else localById.keys

            val toPushFavs = addedLocally.mapNotNull { id ->
                localById[id]?.takeIf { !remoteById.containsKey(id) && isCloudTrackEligible(it.song) }?.let { song ->
                    CloudFavoriteRecord(
                        songId = song.id,
                        title = song.title,
                        artists = song.artists.joinToString(", ") { it.name }.ifBlank { "Nghệ sĩ" },
                        albumName = song.song.albumName,
                        duration = song.song.duration,
                        thumbnailUrl = song.thumbnailUrl,
                        likedAt = (song.song.likedDate ?: LocalDateTime.now())
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toString(),
                    )
                }
            }
            if (toPushFavs.isNotEmpty()) {
                MochySupabaseClient.upsertFavorites(app, activeSession, toPushFavs)
            }
            val toDeleteRemote = removedLocally.filter { remoteById.containsKey(it) }
            if (toDeleteRemote.isNotEmpty()) {
                MochySupabaseClient.deleteFavorites(app, activeSession, toDeleteRemote)
            }

            val toImportLocal = remoteFavs.filter { !localById.containsKey(it.songId) && !removedLocally.contains(it.songId) }
            if (toImportLocal.isNotEmpty()) {
                suppressObserverUntilMs = System.currentTimeMillis() + 2_500L
                for (fav in toImportLocal) {
                    ensureSongLikedInLocalDb(db, fav)
                }
            }

            val unlikedRemotely = if (prevSynced != null) {
                (prevSynced intersect localById.keys) - remoteById.keys
            } else {
                emptySet()
            }
            if (unlikedRemotely.isNotEmpty()) {
                suppressObserverUntilMs = System.currentTimeMillis() + 2_500L
                for (songId in unlikedRemotely) {
                    localById[songId]?.song?.takeIf(::isCloudTrackEligible)?.let { songEntity ->
                        db.update(songEntity.copy(liked = false, likedDate = null))
                    }
                }
            }

            val finalSyncedIds = ((localById.keys - unlikedRemotely) + toImportLocal.map { it.songId }) - toDeleteRemote.toSet()
            MochyCloudVault.saveSyncedFavoriteIds(app, activeSession.userId, finalSyncedIds)

            // 3. Lịch sử nghe (gộp mới nhất, tối đa 500 bài)
            val remoteHistory = MochySupabaseClient.fetchHistory(app, activeSession)
            val remoteHistoryMap = remoteHistory.associateBy { it.songId }
            val localEvents = db.events().first()
            val existingEventKeys = localEvents.mapTo(HashSet()) {
                it.song.id to it.event.timestamp.atZone(ZoneId.systemDefault()).toEpochSecond()
            }
            val groupedLocal = localEvents.groupBy { it.song.id }
            val toPushHistory = mutableListOf<CloudHistoryRecord>()

            for ((songId, events) in groupedLocal) {
                val song = events.first().song
                if (!isCloudTrackEligible(song.song)) continue
                val localCount = events.size.toLong()
                val localPlayMs = events.sumOf { it.event.playTime }.coerceAtLeast(0L)
                val latestLocal = events.maxOf { it.event.timestamp }
                val latestLocalInstant = latestLocal.atZone(ZoneId.systemDefault()).toInstant()
                val remote = remoteHistoryMap[songId]
                val remoteCount = remote?.playCount ?: 0L
                val remoteMs = remote?.totalPlayMs ?: 0L
                val remoteInstant = remote?.lastPlayedAt?.let(::parseIsoInstant)

                val mergedCount = maxOf(localCount, remoteCount)
                val mergedMs = maxOf(localPlayMs, remoteMs)
                val mergedInstant = listOfNotNull(latestLocalInstant, remoteInstant).maxOrNull() ?: Instant.now()
                historyPlayCountCache[songId] = mergedCount to mergedMs

                if (remote == null || localCount > remoteCount || latestLocalInstant.isAfter(remoteInstant ?: Instant.EPOCH)) {
                    toPushHistory += CloudHistoryRecord(
                        songId = songId,
                        title = song.title,
                        artists = song.artists.joinToString(", ") { it.name }.ifBlank { "Nghệ sĩ" },
                        albumName = song.song.albumName,
                        duration = song.song.duration,
                        thumbnailUrl = song.thumbnailUrl,
                        playCount = mergedCount,
                        totalPlayMs = mergedMs,
                        lastPlayedAt = mergedInstant.toString(),
                    )
                }
            }
            if (toPushHistory.isNotEmpty()) {
                MochySupabaseClient.upsertHistory(app, activeSession, toPushHistory)
            }

            for (rh in remoteHistory) {
                historyPlayCountCache.putIfAbsent(rh.songId, rh.playCount to rh.totalPlayMs)
                if (!groupedLocal.containsKey(rh.songId)) {
                    ensureSongHistoryInLocalDb(db, rh, existingEventKeys)
                }
            }

            // 4. Playlist cá nhân hai chiều (GZIP <= 16 KB, tối đa 25 playlist)
            val playlistsCount = if (remoteMeta != null) {
                syncPersonalPlaylists(app, activeSession, db, remoteMeta.playlistsGz)
            } else {
                db.playlistsByCreateDateAsc().first().count { isSyncablePlaylist(it.playlist) }
            }

            // 5. Album đã lưu, Nghệ sĩ theo dõi & Equalizer (`library_extra_gz` <= 16 KB)
            val (albumsCount, artistsCount) = if (remoteMeta != null) {
                syncLibraryExtra(app, activeSession, db, remoteMeta.libraryExtraGz)
            } else {
                val albCount = db.albumsLikedByCreateDateAsc().first().count { !it.album.isLocal && it.album.id.isNotBlank() }
                val artCount = db.artistsBookmarkedByCreateDateAsc().first().count { !it.artist.isLocal && it.artist.id.isNotBlank() }
                albCount to artCount
            }

            val finalSession = MochyCloudVault.loadSession(app)
                ?.takeIf { it.userId == activeSession.userId }
                ?: activeSession
            val decodedRemoteResume = if (remoteMeta != null) {
                MochyPlaybackResumeCodec.decode(remoteMeta.playbackResumeGz)
            } else {
                _state.value.remoteResume
            }
            val currentResume = _state.value.remoteResume
            val resolvedResume = when {
                remoteMeta == null -> currentResume
                remoteMeta.playbackResumeGz.isNullOrBlank() -> null
                decodedRemoteResume != null &&
                    currentResume != null &&
                    currentResume.updatedAtEpochMs > decodedRemoteResume.updatedAtEpochMs -> currentResume
                else -> decodedRemoteResume
            }
            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = finalSession.username,
                displayName = activeDisplay,
                avatarLocalPath = avatarPath,
                favoritesCount = finalSyncedIds.size,
                playlistsCount = playlistsCount,
                albumsCount = albumsCount,
                artistsCount = artistsCount,
                historyCount = maxOf(groupedLocal.size, remoteHistory.size),
                syncing = false,
                lastSyncEpochMs = System.currentTimeMillis(),
                lastError = null,
                remoteResume = resolvedResume,
            )
        } catch (err: MochyCloudAuthExpiredException) {
            handleSessionExpired(app, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
        } catch (err: Throwable) {
            _state.value = _state.value.copy(
                syncing = false,
                lastError = err.message ?: "Đồng bộ Mochy Cloud thất bại.",
            )
        }
    }

    private suspend fun syncIdCardMetadata(
        app: Context,
        session: MochyCloudSession,
        remoteMeta: CloudUserMetadata,
    ) {
        val prefs = app.dataStore.data.first()
        val localCard = prefs[ProfileCardNumberKey]?.takeIf { it.isNotBlank() }
        val localJoined = prefs[ProfileJoinedEpochDayKey]?.takeIf { it > 0L }
        if (remoteMeta.cardNumber != null) {
            val targetJoined = remoteMeta.joinedEpochDay ?: localJoined ?: LocalDate.now().toEpochDay()
            if (localCard != remoteMeta.cardNumber || localJoined != targetJoined) {
                app.dataStore.edit { p ->
                    p[ProfileCardNumberKey] = remoteMeta.cardNumber
                    p[ProfileJoinedEpochDayKey] = targetJoined
                }
            }
            if (remoteMeta.joinedEpochDay == null) {
                runCatching {
                    MochySupabaseClient.updateUserMetadata(app, session, joinedEpochDay = targetJoined)
                }.onFailure { err -> if (err is MochyCloudAuthExpiredException) throw err }
            }
        } else {
            val cardToUse = localCard ?: (10_000_000_000L + Random.nextLong(89_999_999_999L)).toString()
            val joinedToUse = localJoined ?: LocalDate.now().toEpochDay()
            app.dataStore.edit { p ->
                p[ProfileCardNumberKey] = cardToUse
                p[ProfileJoinedEpochDayKey] = joinedToUse
            }
            runCatching {
                MochySupabaseClient.updateUserMetadata(
                    context = app,
                    session = session,
                    cardNumber = cardToUse,
                    joinedEpochDay = joinedToUse,
                )
            }.onFailure { err -> if (err is MochyCloudAuthExpiredException) throw err }
        }
    }

    private suspend fun syncPersonalPlaylists(
        app: Context,
        session: MochyCloudSession,
        db: MusicDatabase,
        remotePlaylistsGz: String?,
    ): Int {
        val remotePlaylists = MochyPlaylistCodec.decode(remotePlaylistsGz)
        val initialLocal = db.playlistsByCreateDateAsc().first().filter { isSyncablePlaylist(it.playlist) }
        val localMap = initialLocal.associateBy { it.id }
        val prevSyncedPlIds = MochyCloudVault.loadSyncedPlaylistIds(app, session.userId)
        val removedLocally = if (prevSyncedPlIds != null) prevSyncedPlIds - localMap.keys else emptySet()
        val remoteIds = remotePlaylists.map { it.id }.toSet()

        if (prevSyncedPlIds != null && remotePlaylists.size < 25) {
            val deletedRemotely = (prevSyncedPlIds intersect localMap.keys) - remoteIds
            if (deletedRemotely.isNotEmpty()) {
                suppressObserverUntilMs = System.currentTimeMillis() + 3_000L
                for (delId in deletedRemotely) {
                    db.clearPlaylist(delId)
                    localMap[delId]?.playlist?.let { db.delete(it) }
                }
            }
        }

        for (remotePl in remotePlaylists) {
            if (remotePl.id in removedLocally) continue
            val existing = localMap[remotePl.id]
            val remoteLocalTime = Instant.ofEpochSecond(remotePl.updatedEpochSec.coerceAtLeast(1L))
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()

            if (existing == null) {
                suppressObserverUntilMs = System.currentTimeMillis() + 3_000L
                db.insert(
                    PlaylistEntity(
                        id = remotePl.id,
                        name = remotePl.name,
                        createdAt = remoteLocalTime,
                        lastUpdateTime = remoteLocalTime,
                        isEditable = true,
                        bookmarkedAt = LocalDateTime.now(),
                    ),
                )
                writePlaylistTracks(db, remotePl)
            } else {
                val localEpochSec = (existing.playlist.lastUpdateTime ?: existing.playlist.createdAt ?: LocalDateTime.now())
                    .atZone(ZoneId.systemDefault())
                    .toEpochSecond()
                val existingTracks = db.playlistSongs(remotePl.id).first()
                val existingTrackIds = existingTracks.take(200).map { it.song.id }
                val remoteTrackIds = remotePl.tracks.map { it.songId }
                val remoteIsNewer = remotePl.updatedEpochSec > localEpochSec + 2L ||
                    (existingTracks.isEmpty() && remotePl.tracks.isNotEmpty()) ||
                    (remotePl.updatedEpochSec >= localEpochSec && (existing.playlist.name != remotePl.name || existingTrackIds != remoteTrackIds))
                if (remoteIsNewer) {
                    suppressObserverUntilMs = System.currentTimeMillis() + 3_000L
                    db.update(
                        existing.playlist.copy(
                            name = remotePl.name,
                            lastUpdateTime = remoteLocalTime,
                            bookmarkedAt = existing.playlist.bookmarkedAt ?: LocalDateTime.now(),
                        ),
                    )
                    db.clearPlaylist(remotePl.id)
                    writePlaylistTracks(db, remotePl)
                }
            }
        }

        val finalLocal = db.playlistsByCreateDateAsc().first()
            .filter { isSyncablePlaylist(it.playlist) }
            .take(25)
        val recordsToPush = finalLocal.map { pl ->
            val songs = db.playlistSongs(pl.id).first().take(200)
            val updatedSec = (pl.playlist.lastUpdateTime ?: pl.playlist.createdAt ?: LocalDateTime.now())
                .atZone(ZoneId.systemDefault())
                .toEpochSecond()
                .coerceAtLeast(1L)
            CloudPlaylistRecord(
                id = pl.id,
                name = pl.playlist.name,
                updatedEpochSec = updatedSec,
                tracks = songs.map { ps ->
                    CloudPlaylistTrack(
                        songId = ps.song.id,
                        title = ps.song.title,
                        artists = ps.song.artists.joinToString(", ") { it.name }.ifBlank { "Nghệ sĩ" },
                        albumName = ps.song.song.albumName,
                        duration = ps.song.song.duration,
                        thumbnailUrl = ps.song.thumbnailUrl,
                    )
                },
            )
        }

        val encoded = MochyPlaylistCodec.encode(recordsToPush)
        if (encoded != remotePlaylistsGz.orEmpty()) {
            runCatching {
                MochySupabaseClient.updateUserMetadata(
                    context = app,
                    session = session,
                    playlistsGz = if (recordsToPush.isEmpty()) null else encoded,
                    clearPlaylists = recordsToPush.isEmpty(),
                )
            }.onFailure { err -> if (err is MochyCloudAuthExpiredException) throw err }
        }
        MochyCloudVault.saveSyncedPlaylistIds(app, session.userId, finalLocal.map { it.id }.toSet())
        val finalDigest = computePlaylistsDigest(buildPlaylistDigestEntries(db, finalLocal))
        MochyCloudVault.saveSyncedPlaylistsDigest(app, session.userId, finalDigest)
        return finalLocal.size
    }

    private suspend fun syncLibraryExtra(
        app: Context,
        session: MochyCloudSession,
        db: MusicDatabase,
        remoteLibraryExtraGz: String?,
    ): Pair<Int, Int> {
        val remoteExtra = MochyLibraryExtraCodec.decode(remoteLibraryExtraGz)

        // A. Album đã lưu (bookmarkedAt != null)
        val initialAlbums = db.albumsLikedByCreateDateAsc().first()
            .filter { !it.album.isLocal && it.album.id.isNotBlank() }
        val localAlbumMap = initialAlbums.associateBy { it.album.id }
        val prevSyncedAlbums = MochyCloudVault.loadSyncedAlbumIds(app, session.userId)
        val removedAlbumsLocally = prevSyncedAlbums?.let { it - localAlbumMap.keys }.orEmpty()

        for (remoteAlb in remoteExtra?.albums.orEmpty()) {
            if (remoteAlb.id in removedAlbumsLocally) continue
            val existing = db.album(remoteAlb.id).first()
            val bookmarkedLocal = if (remoteAlb.bookmarkedEpochSec > 0L) {
                Instant.ofEpochSecond(remoteAlb.bookmarkedEpochSec).atZone(ZoneId.systemDefault()).toLocalDateTime()
            } else {
                LocalDateTime.now()
            }
            if (existing == null) {
                db.insert(
                    AlbumEntity(
                        id = remoteAlb.id,
                        playlistId = remoteAlb.playlistId,
                        title = remoteAlb.title,
                        year = remoteAlb.year,
                        thumbnailUrl = remoteAlb.thumbnailUrl,
                        songCount = remoteAlb.songCount,
                        duration = remoteAlb.duration,
                        bookmarkedAt = bookmarkedLocal,
                    ),
                )
                remoteAlb.artists.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEachIndexed { idx, artistName ->
                        val artistId = db.artistByName(artistName)?.id ?: ArtistEntity.generateArtistId()
                        db.insert(ArtistEntity(id = artistId, name = artistName))
                        db.insert(AlbumArtistMap(albumId = remoteAlb.id, artistId = artistId, order = idx))
                    }
            } else if (existing.album.bookmarkedAt == null) {
                db.update(existing.album.copy(bookmarkedAt = bookmarkedLocal))
            }
        }
        if (remoteExtra != null && !remoteExtra.albumsTruncated && prevSyncedAlbums != null) {
            val remoteIds = remoteExtra.albums.map { it.id }.toSet()
            for (localAlb in initialAlbums) {
                if (localAlb.album.id in prevSyncedAlbums && localAlb.album.id !in remoteIds) {
                    db.update(localAlb.album.copy(bookmarkedAt = null))
                }
            }
        }

        // B. Nghệ sĩ theo dõi (bookmarkedAt != null)
        val initialArtists = db.artistsBookmarkedByCreateDateAsc().first()
            .filter { !it.artist.isLocal && it.artist.id.isNotBlank() }
        val localArtistMap = initialArtists.associateBy { it.artist.id }
        val prevSyncedArtists = MochyCloudVault.loadSyncedArtistIds(app, session.userId)
        val removedArtistsLocally = prevSyncedArtists?.let { it - localArtistMap.keys }.orEmpty()

        for (remoteArt in remoteExtra?.artists.orEmpty()) {
            if (remoteArt.id in removedArtistsLocally) continue
            val existing = db.getArtistById(remoteArt.id)
            val bookmarkedLocal = if (remoteArt.bookmarkedEpochSec > 0L) {
                Instant.ofEpochSecond(remoteArt.bookmarkedEpochSec).atZone(ZoneId.systemDefault()).toLocalDateTime()
            } else {
                LocalDateTime.now()
            }
            if (existing == null) {
                db.insert(
                    ArtistEntity(
                        id = remoteArt.id,
                        name = remoteArt.name,
                        thumbnailUrl = remoteArt.thumbnailUrl,
                        channelId = remoteArt.channelId,
                        bookmarkedAt = bookmarkedLocal,
                    ),
                )
            } else if (existing.bookmarkedAt == null) {
                db.update(
                    existing.copy(
                        bookmarkedAt = bookmarkedLocal,
                        thumbnailUrl = existing.thumbnailUrl ?: remoteArt.thumbnailUrl,
                        channelId = existing.channelId ?: remoteArt.channelId,
                    ),
                )
            }
        }
        if (remoteExtra != null && !remoteExtra.artistsTruncated && prevSyncedArtists != null) {
            val remoteIds = remoteExtra.artists.map { it.id }.toSet()
            for (localArt in initialArtists) {
                if (localArt.artist.id in prevSyncedArtists && localArt.artist.id !in remoteIds) {
                    db.update(localArt.artist.copy(bookmarkedAt = null))
                }
            }
        }

        // C. Cài đặt Equalizer & âm thanh
        val audioLab = audioLab(app)
        val initialEq = audioLab.eq.value
        val initialEqJson = audioLabJson.encodeToString(EqSettings.serializer(), initialEq)
        val initialPrefs = app.dataStore.data.first()
        val initialAudioDigest = sha256Hex(
            canonicalAudioConfig(
                eqJson = initialEqJson,
                norm = initialPrefs[AudioNormalizationKey],
                quality = initialPrefs[AudioQualityKey],
            ),
        )
        val prevEqDigest = MochyCloudVault.loadSyncedEqDigest(app, session.userId)
        val remoteAudio = remoteExtra?.audio
        val remoteEqJson = remoteAudio?.eqSettingsJson

        if (remoteAudio != null) {
            val remoteAudioDigest = sha256Hex(
                canonicalAudioConfig(
                    eqJson = remoteEqJson ?: initialEqJson,
                    norm = remoteAudio.audioNormalization ?: initialPrefs[AudioNormalizationKey],
                    quality = remoteAudio.audioQuality ?: initialPrefs[AudioQualityKey],
                ),
            )
            if (prevEqDigest == null) {
                if (!remoteEqJson.isNullOrBlank() && initialEq == EqSettings()) {
                    runCatching { audioLabJson.decodeFromString(EqSettings.serializer(), remoteEqJson) }
                        .getOrNull()
                        ?.let { decodedEq -> audioLab.updateEq { decodedEq } }
                }
                app.dataStore.edit { p ->
                    if (p[AudioNormalizationKey] == null) {
                        remoteAudio.audioNormalization?.let { p[AudioNormalizationKey] = it }
                    }
                    if (p[AudioQualityKey].isNullOrBlank()) {
                        remoteAudio.audioQuality?.takeIf { it.isNotBlank() }?.let { p[AudioQualityKey] = it }
                    }
                }
            } else if (initialAudioDigest == prevEqDigest && remoteAudioDigest != prevEqDigest) {
                if (!remoteEqJson.isNullOrBlank()) {
                    runCatching { audioLabJson.decodeFromString(EqSettings.serializer(), remoteEqJson) }
                        .getOrNull()
                        ?.let { decodedEq -> audioLab.updateEq { decodedEq } }
                }
                app.dataStore.edit { p ->
                    remoteAudio.audioNormalization?.let { p[AudioNormalizationKey] = it }
                    remoteAudio.audioQuality?.takeIf { it.isNotBlank() }?.let { p[AudioQualityKey] = it }
                }
            }
        }

        // Thu thập trạng thái cuối & đẩy lên user_metadata.library_extra_gz (<= 16 KB)
        val finalAlbums = db.albumsLikedByCreateDateAsc().first()
            .filter { !it.album.isLocal && it.album.id.isNotBlank() }
        val finalArtists = db.artistsBookmarkedByCreateDateAsc().first()
            .filter { !it.artist.isLocal && it.artist.id.isNotBlank() }
        val finalPrefs = app.dataStore.data.first()
        val finalEqJson = audioLabJson.encodeToString(EqSettings.serializer(), audioLab.eq.value)
        val extraPayload = CloudLibraryExtra(
            albums = finalAlbums.take(MaxCloudExtraItems).map { alb ->
                val epochSec = (alb.album.bookmarkedAt ?: LocalDateTime.now())
                    .atZone(ZoneId.systemDefault())
                    .toEpochSecond()
                    .coerceAtLeast(1L)
                CloudAlbumRecord(
                    id = alb.album.id,
                    playlistId = alb.album.playlistId,
                    title = alb.album.title,
                    artists = alb.artists.joinToString(", ") { it.name },
                    year = alb.album.year,
                    songCount = alb.album.songCount,
                    duration = alb.album.duration,
                    thumbnailUrl = alb.album.thumbnailUrl,
                    bookmarkedEpochSec = epochSec,
                )
            },
            artists = finalArtists.take(MaxCloudExtraItems).map { art ->
                val epochSec = (art.artist.bookmarkedAt ?: LocalDateTime.now())
                    .atZone(ZoneId.systemDefault())
                    .toEpochSecond()
                    .coerceAtLeast(1L)
                CloudArtistRecord(
                    id = art.artist.id,
                    name = art.artist.name,
                    channelId = art.artist.channelId,
                    thumbnailUrl = art.artist.thumbnailUrl,
                    bookmarkedEpochSec = epochSec,
                )
            },
            audio = CloudAudioConfig(
                eqSettingsJson = finalEqJson,
                audioNormalization = finalPrefs[AudioNormalizationKey],
                audioQuality = finalPrefs[AudioQualityKey],
            ),
        )
        val encodedExtra = MochyLibraryExtraCodec.encode(extraPayload)
        if (encodedExtra != remoteLibraryExtraGz.orEmpty()) {
            runCatching {
                MochySupabaseClient.updateUserMetadata(
                    context = app,
                    session = session,
                    libraryExtraGz = encodedExtra.ifEmpty { null },
                    clearLibraryExtra = encodedExtra.isEmpty(),
                )
            }.onFailure { err -> if (err is MochyCloudAuthExpiredException) throw err }
        }

        MochyCloudVault.saveSyncedAlbumIds(app, session.userId, finalAlbums.map { it.album.id }.toSet())
        MochyCloudVault.saveSyncedArtistIds(app, session.userId, finalArtists.map { it.artist.id }.toSet())
        val finalAudioDigest = sha256Hex(
            canonicalAudioConfig(
                eqJson = finalEqJson,
                norm = finalPrefs[AudioNormalizationKey],
                quality = finalPrefs[AudioQualityKey],
            ),
        )
        MochyCloudVault.saveSyncedEqDigest(app, session.userId, finalAudioDigest)
        return finalAlbums.size to finalArtists.size
    }

    private fun writePlaylistTracks(db: MusicDatabase, remotePl: CloudPlaylistRecord) {
        remotePl.tracks.forEachIndexed { idx, track ->
            if (db.getSongByIdBlocking(track.songId) == null) {
                insertSkeletonSong(
                    db = db,
                    songId = track.songId,
                    title = track.title,
                    artists = track.artists,
                    albumName = track.albumName,
                    duration = track.duration,
                    thumbnailUrl = track.thumbnailUrl,
                    liked = false,
                    likedDate = null,
                )
            }
            db.insert(
                PlaylistSongMap(
                    playlistId = remotePl.id,
                    songId = track.songId,
                    position = idx,
                ),
            )
        }
    }

    private suspend fun getValidSession(app: Context): MochyCloudSession? {
        if (!MochyCloudVault.isConfigured(app)) return null
        val current = MochyCloudVault.loadSession(app) ?: return null
        val nowSec = System.currentTimeMillis() / 1000L
        if (current.expiresAtEpochSec > nowSec + 90L) return current
        return sessionRefreshMutex.withLock {
            val latest = MochyCloudVault.loadSession(app) ?: return@withLock null
            val checkNow = System.currentTimeMillis() / 1000L
            if (latest.expiresAtEpochSec > checkNow + 90L) return@withLock latest
            when (val outcome = MochySupabaseClient.refreshSessionWithStatus(app, latest)) {
                is RefreshSessionResult.Success -> {
                    MochyCloudVault.saveSession(app, outcome.session)
                    lastSessionFailureReason = null
                    lastSessionExpired = false
                    outcome.session
                }
                is RefreshSessionResult.AuthExpired -> {
                    lastSessionFailureReason = outcome.message
                    lastSessionExpired = true
                    handleSessionExpired(app, outcome.message)
                    null
                }
                is RefreshSessionResult.NetworkError -> {
                    lastSessionFailureReason = outcome.message
                    lastSessionExpired = false
                    null
                }
            }
        }
    }

    private fun cancelBackgroundJobs() {
        favoritesObserverJob?.cancel()
        favoritesObserverJob = null
        playlistsObserverJob?.cancel()
        playlistsObserverJob = null
        historyFlushJob?.cancel()
        historyFlushJob = null
        favoriteToggleSyncJob?.cancel()
        favoriteToggleSyncJob = null
    }

    private fun handleSessionExpired(app: Context, message: String) {
        cancelBackgroundJobs()
        pendingHistoryMap.clear()
        historyPlayCountCache.clear()
        MochyCloudVault.clearSession(app)
        lastSessionFailureReason = message
        lastSessionExpired = true
        _state.value = MochyCloudState(
            isConfigured = MochyCloudVault.isConfigured(app),
            signedIn = false,
            lastError = message,
        )
    }

    private suspend fun buildPlaylistDigestEntries(db: MusicDatabase, playlists: List<Playlist>): List<String> =
        playlists.filter { isSyncablePlaylist(it.playlist) }
            .take(25)
            .map { pl ->
                val trackIds = db.playlistSongs(pl.id).first().take(200).joinToString(",") { it.song.id }
                val updatedSec = (pl.playlist.lastUpdateTime ?: pl.playlist.createdAt)
                    ?.atZone(ZoneId.systemDefault())
                    ?.toEpochSecond()
                    ?: 0L
                "${pl.id}\u0000${pl.playlist.name}\u0000$updatedSec\u0000$trackIds"
            }

    private fun computePlaylistsDigest(items: List<String>): String =
        sha256Hex(items.sorted().joinToString("\n"))

    private fun canonicalAudioConfig(eqJson: String?, norm: Boolean?, quality: String?): String =
        "${eqJson.orEmpty()}\u0000${norm?.toString().orEmpty()}\u0000${quality.orEmpty()}"

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                append(((b.toInt() ushr 4) and 0xF).toString(16))
                append((b.toInt() and 0xF).toString(16))
            }
        }
    }

    private fun ensureSongLikedInLocalDb(db: MusicDatabase, fav: CloudFavoriteRecord) {
        val likedLocalDate = parseIsoInstant(fav.likedAt)
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()
            ?: LocalDateTime.now()
        val existing = db.getSongByIdBlocking(fav.songId)
        if (existing != null) {
            if (!existing.song.liked) {
                db.update(existing.song.copy(liked = true, likedDate = likedLocalDate))
            }
            return
        }
        insertSkeletonSong(
            db = db,
            songId = fav.songId,
            title = fav.title,
            artists = fav.artists,
            albumName = fav.albumName,
            duration = fav.duration,
            thumbnailUrl = fav.thumbnailUrl,
            liked = true,
            likedDate = likedLocalDate,
        )
    }

    private fun ensureSongHistoryInLocalDb(
        db: MusicDatabase,
        record: CloudHistoryRecord,
        existingEventKeys: MutableSet<Pair<String, Long>>,
    ) {
        if (db.getSongByIdBlocking(record.songId) == null) {
            insertSkeletonSong(
                db = db,
                songId = record.songId,
                title = record.title,
                artists = record.artists,
                albumName = record.albumName,
                duration = record.duration,
                thumbnailUrl = record.thumbnailUrl,
                liked = false,
                likedDate = null,
            )
        }
        val ts = parseIsoInstant(record.lastPlayedAt)
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()
            ?: LocalDateTime.now()
        val epochSec = ts.atZone(ZoneId.systemDefault()).toEpochSecond()
        if (existingEventKeys.add(record.songId to epochSec)) {
            db.insert(
                Event(
                    songId = record.songId,
                    timestamp = ts,
                    playTime = record.totalPlayMs.coerceAtLeast(30_000L),
                ),
            )
        }
    }

    private fun insertSkeletonSong(
        db: MusicDatabase,
        songId: String,
        title: String,
        artists: String,
        albumName: String?,
        duration: Int,
        thumbnailUrl: String?,
        liked: Boolean,
        likedDate: LocalDateTime?,
    ) {
        val safeTitle = title.ifBlank { "Bài hát đã đồng bộ" }
        db.insert(
            SongEntity(
                id = songId,
                title = safeTitle,
                duration = duration,
                thumbnailUrl = thumbnailUrl,
                albumName = albumName,
                liked = liked,
                likedDate = likedDate,
            ),
        )
        artists.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEachIndexed { idx, artistName ->
                val artistId = db.artistByName(artistName)?.id ?: ArtistEntity.generateArtistId()
                db.insert(ArtistEntity(id = artistId, name = artistName))
                db.insert(SongArtistMap(songId = songId, artistId = artistId, position = idx))
            }
    }

    private fun parseIsoInstant(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private suspend fun readLocalAvatarFile(app: Context): File? = runCatching {
        val path = app.dataStore.data.first()[ProfilePicUriKey]?.trim().orEmpty()
        if (path.isBlank()) return@runCatching null
        File(path.removePrefix("file://")).takeIf { it.exists() && it.isFile && it.length() > 0L }
    }.getOrNull()

    private fun isCloudSafeId(id: String): Boolean =
        id.isNotBlank() &&
            id.length <= 64 &&
            !id.startsWith("LA") &&
            !id.contains("://") &&
            !id.contains("/")

    private fun isCloudTrackEligible(song: SongEntity): Boolean =
        !song.isLocal && isCloudSafeId(song.id)

    private fun sanitizeHttpUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed == "null") return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return if (trimmed.length <= 320) trimmed else trimmed.substringBefore('?').takeIf { it.length <= 320 }
    }

    private fun database(context: Context): MusicDatabase =
        EntryPointAccessors.fromApplication(context.applicationContext, CloudSyncEntryPoint::class.java)
            .database()

    private fun audioLab(context: Context): AudioLabStore =
        EntryPointAccessors.fromApplication(context.applicationContext, AudioLabEntryPoint::class.java)
            .audioLabStore()
}

internal object MochyPlaybackResumeCodec {

    private const val MaxEncodedChars = 2_950 // luôn nhỏ hơn 3 KB
    private const val MaxQueueTracks = 25
    private val QueueCaps = intArrayOf(25, 15, 8, 3, 0)

    fun encode(resume: CloudPlaybackResume): String {
        for (cap in QueueCaps) {
            val candidate = compressToBase64(resume, cap)
            if (candidate.length <= MaxEncodedChars) return candidate
        }
        return compressToBase64(
            resume.copy(
                title = resume.title.take(80),
                artists = resume.artists.take(80),
                albumName = resume.albumName?.take(80),
                thumbnailUrl = resume.thumbnailUrl?.take(180),
            ),
            0,
        ).takeIf { it.length <= MaxEncodedChars }.orEmpty()
    }

    fun decode(playbackResumeGz: String?): CloudPlaybackResume? {
        if (playbackResumeGz.isNullOrBlank()) return null
        return runCatching {
            val jsonStr = gunzipFromBase64(playbackResumeGz)
            val root = JSONObject(jsonStr)
            val deviceId = root.optString("deviceId").trim()
            val songId = root.optString("songId").trim()
            if (deviceId.isBlank() || songId.isBlank()) return@runCatching null

            val arr = root.optJSONArray("queueTracks") ?: JSONArray()
            val queue = buildList(minOf(arr.length(), MaxQueueTracks)) {
                for (i in 0 until minOf(arr.length(), MaxQueueTracks)) {
                    val row = arr.optJSONArray(i) ?: continue
                    val qId = row.optString(0).trim()
                    if (qId.isBlank()) continue
                    add(
                        CloudPlaylistTrack(
                            songId = qId,
                            title = row.optString(1).ifBlank { "Bài hát" },
                            artists = row.optString(2).ifBlank { "Nghệ sĩ" },
                            albumName = row.optString(3).takeIf { it.isNotBlank() && it != "null" },
                            duration = row.optInt(4, -1),
                            thumbnailUrl = row.optString(5).takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }
            }

            CloudPlaybackResume(
                deviceId = deviceId,
                deviceName = root.optString("deviceName").trim().ifBlank { "Thiết bị khác" },
                songId = songId,
                title = root.optString("title").trim().ifBlank { "Bài hát" },
                artists = root.optString("artists").trim().ifBlank { "Nghệ sĩ" },
                albumName = root.optString("albumName").takeIf { it.isNotBlank() && it != "null" },
                durationSec = root.optInt("durationSec", -1),
                thumbnailUrl = root.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                positionMs = root.optLong("positionMs", 0L).coerceAtLeast(0L),
                queueTracks = queue,
                updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L).coerceAtLeast(0L),
            )
        }.getOrNull()
    }

    private fun compressToBase64(resume: CloudPlaybackResume, maxTracks: Int): String {
        val arr = JSONArray()
        for (t in resume.queueTracks.take(maxTracks)) {
            arr.put(
                JSONArray().apply {
                    put(t.songId.take(64))
                    put(t.title.take(100))
                    put(t.artists.take(100))
                    put(t.albumName.orEmpty().take(100))
                    put(t.duration.coerceIn(-1, 86_400))
                    put(t.thumbnailUrl.orEmpty().take(280))
                },
            )
        }
        val root = JSONObject().apply {
            put("v", 1)
            put("deviceId", resume.deviceId.take(64))
            put("deviceName", resume.deviceName.take(40))
            put("songId", resume.songId.take(64))
            put("title", resume.title.take(140))
            put("artists", resume.artists.take(140))
            if (!resume.albumName.isNullOrBlank()) put("albumName", resume.albumName.take(120))
            put("durationSec", resume.durationSec.coerceIn(-1, 86_400))
            if (!resume.thumbnailUrl.isNullOrBlank()) put("thumbnailUrl", resume.thumbnailUrl.take(320))
            put("positionMs", resume.positionMs.coerceAtLeast(0L))
            put("queueTracks", arr)
            put("updatedAtEpochMs", resume.updatedAtEpochMs)
        }.toString()
        return gzipToBase64(root)
    }
}
