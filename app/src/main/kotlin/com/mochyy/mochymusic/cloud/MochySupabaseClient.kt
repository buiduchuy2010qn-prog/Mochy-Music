package com.mochyy.mochymusic.cloud

import android.content.Context
import com.mochyy.mochymusic.utils.followAppProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

data class CloudProfileRecord(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarWebp: String?,
)

data class CloudFavoriteRecord(
    val songId: String,
    val title: String,
    val artists: String,
    val albumName: String?,
    val duration: Int,
    val thumbnailUrl: String?,
    val likedAt: String,
)

data class CloudHistoryRecord(
    val songId: String,
    val title: String,
    val artists: String,
    val albumName: String?,
    val duration: Int,
    val thumbnailUrl: String?,
    val playCount: Long,
    val totalPlayMs: Long,
    val lastPlayedAt: String,
)

data class CloudUserMetadata(
    val cardNumber: String?,
    val joinedEpochDay: Long?,
    val playlistsGz: String?,
    val libraryExtraGz: String? = null,
    val playbackResumeGz: String? = null,
)

open class MochyCloudException(message: String) : Exception(message)

class MochyCloudAuthExpiredException(message: String) : MochyCloudException(message)

sealed class RefreshSessionResult {
    data class Success(val session: MochyCloudSession) : RefreshSessionResult()
    data class AuthExpired(val message: String) : RefreshSessionResult()
    data class NetworkError(val message: String) : RefreshSessionResult()
}

/**
 * Giao tiếp HTTPS trực tiếp với Supabase Auth + PostgREST cho Mochy Cloud.
 * Tuân thủ quy tắc Publishable Key (`sb_publishable_...`):
 * - Chỉ truyền qua header `apikey`, tuyệt đối không đặt Publishable Key vào `Authorization: Bearer`.
 * - Header `Authorization: Bearer` chỉ mang JWT truy cập của chính người dùng đã đăng nhập.
 */
object MochySupabaseClient {

    private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val UsernameRegex = Regex("^[a-z0-9._]{3,24}$")
    private val refreshMutex = Mutex()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followAppProxy()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun normalizeUsername(raw: String): String = raw.trim().lowercase()

    fun isValidUsername(username: String): Boolean = UsernameRegex.matches(username)

    private fun syntheticEmail(username: String): String = "$username@account.mochy.app"

    suspend fun signUp(
        context: Context,
        rawUsername: String,
        password: String,
        displayName: String,
    ): MochyCloudSession = withContext(Dispatchers.IO) {
        val username = normalizeUsername(rawUsername)
        if (!isValidUsername(username)) {
            throw MochyCloudException("Tên đăng nhập 3–24 ký tự, chỉ gồm chữ thường, số, dấu chấm hoặc gạch dưới.")
        }
        if (password.length < 6) {
            throw MochyCloudException("Mật khẩu phải có ít nhất 6 ký tự.")
        }
        val cleanDisplay = displayName.trim().ifBlank { username }.take(40)
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")

        val body = JSONObject().apply {
            put("email", syntheticEmail(username))
            put("password", password)
            put("data", JSONObject().apply {
                put("username", username)
                put("display_name", cleanDisplay)
            })
        }.toString().toRequestBody(JsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/signup")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()

        val responseJson = executeAuthRequest(request, isSignUp = true, isSignIn = false)
        val session = parseAuthSession(responseJson, fallbackUsername = username, fallbackDisplay = cleanDisplay)
        ensureProfileRow(context, session, cleanDisplay)
        session
    }

    suspend fun signIn(
        context: Context,
        rawUsername: String,
        password: String,
    ): MochyCloudSession = withContext(Dispatchers.IO) {
        val username = normalizeUsername(rawUsername)
        if (!isValidUsername(username)) {
            throw MochyCloudException("Tên đăng nhập không hợp lệ.")
        }
        if (password.isEmpty()) {
            throw MochyCloudException("Vui lòng nhập mật khẩu.")
        }
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")

        val body = JSONObject().apply {
            put("email", syntheticEmail(username))
            put("password", password)
        }.toString().toRequestBody(JsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()

        val responseJson = executeAuthRequest(request, isSignUp = false, isSignIn = true)
        parseAuthSession(responseJson, fallbackUsername = username, fallbackDisplay = username)
    }

    suspend fun refreshSession(
        context: Context,
        session: MochyCloudSession,
    ): MochyCloudSession? = (refreshSessionWithStatus(context, session) as? RefreshSessionResult.Success)?.session

    suspend fun refreshSessionWithStatus(
        context: Context,
        session: MochyCloudSession,
    ): RefreshSessionResult = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val nowSec = System.currentTimeMillis() / 1000L
            val cached = MochyCloudVault.loadSession(context)
            if (cached != null &&
                cached.userId == session.userId &&
                cached.accessToken != session.accessToken &&
                cached.expiresAtEpochSec > nowSec + 90L
            ) {
                return@withLock RefreshSessionResult.Success(cached)
            }
            if (session.refreshToken.isBlank()) {
                return@withLock RefreshSessionResult.AuthExpired("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
            }
            val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
                ?: return@withLock RefreshSessionResult.NetworkError("Chưa cấu hình Mochy Cloud.")
            val body = JSONObject().apply {
                put("refresh_token", session.refreshToken)
            }.toString().toRequestBody(JsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
                .header("apikey", publishableKey)
                .header("Accept", "application/json")
                .post(body)
                .build()

            val callResult = try {
                http.newCall(request).execute().use { resp ->
                    HttpCallResult(resp.code, resp.body?.string().orEmpty())
                }
            } catch (_: IOException) {
                return@withLock RefreshSessionResult.NetworkError("Không thể kết nối Mochy Cloud, sẽ thử lại sau.")
            }

            if (callResult.code in 200..299) {
                val parsed = runCatching {
                    val json = JSONObject(callResult.body)
                    parseAuthSession(json, fallbackUsername = session.username, fallbackDisplay = session.displayName)
                }.getOrNull()
                if (parsed != null) {
                    MochyCloudVault.saveSession(context, parsed)
                    return@withLock RefreshSessionResult.Success(parsed)
                }
                return@withLock RefreshSessionResult.NetworkError("Phản hồi làm mới phiên không hợp lệ.")
            }

            if (callResult.code == 400 || callResult.code == 401 || callResult.code == 403) {
                return@withLock RefreshSessionResult.AuthExpired("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
            }

            if (callResult.code == 429 || callResult.code >= 500) {
                return@withLock RefreshSessionResult.NetworkError("Máy chủ Mochy Cloud đang bận, thử lại sau nhé.")
            }

            RefreshSessionResult.NetworkError("Không thể làm mới phiên (${callResult.code}).")
        }
    }

    suspend fun fetchUserMetadata(
        context: Context,
        session: MochyCloudSession,
    ): CloudUserMetadata? = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext null
        val json = executeAuthenticatedAuthJson(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
        }
        val meta = json.optJSONObject("user_metadata")
            ?: json.optJSONObject("user")?.optJSONObject("user_metadata")
        CloudUserMetadata(
            cardNumber = meta?.optString("card_number")?.takeIf { it.isNotBlank() && it != "null" },
            joinedEpochDay = meta?.optLong("joined_epoch_day", -1L)?.takeIf { it > 0L },
            playlistsGz = meta?.optString("playlists_gz")?.takeIf { it.isNotBlank() && it != "null" },
            libraryExtraGz = meta?.optString("library_extra_gz")?.takeIf { it.isNotBlank() && it != "null" },
            playbackResumeGz = meta?.optString("playback_resume_gz")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    suspend fun updateUserMetadata(
        context: Context,
        session: MochyCloudSession,
        cardNumber: String? = null,
        joinedEpochDay: Long? = null,
        playlistsGz: String? = null,
        clearPlaylists: Boolean = false,
        libraryExtraGz: String? = null,
        clearLibraryExtra: Boolean = false,
        playbackResumeGz: String? = null,
        clearPlaybackResume: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        val data = JSONObject().apply {
            if (cardNumber != null) put("card_number", cardNumber.take(24))
            if (joinedEpochDay != null && joinedEpochDay > 0L) put("joined_epoch_day", joinedEpochDay)
            if (clearPlaylists) {
                put("playlists_gz", "")
            } else if (playlistsGz != null) {
                put("playlists_gz", playlistsGz.take(16_384))
            }
            if (clearLibraryExtra) {
                put("library_extra_gz", "")
            } else if (libraryExtraGz != null) {
                put("library_extra_gz", libraryExtraGz.take(16_384))
            }
            if (clearPlaybackResume) {
                put("playback_resume_gz", "")
            } else if (playbackResumeGz != null) {
                put("playback_resume_gz", playbackResumeGz.take(3_072))
            }
        }
        if (data.length() == 0) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val body = JSONObject().apply {
            put("data", data)
        }.toString().toRequestBody(JsonMediaType)
        executeAuthenticatedAuthJson(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .put(body)
                .build()
        }
    }

    suspend fun updatePassword(
        context: Context,
        session: MochyCloudSession,
        newPassword: String,
    ): Unit = withContext(Dispatchers.IO) {
        if (newPassword.length < 6) {
            throw MochyCloudException("Mật khẩu mới phải có ít nhất 6 ký tự.")
        }
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val body = JSONObject().apply {
            put("password", newPassword)
        }.toString().toRequestBody(JsonMediaType)
        executeAuthenticatedAuthJson(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .put(body)
                .build()
        }
    }

    suspend fun wipeCloudData(
        context: Context,
        session: MochyCloudSession,
    ): Unit = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val uid = urlEncode(session.userId)
        executeAuthenticatedRestWrite(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/rest/v1/mochy_favorites?user_id=eq.$uid")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()
        }

        executeAuthenticatedRestWrite(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/rest/v1/mochy_history?user_id=eq.$uid")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()
        }

        val avatarPatch = JSONObject().apply { put("avatar_webp", JSONObject.NULL) }.toString().toRequestBody(JsonMediaType)
        executeAuthenticatedRestWrite(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/rest/v1/mochy_profiles?user_id=eq.$uid")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Prefer", "return=minimal")
                .patch(avatarPatch)
                .build()
        }

        updateUserMetadata(
            context,
            session,
            clearPlaylists = true,
            clearLibraryExtra = true,
            clearPlaybackResume = true,
        )
    }

    suspend fun signOutRemote(context: Context, session: MochyCloudSession) = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/logout")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .post("".toRequestBody(JsonMediaType))
            .build()
        runCatching { http.newCall(request).execute().close() }
    }

    suspend fun fetchProfile(context: Context, session: MochyCloudSession): CloudProfileRecord? = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext null
        val uid = urlEncode(session.userId)
        val arr = executeAuthenticatedRestArray(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/rest/v1/mochy_profiles?user_id=eq.$uid&select=user_id,username,display_name,avatar_webp&limit=1")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        }
        if (arr.length() == 0) return@withContext null
        val obj = arr.getJSONObject(0)
        CloudProfileRecord(
            userId = obj.optString("user_id", session.userId),
            username = obj.optString("username", session.username),
            displayName = obj.optString("display_name", session.displayName),
            avatarWebp = obj.optString("avatar_webp").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    suspend fun updateProfile(
        context: Context,
        session: MochyCloudSession,
        displayName: String?,
        avatarWebp: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val payload = JSONObject().apply {
            if (displayName != null) put("display_name", displayName.trim().ifBlank { session.username }.take(40))
            if (avatarWebp != null) {
                if (avatarWebp.length > MochyAvatarCodec.MaxDataUriLength || !avatarWebp.startsWith("data:image/webp;base64,")) {
                    throw MochyCloudException("Ảnh đại diện vượt quá giới hạn dung lượng nhẹ.")
                }
                put("avatar_webp", avatarWebp)
            }
        }
        if (payload.length() == 0) return@withContext
        val uid = urlEncode(session.userId)
        val body = payload.toString().toRequestBody(JsonMediaType)
        executeAuthenticatedRestWrite(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/rest/v1/mochy_profiles?user_id=eq.$uid")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Prefer", "return=minimal")
                .patch(body)
                .build()
        }
    }

    suspend fun fetchFavorites(context: Context, session: MochyCloudSession): List<CloudFavoriteRecord> = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext emptyList()
        val uid = urlEncode(session.userId)
        val arr = executeAuthenticatedRestArray(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/rest/v1/mochy_favorites?user_id=eq.$uid&select=song_id,title,artists,album_name,duration,thumbnail_url,liked_at&order=liked_at.desc&limit=2000")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        }
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val songId = o.optString("song_id")
                if (songId.isBlank()) continue
                add(
                    CloudFavoriteRecord(
                        songId = songId,
                        title = o.optString("title"),
                        artists = o.optString("artists"),
                        albumName = o.optString("album_name").takeIf { it.isNotBlank() && it != "null" },
                        duration = o.optInt("duration", -1),
                        thumbnailUrl = o.optString("thumbnail_url").takeIf { it.isNotBlank() && it != "null" },
                        likedAt = o.optString("liked_at"),
                    ),
                )
            }
        }
    }

    suspend fun upsertFavorites(
        context: Context,
        session: MochyCloudSession,
        items: List<CloudFavoriteRecord>,
    ) = withContext(Dispatchers.IO) {
        val safeItems = items
            .filter { isCloudSafeSongId(it.songId) }
            .distinctBy { it.songId }
        if (safeItems.isEmpty()) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext
        for (chunk in safeItems.chunked(100)) {
            val arr = JSONArray()
            for (item in chunk) {
                arr.put(
                    buildBaseTrackJson(
                        userId = session.userId,
                        songId = item.songId,
                        title = item.title,
                        artists = item.artists,
                        albumName = item.albumName,
                        duration = item.duration,
                        thumbnailUrl = item.thumbnailUrl,
                    ).apply {
                        put(
                            "liked_at",
                            item.likedAt.trim().takeIf { it.isNotBlank() && it != "null" } ?: Instant.now().toString(),
                        )
                    },
                )
            }
            val body = arr.toString().toRequestBody(JsonMediaType)
            executeAuthenticatedRestWrite(context, session) { accessToken ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/mochy_favorites?on_conflict=user_id,song_id")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Prefer", "resolution=merge-duplicates,return=minimal")
                    .post(body)
                    .build()
            }
        }
    }

    suspend fun deleteFavorites(
        context: Context,
        session: MochyCloudSession,
        songIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext
        val uid = urlEncode(session.userId)
        for (chunk in songIds.chunked(60)) {
            val joined = chunk.joinToString(",") { id -> "\"${id.replace("\"", "")}\"" }
            val inFilter = urlEncode("($joined)")
            executeAuthenticatedRestWrite(context, session) { accessToken ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/mochy_favorites?user_id=eq.$uid&song_id=in.$inFilter")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
            }
        }
    }

    suspend fun fetchHistory(context: Context, session: MochyCloudSession): List<CloudHistoryRecord> = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext emptyList()
        val uid = urlEncode(session.userId)
        val arr = executeAuthenticatedRestArray(context, session) { accessToken ->
            Request.Builder()
                .url("$baseUrl/rest/v1/mochy_history?user_id=eq.$uid&select=song_id,title,artists,album_name,duration,thumbnail_url,play_count,total_play_ms,last_played_at&order=last_played_at.desc&limit=500")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        }
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val songId = o.optString("song_id")
                if (songId.isBlank()) continue
                add(
                    CloudHistoryRecord(
                        songId = songId,
                        title = o.optString("title"),
                        artists = o.optString("artists"),
                        albumName = o.optString("album_name").takeIf { it.isNotBlank() && it != "null" },
                        duration = o.optInt("duration", -1),
                        thumbnailUrl = o.optString("thumbnail_url").takeIf { it.isNotBlank() && it != "null" },
                        playCount = o.optLong("play_count", 1L).coerceAtLeast(1L),
                        totalPlayMs = o.optLong("total_play_ms", 0L).coerceAtLeast(0L),
                        lastPlayedAt = o.optString("last_played_at"),
                    ),
                )
            }
        }
    }

    suspend fun upsertHistory(
        context: Context,
        session: MochyCloudSession,
        items: List<CloudHistoryRecord>,
    ) = withContext(Dispatchers.IO) {
        val safeItems = items
            .filter { isCloudSafeSongId(it.songId) }
            .distinctBy { it.songId }
        if (safeItems.isEmpty()) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext
        for (chunk in safeItems.chunked(100)) {
            val arr = JSONArray()
            for (item in chunk) {
                arr.put(
                    buildBaseTrackJson(
                        userId = session.userId,
                        songId = item.songId,
                        title = item.title,
                        artists = item.artists,
                        albumName = item.albumName,
                        duration = item.duration,
                        thumbnailUrl = item.thumbnailUrl,
                    ).apply {
                        put("play_count", item.playCount.coerceIn(1L, 2_000_000_000L))
                        put("total_play_ms", item.totalPlayMs.coerceAtLeast(0L))
                        put(
                            "last_played_at",
                            item.lastPlayedAt.trim().takeIf { it.isNotBlank() && it != "null" } ?: Instant.now().toString(),
                        )
                    },
                )
            }
            val body = arr.toString().toRequestBody(JsonMediaType)
            executeAuthenticatedRestWrite(context, session) { accessToken ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/mochy_history?on_conflict=user_id,song_id")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Prefer", "resolution=merge-duplicates,return=minimal")
                    .post(body)
                    .build()
            }
        }
    }

    internal fun ensureProfileRow(context: Context, session: MochyCloudSession, displayName: String) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return
        val activeToken = MochyCloudVault.loadSession(context)
            ?.takeIf { it.userId == session.userId }
            ?.accessToken
            ?: session.accessToken
        val body = JSONObject().apply {
            put("user_id", session.userId)
            put("username", session.username)
            put("display_name", displayName.trim().ifBlank { session.username }.take(40))
        }.toString().toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_profiles?on_conflict=user_id")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer $activeToken")
            .header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(body)
            .build()
        runCatching { http.newCall(request).execute().close() }
    }

    private fun isCloudSafeSongId(id: String): Boolean =
        id.isNotBlank() && id.length <= 64 && !id.startsWith("LA") && !id.contains("://") && !id.contains("/")

    private fun sanitizeCloudUrl(raw: String?): Any {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed == "null" ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true)
        ) {
            return JSONObject.NULL
        }
        if (trimmed.length <= 512) return trimmed
        val stripped = trimmed.substringBefore('?')
        return if (stripped.length <= 512 && stripped.startsWith("http", ignoreCase = true)) {
            stripped
        } else {
            JSONObject.NULL
        }
    }

    private fun buildBaseTrackJson(
        userId: String,
        songId: String,
        title: String,
        artists: String,
        albumName: String?,
        duration: Int,
        thumbnailUrl: String?,
    ): JSONObject = JSONObject().apply {
        put("user_id", userId)
        put("song_id", songId)
        put("title", title.trim().ifBlank { "Bài hát" }.take(240))
        put("artists", artists.trim().ifBlank { "Nghệ sĩ" }.take(240))
        put(
            "album_name",
            albumName?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.take(240) ?: JSONObject.NULL,
        )
        put("duration", duration.coerceIn(-1, 86400))
        put("thumbnail_url", sanitizeCloudUrl(thumbnailUrl))
    }

    private data class HttpCallResult(val code: Int, val body: String)

    private fun executeSingleCall(request: Request): HttpCallResult {
        try {
            http.newCall(request).execute().use { resp ->
                return HttpCallResult(resp.code, resp.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            throw MochyCloudException("Không thể kết nối Mochy Cloud, kiểm tra mạng rồi thử lại.")
        }
    }

    private suspend fun <T> executeAuthenticatedCall(
        context: Context,
        session: MochyCloudSession,
        buildRequest: (accessToken: String) -> Request,
        handleResponse: (code: Int, rawBody: String) -> T,
    ): T {
        val latest = MochyCloudVault.loadSession(context)
            ?.takeIf { it.userId == session.userId && it.expiresAtEpochSec >= session.expiresAtEpochSec }
            ?: session
        val first = executeSingleCall(buildRequest(latest.accessToken))
        if (first.code != 401) {
            return handleResponse(first.code, first.body)
        }
        val refreshed = when (val outcome = refreshSessionWithStatus(context, latest)) {
            is RefreshSessionResult.Success -> outcome.session
            is RefreshSessionResult.AuthExpired -> throw MochyCloudAuthExpiredException(outcome.message)
            is RefreshSessionResult.NetworkError -> throw MochyCloudException(outcome.message)
        }
        val retry = executeSingleCall(buildRequest(refreshed.accessToken))
        if (retry.code == 401) {
            throw MochyCloudAuthExpiredException("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.")
        }
        return handleResponse(retry.code, retry.body)
    }

    private suspend fun executeAuthenticatedAuthJson(
        context: Context,
        session: MochyCloudSession,
        buildRequest: (accessToken: String) -> Request,
    ): JSONObject = executeAuthenticatedCall(
        context = context,
        session = session,
        buildRequest = buildRequest,
        handleResponse = { code, raw ->
            parseAuthResponseOrThrow(code, raw, isSignUp = false, isSignIn = false)
        },
    )

    private suspend fun executeAuthenticatedRestArray(
        context: Context,
        session: MochyCloudSession,
        buildRequest: (accessToken: String) -> Request,
    ): JSONArray = executeAuthenticatedCall(
        context = context,
        session = session,
        buildRequest = buildRequest,
        handleResponse = { code, raw ->
            if (code in 200..299) {
                runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            } else {
                throw MochyCloudException(mapRestError(code, raw))
            }
        },
    )

    private suspend fun executeAuthenticatedRestWrite(
        context: Context,
        session: MochyCloudSession,
        buildRequest: (accessToken: String) -> Request,
    ) = executeAuthenticatedCall(
        context = context,
        session = session,
        buildRequest = buildRequest,
        handleResponse = { code, raw ->
            if (code !in 200..299) {
                throw MochyCloudException(mapRestError(code, raw))
            }
        },
    )

    private fun executeAuthRequest(request: Request, isSignUp: Boolean, isSignIn: Boolean): JSONObject {
        val res = executeSingleCall(request)
        return parseAuthResponseOrThrow(res.code, res.body, isSignUp = isSignUp, isSignIn = isSignIn)
    }

    private fun parseAuthResponseOrThrow(
        code: Int,
        raw: String,
        isSignUp: Boolean,
        isSignIn: Boolean,
    ): JSONObject {
        if (code in 200..299) {
            return runCatching { JSONObject(raw) }.getOrElse {
                throw MochyCloudException("Phản hồi máy chủ không hợp lệ.")
            }
        }
        val errJson = runCatching { JSONObject(raw) }.getOrNull()
        val msg = errJson?.optString("msg")?.ifBlank { null }
            ?: errJson?.optString("error_description")?.ifBlank { null }
            ?: errJson?.optString("message").orEmpty()
        val lower = msg.lowercase()
        val friendly = when {
            code == 401 -> "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại."
            code == 429 || "rate limit" in lower -> "Thao tác quá nhanh, vui lòng thử lại sau ít phút."
            isSignUp && ("already registered" in lower || "already exists" in lower) ->
                "Tên đăng nhập này đã có người sử dụng."
            isSignIn && ("invalid login credentials" in lower || "invalid" in lower) ->
                "Sai tên đăng nhập hoặc mật khẩu."
            code >= 500 -> "Máy chủ Mochy Cloud đang bận, thử lại sau nhé."
            msg.isNotBlank() -> msg
            else -> "Lỗi xác thực ($code)."
        }
        throw MochyCloudException(friendly)
    }

    private fun mapRestError(code: Int, raw: String): String {
        val err = runCatching { JSONObject(raw) }.getOrNull()
        val pgCode = err?.optString("code").orEmpty()
        val msg = err?.optString("message").orEmpty()
        return when {
            code == 401 -> "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại."
            pgCode == "23505" -> "Tên đăng nhập đã tồn tại."
            pgCode == "P0001" && "cooldown" in msg.lowercase() ->
                "Vui lòng đợi 30 giây giữa các lần đổi ảnh đại diện."
            "avatar" in msg.lowercase() -> "Ảnh đại diện không hợp lệ hoặc vượt giới hạn nhẹ."
            code >= 500 -> "Máy chủ Mochy Cloud đang bận."
            code == 400 && pgCode.isNotBlank() -> "Đồng bộ thất bại (mã 400 · $pgCode)."
            else -> "Đồng bộ thất bại (mã $code)."
        }
    }

    private fun parseAuthSession(
        json: JSONObject,
        fallbackUsername: String,
        fallbackDisplay: String,
    ): MochyCloudSession {
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0L }
            ?: ((System.currentTimeMillis() / 1000L) + json.optLong("expires_in", 3600L))
        val userObj = json.optJSONObject("user")
        val userId = userObj?.optString("id").orEmpty()
        val meta = userObj?.optJSONObject("user_metadata")
        val username = meta?.optString("username")?.takeIf { it.isNotBlank() } ?: fallbackUsername
        val display = meta?.optString("display_name")?.takeIf { it.isNotBlank() } ?: fallbackDisplay
        if (access.isBlank() || userId.isBlank()) {
            throw MochyCloudException("Không nhận được phiên đăng nhập hợp lệ.")
        }
        return MochyCloudSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSec = expiresAt,
            userId = userId,
            username = username,
            displayName = display,
        )
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
