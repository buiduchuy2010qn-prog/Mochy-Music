package com.mochyy.mochymusic.module.applemusic

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.LicenseServer
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SignInSpec
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamDrm
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.TrackMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays the user's own Apple Music subscription inside Mochy.
 *
 * Signing in happens on music.apple.com itself; the module keeps the web player's developer token
 * and the account's music-user token. A song is found in the catalog by title, artist and length,
 * its web-playback stream is opened, and the device's Widevine CDM decrypts it inside the
 * platform, licensed by Apple's web license server exactly as the web player in Chrome is.
 * Mochy's own player does the playing, so every effect applies.
 *
 * What it can't do: lossless, Atmos and Apple's Spatial mix (only in streams encoded for FairPlay,
 * Apple's own DRM) and downloads (no offline licenses; the audio is never decrypted outside the
 * CDM).
 */
class AppleMusicModule : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "Apple Music",
        version = "1.0",
        description = "Phát nhạc từ gói Apple Music của bạn trong Mochy ở chất lượng AAC 256, hỗ trợ đầy đủ hiệu ứng Audio Lab",
    )

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val matches = ConcurrentHashMap<String, CatalogTrack>()
    private val misses = ConcurrentHashMap<String, Long>()

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val signIn = SignInSpec(
        startUrl = "https://music.apple.com/vn/library/playlists",
        origins = listOf("https://music.apple.com", "https://idmsa.apple.com", "https://authorize.music.apple.com", "https://www.apple.com"),
        sessionOrigins = setOf("https://music.apple.com"),
        desktopSite = false,
        title = "Đăng nhập Apple Music",
        subtitle = "Phát nhạc từ gói đăng ký của bạn ngay trong Mochy",
    )

    override val choices = listOf(
        ModuleChoice(
            id = ChoiceQuality,
            label = "Luồng phát",
            options = listOf(
                StreamOption(OptionAac256, "AAC 256", "Chất lượng stereo tiêu chuẩn của Apple Music"),
                StreamOption(OptionHe64, "HE-AAC 64", "Tiết kiệm 3/4 dung lượng cho mạng chậm hoặc giới hạn dữ liệu"),
            ),
        ),
        ModuleChoice(
            id = ChoiceRegion,
            label = "Khu vực",
            options = listOf(StreamOption(RegionAuto, "Theo tài khoản", "Tìm kiếm danh mục theo quốc gia của tài khoản Apple Music")) +
                Regions.map { (code, name) ->
                    StreamOption(code, name, "Tìm kiếm danh mục $name. Bài hát chưa cấp phép tại quốc gia của tài khoản có thể không phát được")
                },
        ),
    )

    // ---- account ------------------------------------------------------------------------------

    private var userToken: String?
        get() = host.storage.get("user_token")
        set(value) = host.storage.set("user_token", value)

    private var capturedDeveloperToken: String?
        get() = host.storage.get("developer_token")
        set(value) = host.storage.set("developer_token", value)

    private var storefront: String?
        get() = host.storage.get("storefront")
        set(value) = host.storage.set("storefront", value)

    override fun accountState(): AccountState = when {
        userToken == null -> AccountState.SignedOut
        host.storage.get("expired") != null -> AccountState.Expired(host.storage.get("expired")!!)
        else -> AccountState.SignedIn(host.storage.get("account_label") ?: "Đã đăng nhập")
    }

    override fun onSignInHeader(name: String, value: String, url: String): Boolean {
        val trimmed = value.trim()
        when (name.trim().lowercase()) {
            "authorization" -> {
                val token = trimmed.removePrefix("Bearer ").removePrefix("bearer ").trim()
                if (token.startsWith("eyJ") && jwtExpiryMs(token) > System.currentTimeMillis()) capturedDeveloperToken = token
            }
            "media-user-token" -> if (trimmed.length >= 20) {
                userToken = trimmed
                host.storage.set("expired", null)
            }
        }
        return userToken != null && capturedDeveloperToken != null
    }

    override suspend fun verifyAccount(): AccountState = verifyAccount(retried = false)

    private fun verifyAccount(retried: Boolean): AccountState {
        if (userToken == null) return AccountState.SignedOut
        val response = api("/v1/me/storefront")
        if (response.code == 401 || response.code == 403) {
            // the developer token may just have rotated; try the current one, once, before giving up
            if (!retried && refreshDeveloperToken()) return verifyAccount(retried = true)
            host.storage.set("expired", "Phiên đăng nhập Apple đã hết hạn")
            return AccountState.Expired("Phiên đăng nhập Apple đã hết hạn")
        }
        if (!response.ok) return AccountState.Expired("Apple Music không phản hồi (${response.code})")
        val front = parse(response).array("data")?.firstOrNull()?.jsonObject
        val id = front?.string("id")
        val country = front?.get("attributes")?.jsonObject?.string("name")
        if (id != null) storefront = id
        val label = "Đã đăng nhập${country?.let { " · $it" }.orEmpty()}"
        host.storage.set("account_label", label)
        host.storage.set("expired", null)
        return AccountState.SignedIn(label)
    }

    override fun signOut() {
        listOf("user_token", "developer_token", "storefront", "account_label", "expired").forEach { host.storage.set(it, null) }
        matches.clear()
        misses.clear()
    }

    // ---- finding and streaming a song ---------------------------------------------------------

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        val token = userToken ?: return ResolveResult.Unavailable("Vui lòng đăng nhập Apple Music trước").also {
            report(DiagnosticStep("Tài khoản", false, "Chưa đăng nhập"))
        }
        if (storefront == null) {
            report(DiagnosticStep("Tài khoản", null, "Đang kiểm tra tài khoản…"))
            val state = verifyAccount()
            if (state !is AccountState.SignedIn) {
                report(DiagnosticStep("Tài khoản", false, (state as? AccountState.Expired)?.reason ?: "Đã đăng xuất"))
                return ResolveResult.Unavailable("Tài khoản Apple Music chưa được đăng nhập")
            }
        }
        report(DiagnosticStep("Tài khoản", true, host.storage.get("account_label") ?: "Đã đăng nhập"))

        val defaultStorefront = if (Locale.getDefault().language.equals("vi", ignoreCase = true)) "vn" else "us"
        val region = picks[ChoiceRegion]?.takeIf { it != RegionAuto } ?: storefront ?: defaultStorefront
        val quality = picks[ChoiceQuality] ?: OptionAac256
        val key = "$region|${track.title}|${track.artists.joinToString(",")}"
        val missedAt = misses[key]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MissMemoryMs) {
            report(DiagnosticStep("Tìm bài hát", false, "Không có trên Apple Music"))
            return ResolveResult.NotFound("Không có trên Apple Music")
        }
        report(DiagnosticStep("Tìm bài hát", null, "Đang tìm trên Apple Music ($region)…"))
        val ranked = matches[key]?.let { listOf(TrackMatcher.Scored(it, 1f)) } ?: match(track, region)
        if (ranked.isEmpty()) {
            misses[key] = System.currentTimeMillis()
            report(DiagnosticStep("Tìm bài hát", false, "Không tìm thấy bản thu khớp với “${track.title}” tại $region"))
            return ResolveResult.NotFound("Không tìm thấy bản thu khớp trên Apple Music")
        }
        val best = ranked.first()
        report(DiagnosticStep("Tìm bài hát", true, "${best.track.title} · ${best.track.artist}" +
            if (ranked.size > 1) " (${ranked.size} bản)" else ""))

        // Apple's web-playback stream, the one its web player uses in Chrome. (The enhanced HLS
        // streams list Widevine keys too and even license, but their audio is labelled with an
        // all-zero key id that only FairPlay ignores, so Widevine can't decrypt a sample of it.)
        // The same recording's copies are tried in turn, best match first.
        report(DiagnosticStep("Luồng phát", null, "Đang mở luồng phát…"))
        val nearEqual = ranked.filter { it.score >= best.score - 0.06f }.take(4)
        var lastProblem = "Không có luồng phát cho bài hát này tại $region"
        for (candidate in nearEqual) {
            when (val result = webPlaybackStream(candidate.track, quality, token, track)) {
                is StreamAttempt.Ok -> {
                    matches[key] = candidate.track
                    report(DiagnosticStep("Luồng phát", true, result.detail))
                    return ResolveResult.Found(result.stream)
                }
                is StreamAttempt.Problem -> lastProblem = result.reason
            }
        }
        report(DiagnosticStep("Luồng phát", false, lastProblem))
        return ResolveResult.Unavailable(lastProblem)
    }

    private sealed interface StreamAttempt {
        class Ok(val stream: SourceStream, val detail: String) : StreamAttempt
        class Problem(val reason: String) : StreamAttempt
    }

    /**
     * Apple's web-playback stream: AAC 256 (or HE-AAC 64) for every song, behind one Widevine
     * key whose real id the playlist names.
     */
    private fun webPlaybackStream(song: CatalogTrack, quality: String, token: String, track: TrackQuery): StreamAttempt {
        val body = buildJsonObject {
            put("salableAdamId", song.id)
            put("language", "en-US")
        }.toString().toByteArray()
        val response = host.http(
            HttpRequest(
                WebPlaybackUrl,
                method = "POST",
                headers = webHeaders + mapOf("Authorization" to "Bearer ${developerToken()}", "Media-User-Token" to token),
                body = body,
                contentType = "application/json",
            )
        )
        if (!response.ok) return StreamAttempt.Problem("Web playback phản hồi ${response.code}")
        val answer = parse(response)
        val entry = answer.array("songList")?.firstOrNull()?.jsonObject
            ?: return StreamAttempt.Problem(answer.string("customerMessage") ?: "Web playback không có luồng phát cho bài hát này")
        val assets = entry.array("assets").orEmpty().map { it.jsonObject }
        val wantedFlavor = if (quality == OptionHe64) "ctrp64" else "ctrp256"
        val asset = assets.firstOrNull { it.string("flavor")?.endsWith(wantedFlavor) == true }
            ?: assets.firstOrNull { it.string("flavor")?.contains("ctrp") == true }
            ?: return StreamAttempt.Problem("Web playback chỉ hỗ trợ ${assets.mapNotNull { it.string("flavor") }.joinToString()}")
        val url = asset.string("URL") ?: return StreamAttempt.Problem("Luồng phát web playback không có địa chỉ")
        val media = host.http(HttpRequest(url, headers = webHeaders))
        if (!media.ok) return StreamAttempt.Problem("Danh sách phát web playback phản hồi ${media.code}")
        val (keyUri, keyId) = AppleHls.legacyKey(media.text)
            ?: return StreamAttempt.Problem("Luồng phát web playback không có khóa Widevine")
        val pssh = AppleHls.psshFor(keyId)
        val playlist = AppleHls.rewriteLegacy(media.text, url, keyUri, pssh)
        val key = AppleHls.WidevineKey(keyUri, pssh, keyId)
        val songId = song.id
        val flavor = asset.string("flavor").orEmpty()
        val label = when {
            flavor.endsWith("ctrp256") -> "AAC 256"
            flavor.endsWith("ctrp64") -> "HE-AAC 64"
            else -> "AAC"
        }
        return StreamAttempt.Ok(
            stream = SourceStream(
                url = "data:application/vnd.apple.mpegurl;base64," + Base64.getEncoder().encodeToString(playlist.toByteArray()),
                format = StreamFormat.Hls,
                drm = StreamDrm.Widevine(LicenseServer { acquireLicense(it, listOf(key), songId, token) }, listOf(pssh)),
                sourceLabel = "Apple Music",
                qualityLabel = label,
                matched = matched(song, track),
                // "32:ctrp64" is HE-AAC, which its container reads as plain AAC
                codec = if (flavor.endsWith("ctrp64")) "HE-AAC" else "AAC",
            ),
            detail = "$label · ${AppleHls.segmentCount(playlist)} phân đoạn",
        )
    }

    private fun matched(song: CatalogTrack, track: TrackQuery) = MatchedTrack(
        id = song.id,
        title = song.title,
        artist = song.artist,
        album = song.album,
        durationMs = song.durationMs,
        confidence = TrackMatcher.score(track, song),
    )

    private fun match(track: TrackQuery, region: String): List<TrackMatcher.Scored> {
        val isrc = track.isrc
        if (isrc != null) {
            val byIsrc = api("/v1/catalog/$region/songs?filter[isrc]=${enc(isrc)}")
            if (byIsrc.ok) {
                val songs = songsIn(parse(byIsrc).array("data"))
                if (songs.isNotEmpty()) return songs.map { TrackMatcher.Scored(it, 1f) }
            }
        }
        val term = "${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
        val search = api("/v1/catalog/$region/search?term=${enc(term)}&types=songs&limit=15")
        if (!search.ok) throw IOException("Apple Music search answered ${search.code}")
        val songs = songsIn(parse(search).jsonObject["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data") as? JsonArray)
        return TrackMatcher.ranked(track, songs)
    }

    private fun songsIn(data: JsonArray?): List<CatalogTrack> = data.orEmpty().mapNotNull { element ->
        val obj = element.jsonObject
        val a = obj["attributes"]?.jsonObject ?: return@mapNotNull null
        CatalogTrack(
            id = obj.string("id") ?: return@mapNotNull null,
            title = a.string("name") ?: return@mapNotNull null,
            artist = a.string("artistName").orEmpty(),
            album = a.string("albumName"),
            durationMs = (a["durationInMillis"] as? JsonPrimitive)?.longOrNull,
            isrc = a.string("isrc"),
        )
    }

    /** trades the CDM's challenge for a license, for whichever of the song's keys it's asking about */
    private fun acquireLicense(challenge: ByteArray, keys: List<AppleHls.WidevineKey>, songId: String, token: String): ByteArray {
        val key = keys.firstOrNull { challenge.indexOf(it.keyId) >= 0 } ?: keys.last()
        val body = buildJsonObject {
            put("challenge", Base64.getEncoder().encodeToString(challenge))
            put("key-system", "com.widevine.alpha")
            put("uri", key.uri)
            put("adamId", songId)
            put("isLibrary", false)
            put("user-initiated", true)
        }.toString().toByteArray()
        val response = host.http(
            HttpRequest(
                LicenseUrl,
                method = "POST",
                headers = webHeaders + mapOf(
                    "Authorization" to "Bearer ${developerToken()}",
                    "Media-User-Token" to token,
                ),
                body = body,
                contentType = "application/json",
            )
        )
        if (!response.ok) throw IOException("Apple's license server answered ${response.code}")
        val answer = parse(response)
        val status = (answer["status"] as? JsonPrimitive)?.intOrNull
        val license = answer.string("license")
        if (status != 0 || license == null) {
            val message = answer.string("customerMessage") ?: "status $status"
            if (status == -1002) host.storage.set("expired", message)
            throw IOException("Apple refused the license: $message")
        }
        return Base64.getDecoder().decode(license)
    }

    // ---- HTTP ----------------------------------------------------------------------------------

    private fun api(path: String): HttpResponse = host.http(
        HttpRequest(
            "https://amp-api.music.apple.com$path",
            headers = webHeaders + buildMap {
                put("Authorization", "Bearer ${developerToken()}")
                userToken?.let { put("Media-User-Token", it) }
            },
        )
    )

    /** the web player's developer token: the one seen at sign-in while it lasts, then the page's current one */
    private fun developerToken(): String {
        capturedDeveloperToken?.takeIf { jwtExpiryMs(it) > System.currentTimeMillis() + 60_000 }?.let { return it }
        refreshDeveloperToken()
        return capturedDeveloperToken ?: ""
    }

    /** reads the token the web player ships in its main script */
    private fun refreshDeveloperToken(): Boolean {
        for (browseUrl in listOf("https://music.apple.com/vn/browse", "https://music.apple.com/us/browse")) {
            val page = runCatching { host.http(HttpRequest(browseUrl, headers = webHeaders)) }.getOrNull()
                ?.takeIf { it.ok } ?: continue
            val scripts = Regex("""/assets/index(?:-legacy)?[~-][a-zA-Z0-9_-]+\.js""").findAll(page.text).map { it.value }.distinct()
            for (path in scripts) {
                val script = runCatching { host.http(HttpRequest("https://music.apple.com$path", headers = webHeaders)) }.getOrNull()
                    ?.takeIf { it.ok } ?: continue
                val token = Regex("""eyJ[a-zA-Z0-9_-]+\.eyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+""").findAll(script.text)
                    .map { it.value }
                    .filter { jwtExpiryMs(it) > System.currentTimeMillis() }
                    .sortedByDescending { jwtPayload(it)?.contains("AMPWebPlay") == true }
                    .firstOrNull()
                if (token != null) {
                    capturedDeveloperToken = token
                    return true
                }
            }
        }
        return false
    }

    private fun parse(response: HttpResponse): JsonObject =
        runCatching { json.parseToJsonElement(response.text).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray
    /** the object, or an empty one: Apple leaves fields out freely */
    private val JsonElement.jsonObject: JsonObject get() = this as? JsonObject ?: JsonObject(emptyMap())

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        const val Id = "applemusic"
        const val ChoiceQuality = "quality"
        const val ChoiceRegion = "region"
        const val RegionAuto = "auto"
        const val OptionAac256 = "aac256"
        const val OptionHe64 = "he64"

        private const val LicenseUrl = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"
        private const val WebPlaybackUrl = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"

        private val Regions = listOf(
            "vn" to "Việt Nam",
            "us" to "Hoa Kỳ", "gb" to "Vương quốc Anh", "jp" to "Nhật Bản", "kr" to "Hàn Quốc",
            "id" to "Indonesia", "sg" to "Singapore", "my" to "Malaysia", "ph" to "Philippines", "th" to "Thái Lan",
            "in" to "Ấn Độ", "au" to "Úc", "ca" to "Canada", "de" to "Đức", "fr" to "Pháp",
            "br" to "Brazil", "mx" to "Mexico",
        )
        private const val MissMemoryMs = 30L * 60 * 1000

        /** Apple turns non-browser clients away; the web player's own headers */
        private val webHeaders = mapOf(
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        )

        internal fun jwtPayload(token: String): String? = runCatching {
            String(Base64.getUrlDecoder().decode(token.split(".")[1]), Charsets.UTF_8)
        }.getOrNull()

        internal fun jwtExpiryMs(token: String): Long =
            jwtPayload(token)?.let { Regex(""""exp":(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }?.times(1000) ?: 0L
    }
}
