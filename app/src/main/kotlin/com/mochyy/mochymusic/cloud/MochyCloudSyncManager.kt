package com.mochyy.mochymusic.cloud

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.constants.ProfileCardNumberKey
import com.mochyy.mochymusic.constants.ProfileJoinedEpochDayKey
import com.mochyy.mochymusic.constants.ProfilePicUriKey
import com.mochyy.mochymusic.constants.UsernameKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Event
import com.mochyy.mochymusic.db.entities.PlaylistEntity
import com.mochyy.mochymusic.db.entities.PlaylistSongMap
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.di.CloudSyncEntryPoint
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class MochyCloudState(
    val isConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val avatarLocalPath: String? = null,
    val favoritesCount: Int = 0,
    val playlistsCount: Int = 0,
    val historyCount: Int = 0,
    val syncing: Boolean = false,
    val lastSyncEpochMs: Long = 0L,
    val lastError: String? = null,
)

/**
 * Điều phối Mochy Cloud gọn nhẹ:
 * - Đồng bộ hai chiều Yêu thích (`mochy_favorites`), Playlist cá nhân (`user_metadata.playlists_gz`),
 *   Lịch sử nghe (`mochy_history`), Thẻ ID bạc 3D (`user_metadata`) & Ảnh đại diện WebP (`<= 24 KB`).
 * - Hàng đợi gộp lịch sử nghe (debounce 45s hoặc đủ 10 bài, lưu bền vững chống mất dữ liệu).
 */
object MochyCloudSyncManager {

    private const val HistoryBatchFlushCount = 10
    private const val HistoryBatchDebounceMs = 45_000L

    private val _state = MutableStateFlow(MochyCloudState())
    val state: StateFlow<MochyCloudState> = _state.asStateFlow()

    private val syncMutex = Mutex()
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
                readLocalAvatarFile(app)?.let { local ->
                    _state.value = _state.value.copy(avatarLocalPath = local.absolutePath)
                }
                syncNowInternal(app)
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
            appScope?.let { startObservers(app, it) }
            syncNowInternal(app)
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
            appScope?.let { startObservers(app, it) }
            syncNowInternal(app)
        }.onFailure { err ->
            _state.value = _state.value.copy(lastError = err.message)
        }
    }

    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        favoritesObserverJob?.cancel()
        favoritesObserverJob = null
        playlistsObserverJob?.cancel()
        playlistsObserverJob = null
        historyFlushJob?.cancel()
        historyFlushJob = null
        favoriteToggleSyncJob?.cancel()
        favoriteToggleSyncJob = null
        pendingHistoryMap.clear()
        val old = MochyCloudVault.loadSession(app)
        MochyCloudVault.clearSession(app)
        historyPlayCountCache.clear()
        if (old != null) {
            runCatching { MochySupabaseClient.signOutRemote(app, old) }
        }
        _state.value = MochyCloudState(isConfigured = MochyCloudVault.isConfigured(app))
    }

    suspend fun updateProfileAndAvatar(
        context: Context,
        newDisplayName: String?,
        avatarUri: Uri?,
    ): Result<Unit> = runCatching {
        val app = context.applicationContext
        val session = getValidSession(app) ?: throw MochyCloudException("Bạn chưa đăng nhập Mochy Cloud.")
        var webpDataUri: String? = null
        if (avatarUri != null) {
            webpDataUri = MochyAvatarCodec.encodeUriToWebpDataUri(app, avatarUri)
                ?: throw MochyCloudException("Không thể nén ảnh đại diện xuống dưới 24 KB.")
        }
        MochySupabaseClient.updateProfile(app, session, newDisplayName, webpDataUri)
        val updatedName = newDisplayName?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: session.displayName
        val updatedSession = session.copy(displayName = updatedName)
        MochyCloudVault.saveSession(app, updatedSession)
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
        _state.value = _state.value.copy(lastError = err.message)
    }

    suspend fun changePassword(
        context: Context,
        newPassword: String,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val session = getValidSession(app) ?: throw MochyCloudException("Bạn chưa đăng nhập Mochy Cloud.")
            MochySupabaseClient.updatePassword(app, session, newPassword)
            Unit
        }
    }

    suspend fun clearAllCloudData(context: Context): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                val app = context.applicationContext
                val session = getValidSession(app) ?: throw MochyCloudException("Bạn chưa đăng nhập Mochy Cloud.")
                historyFlushJob?.cancel()
                pendingHistoryMap.clear()
                historyPlayCountCache.clear()
                MochyCloudVault.savePendingHistory(app, session.userId, emptyList())
                MochyCloudVault.saveSyncedFavoriteIds(app, session.userId, emptySet())
                MochyCloudVault.saveSyncedPlaylistIds(app, session.userId, emptySet())
                MochySupabaseClient.wipeCloudData(app, session)
                ProfileStore.save(app, session.displayName, null, syncToCloud = false)
                _state.value = _state.value.copy(
                    avatarLocalPath = null,
                    favoritesCount = 0,
                    playlistsCount = 0,
                    historyCount = 0,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    lastError = null,
                )
            }
        }
    }

    fun syncNow(context: Context) {
        val scope = appScope ?: return
        scope.launch(Dispatchers.IO) {
            syncNowInternal(context.applicationContext)
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
            }
        }
    }

    fun onFavoriteToggled(context: Context) {
        val app = context.applicationContext
        val scope = appScope ?: return
        if (!MochyCloudVault.isConfigured(app) || MochyCloudVault.loadSession(app) == null) return
        favoriteToggleSyncJob?.cancel()
        favoriteToggleSyncJob = scope.launch(Dispatchers.IO) {
            delay(1_500L)
            syncNowInternal(app)
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
                    syncNowInternal(app)
                }
        }

        playlistsObserverJob?.cancel()
        playlistsObserverJob = scope.launch(Dispatchers.IO) {
            val db = database(app)
            db.playlistsByCreateDateAsc()
                .map { list ->
                    list.filter { isSyncablePlaylist(it.playlist) }
                        .map { Triple(it.id, it.playlist.name, it.songCount) }
                }
                .distinctUntilChanged()
                .collectLatest { items ->
                    val session = MochyCloudVault.loadSession(app) ?: return@collectLatest
                    if (System.currentTimeMillis() < suppressObserverUntilMs) return@collectLatest
                    val prevSynced = MochyCloudVault.loadSyncedPlaylistIds(app, session.userId) ?: return@collectLatest
                    if (items.map { it.first }.toSet() == prevSynced && prevSynced.isNotEmpty()) {
                        // Vẫn cho phép đẩy lên nếu tên hoặc số bài đổi
                        delay(4_000L)
                        if (System.currentTimeMillis() < suppressObserverUntilMs) return@collectLatest
                        syncNowInternal(app)
                    } else if (items.map { it.first }.toSet() != prevSynced) {
                        delay(4_000L)
                        if (System.currentTimeMillis() < suppressObserverUntilMs) return@collectLatest
                        syncNowInternal(app)
                    }
                }
        }
    }

    private fun isSyncablePlaylist(pl: PlaylistEntity): Boolean =
        pl.browseId == null &&
            pl.isEditable &&
            pl.id != PlaylistEntity.LIKED_PLAYLIST_ID &&
            pl.id != PlaylistEntity.DOWNLOADED_PLAYLIST_ID

    private suspend fun syncNowInternal(app: Context) = syncMutex.withLock {
        val session = getValidSession(app) ?: return@withLock
        _state.value = _state.value.copy(syncing = true, lastError = null)
        try {
            val db = database(app)

            // Đẩy hàng đợi lịch sử còn tồn trước khi đồng bộ
            flushPendingHistoryInternal(app)

            // 1. Hồ sơ & ảnh đại diện nhẹ
            val localAvatarFile = readLocalAvatarFile(app)
            val localUserName = runCatching {
                app.dataStore.data.first()[UsernameKey]?.trim().orEmpty()
            }.getOrDefault("")
            var profile = MochySupabaseClient.fetchProfile(app, session)
            if (profile == null) {
                MochySupabaseClient.ensureProfileRow(
                    app,
                    session,
                    localUserName.ifBlank { session.displayName },
                )
                profile = MochySupabaseClient.fetchProfile(app, session)
            }
            var avatarPath = localAvatarFile?.absolutePath ?: _state.value.avatarLocalPath
            var activeDisplay = session.displayName
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
                            MochySupabaseClient.updateProfile(app, session, activeDisplay, webp)
                        }
                    }
                    avatarPath = localAvatarFile.absolutePath
                    ProfileStore.save(app, activeDisplay, null, syncToCloud = false)
                } else {
                    ProfileStore.save(app, activeDisplay, null, syncToCloud = false)
                }
            }

            // 1B. Metadata tài khoản (Thẻ ID bạc 3D + GZIP Playlist)
            val remoteMeta = runCatching { MochySupabaseClient.fetchUserMetadata(app, session) }.getOrNull()
            if (remoteMeta != null) {
                syncIdCardMetadata(app, session, remoteMeta)
            }

            // 2. Yêu thích (hai chiều)
            val remoteFavs = MochySupabaseClient.fetchFavorites(app, session)
            val remoteById = remoteFavs.associateBy { it.songId }
            val localLiked = db.likedSongsByCreateDateAsc().first()
            val localById = localLiked.associateBy { it.id }
            val prevSynced = MochyCloudVault.loadSyncedFavoriteIds(app, session.userId)

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
                MochySupabaseClient.upsertFavorites(app, session, toPushFavs)
            }
            val toDeleteRemote = removedLocally.filter { remoteById.containsKey(it) }
            if (toDeleteRemote.isNotEmpty()) {
                MochySupabaseClient.deleteFavorites(app, session, toDeleteRemote)
            }

            val toImportLocal = remoteFavs.filter { !localById.containsKey(it.songId) && !removedLocally.contains(it.songId) }
            if (toImportLocal.isNotEmpty()) {
                suppressObserverUntilMs = System.currentTimeMillis() + 2_500L
                for (fav in toImportLocal) {
                    ensureSongLikedInLocalDb(db, fav)
                }
            }

            val finalSyncedIds = (localById.keys + toImportLocal.map { it.songId }) - toDeleteRemote.toSet()
            MochyCloudVault.saveSyncedFavoriteIds(app, session.userId, finalSyncedIds)

            // 3. Lịch sử nghe (gộp mới nhất, tối đa 500 bài)
            val remoteHistory = MochySupabaseClient.fetchHistory(app, session)
            val remoteHistoryMap = remoteHistory.associateBy { it.songId }
            val localEvents = db.recentEvents(300).first()
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
                MochySupabaseClient.upsertHistory(app, session, toPushHistory)
            }

            for (rh in remoteHistory) {
                historyPlayCountCache.putIfAbsent(rh.songId, rh.playCount to rh.totalPlayMs)
                if (!groupedLocal.containsKey(rh.songId)) {
                    ensureSongHistoryInLocalDb(db, rh)
                }
            }

            // 4. Playlist cá nhân hai chiều (GZIP <= 16 KB, tối đa 25 playlist)
            val playlistsCount = if (remoteMeta != null) {
                syncPersonalPlaylists(app, session, db, remoteMeta.playlistsGz)
            } else {
                db.playlistsByCreateDateAsc().first().count { isSyncablePlaylist(it.playlist) }
            }

            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = session.username,
                displayName = activeDisplay,
                avatarLocalPath = avatarPath,
                favoritesCount = finalSyncedIds.size,
                playlistsCount = playlistsCount,
                historyCount = maxOf(groupedLocal.size, remoteHistory.size),
                syncing = false,
                lastSyncEpochMs = System.currentTimeMillis(),
                lastError = null,
            )
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
                }
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
            }
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
                val remoteIsNewer = remotePl.updatedEpochSec > localEpochSec + 2L ||
                    (existingTracks.isEmpty() && remotePl.tracks.isNotEmpty())
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
            }
        }
        MochyCloudVault.saveSyncedPlaylistIds(app, session.userId, finalLocal.map { it.id }.toSet())
        return finalLocal.size
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
        val refreshed = MochySupabaseClient.refreshSession(app, current)
        if (refreshed != null) {
            MochyCloudVault.saveSession(app, refreshed)
            return refreshed
        }
        return current
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

    private fun ensureSongHistoryInLocalDb(db: MusicDatabase, record: CloudHistoryRecord) {
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
        db.insert(
            Event(
                songId = record.songId,
                timestamp = ts,
                playTime = record.totalPlayMs.coerceAtLeast(30_000L),
            ),
        )
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
                val artistId = ArtistEntity.generateArtistId()
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

    private fun isCloudTrackEligible(song: SongEntity): Boolean =
        !song.isLocal &&
            song.id.isNotBlank() &&
            song.id.length <= 64 &&
            !song.id.startsWith("LA") &&
            !song.id.contains("://") &&
            !song.id.contains("/")

    private fun database(context: Context): MusicDatabase =
        EntryPointAccessors.fromApplication(context.applicationContext, CloudSyncEntryPoint::class.java)
            .database()
}
