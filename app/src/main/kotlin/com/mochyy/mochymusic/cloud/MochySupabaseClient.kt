package com.mochyy.mochymusic.cloud

import android.content.Context
import com.mochyy.mochymusic.utils.AppProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    val hasGoogleLinked: Boolean = false,
    val googleEmail: String? = null,
    val hasPasswordLogin: Boolean = false,
    val username: String? = null,
    val createdAtIso: String? = null,
    val lastSignInAtIso: String? = null,
)

data class CloudIdentityInfo(
    val hasGoogleLinked: Boolean = false,
    val googleEmail: String? = null,
    val hasPasswordLogin: Boolean = false,
    val username: String? = null,
    val createdAtIso: String? = null,
    val lastSignInAtIso: String? = null,
)

data class ResolvedLoginAccount(
    val exists: Boolean,
    val loginEmail: String? = null,
    val hasGoogle: Boolean = false,
    val hasPassword: Boolean = false,
)

open class MochyCloudException(message: String) : Exception(message)

class MochyCloudAuthExpiredException(message: String) : MochyCloudException(message)

sealed class RefreshSessionResult {
    data class Success(val session: MochyCloudSession) : RefreshSessionResult()
    data class AuthExpired(val message: String) : RefreshSessionResult()
    data class NetworkError(val message: String) : RefreshSessionResult()
}

data class OAuthAuthResult(
    val session: MochyCloudSession,
    val email: String?,
    val avatarUrl: String?,
    val hasGoogleLinked: Boolean = true,
    val hasPasswordLogin: Boolean = false,
    val createdAtIso: String? = null,
    val lastSignInAtIso: String? = null,
)

data class ParsedOAuthCallback(
    val code: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val error: String? = null,
    val errorDescription: String? = null,
    val errorCode: String? = null,
)

/**
 * Pure-JVM helpers for Supabase Google OAuth PKCE (RFC 7636 S256).
 * Uses only standard JDK classes so unit tests run directly on the JVM without Android stubs.
 */
object MochyGoogleOAuthHelper {

    const val GoogleOAuthRedirectUri = "mochy-cloud://auth-callback"

    private const val SyntheticEmailSuffix = "@account.mochy.app"
    private val ValidUsernameRegex = Regex("^[a-z0-9._]{3,24}$")
    private val secureRandom = SecureRandom()

    fun generatePkceVerifier(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun computePkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun isRealUserEmail(email: String?): Boolean {
        val trimmed = email?.trim()?.lowercase() ?: return false
        if (trimmed.isEmpty() || trimmed.length > 254 || trimmed.any { it.isWhitespace() }) return false
        if (trimmed.endsWith(SyntheticEmailSuffix)) return false
        val at = trimmed.indexOf('@')
        if (at <= 0 || at != trimmed.lastIndexOf('@') || at >= trimmed.lastIndex) return false
        val domain = trimmed.substring(at + 1)
        val dot = domain.indexOf('.')
        return dot > 0 && dot < domain.lastIndex
    }

    fun buildGoogleAuthorizeUrl(
        baseUrl: String,
        codeChallenge: String,
        loginHintEmail: String? = null,
    ): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val base = "$cleanBase/auth/v1/authorize" +
            "?provider=google" +
            "&redirect_to=${encodeQueryComponent(GoogleOAuthRedirectUri)}" +
            "&code_challenge=${encodeQueryComponent(codeChallenge)}" +
            "&code_challenge_method=s256" +
            "&prompt=select_account"
        val hint = loginHintEmail?.trim()?.takeIf(::isRealUserEmail)
        return if (hint != null) {
            "$base&login_hint=${encodeQueryComponent(hint)}"
        } else {
            base
        }
    }

    fun buildGoogleLinkAuthorizeUrl(
        baseUrl: String,
        codeChallenge: String,
        loginHintEmail: String? = null,
    ): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val base = "$cleanBase/auth/v1/user/identities/authorize" +
            "?provider=google" +
            "&redirect_to=${encodeQueryComponent(GoogleOAuthRedirectUri)}" +
            "&code_challenge=${encodeQueryComponent(codeChallenge)}" +
            "&code_challenge_method=s256" +
            "&skip_http_redirect=true" +
            "&prompt=select_account"
        val hint = loginHintEmail?.trim()?.takeIf(::isRealUserEmail)
        return if (hint != null) {
            "$base&login_hint=${encodeQueryComponent(hint)}"
        } else {
            base
        }
    }

    fun parseOAuthCallbackParams(
        rawQuery: String?,
        rawFragment: String?,
    ): ParsedOAuthCallback {
        val params = LinkedHashMap<String, String>()
        parseUrlEncodedPairs(rawFragment?.removePrefix("#"), params)
        parseUrlEncodedPairs(rawQuery?.removePrefix("?"), params)
        return ParsedOAuthCallback(
            code = params["code"]?.takeIf { it.isNotBlank() },
            accessToken = params["access_token"]?.takeIf { it.isNotBlank() },
            refreshToken = params["refresh_token"]?.takeIf { it.isNotBlank() },
            expiresIn = params["expires_in"]?.toLongOrNull()?.takeIf { it > 0L },
            error = params["error"]?.takeIf { it.isNotBlank() },
            errorDescription = params["error_description"]?.takeIf { it.isNotBlank() }
                ?: params["error_msg"]?.takeIf { it.isNotBlank() },
            errorCode = params["error_code"]?.takeIf { it.isNotBlank() },
        )
    }

    fun deriveUsernameFromEmail(
        email: String?,
        userId: String,
        withSuffix: Boolean = false,
    ): String {
        val rawPrefix = email
            ?.trim()
            ?.substringBefore('@')
            ?.lowercase()
            .orEmpty()
        val cleaned = buildString(rawPrefix.length) {
            for (ch in rawPrefix) {
                if (ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_') {
                    append(ch)
                }
            }
        }.trim('.', '_')

        val base = if (cleaned.length < 3) "mochy" else cleaned
        if (!withSuffix) {
            return base.take(24).trimEnd('.', '_').let { if (it.length < 3) "mochy" else it }
        }
        val suffixSource = buildString(4) {
            for (ch in userId.lowercase()) {
                if (ch in 'a'..'z' || ch in '0'..'9') append(ch)
                if (length >= 4) break
            }
        }.padEnd(4, '0')
        val maxBase = 24 - 1 - suffixSource.length
        val shortBase = base.take(maxBase).trimEnd('.', '_').ifEmpty { "mochy" }
        return "${shortBase}_$suffixSource"
    }

    fun mapOAuthErrorToVietnamese(
        error: String?,
        errorDescription: String?,
    ): String {
        val desc = errorDescription?.trim().orEmpty()
        val err = error?.trim().orEmpty()
        val combined = "$err $desc".lowercase()
        return when {
            "identity_already_exists" in combined || "identity is already linked" in combined ->
                "Tài khoản Google này đã được đăng ký hoặc liên kết với một tài khoản Mochy khác."
            "manual_linking_disabled" in combined || "manual linking" in combined ->
                "Chưa bật tính năng liên kết thủ công trên Supabase (Authentication › Settings › Allow manual linking)."
            "provider is not enabled" in combined || "unsupported provider" in combined ->
                "Đăng nhập Google chưa được bật trên máy chủ Supabase (Authentication › Providers › Google)."
            "email_exists" in combined || "user_already_exists" in combined || "already registered" in combined ->
                "Tài khoản hoặc tên đăng nhập này đã được đăng ký."
            "access_denied" in combined || "cancel" in combined ->
                "Bạn đã hủy thao tác đăng nhập/liên kết Google."
            "flow_state" in combined || "bad_oauth_state" in combined || "code verifier" in combined ->
                "Phiên xác thực Google đã hết hạn, vui lòng thử lại."
            "redirect" in combined && ("not allowed" in combined || "mismatch" in combined || "invalid" in combined) ->
                "Đường dẫn chuyển hướng Google chưa được cấp phép trên máy chủ."
            desc.isNotBlank() -> desc
            err.isNotBlank() -> "Đăng nhập Google thất bại ($err)."
            else -> "Đăng nhập bằng Google thất bại."
        }
    }

    fun isNewlyRegisteredAccount(
        createdAtIso: String?,
        lastSignInAtIso: String?,
    ): Boolean {
        val createdSec = parseIsoEpochSec(createdAtIso) ?: return false
        val lastSignInSec = parseIsoEpochSec(lastSignInAtIso) ?: return false
        return abs(lastSignInSec - createdSec) <= 20L
    }

    fun parseIdentityInfo(userJson: String): CloudIdentityInfo {
        if (userJson.isBlank()) return CloudIdentityInfo()
        return runCatching {
            val root = CloudCodecJson.parseToJsonElement(userJson).jsonObject
            val userObj = root.optJsonObject("user") ?: root
            val meta = userObj.optJsonObject("user_metadata")
            val appMeta = userObj.optJsonObject("app_metadata")
            val identities = userObj.optJsonArray("identities")

            var hasGoogleIdentity = false
            var hasEmailIdentity = false
            var googleIdentityEmail: String? = null

            if (identities != null) {
                for (i in 0 until identities.size) {
                    val item = identities[i] as? JsonObject ?: continue
                    val provider = item.optJsonString("provider").trim().lowercase()
                    when (provider) {
                        "google" -> {
                            hasGoogleIdentity = true
                            if (googleIdentityEmail == null) {
                                val idData = item.optJsonObject("identity_data")
                                val candidate = idData?.optJsonString("email")
                                    ?.trim()
                                    ?.takeIf(::isRealUserEmail)
                                    ?: item.optJsonString("email")
                                        .trim()
                                        .takeIf(::isRealUserEmail)
                                googleIdentityEmail = candidate?.lowercase()
                            }
                        }
                        "email" -> {
                            hasEmailIdentity = true
                        }
                    }
                }
            }

            val appProviders = buildSet {
                appMeta?.optJsonString("provider")
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let(::add)
                val arr = appMeta?.optJsonArray("providers")
                if (arr != null) {
                    for (i in 0 until arr.size) {
                        arr.optJsonString(i)
                            .trim()
                            .lowercase()
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?.let(::add)
                    }
                }
            }

            val userEmail = userObj.optJsonString("email")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val isSyntheticEmail = userEmail?.lowercase()?.endsWith(SyntheticEmailSuffix) == true
            val metaHasPassword = meta?.optJsonBoolean("has_password", false) == true

            val hasGoogleLinked = hasGoogleIdentity || ("google" in appProviders)
            val hasPasswordLogin = hasEmailIdentity ||
                ("email" in appProviders) ||
                isSyntheticEmail ||
                metaHasPassword

            val resolvedGoogleEmail = if (hasGoogleLinked) {
                googleIdentityEmail
                    ?: userEmail?.takeIf(::isRealUserEmail)?.lowercase()
                    ?: meta?.optJsonString("email")?.trim()?.takeIf(::isRealUserEmail)?.lowercase()
            } else {
                null
            }

            val resolvedUsername = meta?.optJsonString("username")
                ?.trim()
                ?.lowercase()
                ?.removePrefix("@")
                ?.takeIf { it.isNotBlank() && it != "null" && ValidUsernameRegex.matches(it) }

            val createdAtIso = userObj.optJsonString("created_at")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val lastSignInAtIso = userObj.optJsonString("last_sign_in_at")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }

            CloudIdentityInfo(
                hasGoogleLinked = hasGoogleLinked,
                googleEmail = resolvedGoogleEmail,
                hasPasswordLogin = hasPasswordLogin,
                username = resolvedUsername,
                createdAtIso = createdAtIso,
                lastSignInAtIso = lastSignInAtIso,
            )
        }.getOrDefault(CloudIdentityInfo())
    }

    private fun parseIsoEpochSec(raw: String?): Long? {
        val clean = raw?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return runCatching { Instant.parse(clean).epochSecond }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(clean).toInstant().epochSecond }.getOrNull()
            ?: runCatching { LocalDateTime.parse(clean).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    }

    private fun parseUrlEncodedPairs(raw: String?, out: MutableMap<String, String>) {
        if (raw.isNullOrBlank()) return
        for (segment in raw.split('&')) {
            if (segment.isBlank()) continue
            val eq = segment.indexOf('=')
            val rawKey = if (eq >= 0) segment.substring(0, eq) else segment
            val rawVal = if (eq >= 0) segment.substring(eq + 1) else ""
            val key = decodeQueryComponent(rawKey).trim()
            val value = decodeQueryComponent(rawVal).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                out[key] = value
            }
        }
    }

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decodeQueryComponent(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}

sealed class EdgeSignInOutcome {
    data class Success(val sessionRawJson: String) : EdgeSignInOutcome()
    data object NotFound : EdgeSignInOutcome()
    data object GoogleNoPassword : EdgeSignInOutcome()
    data class InvalidCredentials(val hasGoogle: Boolean = false) : EdgeSignInOutcome()
    data class RateLimited(val retryAfterSec: Int? = null) : EdgeSignInOutcome()
    data object Unavailable : EdgeSignInOutcome()
}

sealed class EdgeCheckSignUpOutcome {
    data object Available : EdgeCheckSignUpOutcome()
    data class Taken(val hasGoogle: Boolean = false) : EdgeCheckSignUpOutcome()
    data class RateLimited(val retryAfterSec: Int? = null) : EdgeCheckSignUpOutcome()
    data object Unavailable : EdgeCheckSignUpOutcome()
}

/**
 * Pure-JVM helpers for the `mochy-username-login` Supabase Edge Function protocol.
 * Only treats a response as authoritative when `"source": "mochy-edge"` is present,
 * ensuring safe fallback if the Edge Function has not been deployed yet or JWT verification
 * is still enabled on the gateway.
 */
object MochyUsernameLoginEdgeHelper {

    const val EdgeFunctionPath = "/functions/v1/mochy-username-login"
    const val ExpectedSourceTag = "mochy-edge"

    fun buildEndpointUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/') + EdgeFunctionPath

    fun parseSignInResponse(httpCode: Int, rawBody: String): EdgeSignInOutcome {
        if (rawBody.isBlank()) return EdgeSignInOutcome.Unavailable
        return runCatching {
            val root = CloudCodecJson.parseToJsonElement(rawBody).jsonObject
            if (root.optJsonString("source") != ExpectedSourceTag) {
                return@runCatching EdgeSignInOutcome.Unavailable
            }
            when (root.optJsonString("status").trim().lowercase()) {
                "ok" -> {
                    val sessionObj = root.optJsonObject("session")
                        ?: return@runCatching EdgeSignInOutcome.Unavailable
                    if (sessionObj.optJsonString("access_token").isBlank()) {
                        return@runCatching EdgeSignInOutcome.Unavailable
                    }
                    EdgeSignInOutcome.Success(sessionObj.toString())
                }
                "not_found" -> EdgeSignInOutcome.NotFound
                "google_no_password" -> EdgeSignInOutcome.GoogleNoPassword
                "invalid_credentials" -> EdgeSignInOutcome.InvalidCredentials(
                    hasGoogle = root.optJsonBoolean("has_google", false),
                )
                "rate_limited" -> EdgeSignInOutcome.RateLimited(
                    retryAfterSec = root.optJsonInt("retry_after", 0).takeIf { it > 0 },
                )
                else -> if (httpCode == 429) EdgeSignInOutcome.RateLimited() else EdgeSignInOutcome.Unavailable
            }
        }.getOrDefault(EdgeSignInOutcome.Unavailable)
    }

    fun parseCheckSignUpResponse(httpCode: Int, rawBody: String): EdgeCheckSignUpOutcome {
        if (rawBody.isBlank()) return EdgeCheckSignUpOutcome.Unavailable
        return runCatching {
            val root = CloudCodecJson.parseToJsonElement(rawBody).jsonObject
            if (root.optJsonString("source") != ExpectedSourceTag) {
                return@runCatching EdgeCheckSignUpOutcome.Unavailable
            }
            when (root.optJsonString("status").trim().lowercase()) {
                "available" -> EdgeCheckSignUpOutcome.Available
                "taken" -> EdgeCheckSignUpOutcome.Taken(
                    hasGoogle = root.optJsonBoolean("has_google", false),
                )
                "rate_limited" -> EdgeCheckSignUpOutcome.RateLimited(
                    retryAfterSec = root.optJsonInt("retry_after", 0).takeIf { it > 0 },
                )
                else -> if (httpCode == 429) EdgeCheckSignUpOutcome.RateLimited() else EdgeCheckSignUpOutcome.Unavailable
            }
        }.getOrDefault(EdgeCheckSignUpOutcome.Unavailable)
    }
}

/**
 * Giao tiếp HTTPS trực tiếp với Supabase Auth + PostgREST cho Mochy Cloud.
 * Tuân thủ quy tắc Publishable Key:
 * - Chỉ truyền qua header `apikey`, tuyệt đối không đặt Publishable Key vào `Authorization: Bearer`.
 * - Header `Authorization: Bearer` chỉ mang JWT truy cập của chính người dùng đã đăng nhập.
 */
object MochySupabaseClient {

    private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val UsernameRegex = Regex("^[a-z0-9._]{3,24}$")
    private val refreshMutex = Mutex()

    private val http: OkHttpClient by lazy {
        AppProxy.newClientBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun normalizeUsername(raw: String): String = raw.trim().lowercase()

    fun isValidUsername(username: String): Boolean = UsernameRegex.matches(username)

    private fun syntheticEmail(username: String): String = "$username@account.mochy.app"

    private fun callEdgeCheckSignUp(
        baseUrl: String,
        publishableKey: String,
        username: String,
    ): EdgeCheckSignUpOutcome {
        val body = JSONObject().apply {
            put("action", "check_signup")
            put("username", username)
        }.toString().toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url(MochyUsernameLoginEdgeHelper.buildEndpointUrl(baseUrl))
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(
                    httpCode = resp.code,
                    rawBody = resp.body.string(),
                )
            }
        }.getOrDefault(EdgeCheckSignUpOutcome.Unavailable)
    }

    private fun callEdgeUsernameSignIn(
        baseUrl: String,
        publishableKey: String,
        username: String,
        password: String,
    ): EdgeSignInOutcome {
        val body = JSONObject().apply {
            put("action", "signin")
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url(MochyUsernameLoginEdgeHelper.buildEndpointUrl(baseUrl))
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                MochyUsernameLoginEdgeHelper.parseSignInResponse(
                    httpCode = resp.code,
                    rawBody = resp.body.string(),
                )
            }
        }.getOrDefault(EdgeSignInOutcome.Unavailable)
    }

    suspend fun resolveLoginAccount(
        context: Context,
        username: String,
    ): ResolvedLoginAccount? = withContext(Dispatchers.IO) {
        val clean = normalizeUsername(username).removePrefix("@")
        if (!isValidUsername(clean)) return@withContext null
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext null
        val body = JSONObject().apply {
            put("p_username", clean)
        }.toString().toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/rpc/mochy_resolve_login_account")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()
        runCatching {
            http.newCall(request).execute().use { resp ->
                if (resp.code !in 200..299) return@runCatching null
                val raw = resp.body.string().trim()
                if (raw.isBlank() || raw == "null") return@runCatching null
                val obj = JSONObject(raw)
                if (!obj.has("exists")) return@runCatching null
                val loginEmail = obj.optString("login_email")
                    .trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                ResolvedLoginAccount(
                    exists = obj.optBoolean("exists", false),
                    loginEmail = loginEmail,
                    hasGoogle = obj.optBoolean("has_google", false),
                    hasPassword = obj.optBoolean("has_password", false),
                )
            }
        }.getOrNull()
    }

    suspend fun requestGoogleLinkAuthorizeUrl(
        context: Context,
        session: MochyCloudSession,
        codeChallenge: String,
        loginHintEmail: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")
        val endpoint = MochyGoogleOAuthHelper.buildGoogleLinkAuthorizeUrl(
            baseUrl = baseUrl,
            codeChallenge = codeChallenge,
            loginHintEmail = loginHintEmail,
        )
        val json = executeAuthenticatedAuthJson(context, session) { accessToken ->
            Request.Builder()
                .url(endpoint)
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
        }
        val redirectUrl = json.optString("url").trim()
        if (redirectUrl.isBlank() ||
            (!redirectUrl.startsWith("https://", ignoreCase = true) &&
                !redirectUrl.startsWith("http://", ignoreCase = true))
        ) {
            throw MochyCloudException("Không nhận được đường dẫn liên kết Google từ máy chủ Mochy Cloud.")
        }
        redirectUrl
    }

    suspend fun signUp(
        context: Context,
        rawUsername: String,
        password: String,
        displayName: String,
    ): MochyCloudSession = withContext(Dispatchers.IO) {
        val username = normalizeUsername(rawUsername).removePrefix("@")
        if (!isValidUsername(username)) {
            throw MochyCloudException("Tên đăng nhập 3–24 ký tự, chỉ gồm chữ thường, số, dấu chấm hoặc gạch dưới.")
        }
        if (password.length < 6) {
            throw MochyCloudException("Mật khẩu phải có ít nhất 6 ký tự.")
        }
        val cleanDisplay = displayName.trim().ifBlank { username }.take(40)
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")

        when (val edgeCheck = callEdgeCheckSignUp(baseUrl, publishableKey, username)) {
            EdgeCheckSignUpOutcome.Available -> Unit
            is EdgeCheckSignUpOutcome.Taken -> {
                if (edgeCheck.hasGoogle) {
                    throw MochyCloudException(
                        "Tên đăng nhập @$username đã được đăng ký và liên kết với Google. Hãy chuyển sang Đăng nhập hoặc bấm 'Tiếp tục với Google'.",
                    )
                }
                throw MochyCloudException(
                    "Tên đăng nhập @$username đã được đăng ký. Vui lòng chọn tên khác hoặc chuyển sang Đăng nhập.",
                )
            }
            is EdgeCheckSignUpOutcome.RateLimited -> {
                throw MochyCloudException("Bạn thao tác quá nhanh. Vui lòng chờ vài phút rồi thử lại.")
            }
            EdgeCheckSignUpOutcome.Unavailable -> {
                val resolved = resolveLoginAccount(context, username)
                if (resolved?.exists == true) {
                    if (resolved.hasGoogle) {
                        throw MochyCloudException(
                            "Tên đăng nhập @$username đã được đăng ký và liên kết với Google. Hãy chuyển sang Đăng nhập hoặc bấm 'Tiếp tục với Google'.",
                        )
                    }
                    throw MochyCloudException(
                        "Tên đăng nhập @$username đã được đăng ký. Vui lòng chọn tên khác hoặc chuyển sang Đăng nhập.",
                    )
                }
            }
        }

        val body = JSONObject().apply {
            put("email", syntheticEmail(username))
            put("password", password)
            put("data", JSONObject().apply {
                put("username", username)
                put("display_name", cleanDisplay)
                put("has_password", true)
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
        val effectiveUsername = ensureProfileRow(context, session, cleanDisplay)
        if (effectiveUsername != session.username) session.copy(username = effectiveUsername) else session
    }

    suspend fun exchangePkceCode(
        context: Context,
        authCode: String,
        codeVerifier: String,
    ): OAuthAuthResult = withContext(Dispatchers.IO) {
        val cleanCode = authCode.trim()
        val cleanVerifier = codeVerifier.trim()
        if (cleanCode.isBlank() || cleanVerifier.isBlank()) {
            throw MochyCloudException("Thiếu mã xác thực Google, vui lòng thử đăng nhập lại.")
        }
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")

        val body = JSONObject().apply {
            put("auth_code", cleanCode)
            put("code_verifier", cleanVerifier)
        }.toString().toRequestBody(JsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=pkce")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()

        val responseJson = executeAuthRequest(request, isSignUp = false, isSignIn = false)
        parseOAuthSession(responseJson)
    }

    suspend fun fetchSessionFromAccessToken(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresInSec: Long,
    ): OAuthAuthResult = withContext(Dispatchers.IO) {
        val cleanAccess = accessToken.trim()
        if (cleanAccess.isBlank()) {
            throw MochyCloudException("Không nhận được phiên đăng nhập Google hợp lệ.")
        }
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer $cleanAccess")
            .header("Accept", "application/json")
            .get()
            .build()

        val responseJson = executeAuthRequest(request, isSignUp = false, isSignIn = false)
        val userObj = responseJson.optJSONObject("user") ?: responseJson
        val expiresAt = (System.currentTimeMillis() / 1000L) + expiresInSec.coerceAtLeast(60L)
        buildOAuthAuthResult(
            accessToken = cleanAccess,
            refreshToken = refreshToken.trim(),
            expiresAtEpochSec = expiresAt,
            userObj = userObj,
        )
    }

    suspend fun downloadImageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true) || cleanUrl.length > 1024) {
            return@withContext null
        }
        val maxBytes = 2 * 1024 * 1024
        val request = Request.Builder()
            .url(cleanUrl)
            .header("Accept", "image/*")
            .get()
            .build()
        runCatching {
            http.newCall(request).execute().use { resp ->
                if (resp.code != 200) return@runCatching null
                val body = resp.body ?: return@runCatching null
                val contentLength = body.contentLength()
                if (contentLength > maxBytes) return@runCatching null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                body.byteStream().use { input ->
                    var total = 0
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) return@runCatching null
                        out.write(buf, 0, read)
                    }
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    suspend fun updateIdentityMetadata(
        context: Context,
        session: MochyCloudSession,
        username: String? = null,
        displayName: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val data = JSONObject().apply {
            username?.trim()?.let(::normalizeUsername)?.removePrefix("@")?.takeIf(::isValidUsername)?.let {
                put("username", it)
            }
            displayName?.trim()?.takeIf { it.isNotBlank() }?.take(40)?.let {
                put("display_name", it)
            }
        }
        if (data.length() == 0) return@withContext
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return@withContext
        val body = JSONObject().apply {
            put("data", data)
        }.toString().toRequestBody(JsonMediaType)
        runCatching {
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
    }

    suspend fun signIn(
        context: Context,
        rawUsername: String,
        password: String,
    ): MochyCloudSession = signInDetailed(context, rawUsername, password).first

    internal suspend fun signInDetailed(
        context: Context,
        rawUsername: String,
        password: String,
    ): Pair<MochyCloudSession, CloudIdentityInfo> = withContext(Dispatchers.IO) {
        val cleaned = rawUsername.trim().lowercase()
        if (cleaned.isEmpty()) {
            throw MochyCloudException("Vui lòng nhập tên đăng nhập hoặc Gmail.")
        }
        if (password.isEmpty()) {
            throw MochyCloudException("Vui lòng nhập mật khẩu.")
        }
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Bản dựng này chưa cấu hình Mochy Cloud.")

        val isDirectEmail = cleaned.contains("@") && !cleaned.startsWith("@")
        val targetEmail: String
        val fallbackUsername: String
        var resolvedUsernameForErrors: String? = null

        if (isDirectEmail) {
            val at = cleaned.indexOf('@')
            val domain = cleaned.substring(at + 1)
            val validEmail = at > 0 &&
                at == cleaned.lastIndexOf('@') &&
                cleaned.length <= 254 &&
                !cleaned.any { it.isWhitespace() } &&
                domain.indexOf('.').let { dot -> dot > 0 && dot < domain.lastIndex }
            if (!validEmail) {
                throw MochyCloudException("Email hoặc tên đăng nhập không hợp lệ.")
            }
            targetEmail = cleaned
            fallbackUsername = cleaned.substringBefore('@')
        } else {
            val username = cleaned.removePrefix("@")
            if (!isValidUsername(username)) {
                throw MochyCloudException("Tên đăng nhập không hợp lệ.")
            }
            when (val edgeOutcome = callEdgeUsernameSignIn(baseUrl, publishableKey, username, password)) {
                is EdgeSignInOutcome.Success -> {
                    val responseJson = JSONObject(edgeOutcome.sessionRawJson)
                    val identityInfo = MochyGoogleOAuthHelper.parseIdentityInfo(edgeOutcome.sessionRawJson)
                    val session = parseAuthSession(
                        json = responseJson,
                        fallbackUsername = username,
                        fallbackDisplay = username,
                    )
                    return@withContext session to identityInfo
                }
                EdgeSignInOutcome.NotFound -> {
                    throw MochyCloudException("Tài khoản @$username chưa được đăng ký trên Mochy Cloud.")
                }
                EdgeSignInOutcome.GoogleNoPassword -> {
                    throw MochyCloudException(
                        "Tài khoản @$username được đăng ký bằng Google và chưa đặt mật khẩu. Hãy bấm 'Tiếp tục với Google' (sau đó vào Tài khoản Mochy Cloud để đặt mật khẩu nếu muốn đăng nhập bằng @$username).",
                    )
                }
                is EdgeSignInOutcome.InvalidCredentials -> {
                    val hint = if (edgeOutcome.hasGoogle) {
                        "Sai mật khẩu cho tài khoản @$username (hoặc bấm 'Tiếp tục với Google')."
                    } else {
                        "Sai mật khẩu cho tài khoản @$username."
                    }
                    throw MochyCloudException(hint)
                }
                is EdgeSignInOutcome.RateLimited -> {
                    throw MochyCloudException("Bạn đăng nhập sai hoặc thử quá nhiều lần. Vui lòng chờ vài phút rồi thử lại.")
                }
                EdgeSignInOutcome.Unavailable -> Unit
            }
            val resolved = resolveLoginAccount(context, username)
            if (resolved != null) {
                if (!resolved.exists) {
                    throw MochyCloudException("Tài khoản @$username chưa được đăng ký trên Mochy Cloud.")
                }
                if (resolved.hasGoogle && !resolved.hasPassword) {
                    throw MochyCloudException(
                        "Tài khoản @$username được đăng ký bằng Google và chưa đặt mật khẩu. Hãy bấm 'Tiếp tục với Google' (sau đó vào Tài khoản Mochy Cloud để đặt mật khẩu nếu muốn đăng nhập bằng @$username).",
                    )
                }
                targetEmail = resolved.loginEmail?.takeIf { it.isNotBlank() } ?: syntheticEmail(username)
                resolvedUsernameForErrors = username
            } else {
                targetEmail = syntheticEmail(username)
            }
            fallbackUsername = username
        }

        val body = JSONObject().apply {
            put("email", targetEmail)
            put("password", password)
        }.toString().toRequestBody(JsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body)
            .build()

        val res = executeSingleCall(request)
        val responseJson = if (res.code == 400 && resolvedUsernameForErrors != null) {
            val errJson = runCatching { JSONObject(res.body) }.getOrNull()
            val errCode = errJson?.optString("error_code")?.ifBlank { null }
                ?: errJson?.optString("error")?.ifBlank { null }.orEmpty()
            val msg = errJson?.optString("msg")?.ifBlank { null }
                ?: errJson?.optString("error_description")?.ifBlank { null }
                ?: errJson?.optString("message").orEmpty()
            val combined = "$errCode $msg".lowercase()
            if ("invalid login credentials" in combined ||
                "invalid_credentials" in combined ||
                "invalid_grant" in combined
            ) {
                throw MochyCloudException("Sai mật khẩu cho tài khoản @$resolvedUsernameForErrors.")
            }
            parseAuthResponseOrThrow(res.code, res.body, isSignUp = false, isSignIn = true)
        } else {
            parseAuthResponseOrThrow(res.code, res.body, isSignUp = false, isSignIn = true)
        }

        val identityInfo = MochyGoogleOAuthHelper.parseIdentityInfo(responseJson.toString())
        val session = parseAuthSession(
            json = responseJson,
            fallbackUsername = fallbackUsername,
            fallbackDisplay = fallbackUsername,
        )
        session to identityInfo
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
                    HttpCallResult(resp.code, resp.body.string())
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
        val identityInfo = MochyGoogleOAuthHelper.parseIdentityInfo(json.toString())
        CloudUserMetadata(
            cardNumber = meta?.optString("card_number")?.takeIf { it.isNotBlank() && it != "null" },
            joinedEpochDay = meta?.optLong("joined_epoch_day", -1L)?.takeIf { it > 0L },
            playlistsGz = meta?.optString("playlists_gz")?.takeIf { it.isNotBlank() && it != "null" },
            libraryExtraGz = meta?.optString("library_extra_gz")?.takeIf { it.isNotBlank() && it != "null" },
            playbackResumeGz = meta?.optString("playback_resume_gz")?.takeIf { it.isNotBlank() && it != "null" },
            hasGoogleLinked = identityInfo.hasGoogleLinked,
            googleEmail = identityInfo.googleEmail,
            hasPasswordLogin = identityInfo.hasPasswordLogin,
            username = identityInfo.username,
            createdAtIso = identityInfo.createdAtIso,
            lastSignInAtIso = identityInfo.lastSignInAtIso,
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
            } else if (playlistsGz != null && playlistsGz.length <= 6_000) {
                put("playlists_gz", playlistsGz)
            }
            if (clearLibraryExtra) {
                put("library_extra_gz", "")
            } else if (libraryExtraGz != null && libraryExtraGz.length <= 4_200) {
                put("library_extra_gz", libraryExtraGz)
            }
            if (clearPlaybackResume) {
                put("playback_resume_gz", "")
            } else if (playbackResumeGz != null && playbackResumeGz.length <= 1_500) {
                put("playback_resume_gz", playbackResumeGz)
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
            put("data", JSONObject().apply {
                put("has_password", true)
            })
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

    suspend fun updateUsername(
        context: Context,
        session: MochyCloudSession,
        cleanUsername: String,
    ): String = withContext(Dispatchers.IO) {
        val clean = normalizeUsername(cleanUsername).removePrefix("@")
        if (!isValidUsername(clean)) {
            throw MochyCloudException("Tên đăng nhập 3–24 ký tự, chỉ gồm chữ thường, số, dấu chấm hoặc gạch dưới.")
        }
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context)
            ?: throw MochyCloudException("Chưa cấu hình Mochy Cloud.")
        val uid = urlEncode(session.userId)
        val body = JSONObject().apply {
            put("username", clean)
        }.toString().toRequestBody(JsonMediaType)

        executeAuthenticatedCall(
            context = context,
            session = session,
            buildRequest = { accessToken ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/mochy_profiles?user_id=eq.$uid")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Prefer", "return=minimal")
                    .patch(body)
                    .build()
            },
            handleResponse = { code, raw ->
                if (code !in 200..299) {
                    val pgCode = runCatching { JSONObject(raw).optString("code") }.getOrNull().orEmpty()
                    if (code == 409 || pgCode == "23505") {
                        throw MochyCloudException("Tên đăng nhập @$clean đã được đăng ký bởi người khác.")
                    }
                    throw MochyCloudException(mapRestError(code, raw))
                }
            },
        )
        updateIdentityMetadata(context, session, username = clean)
        clean
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

    internal fun ensureProfileRow(
        context: Context,
        session: MochyCloudSession,
        displayName: String,
    ): String {
        val (baseUrl, publishableKey) = MochyCloudVault.revealEndpoint(context) ?: return session.username
        val activeToken = MochyCloudVault.loadSession(context)
            ?.takeIf { it.userId == session.userId }
            ?.accessToken
            ?: session.accessToken
        val cleanDisplay = displayName.trim().ifBlank { session.username }.take(40)

        fun postProfileRow(candidateUsername: String): HttpCallResult? {
            val body = JSONObject().apply {
                put("user_id", session.userId)
                put("username", candidateUsername)
                put("display_name", cleanDisplay)
            }.toString().toRequestBody(JsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/mochy_profiles?on_conflict=user_id")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer $activeToken")
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(body)
                .build()
            return runCatching {
                http.newCall(request).execute().use { resp ->
                    HttpCallResult(resp.code, resp.body.string())
                }
            }.getOrNull()
        }

        val first = postProfileRow(session.username) ?: return session.username
        if (first.code in 200..299) {
            return session.username
        }
        val pgCode = runCatching { JSONObject(first.body).optString("code") }.getOrNull().orEmpty()
        if (first.code == 409 || pgCode == "23505") {
            val fallbackUsername = MochyGoogleOAuthHelper.deriveUsernameFromEmail(
                email = session.username,
                userId = session.userId,
                withSuffix = true,
            )
            if (fallbackUsername != session.username) {
                val retry = postProfileRow(fallbackUsername)
                if (retry != null && retry.code in 200..299) {
                    return fallbackUsername
                }
            }
        }
        return session.username
    }

    private fun isCloudSafeSongId(id: String): Boolean {
        val clean = id.trim()
        return clean.isNotEmpty() &&
            clean.length <= 64 &&
            !clean.startsWith("LA") &&
            !clean.startsWith("LOCAL_", ignoreCase = true) &&
            !clean.contains("://") &&
            !clean.contains("/")
    }

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
                return HttpCallResult(resp.code, resp.body.string())
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
        val errCode = errJson?.optString("error_code")?.ifBlank { null }
            ?: errJson?.optString("error")?.ifBlank { null }.orEmpty()
        val msg = errJson?.optString("msg")?.ifBlank { null }
            ?: errJson?.optString("error_description")?.ifBlank { null }
            ?: errJson?.optString("message").orEmpty()
        val combined = "$errCode $msg".lowercase()
        val friendly = when {
            code == 401 -> "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại."
            code == 429 || "rate limit" in combined -> "Thao tác quá nhanh, vui lòng thử lại sau ít phút."
            "identity_already_exists" in combined || "identity is already linked" in combined ->
                "Tài khoản Google này đã được đăng ký hoặc liên kết với một tài khoản Mochy khác."
            "manual_linking_disabled" in combined || "manual linking" in combined ->
                "Chưa bật tính năng liên kết thủ công trên Supabase (Authentication › Settings › Allow manual linking)."
            "provider is not enabled" in combined || "unsupported provider" in combined ->
                "Đăng nhập Google chưa được bật trên máy chủ Supabase (Authentication › Providers › Google)."
            "access_denied" in combined || "cancel" in combined ->
                "Bạn đã hủy thao tác đăng nhập/liên kết Google."
            "code verifier" in combined || "invalid flow state" in combined || "flow_state" in combined ->
                "Phiên xác thực Google đã hết hạn, vui lòng thử lại."
            "email_exists" in combined || "user_already_exists" in combined || "already registered" in combined ||
                (isSignUp && "already exists" in combined) ->
                "Tài khoản hoặc tên đăng nhập này đã được đăng ký."
            isSignIn && ("invalid login credentials" in combined || "invalid_credentials" in combined || "invalid" in combined) ->
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
            code == 429 -> "Bạn thao tác quá nhanh, vui lòng thử lại sau ít phút."
            pgCode == "23505" -> "Tên đăng nhập đã tồn tại."
            pgCode == "P0001" && "cooldown" in msg.lowercase() ->
                "Vui lòng đợi 30 giây giữa các lần đổi ảnh đại diện."
            pgCode == "P0001" && "favorites_limit" in msg.lowercase() ->
                "Danh sách bài hát yêu thích trên Mochy Cloud đã đạt giới hạn tối đa (3.000 bài)."
            "avatar" in msg.lowercase() -> "Ảnh đại diện không hợp lệ hoặc vượt giới hạn nhẹ."
            code >= 500 -> "Máy chủ Mochy Cloud đang bận."
            code == 400 && pgCode.isNotBlank() -> "Đồng bộ thất bại (mã 400 · $pgCode)."
            else -> "Đồng bộ thất bại (mã $code)."
        }
    }

    private fun parseOAuthSession(json: JSONObject): OAuthAuthResult {
        val access = json.optString("access_token").trim()
        val refresh = json.optString("refresh_token").trim()
        val expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0L }
            ?: ((System.currentTimeMillis() / 1000L) + json.optLong("expires_in", 3600L).coerceAtLeast(60L))
        val userObj = json.optJSONObject("user")
        return buildOAuthAuthResult(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSec = expiresAt,
            userObj = userObj,
        )
    }

    private fun buildOAuthAuthResult(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochSec: Long,
        userObj: JSONObject?,
    ): OAuthAuthResult {
        val userId = userObj?.optString("id")?.trim().orEmpty()
        if (accessToken.isBlank() || userId.isBlank()) {
            throw MochyCloudException("Không nhận được phiên đăng nhập hợp lệ.")
        }
        val identityInfo = userObj?.let { MochyGoogleOAuthHelper.parseIdentityInfo(it.toString()) }
            ?: CloudIdentityInfo()
        val meta = userObj?.optJSONObject("user_metadata")
        val rawEmail = userObj?.optString("email")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            ?: meta?.optString("email")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val realEmail = identityInfo.googleEmail
            ?: rawEmail?.takeIf { MochyGoogleOAuthHelper.isRealUserEmail(it) }?.lowercase()
        val emailPrefix = (realEmail ?: rawEmail)?.substringBefore('@')?.trim()?.takeIf { it.isNotBlank() }

        val displayName = listOf(
            meta?.optString("display_name"),
            meta?.optString("full_name"),
            meta?.optString("name"),
            emailPrefix,
        ).firstOrNull { !it.isNullOrBlank() && it != "null" }
            ?.trim()
            ?.take(40)
            ?: "Mochy User"

        val metaUsername = identityInfo.username
            ?: meta?.optString("username")
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let(::normalizeUsername)
                ?.removePrefix("@")
                ?.takeIf(::isValidUsername)
        val username = metaUsername
            ?: MochyGoogleOAuthHelper.deriveUsernameFromEmail(realEmail ?: rawEmail, userId, withSuffix = false)

        val avatarUrl = listOf(
            meta?.optString("avatar_url"),
            meta?.optString("picture"),
        ).firstOrNull { raw ->
            val clean = raw?.trim().orEmpty()
            clean.startsWith("https://", ignoreCase = true) && clean.length <= 1024
        }?.trim()

        return OAuthAuthResult(
            session = MochyCloudSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSec = expiresAtEpochSec,
                userId = userId,
                username = username,
                displayName = displayName,
            ),
            email = realEmail,
            avatarUrl = avatarUrl,
            hasGoogleLinked = identityInfo.hasGoogleLinked || realEmail != null,
            hasPasswordLogin = identityInfo.hasPasswordLogin,
            createdAtIso = identityInfo.createdAtIso,
            lastSignInAtIso = identityInfo.lastSignInAtIso,
        )
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
        if (access.isBlank() || userId.isBlank()) {
            throw MochyCloudException("Không nhận được phiên đăng nhập hợp lệ.")
        }
        val meta = userObj?.optJSONObject("user_metadata")
        val cleanFallbackUsername = normalizeUsername(fallbackUsername).removePrefix("@")
        val username = meta?.optString("username")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(::normalizeUsername)
            ?.removePrefix("@")
            ?.takeIf(::isValidUsername)
            ?: cleanFallbackUsername.takeIf(::isValidUsername)
            ?: MochyGoogleOAuthHelper.deriveUsernameFromEmail(
                email = userObj?.optString("email"),
                userId = userId,
                withSuffix = false,
            )
        val display = listOf(
            meta?.optString("display_name"),
            meta?.optString("full_name"),
            meta?.optString("name"),
        ).firstOrNull { !it.isNullOrBlank() && it != "null" }
            ?.trim()
            ?.take(40)
            ?: fallbackDisplay.trim().ifBlank { username }.take(40)
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
