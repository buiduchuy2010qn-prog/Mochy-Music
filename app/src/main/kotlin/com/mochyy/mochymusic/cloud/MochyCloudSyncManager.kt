package com.mochyy.mochymusic.cloud

import android.content.Context
import android.net.Uri
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Event
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.di.CloudSyncEntryPoint
import com.mochyy.mochymusic.ui.screens.profile.ProfileStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

data class MochyCloudState(
    val isConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val avatarLocalPath: String? = null,
    val favoritesCount: Int = 0,
    val historyCount: Int = 0,
    val syncing: Boolean = false,
    val lastSyncEpochMs: Long = 0L,
    val lastError: String? = null,
)

/**
 * Điều phối Mochy Cloud gọn nhẹ:
 * - Đồng bộ hai chiều danh sách Yêu thích (`mochy_favorites`) và Lịch sử nghe (`mochy_history`).
 * - Đồng bộ ảnh đại diện WebP siêu nhẹ (`<= 24 KB`) + tên hiển thị.
 * - Tuyệt đối không đồng bộ dữ liệu nặng hoặc nhật ký nhạy cảm.
 */
object MochyCloudSyncManager {

    private val _state = MutableStateFlow(MochyCloudState())
    val state: StateFlow<MochyCloudState> = _state.asStateFlow()

    private val syncMutex = Mutex()
    private val historyPlayCountCache = ConcurrentHashMap<String, Pair<Long, Long>>()
    private var appContext: Context? = null
    private var appScope: CoroutineScope? = null
    private var favoritesObserverJob: Job? = null
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
            startFavoritesObserver(app, scope)
            scope.launch(Dispatchers.IO) {
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
                ProfileStore.save(app, session.displayName, localAvatarFile?.let(Uri::fromFile) ?: avatarUri)
            } else {
                ProfileStore.save(app, session.displayName, null)
            }
            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = session.username,
                displayName = session.displayName,
                avatarLocalPath = localAvatarPath,
                lastError = null,
            )
            appScope?.let { startFavoritesObserver(app, it) }
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
            ProfileStore.save(app, session.displayName, null)
            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = session.username,
                displayName = session.displayName,
                lastError = null,
            )
            appScope?.let { startFavoritesObserver(app, it) }
            syncNowInternal(app)
        }.onFailure { err ->
            _state.value = _state.value.copy(lastError = err.message)
        }
    }

    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        favoritesObserverJob?.cancel()
        favoritesObserverJob = null
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
            ProfileStore.save(app, updatedName, localFile?.let(Uri::fromFile))
        }
        _state.value = _state.value.copy(
            displayName = updatedName,
            avatarLocalPath = localFile?.absolutePath ?: _state.value.avatarLocalPath,
            lastError = null,
        )
    }.onFailure { err ->
        _state.value = _state.value.copy(lastError = err.message)
    }

    fun syncNow(context: Context) {
        val scope = appScope ?: return
        scope.launch(Dispatchers.IO) {
            syncNowInternal(context.applicationContext)
        }
    }

    fun onSongPlayed(context: Context, songId: String, playTimeMs: Long) {
        if (songId.isBlank() || playTimeMs < 5_000L) return
        val app = context.applicationContext
        val scope = appScope ?: return
        if (MochyCloudVault.loadSession(app) == null) return
        scope.launch(Dispatchers.IO) {
            val session = getValidSession(app) ?: return@launch
            val db = database(app)
            val song = db.getSongByIdBlocking(songId) ?: return@launch
            val prev = historyPlayCountCache[songId] ?: (0L to 0L)
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
            runCatching {
                MochySupabaseClient.upsertHistory(app, session, listOf(record))
                _state.value = _state.value.copy(
                    historyCount = maxOf(_state.value.historyCount, historyPlayCountCache.size),
                    lastSyncEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun startFavoritesObserver(app: Context, scope: CoroutineScope) {
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
    }

    private suspend fun syncNowInternal(app: Context) = syncMutex.withLock {
        val session = getValidSession(app) ?: return@withLock
        _state.value = _state.value.copy(syncing = true, lastError = null)
        try {
            val db = database(app)

            // 1. Hồ sơ & ảnh đại diện nhẹ
            val profile = MochySupabaseClient.fetchProfile(app, session)
            var avatarPath = _state.value.avatarLocalPath
            var activeDisplay = session.displayName
            if (profile != null) {
                if (profile.displayName.isNotBlank()) {
                    activeDisplay = profile.displayName
                }
                val decodedAvatar = profile.avatarWebp?.let { MochyAvatarCodec.decodeDataUriToFile(app, it) }
                if (decodedAvatar != null) {
                    avatarPath = decodedAvatar.absolutePath
                }
                ProfileStore.save(app, activeDisplay, decodedAvatar?.let(Uri::fromFile))
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
                localById[id]?.takeIf { !remoteById.containsKey(id) }?.let { song ->
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

            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = session.username,
                displayName = activeDisplay,
                avatarLocalPath = avatarPath,
                favoritesCount = finalSyncedIds.size,
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

    private fun database(context: Context): MusicDatabase =
        EntryPointAccessors.fromApplication(context.applicationContext, CloudSyncEntryPoint::class.java)
            .database()
}
