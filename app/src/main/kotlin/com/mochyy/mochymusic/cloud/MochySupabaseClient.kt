package com.mochyy.mochymusic.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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
)

class MochyCloudException(message: String) : Exception(message)

/**
 * Giao tiếp HTTPS trực tiếp với Supabase Auth + PostgREST cho Mochy Cloud.
 * Tuân thủ quy tắc Publishable Key (`sb_publishable_...`):
 * - Chỉ truyền qua header `apikey`, tuyệt đối không đặt Publishable Key vào `Authorization: Bearer`.
 * - Header `Authorization: Bearer` chỉ mang JWT truy cập của chính người dùng đã đăng nhập.
 */
object MochySupabaseClient {

    private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val UsernameRegex = Regex("^[a-z0-9._]{3,24}$")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
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

        val responseJson = executeAuthRequest(request, isSignUp = true)
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

        val responseJson = executeAuthRequest(request, isSignUp = false)
        parseAuthSession(responseJson, fallbackUsername = username, fallbackDisplay = username)
    }

    suspend fun refreshSession(
        context: Context,
        session: MochyCloudSession,
    ): MochyCloudSession? = withContext(Dispatchers.IO) {
        if (session.refreshToken.isBlank()) return@withContext null
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext null
        val body = JSONObject().apply {
            put("refresh_token", session.refreshToken)
        }.toString().toRequestBody(JsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()

        runCatching {
            val json = executeAuthRequest(request, isSignUp = false)
            parseAuthSession(json, fallbackUsername = session.username, fallbackDisplay = session.displayName)
        }.getOrNull()
    }

    suspend fun fetchUserMetadata(
        context: Context,
        session: MochyCloudSession,
    ): CloudUserMetadata? = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext null
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .get()
            .build()
        val json = executeAuthRequest(request, isSignUp = false)
        val meta = json.optJSONObject("user_metadata")
            ?: json.optJSONObject("user")?.optJSONObject("user_metadata")
        CloudUserMetadata(
            cardNumber = meta?.optString("card_number")?.takeIf { it.isNotBlank() && it != "null" },
            joinedEpochDay = meta?.optLong("joined_epoch_day", -1L)?.takeIf { it > 0L },
            playlistsGz = meta?.optString("playlists_gz")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    suspend fun updateUserMetadata(
        context: Context,
        session: MochyCloudSession,
        cardNumber: String? = null,
        joinedEpochDay: Long? = null,
        playlistsGz: String? = null,
        clearPlaylists: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val data = JSONObject().apply {
            if (cardNumber != null) put("card_number", cardNumber.take(24))
            if (joinedEpochDay != null && joinedEpochDay > 0L) put("joined_epoch_day", joinedEpochDay)
            if (clearPlaylists) {
                put("playlists_gz", "")
            } else if (playlistsGz != null) {
                put("playlists_gz", playlistsGz)
            }
        }
        if (data.length() == 0) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val body = JSONObject().apply {
            put("data", data)
        }.toString().toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .put(body)
            .build()
        executeAuthRequest(request, isSignUp = false)
    }

    suspend fun updatePassword(
        context: Context,
        session: MochyCloudSession,
        newPassword: String,
    ) = withContext(Dispatchers.IO) {
        if (newPassword.length < 6) {
            throw MochyCloudException("Mật khẩu mới phải có ít nhất 6 ký tự.")
        }
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val body = JSONObject().apply {
            put("password", newPassword)
        }.toString().toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .put(body)
            .build()
        executeAuthRequest(request, isSignUp = false)
    }

    suspend fun wipeCloudData(
        context: Context,
        session: MochyCloudSession,
    ) = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val uid = urlEncode(session.userId)
        val delFavs = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_favorites?user_id=eq.$uid")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .delete()
            .build()
        executeRestWrite(delFavs)

        val delHistory = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_history?user_id=eq.$uid")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .delete()
            .build()
        executeRestWrite(delHistory)

        val clearAvatar = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_profiles?user_id=eq.$uid")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Prefer", "return=minimal")
            .patch(JSONObject().apply { put("avatar_webp", JSONObject.NULL) }.toString().toRequestBody(JsonMediaType))
            .build()
        executeRestWrite(clearAvatar)

        updateUserMetadata(context, session, clearPlaylists = true)
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
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_profiles?user_id=eq.$uid&select=user_id,username,display_name,avatar_webp&limit=1")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        val arr = executeRestArray(request)
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
    ) = withContext(Dispatchers.IO) {
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
        val uid = urlEncode(session.userId)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_profiles?user_id=eq.$uid")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Prefer", "return=minimal")
            .patch(payload.toString().toRequestBody(JsonMediaType))
            .build()
        executeRestWrite(request)
    }

    suspend fun fetchFavorites(context: Context, session: MochyCloudSession): List<CloudFavoriteRecord> = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext emptyList()
        val uid = urlEncode(session.userId)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_favorites?user_id=eq.$uid&select=song_id,title,artists,album_name,duration,thumbnail_url,liked_at&order=liked_at.desc&limit=2000")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        val arr = executeRestArray(request)
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
        if (items.isEmpty()) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext
        for (chunk in items.chunked(100)) {
            val arr = JSONArray()
            for (item in chunk) {
                arr.put(
                    JSONObject().apply {
                        put("user_id", session.userId)
                        put("song_id", item.songId.take(64))
                        put("title", item.title.take(240))
                        put("artists", item.artists.take(240))
                        if (!item.albumName.isNullOrBlank()) put("album_name", item.albumName.take(240))
                        put("duration", item.duration.coerceIn(-1, 86400))
                        if (!item.thumbnailUrl.isNullOrBlank()) put("thumbnail_url", item.thumbnailUrl.take(512))
                        if (item.likedAt.isNotBlank()) put("liked_at", item.likedAt)
                    },
                )
            }
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/mochy_favorites?on_conflict=user_id,song_id")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(arr.toString().toRequestBody(JsonMediaType))
                .build()
            executeRestWrite(request)
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
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/mochy_favorites?user_id=eq.$uid&song_id=in.$inFilter")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .delete()
                .build()
            executeRestWrite(request)
        }
    }

    suspend fun fetchHistory(context: Context, session: MochyCloudSession): List<CloudHistoryRecord> = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext emptyList()
        val uid = urlEncode(session.userId)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_history?user_id=eq.$uid&select=song_id,title,artists,album_name,duration,thumbnail_url,play_count,total_play_ms,last_played_at&order=last_played_at.desc&limit=500")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()
        val arr = executeRestArray(request)
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
        if (items.isEmpty()) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext
        for (chunk in items.chunked(100)) {
            val arr = JSONArray()
            for (item in chunk) {
                arr.put(
                    JSONObject().apply {
                        put("user_id", session.userId)
                        put("song_id", item.songId.take(64))
                        put("title", item.title.take(240))
                        put("artists", item.artists.take(240))
                        if (!item.albumName.isNullOrBlank()) put("album_name", item.albumName.take(240))
                        put("duration", item.duration.coerceIn(-1, 86400))
                        if (!item.thumbnailUrl.isNullOrBlank()) put("thumbnail_url", item.thumbnailUrl.take(512))
                        put("play_count", item.playCount.coerceAtLeast(1L))
                        put("total_play_ms", item.totalPlayMs.coerceAtLeast(0L))
                        if (item.lastPlayedAt.isNotBlank()) put("last_played_at", item.lastPlayedAt)
                    },
                )
            }
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/mochy_history?on_conflict=user_id,song_id")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(arr.toString().toRequestBody(JsonMediaType))
                .build()
            executeRestWrite(request)
        }
    }

    private fun ensureProfileRow(context: Context, session: MochyCloudSession, displayName: String) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return
        val body = JSONObject().apply {
            put("user_id", session.userId)
            put("username", session.username)
            put("display_name", displayName.take(40))
        }.toString().toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/mochy_profiles?on_conflict=user_id")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(body)
            .build()
        runCatching { http.newCall(request).execute().close() }
    }

    private fun executeAuthRequest(request: Request, isSignUp: Boolean): JSONObject {
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
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
                resp.code == 429 || "rate limit" in lower -> "Thao tác quá nhanh, vui lòng thử lại sau ít phút."
                isSignUp && ("already registered" in lower || "already exists" in lower) ->
                    "Tên đăng nhập này đã có người sử dụng."
                !isSignUp && ("invalid login credentials" in lower || "invalid" in lower) ->
                    "Sai tên đăng nhập hoặc mật khẩu."
                resp.code >= 500 -> "Máy chủ Mochy Cloud đang bận, thử lại sau nhé."
                msg.isNotBlank() -> msg
                else -> "Lỗi xác thực (${resp.code})."
            }
            throw MochyCloudException(friendly)
        }
    }

    private fun executeRestArray(request: Request): JSONArray {
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            }
            throw MochyCloudException(mapRestError(resp.code, raw))
        }
    }

    private fun executeRestWrite(request: Request) {
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw MochyCloudException(mapRestError(resp.code, raw))
            }
        }
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
