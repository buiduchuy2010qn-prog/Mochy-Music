package com.mochyy.mochymusic.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.constants.AudioNormalizationKey
import com.mochyy.mochymusic.constants.SkipSilenceKey
import com.mochyy.mochymusic.constants.AudioQualityKey
import com.mochyy.mochymusic.constants.CanvasBestQualityKey
import com.mochyy.mochymusic.constants.PlayerCoverZoomKey
import com.mochyy.mochymusic.constants.ProfileCardNumberKey
import com.mochyy.mochymusic.constants.ProfileJoinedEpochDayKey
import com.mochyy.mochymusic.constants.ProfilePicUriKey
import com.mochyy.mochymusic.constants.UsernameKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.daos.SpeedDialDao
import com.mochyy.mochymusic.db.entities.AlbumArtistMap
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Event
import com.mochyy.mochymusic.db.entities.Playlist
import com.mochyy.mochymusic.db.entities.PlaylistEntity
import com.mochyy.mochymusic.db.entities.PlaylistSongMap
import com.mochyy.mochymusic.db.entities.RecognitionHistory
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SpeedDialItem
import com.mochyy.mochymusic.di.AudioLabEntryPoint
import com.mochyy.mochymusic.di.CloudSyncEntryPoint
import com.mochyy.mochymusic.eq.AudioLabStore
import com.mochyy.mochymusic.eq.EightDSettings
import com.mochyy.mochymusic.eq.EqSettings
import com.mochyy.mochymusic.eq.LoudnessSettings
import com.mochyy.mochymusic.eq.NormalizeSettings
import com.mochyy.mochymusic.eq.PlaybackSettings
import com.mochyy.mochymusic.eq.ReverbSettings
import com.mochyy.mochymusic.eq.SpatialSettings
import com.mochyy.mochymusic.eq.VolumeSettings
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.screens.profile.ProfileStore
import com.mochyy.mochymusic.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
    val deviceId: String = "",
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

typealias MochyPlaybackResumeSnapshot = CloudPlaybackResume

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
    val googleAuthInProgress: Boolean = false,
    val linkedGoogleEmail: String? = null,
    val hasGoogleLinked: Boolean = false,
    val hasPasswordLogin: Boolean = true,
    val authStatusMessage: String? = null,
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
    private const val MaxCloudRecognitionItems = 35
    private const val MaxCloudLyricsOffsets = 300
    private const val ExtraSyncPrefsName = "mochy_cloud_extra_sync"
    private const val SourceModulesPrefsName = "source_modules"
    private const val MonochromePrimaryPrefsName = "module_monochrome"
    private const val MonochromeLegacyPrefsName = "module_mochy.source.monochrome"
    private const val CloudSyncStampKey = "cloud_sync_stamp"
    internal val ValidSpeedDialTypes = setOf("SONG", "ALBUM", "ARTIST", "PLAYLIST")

    private val audioLabJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(MochyCloudState())
    val state: StateFlow<MochyCloudState> = _state.asStateFlow()

    private val syncMutex = Mutex()
    private val sessionRefreshMutex = Mutex()
    private val historyFlushMutex = Mutex()
    private val googleAuthMutex = Mutex()
    private val historyPlayCountCache = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val pendingHistoryMap = ConcurrentHashMap<String, CloudHistoryRecord>()
    private val fallbackSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncScope: CoroutineScope get() = appScope ?: fallbackSyncScope
    private var appContext: Context? = null
    private var appScope: CoroutineScope? = null
    private var favoritesObserverJob: Job? = null
    private var playlistsObserverJob: Job? = null
    private var historyFlushJob: Job? = null
    private var favoriteToggleSyncJob: Job? = null
    private var playbackResumePushJob: Job? = null
    private var suppressObserverUntilMs: Long = 0L

    private data class PendingResumeSnapshot(
        val song: MediaMetadata,
        val positionMs: Long,
        val queueTracks: List<CloudPlaylistTrack>,
    )

    @Volatile
    private var pendingResumeSnapshot: PendingResumeSnapshot? = null

    @Volatile
    private var lastAutoResumePushKey: String? = null

    @Volatile
    private var lastAutoResumePushAtMs: Long = 0L

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
        val savedGoogleEmail = if (configured && cached != null) MochyCloudVault.loadGoogleEmail(app) else null
        _state.value = _state.value.copy(
            isConfigured = configured,
            signedIn = configured && cached != null,
            username = cached?.username.orEmpty(),
            displayName = cached?.displayName.orEmpty(),
            linkedGoogleEmail = savedGoogleEmail,
            hasGoogleLinked = savedGoogleEmail != null,
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
            MochyCloudVault.saveGoogleEmail(app, null)
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
                googleAuthInProgress = false,
                linkedGoogleEmail = null,
                hasGoogleLinked = false,
                hasPasswordLogin = true,
                authStatusMessage = "Đăng ký tài khoản @${session.username} thành công! Bạn có thể liên kết thêm Google bên dưới.",
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
            val (session, identity) = MochySupabaseClient.signInDetailed(app, username, password)
            MochyCloudVault.saveSession(app, session)
            MochyCloudVault.saveGoogleEmail(app, identity.googleEmail)
            val existingAvatar = readLocalAvatarFile(app)
            ProfileStore.save(app, session.displayName, null, syncToCloud = false)
            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = session.username,
                displayName = session.displayName,
                avatarLocalPath = existingAvatar?.absolutePath ?: _state.value.avatarLocalPath,
                googleAuthInProgress = false,
                linkedGoogleEmail = identity.googleEmail,
                hasGoogleLinked = identity.hasGoogleLinked,
                hasPasswordLogin = true,
                authStatusMessage = "Đăng nhập @${session.username} thành công — đang đồng bộ dữ liệu…",
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

    fun clearAuthStatusMessage() {
        if (_state.value.authStatusMessage != null) {
            _state.value = _state.value.copy(authStatusMessage = null)
        }
    }

    private fun launchOAuthCustomTab(context: Context, authorizeUrl: String) {
        val app = context.applicationContext
        val uri = Uri.parse(authorizeUrl)
        val customTabBundle = Bundle().apply {
            putBinder("android.support.customtabs.extra.SESSION", null)
        }
        val baseIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtras(customTabBundle)
            putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF121212.toInt())
            putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val chromeIntent = Intent(baseIntent).setPackage("com.android.chrome")
        val launchIntent = if (app.packageManager.resolveActivity(chromeIntent, 0) != null) {
            chromeIntent
        } else {
            baseIntent
        }
        try {
            context.startActivity(launchIntent)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(fallback)
        }
    }

    fun startGoogleSignIn(
        context: Context,
        loginHintEmail: String? = null,
    ): Result<Unit> = runCatching {
        val app = context.applicationContext
        val (baseUrl, _) = MochyCloudVault.revealEndpoint(app)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")
        val verifier = MochyGoogleOAuthHelper.generatePkceVerifier()
        val challenge = MochyGoogleOAuthHelper.computePkceChallenge(verifier)
        MochyCloudVault.savePendingPkceFlow(app, verifier, MochyCloudVault.PkceModeSignIn)
        val authorizeUrl = MochyGoogleOAuthHelper.buildGoogleAuthorizeUrl(
            baseUrl = baseUrl,
            codeChallenge = challenge,
            loginHintEmail = loginHintEmail,
        )
        launchOAuthCustomTab(context, authorizeUrl)
        _state.value = _state.value.copy(
            googleAuthInProgress = true,
            lastError = null,
            authStatusMessage = null,
        )
    }.onFailure { err ->
        MochyCloudVault.consumePendingPkceFlow(context.applicationContext)
        val msg = err.message?.takeIf { it.isNotBlank() }
            ?: "Không tìm thấy trình duyệt để đăng nhập Google."
        _state.value = _state.value.copy(
            googleAuthInProgress = false,
            lastError = msg,
        )
    }

    suspend fun startLinkGoogleAccount(
        context: Context,
        loginHintEmail: String? = null,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val session = getValidSession(app)
                ?: throw MochyCloudException(lastSessionFailureReason ?: "Bạn chưa đăng nhập Mochy Cloud.")
            _state.value = _state.value.copy(
                googleAuthInProgress = true,
                lastError = null,
                authStatusMessage = null,
            )
            val verifier = MochyGoogleOAuthHelper.generatePkceVerifier()
            val challenge = MochyGoogleOAuthHelper.computePkceChallenge(verifier)
            val authorizeUrl = MochySupabaseClient.requestGoogleLinkAuthorizeUrl(
                context = app,
                session = session,
                codeChallenge = challenge,
                loginHintEmail = loginHintEmail,
            )
            MochyCloudVault.savePendingPkceFlow(app, verifier, MochyCloudVault.PkceModeLink)
            withContext(Dispatchers.Main) {
                launchOAuthCustomTab(context, authorizeUrl)
            }
            Unit
        }.onFailure { err ->
            MochyCloudVault.consumePendingPkceFlow(context.applicationContext)
            if (err is MochyCloudAuthExpiredException) {
                handleSessionExpired(
                    context.applicationContext,
                    err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.",
                )
            } else {
                val msg = err.message?.takeIf { it.isNotBlank() }
                    ?: "Không thể bắt đầu liên kết tài khoản Google."
                _state.value = _state.value.copy(
                    googleAuthInProgress = false,
                    lastError = msg,
                )
            }
        }
    }

    fun cancelGoogleAuthInProgress() {
        if (_state.value.googleAuthInProgress) {
            _state.value = _state.value.copy(googleAuthInProgress = false)
        }
    }

    fun handleGoogleOAuthCallback(context: Context, uri: Uri) {
        val app = context.applicationContext
        appContext = app
        _state.value = _state.value.copy(
            googleAuthInProgress = true,
            lastError = null,
        )
        syncScope.launch(NonCancellable + Dispatchers.IO) {
            googleAuthMutex.withLock {
                val parsed = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
                    rawQuery = uri.encodedQuery,
                    rawFragment = uri.encodedFragment,
                )
                val pendingFlow = MochyCloudVault.consumePendingPkceFlow(app)
                val isLinkMode = pendingFlow?.mode == MochyCloudVault.PkceModeLink

                if (!parsed.error.isNullOrBlank() ||
                    !parsed.errorCode.isNullOrBlank() ||
                    !parsed.errorDescription.isNullOrBlank()
                ) {
                    val combinedError = listOfNotNull(parsed.errorCode, parsed.error)
                        .joinToString(" ")
                        .ifBlank { null }
                    val msg = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese(
                        error = combinedError,
                        errorDescription = parsed.errorDescription,
                    )
                    _state.value = _state.value.copy(
                        googleAuthInProgress = false,
                        lastError = msg,
                    )
                    withContext(Dispatchers.Main) {
                        TopToaster.error(msg)
                    }
                    return@withLock
                }

                val verifier = pendingFlow?.verifier
                if (!parsed.code.isNullOrBlank() && verifier == null) {
                    if (_state.value.signedIn && MochyCloudVault.loadSession(app) != null) {
                        _state.value = _state.value.copy(googleAuthInProgress = false)
                        return@withLock
                    }
                    val expiredMsg = "Phiên xác thực Google đã hết hạn, vui lòng thử lại."
                    _state.value = _state.value.copy(
                        googleAuthInProgress = false,
                        lastError = expiredMsg,
                    )
                    withContext(Dispatchers.Main) {
                        TopToaster.error(expiredMsg)
                    }
                    return@withLock
                }

                if (isLinkMode) {
                    val existingSession = MochyCloudVault.loadSession(app)
                    val linkOutcome = runCatching {
                        val authResult: OAuthAuthResult = when {
                            !parsed.code.isNullOrBlank() && verifier != null -> {
                                MochySupabaseClient.exchangePkceCode(app, parsed.code, verifier)
                            }
                            !parsed.accessToken.isNullOrBlank() -> {
                                MochySupabaseClient.fetchSessionFromAccessToken(
                                    context = app,
                                    accessToken = parsed.accessToken,
                                    refreshToken = parsed.refreshToken.orEmpty(),
                                    expiresInSec = parsed.expiresIn ?: 3600L,
                                )
                            }
                            else -> {
                                throw MochyCloudException("Không nhận được phản hồi hợp lệ từ Google.")
                            }
                        }

                        var linkedSession = if (existingSession != null && authResult.session.userId == existingSession.userId) {
                            authResult.session.copy(
                                username = existingSession.username.ifBlank { authResult.session.username },
                                displayName = existingSession.displayName.ifBlank { authResult.session.displayName },
                            )
                        } else {
                            authResult.session
                        }
                        MochyCloudVault.saveSession(app, linkedSession)

                        val freshMeta = runCatching {
                            MochySupabaseClient.fetchUserMetadata(app, linkedSession)
                        }.getOrNull()
                        val resolvedGoogleEmail = freshMeta?.googleEmail
                            ?: authResult.email
                            ?: MochyCloudVault.loadGoogleEmail(app)
                        val resolvedUsername = freshMeta?.username?.takeIf { it.isNotBlank() }
                            ?: linkedSession.username
                        if (resolvedUsername != linkedSession.username) {
                            linkedSession = linkedSession.copy(username = resolvedUsername)
                            MochyCloudVault.saveSession(app, linkedSession)
                        }
                        MochyCloudVault.saveGoogleEmail(app, resolvedGoogleEmail)

                        val statusMsg = "Đã liên kết Google (${resolvedGoogleEmail ?: "Google"}) vào tài khoản @${linkedSession.username}! Giờ bạn có thể đăng nhập bằng cả Google lẫn @${linkedSession.username}."
                        _state.value = _state.value.copy(
                            isConfigured = true,
                            signedIn = true,
                            username = linkedSession.username,
                            displayName = linkedSession.displayName,
                            hasGoogleLinked = freshMeta?.hasGoogleLinked ?: true,
                            linkedGoogleEmail = resolvedGoogleEmail,
                            hasPasswordLogin = freshMeta?.hasPasswordLogin ?: true,
                            googleAuthInProgress = false,
                            lastError = null,
                            authStatusMessage = statusMsg,
                        )
                        lastSessionFailureReason = null
                        lastSessionExpired = false
                        appScope?.let { startObservers(app, it) }
                        statusMsg
                    }

                    linkOutcome.onSuccess { statusMsg ->
                        withContext(Dispatchers.Main) {
                            TopToaster.show(statusMsg)
                        }
                        syncNowInternal(app, fromUser = true)
                    }.onFailure { err ->
                        val msg = err.message?.takeIf { it.isNotBlank() }
                            ?: "Liên kết tài khoản Google thất bại."
                        _state.value = _state.value.copy(
                            googleAuthInProgress = false,
                            lastError = msg,
                        )
                        withContext(Dispatchers.Main) {
                            TopToaster.error(msg)
                        }
                    }
                    return@withLock
                }

                val outcome = runCatching {
                    val authResult: OAuthAuthResult = when {
                        !parsed.code.isNullOrBlank() && verifier != null -> {
                            MochySupabaseClient.exchangePkceCode(app, parsed.code, verifier)
                        }
                        !parsed.accessToken.isNullOrBlank() -> {
                            MochySupabaseClient.fetchSessionFromAccessToken(
                                context = app,
                                accessToken = parsed.accessToken,
                                refreshToken = parsed.refreshToken.orEmpty(),
                                expiresInSec = parsed.expiresIn ?: 3600L,
                            )
                        }
                        else -> {
                            throw MochyCloudException("Không nhận được phản hồi hợp lệ từ Google.")
                        }
                    }

                    val initialSession = authResult.session
                    MochyCloudVault.saveSession(app, initialSession)

                    val existingProfileLookup = runCatching {
                        MochySupabaseClient.fetchProfile(app, initialSession)
                    }
                    val existingProfileBeforeEnsure = existingProfileLookup.getOrNull()
                    val hadNoProfileBeforeEnsure = existingProfileLookup.isSuccess && existingProfileBeforeEnsure == null

                    val freshMeta = runCatching {
                        MochySupabaseClient.fetchUserMetadata(app, initialSession)
                    }.getOrNull()
                    val resolvedGoogleEmail = freshMeta?.googleEmail ?: authResult.email
                    MochyCloudVault.saveGoogleEmail(app, resolvedGoogleEmail)

                    val createdAtIso = freshMeta?.createdAtIso ?: authResult.createdAtIso
                    val lastSignInAtIso = freshMeta?.lastSignInAtIso ?: authResult.lastSignInAtIso
                    val isNewAccount = MochyGoogleOAuthHelper.isNewlyRegisteredAccount(createdAtIso, lastSignInAtIso) ||
                        hadNoProfileBeforeEnsure

                    var effectiveSession = initialSession
                    val effectiveProfile: CloudProfileRecord? = if (existingProfileBeforeEnsure != null) {
                        existingProfileBeforeEnsure
                    } else {
                        val ensuredUsername = MochySupabaseClient.ensureProfileRow(
                            context = app,
                            session = initialSession,
                            displayName = initialSession.displayName,
                        )
                        if (ensuredUsername != initialSession.username) {
                            effectiveSession = initialSession.copy(username = ensuredUsername)
                            MochyCloudVault.saveSession(app, effectiveSession)
                        }
                        runCatching {
                            MochySupabaseClient.fetchProfile(app, effectiveSession)
                        }.getOrNull()
                    }
                    if (effectiveProfile != null) {
                        val resolvedUsername = effectiveProfile.username.trim().ifBlank { effectiveSession.username }
                        val resolvedDisplay = effectiveProfile.displayName.trim().ifBlank { effectiveSession.displayName }
                        if (resolvedUsername != effectiveSession.username || resolvedDisplay != effectiveSession.displayName) {
                            effectiveSession = effectiveSession.copy(
                                username = resolvedUsername,
                                displayName = resolvedDisplay,
                            )
                            MochyCloudVault.saveSession(app, effectiveSession)
                        }
                    }

                    MochySupabaseClient.updateIdentityMetadata(
                        context = app,
                        session = effectiveSession,
                        username = effectiveSession.username,
                        displayName = effectiveSession.displayName,
                    )

                    val existingLocalAvatar = readLocalAvatarFile(app)
                    var resolvedAvatarPath = existingLocalAvatar?.absolutePath
                    val remoteAvatarFile = effectiveProfile?.avatarWebp?.let {
                        MochyAvatarCodec.decodeDataUriToFile(app, it)
                    }
                    if (remoteAvatarFile != null) {
                        resolvedAvatarPath = remoteAvatarFile.absolutePath
                        ProfileStore.save(
                            app,
                            effectiveSession.displayName,
                            Uri.fromFile(remoteAvatarFile),
                            syncToCloud = false,
                        )
                    } else if (existingLocalAvatar == null && !authResult.avatarUrl.isNullOrBlank()) {
                        val importedAvatarFile = importGoogleAvatar(app, effectiveSession, authResult.avatarUrl)
                        if (importedAvatarFile != null) {
                            resolvedAvatarPath = importedAvatarFile.absolutePath
                            ProfileStore.save(
                                app,
                                effectiveSession.displayName,
                                Uri.fromFile(importedAvatarFile),
                                syncToCloud = false,
                            )
                        } else {
                            ProfileStore.save(app, effectiveSession.displayName, null, syncToCloud = false)
                        }
                    } else {
                        ProfileStore.save(app, effectiveSession.displayName, null, syncToCloud = false)
                    }

                    val savedEmail = MochyCloudVault.loadGoogleEmail(app) ?: resolvedGoogleEmail
                    val hasGoogleLinked = freshMeta?.hasGoogleLinked ?: authResult.hasGoogleLinked
                    val hasPasswordLogin = freshMeta?.hasPasswordLogin ?: authResult.hasPasswordLogin
                    val finalUsername = effectiveSession.username
                    val statusMsg = if (isNewAccount) {
                        "Đăng ký tài khoản mới bằng Google thành công! Tên đăng nhập của bạn: @$finalUsername. Bạn có thể đặt thêm mật khẩu bên dưới để đăng nhập bằng @$finalUsername trên máy khác."
                    } else {
                        "Tài khoản Google này đã được đăng ký từ trước (@$finalUsername) — đã đăng nhập lại và đang đồng bộ dữ liệu."
                    }

                    _state.value = _state.value.copy(
                        isConfigured = true,
                        signedIn = true,
                        username = effectiveSession.username,
                        displayName = effectiveSession.displayName,
                        avatarLocalPath = resolvedAvatarPath ?: _state.value.avatarLocalPath,
                        googleAuthInProgress = false,
                        linkedGoogleEmail = savedEmail,
                        hasGoogleLinked = hasGoogleLinked,
                        hasPasswordLogin = hasPasswordLogin,
                        authStatusMessage = statusMsg,
                        lastError = null,
                    )
                    lastSessionFailureReason = null
                    lastSessionExpired = false
                    appScope?.let { startObservers(app, it) }
                    statusMsg
                }

                outcome.onSuccess { statusMsg ->
                    withContext(Dispatchers.Main) {
                        TopToaster.show(statusMsg)
                    }
                    syncNowInternal(app, fromUser = true)
                }.onFailure { err ->
                    val msg = err.message?.takeIf { it.isNotBlank() }
                        ?: "Đăng nhập bằng Google thất bại."
                    _state.value = _state.value.copy(
                        googleAuthInProgress = false,
                        lastError = msg,
                    )
                    withContext(Dispatchers.Main) {
                        TopToaster.error(msg)
                    }
                }
            }
        }
    }

    private suspend fun importGoogleAvatar(
        app: Context,
        session: MochyCloudSession,
        avatarUrl: String,
    ): File? {
        val rawBytes = MochySupabaseClient.downloadImageBytes(avatarUrl) ?: return null
        val tmpFile = File(app.cacheDir, "mochy_google_avatar_${System.currentTimeMillis()}.img")
        return try {
            tmpFile.writeBytes(rawBytes)
            val webpDataUri = MochyAvatarCodec.encodeUriToWebpDataUri(app, Uri.fromFile(tmpFile))
                ?: return null
            runCatching {
                MochySupabaseClient.updateProfile(app, session, session.displayName, webpDataUri)
            }
            MochyAvatarCodec.decodeDataUriToFile(app, webpDataUri)
        } catch (_: Throwable) {
            null
        } finally {
            tmpFile.delete()
        }
    }

    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        cancelBackgroundJobs()
        pendingHistoryMap.clear()
        val old = MochyCloudVault.loadSession(app)
        MochyCloudVault.clearSession(app)
        clearExtraSyncState(app)
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

    suspend fun updateUsername(
        context: Context,
        rawNewUsername: String,
    ): Result<String> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val clean = MochySupabaseClient.normalizeUsername(rawNewUsername).removePrefix("@")
            if (!MochySupabaseClient.isValidUsername(clean)) {
                throw MochyCloudException("Tên đăng nhập 3–24 ký tự, chỉ gồm chữ thường, số, dấu chấm hoặc gạch dưới.")
            }
            val session = getValidSession(app)
                ?: throw MochyCloudException(lastSessionFailureReason ?: "Bạn chưa đăng nhập Mochy Cloud.")
            val updated = MochySupabaseClient.updateUsername(app, session, clean)
            val latestSession = MochyCloudVault.loadSession(app)
                ?.takeIf { it.userId == session.userId }
                ?: session
            MochyCloudVault.saveSession(app, latestSession.copy(username = updated))
            _state.value = _state.value.copy(
                username = updated,
                authStatusMessage = "Đã đổi tên đăng nhập thành @$updated.",
                lastError = null,
            )
            updated
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
            val hadPasswordBefore = _state.value.hasPasswordLogin
            MochySupabaseClient.updatePassword(app, session, newPassword)
            val effectiveUsername = _state.value.username.ifBlank { session.username }
            val statusMsg = if (!hadPasswordBefore) {
                "Đã đặt mật khẩu thành công! Giờ bạn có thể sang máy khác đăng nhập trực tiếp bằng @$effectiveUsername hoặc Google đều vào cùng 1 tài khoản."
            } else {
                "Đã đổi mật khẩu Mochy Cloud thành công."
            }
            _state.value = _state.value.copy(
                hasPasswordLogin = true,
                authStatusMessage = statusMsg,
                lastError = null,
            )
            Unit
        }.onFailure { err ->
            if (err is MochyCloudAuthExpiredException) {
                handleSessionExpired(context.applicationContext, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
            } else {
                _state.value = _state.value.copy(lastError = err.message)
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
                clearExtraSyncState(app)
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

    fun updatePlaybackResumeSnapshot(
        song: MediaMetadata?,
        positionMs: Long,
        queueTracks: List<CloudPlaylistTrack>,
    ) {
        if (song == null || song.localUri != null || !isCloudSafeId(song.id)) {
            pendingResumeSnapshot = null
            return
        }
        pendingResumeSnapshot = PendingResumeSnapshot(
            song = song,
            positionMs = positionMs.coerceAtLeast(0L),
            queueTracks = queueTracks,
        )
    }

    fun cancelScheduledPlaybackResumePush() {
        playbackResumePushJob?.cancel()
        playbackResumePushJob = null
    }

    fun schedulePlaybackResumePush(context: Context, delayMs: Long = 4_000L) {
        val app = context.applicationContext
        if (!MochyCloudVault.isConfigured(app) || MochyCloudVault.loadSession(app) == null) return
        val snapshot = pendingResumeSnapshot ?: return
        if (snapshot.song.localUri != null || !isCloudSafeId(snapshot.song.id)) return
        val dedupKey = "${snapshot.song.id}:${snapshot.positionMs / 1000L}"
        if (delayMs <= 0L) {
            val now = System.currentTimeMillis()
            if (lastAutoResumePushKey == dedupKey && now - lastAutoResumePushAtMs < 3_000L) return
            playbackResumePushJob?.cancel()
            lastAutoResumePushKey = dedupKey
            lastAutoResumePushAtMs = now
            playbackResumePushJob = syncScope.launch(NonCancellable + Dispatchers.IO) {
                pushPlaybackResumeInternal(
                    app = app,
                    song = snapshot.song,
                    positionMs = snapshot.positionMs,
                    queueTracks = snapshot.queueTracks,
                    updateErrorState = false,
                )
            }
            return
        }
        playbackResumePushJob?.cancel()
        playbackResumePushJob = syncScope.launch(Dispatchers.IO) {
            delay(delayMs)
            val latest = pendingResumeSnapshot ?: return@launch
            if (latest.song.localUri != null || !isCloudSafeId(latest.song.id)) return@launch
            val latestKey = "${latest.song.id}:${latest.positionMs / 1000L}"
            val now = System.currentTimeMillis()
            if (lastAutoResumePushKey == latestKey && now - lastAutoResumePushAtMs < 3_000L) return@launch
            lastAutoResumePushKey = latestKey
            lastAutoResumePushAtMs = now
            pushPlaybackResumeInternal(
                app = app,
                song = latest.song,
                positionMs = latest.positionMs,
                queueTracks = latest.queueTracks,
                updateErrorState = false,
            )
        }
    }

    suspend fun pushPlaybackResume(
        context: Context,
        song: MediaMetadata,
        positionMs: Long,
        queueTracks: List<CloudPlaylistTrack>,
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        pushPlaybackResumeInternal(
            app = context.applicationContext,
            song = song,
            positionMs = positionMs,
            queueTracks = queueTracks,
            updateErrorState = true,
        )
    }

    private suspend fun pushPlaybackResumeInternal(
        app: Context,
        song: MediaMetadata,
        positionMs: Long,
        queueTracks: List<CloudPlaylistTrack>,
        updateErrorState: Boolean,
    ): Result<Unit> = runCatching {
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
            lastError = if (updateErrorState) null else _state.value.lastError,
        )
    }.onFailure { err ->
        if (err is MochyCloudAuthExpiredException) {
            handleSessionExpired(app, err.message ?: "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
        } else if (updateErrorState) {
            _state.value = _state.value.copy(lastError = err.message)
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

    @JvmOverloads
    fun onFavoriteToggled(
        context: Context,
        songId: String? = null,
        liked: Boolean? = null,
    ) {
        val app = context.applicationContext
        if (!MochyCloudVault.isConfigured(app) || MochyCloudVault.loadSession(app) == null) return
        if (favoritesObserverJob?.isActive == true) return
        favoriteToggleSyncJob?.cancel()
        favoriteToggleSyncJob = syncScope.launch(Dispatchers.IO) {
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

            // 4. Playlist cá nhân hai chiều (Base64 <= 5.600 ký tự, tối đa 25 playlist)
            val playlistsCount = if (remoteMeta != null) {
                syncPersonalPlaylists(app, activeSession, db, remoteMeta.playlistsGz)
            } else {
                db.playlistsByCreateDateAsc().first().count { isSyncablePlaylist(it.playlist) }
            }

            // 5. Album đã lưu, Nghệ sĩ theo dõi & Equalizer (`library_extra_gz` <= 3.800 ký tự)
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
            if (remoteMeta != null &&
                decodedRemoteResume != null &&
                (remoteMeta.playbackResumeGz?.length ?: 0) > MochyPlaybackResumeCodec.MaxEncodedChars
            ) {
                val compactedResume = MochyPlaybackResumeCodec.encode(decodedRemoteResume)
                if (compactedResume.isNotEmpty() && compactedResume != remoteMeta.playbackResumeGz) {
                    runCatching {
                        MochySupabaseClient.updateUserMetadata(
                            context = app,
                            session = finalSession,
                            playbackResumeGz = compactedResume,
                        )
                    }.onFailure { err -> if (err is MochyCloudAuthExpiredException) throw err }
                }
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
            val resolvedUsername = profile?.username?.trim()?.takeIf { it.isNotBlank() }
                ?: remoteMeta?.username?.trim()?.takeIf { it.isNotBlank() }
                ?: finalSession.username
            if (resolvedUsername != finalSession.username || activeDisplay != finalSession.displayName) {
                MochyCloudVault.saveSession(
                    app,
                    finalSession.copy(
                        username = resolvedUsername,
                        displayName = activeDisplay,
                    ),
                )
            }
            val resolvedGoogleEmail = if (remoteMeta != null) {
                remoteMeta.googleEmail?.also { MochyCloudVault.saveGoogleEmail(app, it) }
                    ?: run {
                        if (!remoteMeta.hasGoogleLinked) {
                            MochyCloudVault.saveGoogleEmail(app, null)
                            null
                        } else {
                            MochyCloudVault.loadGoogleEmail(app) ?: _state.value.linkedGoogleEmail
                        }
                    }
            } else {
                MochyCloudVault.loadGoogleEmail(app) ?: _state.value.linkedGoogleEmail
            }
            val resolvedHasGoogle = remoteMeta?.hasGoogleLinked ?: _state.value.hasGoogleLinked
            val resolvedHasPassword = remoteMeta?.hasPasswordLogin ?: _state.value.hasPasswordLogin
            _state.value = _state.value.copy(
                isConfigured = true,
                signedIn = true,
                username = resolvedUsername,
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
                hasGoogleLinked = resolvedHasGoogle,
                hasPasswordLogin = resolvedHasPassword,
                linkedGoogleEmail = resolvedGoogleEmail,
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

        for (rawRemotePl in remotePlaylists) {
            if (rawRemotePl.id in removedLocally) continue
            val cleanRemoteTracks = rawRemotePl.tracks.filter { isCloudSafeId(it.songId) }
            val remotePl = if (cleanRemoteTracks.size == rawRemotePl.tracks.size) {
                rawRemotePl
            } else {
                rawRemotePl.copy(tracks = cleanRemoteTracks)
            }
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
                val existingTrackIds = existingTracks
                    .filter { isCloudTrackEligible(it.song.song) }
                    .take(200)
                    .map { it.song.id }
                val localOnlySongIds = existingTracks
                    .asSequence()
                    .filter { !isCloudTrackEligible(it.song.song) }
                    .map { it.song.id }
                    .filter { it.isNotBlank() }
                    .toList()
                val remoteTrackIds = remotePl.tracks.map { it.songId }
                val remoteIsNewer = remotePl.updatedEpochSec > localEpochSec + 2L ||
                    (existingTrackIds.isEmpty() && remotePl.tracks.isNotEmpty()) ||
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
                    writePlaylistTracks(db, remotePl, preservedLocalSongIds = localOnlySongIds)
                }
            }
        }

        val finalLocal = db.playlistsByCreateDateAsc().first()
            .filter { isSyncablePlaylist(it.playlist) }
            .take(25)
        val recordsToPush = finalLocal.map { pl ->
            val songs = db.playlistSongs(pl.id).first()
                .asSequence()
                .filter { isCloudTrackEligible(it.song.song) }
                .take(200)
                .toList()
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
                        thumbnailUrl = sanitizeHttpUrl(ps.song.thumbnailUrl),
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

        // C. Cài đặt Audio Lab (Equalizer + Reverb + Loudness + Spatial + Normalize + Volume + Playback) & âm thanh
        val audioLab = audioLab(app)
        val initialPrefs = app.dataStore.data.first()
        val initialAudio = snapshotLocalAudioConfig(audioLab, initialPrefs)
        val initialAudioDigest = sha256Hex(canonicalAudioConfig(initialAudio))
        val prevEqDigest = MochyCloudVault.loadSyncedEqDigest(app, session.userId)
        val remoteAudio = remoteExtra?.audio

        if (remoteAudio != null) {
            val mergedRemoteAudio = CloudAudioConfig(
                eqSettingsJson = remoteAudio.eqSettingsJson ?: initialAudio.eqSettingsJson,
                audioNormalization = remoteAudio.audioNormalization ?: initialAudio.audioNormalization,
                audioQuality = remoteAudio.audioQuality ?: initialAudio.audioQuality,
                reverbJson = remoteAudio.reverbJson ?: initialAudio.reverbJson,
                loudnessJson = remoteAudio.loudnessJson ?: initialAudio.loudnessJson,
                spatialJson = remoteAudio.spatialJson ?: initialAudio.spatialJson,
                normalizeJson = remoteAudio.normalizeJson ?: initialAudio.normalizeJson,
                volumeJson = remoteAudio.volumeJson ?: initialAudio.volumeJson,
                playbackJson = remoteAudio.playbackJson ?: initialAudio.playbackJson,
                skipSilence = remoteAudio.skipSilence ?: initialAudio.skipSilence,
                eightDJson = remoteAudio.eightDJson ?: initialAudio.eightDJson,
                playerCoverZoom = remoteAudio.playerCoverZoom ?: initialAudio.playerCoverZoom,
                canvasBestQuality = remoteAudio.canvasBestQuality ?: initialAudio.canvasBestQuality,
                deviceProfilesJson = remoteAudio.deviceProfilesJson ?: initialAudio.deviceProfilesJson,
                deviceProfilesEnabled = remoteAudio.deviceProfilesEnabled ?: initialAudio.deviceProfilesEnabled,
            )
            val remoteAudioDigest = sha256Hex(canonicalAudioConfig(mergedRemoteAudio))
            if (prevEqDigest == null) {
                applyAudioConfig(app, audioLab, remoteAudio, onlyIfDefault = true)
            } else if (initialAudioDigest == prevEqDigest && remoteAudioDigest != prevEqDigest) {
                applyAudioConfig(app, audioLab, remoteAudio, onlyIfDefault = false)
            } else if (!remoteAudio.deviceProfilesJson.isNullOrBlank()) {
                // Per-device profiles use per-device LWW timestamps, so merging across devices never clobbers newer edits
                audioLab.mergeDeviceProfilesJson(remoteAudio.deviceProfilesJson, onlyIfEmpty = false)
            }
        }

        // D. Speed Dial (tối đa 9 mục ghim)
        val initialSpeedDial = db.speedDialDao.getAllOnce(SpeedDialDao.MaxPinned)
        val localSpeedDialMap = initialSpeedDial.associateBy { it.id }
        val prevSyncedSpeedDial = loadExtraIdSet(app, session.userId, "speedDialIds")
        val removedSpeedDialLocally = prevSyncedSpeedDial?.let { it - localSpeedDialMap.keys }.orEmpty()

        for (remoteSd in remoteExtra?.speedDial.orEmpty()) {
            if (remoteSd.id in removedSpeedDialLocally) continue
            if (remoteSd.id.isBlank() || remoteSd.title.isBlank() || remoteSd.type !in ValidSpeedDialTypes) continue
            if (localSpeedDialMap[remoteSd.id] == null) {
                mergeSpeedDialItem(
                    db,
                    SpeedDialItem(
                        id = remoteSd.id,
                        secondaryId = remoteSd.secondaryId,
                        title = remoteSd.title,
                        subtitle = remoteSd.subtitle,
                        thumbnailUrl = sanitizeHttpUrl(remoteSd.thumbnailUrl),
                        type = remoteSd.type,
                        explicit = remoteSd.explicit,
                        createDate = remoteSd.createDate.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
            }
        }
        if (remoteExtra != null && !remoteExtra.speedDialTruncated && prevSyncedSpeedDial != null) {
            val remoteSdIds = remoteExtra.speedDial.map { it.id }.toSet()
            for (localSd in initialSpeedDial) {
                if (localSd.id in prevSyncedSpeedDial && localSd.id !in remoteSdIds) {
                    db.speedDialDao.delete(localSd.id)
                }
            }
        }

        // E. Lịch sử nhận diện nhạc Shazam (tối đa 35 mục gần nhất)
        val initialRecs = db.recognitionHistory().first()
        val localRecMap = initialRecs.associateBy { it.trackId }
        val prevSyncedRecIds = loadExtraIdSet(app, session.userId, "recTrackIds")
        val removedRecLocally = prevSyncedRecIds?.let { it - localRecMap.keys }.orEmpty()

        for (remoteRec in remoteExtra?.recognitionHistory.orEmpty()) {
            if (remoteRec.trackId.isBlank() || remoteRec.title.isBlank()) continue
            if (remoteRec.trackId in removedRecLocally) continue
            val existing = localRecMap[remoteRec.trackId]
            if (existing == null) {
                val ts = if (remoteRec.recognizedAtEpochMs > 0L) {
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(remoteRec.recognizedAtEpochMs), ZoneOffset.UTC)
                } else {
                    LocalDateTime.now()
                }
                db.insert(
                    RecognitionHistory(
                        trackId = remoteRec.trackId,
                        title = remoteRec.title,
                        artist = remoteRec.artist.ifBlank { "Nghệ sĩ" },
                        album = remoteRec.album,
                        coverArtUrl = sanitizeHttpUrl(remoteRec.coverArtUrl),
                        coverArtHqUrl = sanitizeHttpUrl(remoteRec.coverArtHqUrl),
                        genre = remoteRec.genre,
                        releaseDate = remoteRec.releaseDate,
                        label = remoteRec.label,
                        shazamUrl = sanitizeHttpUrl(remoteRec.shazamUrl),
                        appleMusicUrl = sanitizeHttpUrl(remoteRec.appleMusicUrl),
                        spotifyUrl = sanitizeHttpUrl(remoteRec.spotifyUrl),
                        isrc = remoteRec.isrc,
                        youtubeVideoId = remoteRec.youtubeVideoId,
                        recognizedAt = ts,
                        liked = remoteRec.liked,
                    ),
                )
            } else {
                if (!existing.liked && remoteRec.liked) {
                    db.updateRecognitionHistoryLiked(existing.id, true)
                }
                if (existing.youtubeVideoId.isNullOrBlank() && !remoteRec.youtubeVideoId.isNullOrBlank()) {
                    db.updateRecognitionHistoryYoutubeId(existing.id, remoteRec.youtubeVideoId)
                }
            }
        }
        if (remoteExtra != null && !remoteExtra.recognitionHistoryTruncated && prevSyncedRecIds != null) {
            val remoteRecIds = remoteExtra.recognitionHistory.map { it.trackId }.toSet()
            for (localRec in initialRecs) {
                if (localRec.trackId in prevSyncedRecIds && localRec.trackId !in remoteRecIds) {
                    db.delete(localRec)
                }
            }
        }

        // F. Độ lệch lời bài hát (lyricsOffset != 0, tối đa 300 bài)
        val prevOffsets = loadExtraOffsetsMap(app, session.userId)
        val remoteOffsets = remoteExtra?.lyricsOffsets.orEmpty()
        if (remoteExtra != null) {
            for ((songId, remoteOffset) in remoteOffsets) {
                if (!isCloudSafeId(songId) || remoteOffset == 0) continue
                val existing = db.getSongByIdBlocking(songId) ?: continue
                if (!isCloudTrackEligible(existing.song)) continue
                val localOffset = existing.song.lyricsOffset
                val prevVal = prevOffsets?.get(songId)
                val shouldApply = (prevVal == null && localOffset == 0) ||
                    (prevVal != null && localOffset == prevVal && remoteOffset != prevVal)
                if (shouldApply) {
                    db.updateSongLyricsOffset(songId, remoteOffset)
                }
            }
            if (!remoteExtra.lyricsOffsetsTruncated && prevOffsets != null) {
                for ((songId, prevVal) in prevOffsets) {
                    if (prevVal != 0 && !remoteOffsets.containsKey(songId)) {
                        val existing = db.getSongByIdBlocking(songId) ?: continue
                        if (existing.song.lyricsOffset == prevVal) {
                            db.updateSongLyricsOffset(songId, 0)
                        }
                    }
                }
            }
        }

        // G. Cấu hình Source/Look Modules ("source_modules" + "module_monochrome")
        val initialModules = readLocalModulesConfig(app)
        val initialModulesDigest = sha256Hex(canonicalModulesConfig(initialModules))
        val prevModulesDigest = loadExtraString(app, session.userId, "modulesDigest")
        val remoteModules = remoteExtra?.modulesConfig
        if (remoteModules != null) {
            val remoteModulesDigest = sha256Hex(canonicalModulesConfig(remoteModules))
            if (prevModulesDigest == null) {
                applyModulesConfigToPrefs(
                    context = app,
                    remote = remoteModules,
                    overwriteScalars = false,
                    overwriteArrays = false,
                )
            } else if (initialModulesDigest == prevModulesDigest && remoteModulesDigest != prevModulesDigest) {
                applyModulesConfigToPrefs(
                    context = app,
                    remote = remoteModules,
                    overwriteScalars = true,
                    overwriteArrays = true,
                )
            }
        }

        // Thu thập trạng thái cuối & đẩy lên user_metadata.library_extra_gz (< 24 KB gzip / <= 16 KB Base64)
        val finalAlbums = db.albumsLikedByCreateDateAsc().first()
            .filter { !it.album.isLocal && it.album.id.isNotBlank() }
        val finalArtists = db.artistsBookmarkedByCreateDateAsc().first()
            .filter { !it.artist.isLocal && it.artist.id.isNotBlank() }
        val finalSpeedDial = db.speedDialDao.getAllOnce(SpeedDialDao.MaxPinned)
        val finalRecs = db.recognitionHistory().first().take(MaxCloudRecognitionItems)
        val finalOffsets = db.songsWithNonZeroLyricsOffset(MaxCloudLyricsOffsets + 50)
            .asSequence()
            .filter { isCloudTrackEligible(it) && it.lyricsOffset != 0 }
            .take(MaxCloudLyricsOffsets)
            .associate { it.id to it.lyricsOffset }
        val finalModules = readLocalModulesConfig(app)
        val finalPrefs = app.dataStore.data.first()
        val finalAudio = snapshotLocalAudioConfig(audioLab, finalPrefs)
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
            audio = finalAudio,
            speedDial = finalSpeedDial.map { sd ->
                CloudSpeedDialRecord(
                    id = sd.id,
                    secondaryId = sd.secondaryId,
                    title = sd.title,
                    subtitle = sd.subtitle,
                    thumbnailUrl = sanitizeHttpUrl(sd.thumbnailUrl),
                    type = sd.type,
                    explicit = sd.explicit,
                    createDate = sd.createDate,
                )
            },
            recognitionHistory = finalRecs.map { rec ->
                CloudRecognitionRecord(
                    trackId = rec.trackId,
                    title = rec.title,
                    artist = rec.artist,
                    album = rec.album,
                    coverArtUrl = sanitizeHttpUrl(rec.coverArtUrl),
                    coverArtHqUrl = sanitizeHttpUrl(rec.coverArtHqUrl),
                    genre = rec.genre,
                    releaseDate = rec.releaseDate,
                    label = rec.label,
                    shazamUrl = sanitizeHttpUrl(rec.shazamUrl),
                    appleMusicUrl = sanitizeHttpUrl(rec.appleMusicUrl),
                    spotifyUrl = sanitizeHttpUrl(rec.spotifyUrl),
                    isrc = rec.isrc,
                    youtubeVideoId = rec.youtubeVideoId,
                    recognizedAtEpochMs = rec.recognizedAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    liked = rec.liked,
                )
            },
            lyricsOffsets = finalOffsets,
            modulesConfig = finalModules.takeIf {
                it.enabled.isNotEmpty() ||
                    it.picks.isNotEmpty() ||
                    it.fields.isNotEmpty() ||
                    !it.moduleOrder.isNullOrBlank() ||
                    !it.providerOrder.isNullOrBlank() ||
                    !it.downloadOrder.isNullOrBlank() ||
                    !it.webModulesJson.isNullOrBlank() ||
                    !it.monochromeServersJson.isNullOrBlank()
            },
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
        saveExtraIdSet(app, session.userId, "speedDialIds", finalSpeedDial.map { it.id }.toSet())
        saveExtraIdSet(app, session.userId, "recTrackIds", finalRecs.map { it.trackId }.toSet())
        saveExtraOffsetsMap(app, session.userId, finalOffsets)
        saveExtraString(app, session.userId, "modulesDigest", sha256Hex(canonicalModulesConfig(finalModules)))
        val finalAudioDigest = sha256Hex(canonicalAudioConfig(finalAudio))
        MochyCloudVault.saveSyncedEqDigest(app, session.userId, finalAudioDigest)
        return finalAlbums.size to finalArtists.size
    }

    private fun writePlaylistTracks(
        db: MusicDatabase,
        remotePl: CloudPlaylistRecord,
        preservedLocalSongIds: List<String> = emptyList(),
    ) {
        var nextPosition = 0
        for (track in remotePl.tracks) {
            if (!isCloudSafeId(track.songId)) continue
            if (db.getSongByIdBlocking(track.songId) == null) {
                insertSkeletonSong(
                    db = db,
                    songId = track.songId,
                    title = track.title,
                    artists = track.artists,
                    albumName = track.albumName,
                    duration = track.duration,
                    thumbnailUrl = sanitizeHttpUrl(track.thumbnailUrl),
                    liked = false,
                    likedDate = null,
                )
            }
            db.insert(
                PlaylistSongMap(
                    playlistId = remotePl.id,
                    songId = track.songId,
                    position = nextPosition++,
                ),
            )
        }
        for (localSongId in preservedLocalSongIds) {
            if (db.getSongByIdBlocking(localSongId) != null) {
                db.insert(
                    PlaylistSongMap(
                        playlistId = remotePl.id,
                        songId = localSongId,
                        position = nextPosition++,
                    ),
                )
            }
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
        playbackResumePushJob?.cancel()
        playbackResumePushJob = null
        pendingResumeSnapshot = null
        lastAutoResumePushKey = null
        lastAutoResumePushAtMs = 0L
    }

    private fun handleSessionExpired(app: Context, message: String) {
        cancelBackgroundJobs()
        pendingHistoryMap.clear()
        historyPlayCountCache.clear()
        MochyCloudVault.clearSession(app)
        clearExtraSyncState(app)
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
                val trackIds = db.playlistSongs(pl.id).first()
                    .asSequence()
                    .filter { isCloudTrackEligible(it.song.song) }
                    .take(200)
                    .joinToString(",") { it.song.id }
                val updatedSec = (pl.playlist.lastUpdateTime ?: pl.playlist.createdAt)
                    ?.atZone(ZoneId.systemDefault())
                    ?.toEpochSecond()
                    ?: 0L
                "${pl.id}\u0000${pl.playlist.name}\u0000$updatedSec\u0000$trackIds"
            }

    private fun computePlaylistsDigest(items: List<String>): String =
        sha256Hex(items.sorted().joinToString("\n"))

    private fun snapshotLocalAudioConfig(
        audioLab: AudioLabStore,
        prefs: androidx.datastore.preferences.core.Preferences,
    ): CloudAudioConfig = CloudAudioConfig(
        eqSettingsJson = audioLabJson.encodeToString(EqSettings.serializer(), audioLab.eq.value),
        audioNormalization = prefs[AudioNormalizationKey],
        audioQuality = prefs[AudioQualityKey],
        reverbJson = audioLabJson.encodeToString(ReverbSettings.serializer(), audioLab.reverb.value),
        loudnessJson = audioLabJson.encodeToString(LoudnessSettings.serializer(), audioLab.loudness.value),
        spatialJson = audioLabJson.encodeToString(SpatialSettings.serializer(), audioLab.spatial.value),
        normalizeJson = audioLabJson.encodeToString(NormalizeSettings.serializer(), audioLab.normalize.value),
        volumeJson = audioLabJson.encodeToString(VolumeSettings.serializer(), audioLab.volume.value),
        playbackJson = audioLabJson.encodeToString(PlaybackSettings.serializer(), audioLab.playback.value),
        skipSilence = prefs[SkipSilenceKey],
        eightDJson = audioLabJson.encodeToString(EightDSettings.serializer(), audioLab.eightD.value),
        playerCoverZoom = prefs[PlayerCoverZoomKey]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        canvasBestQuality = prefs[CanvasBestQualityKey],
        deviceProfilesJson = audioLab.exportDeviceProfilesCompactJson(),
        deviceProfilesEnabled = audioLab.deviceProfilesEnabled.value,
    )

    private suspend fun applyAudioConfig(
        app: Context,
        audioLab: AudioLabStore,
        remote: CloudAudioConfig,
        onlyIfDefault: Boolean,
    ) {
        audioLab.runWithoutDeviceProfileRecording {
            remote.eqSettingsJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.eq.value == EqSettings()) {
                    runCatching { audioLabJson.decodeFromString(EqSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updateEq { decoded } }
                }
            }
            remote.reverbJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.reverb.value == ReverbSettings()) {
                    runCatching { audioLabJson.decodeFromString(ReverbSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updateReverb { decoded } }
                }
            }
            remote.loudnessJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.loudness.value == LoudnessSettings()) {
                    runCatching { audioLabJson.decodeFromString(LoudnessSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updateLoudness { decoded } }
                }
            }
            remote.spatialJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.spatial.value == SpatialSettings()) {
                    runCatching { audioLabJson.decodeFromString(SpatialSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updateSpatial { decoded } }
                }
            }
            remote.normalizeJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.normalize.value == NormalizeSettings()) {
                    runCatching { audioLabJson.decodeFromString(NormalizeSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updateNormalize { decoded } }
                }
            }
            remote.volumeJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.volume.value == VolumeSettings()) {
                    runCatching { audioLabJson.decodeFromString(VolumeSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updateVolume { decoded } }
                }
            }
            remote.playbackJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.playback.value == PlaybackSettings()) {
                    runCatching { audioLabJson.decodeFromString(PlaybackSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updatePlayback { decoded } }
                }
            }
            remote.eightDJson?.takeIf { it.isNotBlank() }?.let { raw ->
                if (!onlyIfDefault || audioLab.eightD.value == EightDSettings()) {
                    runCatching { audioLabJson.decodeFromString(EightDSettings.serializer(), raw) }
                        .getOrNull()?.let { decoded -> audioLab.updateEightD { decoded } }
                }
            }
        }
        val hadLocalProfiles = audioLab.deviceProfiles.value.isNotEmpty()
        // Merge per-device profiles BEFORE toggling deviceProfilesEnabled so a remote active-device
        // profile is never overridden by a freshly stamped global EQ snapshot.
        remote.deviceProfilesJson?.takeIf { it.isNotBlank() }?.let { raw ->
            audioLab.mergeDeviceProfilesJson(raw, onlyIfEmpty = false, applyIfActivePresent = !onlyIfDefault)
        }
        remote.deviceProfilesEnabled?.let { enabled ->
            if (!onlyIfDefault || !hadLocalProfiles) {
                audioLab.setDeviceProfilesEnabled(enabled)
            }
        }
        app.dataStore.edit { p ->
            if (!onlyIfDefault || p[AudioNormalizationKey] == null) {
                remote.audioNormalization?.let { p[AudioNormalizationKey] = it }
            }
            if (!onlyIfDefault || p[AudioQualityKey].isNullOrBlank()) {
                remote.audioQuality?.takeIf { it.isNotBlank() }?.let { p[AudioQualityKey] = it }
            }
            if (!onlyIfDefault || p[SkipSilenceKey] == null) {
                remote.skipSilence?.let { p[SkipSilenceKey] = it }
            }
            if (!onlyIfDefault || p[PlayerCoverZoomKey] == null) {
                remote.playerCoverZoom?.takeIf { it.isFinite() }?.let {
                    p[PlayerCoverZoomKey] = it.coerceIn(0f, 1f)
                }
            }
            if (!onlyIfDefault || p[CanvasBestQualityKey] == null) {
                remote.canvasBestQuality?.let { p[CanvasBestQualityKey] = it }
            }
        }
    }

    private fun canonicalAudioConfig(cfg: CloudAudioConfig): String = listOf(
        cfg.eqSettingsJson.orEmpty(),
        cfg.audioNormalization?.toString().orEmpty(),
        cfg.audioQuality.orEmpty(),
        cfg.reverbJson.orEmpty(),
        cfg.loudnessJson.orEmpty(),
        cfg.spatialJson.orEmpty(),
        cfg.normalizeJson.orEmpty(),
        cfg.volumeJson.orEmpty(),
        cfg.playbackJson.orEmpty(),
        cfg.skipSilence?.toString().orEmpty(),
        cfg.eightDJson.orEmpty(),
        cfg.playerCoverZoom?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.toString().orEmpty(),
        cfg.canvasBestQuality?.toString().orEmpty(),
        com.mochyy.mochymusic.eq.DeviceAudioProfileEngine.canonicalKeyOfJson(cfg.deviceProfilesJson),
        cfg.deviceProfilesEnabled?.toString().orEmpty(),
    ).joinToString("\u0000")

    private fun canonicalModulesConfig(cfg: CloudModulesConfig): String {
        val en = cfg.enabled.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        val pk = cfg.picks.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        val fld = cfg.fields.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return listOf(
            en,
            pk,
            fld,
            cfg.moduleOrder.orEmpty(),
            cfg.providerOrder.orEmpty(),
            cfg.downloadOrder.orEmpty(),
            cfg.webModulesJson.orEmpty(),
            cfg.monochromeServersJson.orEmpty(),
        ).joinToString("\u0000")
    }

    internal fun readLocalModulesConfig(context: Context): CloudModulesConfig {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(SourceModulesPrefsName, Context.MODE_PRIVATE)
        val enabled = sortedMapOf<String, Boolean>()
        val picks = sortedMapOf<String, String>()
        val fields = sortedMapOf<String, String>()
        for ((k, v) in prefs.all) {
            when {
                k.startsWith("enabled_") && v is Boolean -> {
                    val id = k.removePrefix("enabled_")
                    if (id.isNotBlank()) enabled[id] = v
                }
                k.startsWith("pick_") && v is String -> {
                    val suffix = k.removePrefix("pick_")
                    if (suffix.isNotBlank()) picks[suffix] = v
                }
                k.startsWith("field_") && v is String -> {
                    val suffix = k.removePrefix("field_")
                    if (suffix.isNotBlank() && v.length <= 512) fields[suffix] = v
                }
            }
        }
        val order = prefs.getString("module_order", null)?.takeIf { it.isNotBlank() }
        val provOrder = prefs.getString("provider_order", null)?.takeIf { it.isNotBlank() }
        val dlOrder = prefs.getString("download_order", null)?.takeIf { it.isNotBlank() }
        val web = prefs.getString("web_modules", null)
            ?.takeIf { it.isNotBlank() && it != "[]" && it.length <= 16_384 }
        val monoServers = app.getSharedPreferences(MonochromePrimaryPrefsName, Context.MODE_PRIVATE)
            .getString("servers", null)
            ?.takeIf { it.isNotBlank() && it != "[]" }
            ?: app.getSharedPreferences(MonochromeLegacyPrefsName, Context.MODE_PRIVATE)
                .getString("servers", null)
                ?.takeIf { it.isNotBlank() && it != "[]" }
        return CloudModulesConfig(
            enabled = enabled,
            picks = picks,
            fields = fields,
            moduleOrder = order,
            providerOrder = provOrder,
            downloadOrder = dlOrder,
            webModulesJson = web,
            monochromeServersJson = monoServers,
        )
    }

    internal fun applyModulesConfigToPrefs(
        context: Context,
        remote: CloudModulesConfig,
        overwriteScalars: Boolean,
        overwriteArrays: Boolean,
        maxWebModulesChars: Int = 16_384,
    ): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(SourceModulesPrefsName, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false

        for ((id, isOn) in remote.enabled) {
            if (id.isBlank()) continue
            val key = "enabled_$id"
            val has = prefs.contains(key)
            if ((overwriteScalars || !has) && (!has || prefs.getBoolean(key, !isOn) != isOn)) {
                editor.putBoolean(key, isOn)
                changed = true
            }
        }
        for ((suffix, opt) in remote.picks) {
            if (suffix.isBlank() || opt.isBlank()) continue
            val key = "pick_$suffix"
            val cur = prefs.getString(key, null)
            if ((overwriteScalars || cur == null) && cur != opt) {
                editor.putString(key, opt)
                changed = true
            }
        }
        for ((suffix, fval) in remote.fields) {
            if (suffix.isBlank() || fval.length > 512) continue
            val key = "field_$suffix"
            val cur = prefs.getString(key, null)
            if ((overwriteScalars || cur == null) && cur != fval) {
                editor.putString(key, fval)
                changed = true
            }
        }
        remote.moduleOrder?.takeIf { it.isNotBlank() }?.let { ord ->
            val cur = prefs.getString("module_order", null)
            if ((overwriteScalars || cur.isNullOrBlank()) && cur != ord) {
                editor.putString("module_order", ord)
                changed = true
            }
        }
        remote.providerOrder?.takeIf { it.isNotBlank() }?.let { pord ->
            val cur = prefs.getString("provider_order", null)
            if ((overwriteScalars || cur.isNullOrBlank()) && cur != pord) {
                editor.putString("provider_order", pord)
                changed = true
            }
        }
        remote.downloadOrder?.takeIf { it.isNotBlank() }?.let { dord ->
            val cur = prefs.getString("download_order", null)
            if ((overwriteScalars || cur.isNullOrBlank()) && cur != dord) {
                editor.putString("download_order", dord)
                changed = true
            }
        }
        remote.webModulesJson?.takeIf { it.isNotBlank() && it.length <= maxWebModulesChars }?.let { rawWeb ->
            val remoteArr = runCatching { JSONArray(rawWeb) }.getOrNull()
            if (remoteArr != null) {
                val curRaw = prefs.getString("web_modules", null)
                if (overwriteArrays) {
                    if (curRaw != remoteArr.toString()) {
                        editor.putString("web_modules", remoteArr.toString())
                        changed = true
                    }
                } else {
                    val curArr = runCatching { JSONArray(curRaw ?: "[]") }.getOrDefault(JSONArray())
                    val seenBases = HashSet<String>()
                    val merged = JSONArray()
                    for (i in 0 until curArr.length()) {
                        val obj = curArr.optJSONObject(i) ?: continue
                        val base = obj.optString("base").trim()
                        if (base.isNotBlank() && seenBases.add(base)) merged.put(obj)
                    }
                    var addedAny = false
                    for (i in 0 until remoteArr.length()) {
                        val obj = remoteArr.optJSONObject(i) ?: continue
                        val base = obj.optString("base").trim()
                        val manifest = obj.optString("manifest").trim()
                        if (base.isNotBlank() && manifest.isNotBlank() && seenBases.add(base)) {
                            merged.put(obj)
                            addedAny = true
                        }
                    }
                    if (addedAny) {
                        val nextStr = merged.toString()
                        if (nextStr.length <= 32_768) {
                            editor.putString("web_modules", nextStr)
                            changed = true
                        }
                    }
                }
            }
        }

        remote.monochromeServersJson?.takeIf { it.isNotBlank() }?.let { rawMono ->
            val remoteArr = runCatching { JSONArray(rawMono) }.getOrNull()
            if (remoteArr != null) {
                val monoPrefs = app.getSharedPreferences(MonochromePrimaryPrefsName, Context.MODE_PRIVATE)
                val curRaw = monoPrefs.getString("servers", null)
                if (overwriteArrays) {
                    if (curRaw != remoteArr.toString()) {
                        monoPrefs.edit().putString("servers", remoteArr.toString()).apply()
                        changed = true
                    }
                } else {
                    val curArr = runCatching { JSONArray(curRaw ?: "[]") }.getOrDefault(JSONArray())
                    val seenUrls = HashSet<String>()
                    val merged = JSONArray()
                    for (i in 0 until curArr.length()) {
                        val obj = curArr.optJSONObject(i) ?: continue
                        val url = obj.optString("url").trim()
                        if (url.isNotBlank() && seenUrls.add(url)) merged.put(obj)
                    }
                    var addedAny = false
                    for (i in 0 until remoteArr.length()) {
                        val obj = remoteArr.optJSONObject(i) ?: continue
                        val url = obj.optString("url").trim()
                        val kind = obj.optString("kind").trim()
                        if (url.isNotBlank() && kind.isNotBlank() && seenUrls.add(url)) {
                            merged.put(obj)
                            addedAny = true
                        }
                    }
                    if (addedAny) {
                        monoPrefs.edit().putString("servers", merged.toString()).apply()
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            editor.putLong(CloudSyncStampKey, System.currentTimeMillis())
        }
        editor.apply()
        return changed
    }

    internal suspend fun mergeSpeedDialItem(db: MusicDatabase, item: SpeedDialItem): Boolean {
        if (item.id.isBlank() || item.title.isBlank() || item.type !in ValidSpeedDialTypes) return false
        val count = db.speedDialDao.count()
        if (count < SpeedDialDao.MaxPinned) {
            db.pinToSpeedDial(item)
            return true
        }
        val oldest = db.speedDialDao.oldest()
        if (oldest != null && item.createDate > oldest.createDate) {
            db.pinToSpeedDial(item)
            return true
        }
        return false
    }

    private fun clearExtraSyncState(app: Context) {
        app.getSharedPreferences(ExtraSyncPrefsName, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun extraPrefKey(userId: String, field: String): String = "${userId}_$field"

    private fun loadExtraString(app: Context, userId: String, field: String): String? =
        app.getSharedPreferences(ExtraSyncPrefsName, Context.MODE_PRIVATE)
            .getString(extraPrefKey(userId, field), null)
            ?.takeIf { it.isNotBlank() }

    private fun saveExtraString(app: Context, userId: String, field: String, value: String?) {
        val prefs = app.getSharedPreferences(ExtraSyncPrefsName, Context.MODE_PRIVATE)
        if (value.isNullOrBlank()) {
            prefs.edit().remove(extraPrefKey(userId, field)).apply()
        } else {
            prefs.edit().putString(extraPrefKey(userId, field), value).apply()
        }
    }

    private fun loadExtraIdSet(app: Context, userId: String, field: String): Set<String>? {
        val raw = loadExtraString(app, userId, field) ?: return null
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return buildSet(arr.length()) {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun saveExtraIdSet(app: Context, userId: String, field: String, ids: Set<String>) {
        saveExtraString(app, userId, field, JSONArray(ids.toList()).toString())
    }

    private fun loadExtraOffsetsMap(app: Context, userId: String): Map<String, Int>? {
        val raw = loadExtraString(app, userId, "lyricsOffsets") ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optInt(k, 0)
                if (k.isNotBlank() && v != 0) put(k, v)
            }
        }
    }

    private fun saveExtraOffsetsMap(app: Context, userId: String, map: Map<String, Int>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            if (k.isNotBlank() && v != 0) obj.put(k, v)
        }
        saveExtraString(app, userId, "lyricsOffsets", obj.toString())
    }

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

    private fun isCloudSafeId(id: String): Boolean {
        val clean = id.trim()
        return clean.isNotEmpty() &&
            clean.length <= 64 &&
            !clean.startsWith("LA") &&
            !clean.startsWith("LOCAL_", ignoreCase = true) &&
            !clean.contains("://") &&
            !clean.contains("/")
    }

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

    internal const val MaxEncodedChars = 1_200
    private const val MaxQueueTracks = 25
    private val QueueCaps = intArrayOf(25, 14, 7, 3, 0)
    private val EmptyResumeJsonArray = JsonArray(emptyList())

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
            val root = CloudCodecJson.parseToJsonElement(jsonStr).jsonObject
            val deviceId = root.optJsonString("deviceId").trim()
                .ifBlank { root.optJsonString("device_id").trim() }
            val songId = root.optJsonString("songId").trim()
            if (deviceId.isBlank() || songId.isBlank()) return@runCatching null

            val arr = root.optJsonArray("queueTracks") ?: EmptyResumeJsonArray
            val queue = buildList(minOf(arr.size, MaxQueueTracks)) {
                for (i in 0 until minOf(arr.size, MaxQueueTracks)) {
                    val row = arr.optJsonArray(i) ?: continue
                    val qId = row.optJsonString(0).trim()
                    if (qId.isBlank()) continue
                    add(
                        CloudPlaylistTrack(
                            songId = qId,
                            title = row.optJsonString(1).ifBlank { "Bài hát" },
                            artists = row.optJsonString(2).ifBlank { "Nghệ sĩ" },
                            albumName = row.optJsonString(3).takeIf { it.isNotBlank() && it != "null" },
                            duration = row.optJsonInt(4, -1),
                            thumbnailUrl = expandCloudThumbUrl(row.optJsonString(5)),
                        ),
                    )
                }
            }

            CloudPlaybackResume(
                deviceId = deviceId,
                deviceName = root.optJsonString("deviceName").trim().ifBlank { "Thiết bị khác" },
                songId = songId,
                title = root.optJsonString("title").trim().ifBlank { "Bài hát" },
                artists = root.optJsonString("artists").trim().ifBlank { "Nghệ sĩ" },
                albumName = root.optJsonString("albumName").takeIf { it.isNotBlank() && it != "null" },
                durationSec = root.optJsonInt("durationSec", -1),
                thumbnailUrl = expandCloudThumbUrl(root.optJsonString("thumbnailUrl")),
                positionMs = root.optJsonLong("positionMs", 0L).coerceAtLeast(0L),
                queueTracks = queue,
                updatedAtEpochMs = root.optJsonLong("updatedAtEpochMs", 0L).coerceAtLeast(0L),
            )
        }.getOrNull()
    }

    private fun compressToBase64(resume: CloudPlaybackResume, maxTracks: Int): String {
        val arr = buildJsonArray {
            for (t in resume.queueTracks.take(maxTracks)) {
                add(
                    buildJsonArray {
                        add(JsonPrimitive(t.songId.take(64)))
                        add(JsonPrimitive(t.title.take(100)))
                        add(JsonPrimitive(t.artists.take(100)))
                        add(JsonPrimitive(t.albumName.orEmpty().take(100)))
                        add(JsonPrimitive(t.duration.coerceIn(-1, 86_400)))
                        add(JsonPrimitive(compactCloudThumbUrl(t.thumbnailUrl).take(280)))
                    },
                )
            }
        }
        val compactThumb = compactCloudThumbUrl(resume.thumbnailUrl).take(320)
        val root = buildJsonObject {
            put("v", 1)
            put("deviceId", resume.deviceId.take(64))
            put("deviceName", resume.deviceName.take(40))
            put("songId", resume.songId.take(64))
            put("title", resume.title.take(140))
            put("artists", resume.artists.take(140))
            if (!resume.albumName.isNullOrBlank()) put("albumName", resume.albumName.take(120))
            put("durationSec", resume.durationSec.coerceIn(-1, 86_400))
            if (compactThumb.isNotEmpty()) put("thumbnailUrl", compactThumb)
            put("positionMs", resume.positionMs.coerceAtLeast(0L))
            put("queueTracks", arr)
            put("updatedAtEpochMs", resume.updatedAtEpochMs)
        }.toString()
        return gzipToBase64(root)
    }
}
