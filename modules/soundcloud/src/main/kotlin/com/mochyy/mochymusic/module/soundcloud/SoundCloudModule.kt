package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.MODULE_API_VERSION
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.NO_ACCOUNT_LABEL
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Nguồn phát nhạc từ SoundCloud công khai qua API v2, không cần tài khoản.
 * Tự động tìm & lưu tạm `client_id` từ web bundle của SoundCloud, bỏ qua đoạn nghe thử 30s Go+ (SNIP),
 * khớp bài hát qua [TrackMatcher], và chọn luồng Progressive MP3 hoặc HLS AAC/Opus theo tùy chọn người dùng.
 */
class SoundCloudModule : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "SoundCloud",
        version = "1.0",
        description = "Nghe nhạc từ kho SoundCloud công khai, không cần tài khoản, hỗ trợ cả MP3 và HLS",
        apiVersion = MODULE_API_VERSION,
    )

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val matches = ConcurrentHashMap<String, ScTrack>()
    private val misses = ConcurrentHashMap<String, Long>()

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val choices = listOf(
        ModuleChoice(
            id = ChoiceStreamPref,
            label = "Định dạng luồng phát",
            options = listOf(
                StreamOption(
                    OptionMp3,
                    "Ưu tiên MP3 (Progressive)",
                    "Phát trực tiếp MP3 khi có, hỗ trợ tải về và tương thích mọi thiết bị; tự chuyển HLS nếu không có MP3",
                ),
                StreamOption(
                    OptionHls,
                    "Ưu tiên HLS (AAC / Opus)",
                    "Phát luồng HLS AAC hoặc Opus hiện đại trước, ổn định khi mạng yếu; tự chuyển MP3 nếu cần",
                ),
            ),
        ),
    )

    override fun accountState(): AccountState {
        val reason = host.storage.get(UnreachableKey) ?: return AccountState.SignedIn(NO_ACCOUNT_LABEL)
        val markedAt = host.storage.get(UnreachableAtKey)?.toLongOrNull() ?: 0L
        return if (System.currentTimeMillis() - markedAt < UnreachableRetryMs) {
            AccountState.Expired(reason)
        } else {
            AccountState.SignedIn(NO_ACCOUNT_LABEL)
        }
    }

    override suspend fun verifyAccount(): AccountState {
        val clientId = obtainClientId(forceRefresh = false)
        if (clientId == null) {
            val reason = "Không lấy được client_id từ SoundCloud"
            markUnreachable(reason)
            return AccountState.Expired(reason)
        }
        val response = searchWithRetry("test", limit = 1, initialClientId = clientId)
        return if (response?.ok == true) {
            host.storage.set(UnreachableKey, null)
            AccountState.SignedIn("$NO_ACCOUNT_LABEL · SoundCloud hoạt động")
        } else {
            val reason = "SoundCloud không phản hồi (${response?.code ?: "ngoại tuyến"})"
            markUnreachable(reason)
            AccountState.Expired(reason)
        }
    }

    override fun signOut() {
        matches.clear()
        misses.clear()
        host.storage.set(ClientIdKey, null)
        host.storage.set(UnreachableKey, null)
        host.storage.set(UnreachableAtKey, null)
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (DiagnosticStep) -> Unit,
    ): ResolveResult {
        val key = "${track.title}|${track.artists.joinToString(",")}"
        val missedAt = misses[key]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MissMemoryMs) {
            report(DiagnosticStep("Tìm bài hát", false, "Không có trên SoundCloud"))
            return ResolveResult.NotFound("Không có trên SoundCloud")
        }

        report(DiagnosticStep("Kết nối SoundCloud", null, "Đang kiểm tra khóa phiên SoundCloud…"))
        val initialClientId = obtainClientId(forceRefresh = false)
        if (initialClientId == null) {
            val reason = "Không lấy được client_id của SoundCloud"
            report(DiagnosticStep("Kết nối SoundCloud", false, reason))
            markUnreachable(reason)
            return ResolveResult.Unavailable(reason)
        }
        report(DiagnosticStep("Kết nối SoundCloud", true, "Đã sẵn sàng"))

        report(DiagnosticStep("Tìm bài hát", null, "Đang tìm trên SoundCloud…"))
        val ranked: List<MatchedSc> = matches[key]?.let { cached ->
            listOf(MatchedSc(cached, TrackMatcher.score(track, cached.catalog).coerceAtLeast(0.9f)))
        } ?: run {
            val query = "${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
            val response = searchWithRetry(query, limit = SearchLimit, initialClientId = initialClientId)
            if (response == null) {
                val reason = "SoundCloud không phản hồi"
                report(DiagnosticStep("Tìm bài hát", false, reason))
                markUnreachable(reason)
                return ResolveResult.Unavailable(reason)
            }
            if (!response.ok) {
                val reason = "SoundCloud phản hồi ${response.code}"
                report(DiagnosticStep("Tìm bài hát", false, reason))
                return ResolveResult.Unavailable(reason)
            }
            host.storage.set(UnreachableKey, null)

            val tracks = tracksIn(response.text)
            val byCatalog = tracks.associateBy { it.catalog }
            val scored = TrackMatcher.ranked(track, tracks.map { it.catalog }).mapNotNull { hit ->
                byCatalog[hit.track]?.let { MatchedSc(it, hit.score) }
            }
            if (scored.isEmpty()) {
                misses[key] = System.currentTimeMillis()
                report(DiagnosticStep("Tìm bài hát", false, "Không tìm thấy bản đầy đủ khớp với “${track.title}”"))
                return ResolveResult.NotFound("Không tìm thấy bản thu khớp trên SoundCloud")
            }
            scored
        }

        val best = ranked.first()
        report(
            DiagnosticStep(
                "Tìm bài hát",
                true,
                "${best.track.catalog.title} · ${best.track.catalog.artist}" +
                    if (ranked.size > 1) " (${ranked.size} bản)" else "",
            ),
        )

        report(DiagnosticStep("Luồng phát", null, "Đang lấy liên kết phát…"))
        val activeClientId = host.storage.get(ClientIdKey)?.takeIf { it.isNotBlank() } ?: initialClientId
        val pref = picks[ChoiceStreamPref] ?: OptionMp3
        val nearEqual = ranked.filter { it.score >= best.score - 0.06f }.take(4)
        var lastProblem = "Không có luồng phát khả dụng cho bài hát này"
        for (candidate in nearEqual) {
            val stream = resolveStream(candidate.track, pref, track, activeClientId)
            if (stream != null) {
                matches[key] = candidate.track
                report(DiagnosticStep("Luồng phát", true, stream.qualityLabel))
                return ResolveResult.Found(stream)
            } else {
                lastProblem = "Không lấy được luồng phát không mã hóa cho “${candidate.track.catalog.title}”"
            }
        }
        report(DiagnosticStep("Luồng phát", false, lastProblem))
        return ResolveResult.Unavailable(lastProblem)
    }

    // ---- Client ID Discovery -------------------------------------------------------------------

    internal fun obtainClientId(forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            host.storage.get(ClientIdKey)?.takeIf { it.isNotBlank() }?.let { return it }
        } else {
            host.storage.set(ClientIdKey, null)
        }
        val home = host.httpOrNull(HttpRequest(HomeUrl, headers = pageHeaders))?.takeIf { it.ok } ?: return null
        val scriptUrls = AssetScriptPattern.findAll(home.text)
            .map { it.value }
            .toList()
            .asReversed()
            .distinct()
            .take(MaxScriptProbe)
        for (jsUrl in scriptUrls) {
            val script = host.httpOrNull(HttpRequest(jsUrl, headers = pageHeaders))?.takeIf { it.ok } ?: continue
            val found = ClientIdPattern.find(script.text)?.groupValues?.getOrNull(1)
            if (!found.isNullOrBlank()) {
                host.storage.set(ClientIdKey, found)
                return found
            }
        }
        return null
    }

    private fun searchWithRetry(query: String, limit: Int, initialClientId: String): HttpResponse? {
        val first = host.httpOrNull(HttpRequest(searchUrl(query, limit, initialClientId), headers = apiHeaders))
            ?: return null
        if (first.code != 401 && first.code != 403) return first
        val fresh = obtainClientId(forceRefresh = true) ?: return first
        return host.httpOrNull(HttpRequest(searchUrl(query, limit, fresh), headers = apiHeaders)) ?: first
    }

    // ---- Parsing & Stream Resolution -----------------------------------------------------------

    internal class Transcoding(
        val url: String,
        val protocol: String,
        val mimeType: String,
        val preset: String,
        val quality: String,
    )

    internal class ScTrack(
        val catalog: CatalogTrack,
        val transcodings: List<Transcoding>,
        val trackAuthorization: String?,
    )

    private class MatchedSc(val track: ScTrack, val score: Float)

    internal fun tracksIn(text: String): List<ScTrack> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val collection = root["collection"] as? JsonArray ?: return emptyList()
        return collection.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if ((obj["streamable"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
            val policy = obj.string("policy")?.uppercase()
            if (policy == "SNIP" || policy == "BLOCK") return@mapNotNull null

            val durationMs = (obj["duration"] as? JsonPrimitive)?.longOrNull
            val fullDurationMs = (obj["full_duration"] as? JsonPrimitive)?.longOrNull
            // Skip 30s Go+ preview snippets
            if (durationMs != null && fullDurationMs != null && durationMs <= 35_000L && fullDurationMs > 60_000L) {
                return@mapNotNull null
            }

            val id = obj.string("id") ?: return@mapNotNull null
            val rawTitle = obj.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val media = obj["media"] as? JsonObject ?: return@mapNotNull null
            val transcodings = (media["transcodings"] as? JsonArray).orEmpty().mapNotNull { tcEl ->
                val tc = tcEl as? JsonObject ?: return@mapNotNull null
                if ((tc["snipped"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                val url = tc.string("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val fmt = tc["format"] as? JsonObject ?: return@mapNotNull null
                val protocol = fmt.string("protocol")?.lowercase().orEmpty()
                if (protocol !in SupportedProtocols || "encrypt" in protocol || "drm" in protocol) {
                    return@mapNotNull null
                }
                Transcoding(
                    url = url,
                    protocol = protocol,
                    mimeType = fmt.string("mime_type")?.lowercase().orEmpty(),
                    preset = tc.string("preset")?.lowercase().orEmpty(),
                    quality = tc.string("quality")?.lowercase().orEmpty(),
                )
            }
            if (transcodings.isEmpty()) return@mapNotNull null

            val uploader = (obj["user"] as? JsonObject)?.string("username").orEmpty()
            val pub = obj["publisher_metadata"] as? JsonObject
            val pubArtist = pub?.string("artist")?.takeIf { it.isNotBlank() }
            val (splitArtist, cleanTitle) = splitArtistTitle(rawTitle)
            val artist = pubArtist ?: splitArtist ?: uploader.ifBlank { "SoundCloud" }

            ScTrack(
                catalog = CatalogTrack(
                    id = id,
                    title = cleanTitle,
                    artist = artist,
                    album = pub?.string("album_title")?.takeIf { it.isNotBlank() },
                    durationMs = durationMs ?: fullDurationMs,
                    isrc = pub?.string("isrc")?.takeIf { it.isNotBlank() },
                ),
                transcodings = transcodings,
                trackAuthorization = obj.string("track_authorization"),
            )
        }
    }

    private fun resolveStream(
        candidate: ScTrack,
        pref: String,
        query: TrackQuery,
        initialClientId: String,
    ): SourceStream? {
        val ordered = orderTranscodings(candidate.transcodings, pref)
        var currentClientId = initialClientId
        for (tc in ordered) {
            var response = host.httpOrNull(HttpRequest(transcodingResolveUrl(tc.url, currentClientId, candidate.trackAuthorization), headers = apiHeaders))
                ?: continue
            if (response.code == 401 || response.code == 403) {
                val refreshed = obtainClientId(forceRefresh = true) ?: continue
                currentClientId = refreshed
                response = host.httpOrNull(HttpRequest(transcodingResolveUrl(tc.url, currentClientId, candidate.trackAuthorization), headers = apiHeaders))
                    ?: continue
            }
            if (!response.ok) continue
            val obj = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull() ?: continue
            val streamUrl = obj.string("url")?.takeIf { it.startsWith("http") } ?: continue

            val format = if (tc.protocol == "progressive") StreamFormat.Progressive else StreamFormat.Hls
            val (codec, qualityLabel) = describeTranscoding(tc)
            return SourceStream(
                url = streamUrl,
                format = format,
                headers = streamHeaders,
                sourceLabel = "SoundCloud",
                qualityLabel = qualityLabel,
                codec = codec,
                sampleRateHz = if (codec == "Opus") 48_000 else 44_100,
                matched = MatchedTrack(
                    id = candidate.catalog.id,
                    title = candidate.catalog.title,
                    artist = candidate.catalog.artist,
                    album = candidate.catalog.album,
                    durationMs = candidate.catalog.durationMs,
                    confidence = TrackMatcher.score(query, candidate.catalog),
                ),
            )
        }
        return null
    }

    private fun orderTranscodings(list: List<Transcoding>, pref: String): List<Transcoding> {
        fun rank(tc: Transcoding): Int {
            val isProg = tc.protocol == "progressive"
            val isAac = "mp4a" in tc.mimeType || "aac" in tc.mimeType || "aac" in tc.preset
            val isOpus = "opus" in tc.mimeType || "opus" in tc.preset
            val isHq = tc.quality == "hq"
            return if (pref == OptionHls) {
                when {
                    !isProg && isAac -> if (isHq) 0 else 1
                    !isProg && isOpus -> 2
                    !isProg -> 3
                    else -> 4
                }
            } else {
                when {
                    isProg -> if (isHq) 0 else 1
                    !isProg && isAac -> if (isHq) 2 else 3
                    !isProg && isOpus -> 4
                    else -> 5
                }
            }
        }
        return list.sortedBy(::rank)
    }

    private fun describeTranscoding(tc: Transcoding): Pair<String?, String> {
        val isHls = tc.protocol == "hls"
        val isAac = "mp4a" in tc.mimeType || "aac" in tc.mimeType || "aac" in tc.preset
        val isOpus = "opus" in tc.mimeType || "opus" in tc.preset
        val isMp3 = "mpeg" in tc.mimeType || "mp3" in tc.preset
        return when {
            isAac -> "AAC" to if (isHls) "HLS AAC" else "AAC"
            isOpus -> "Opus" to if (isHls) "HLS Opus" else "Opus"
            isMp3 -> "MP3" to if (isHls) "HLS MP3" else "MP3 128"
            else -> null to if (isHls) "HLS" else "Audio"
        }
    }

    private fun splitArtistTitle(rawTitle: String): Pair<String?, String> {
        val parts = rawTitle.split(ArtistTitleSeparator, limit = 2)
        if (parts.size == 2 && parts[0].length in 2..45 && parts[1].isNotBlank()) {
            val right = parts[1].trim()
            if (!VersionSuffixPattern.containsMatchIn(right)) {
                return parts[0].trim() to right
            }
        }
        return null to rawTitle.trim()
    }

    private fun transcodingResolveUrl(baseUrl: String, clientId: String, trackAuth: String?): String {
        val sep = if ('?' in baseUrl) '&' else '?'
        val sb = StringBuilder(baseUrl).append(sep).append("client_id=").append(enc(clientId))
        if (!trackAuth.isNullOrBlank()) {
            sb.append("&track_authorization=").append(enc(trackAuth))
        }
        return sb.toString()
    }

    private fun searchUrl(query: String, limit: Int, clientId: String): String =
        "$ApiBase/search/tracks?q=${enc(query)}&limit=$limit&client_id=${enc(clientId)}"

    private fun markUnreachable(reason: String) {
        host.storage.set(UnreachableKey, reason)
        host.storage.set(UnreachableAtKey, System.currentTimeMillis().toString())
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun ModuleHost.httpOrNull(request: HttpRequest): HttpResponse? = runCatching { http(request) }.getOrNull()

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        const val Id = "mochy.source.soundcloud"
        const val ChoiceStreamPref = "stream_pref"
        const val OptionMp3 = "mp3"
        const val OptionHls = "hls"

        private const val HomeUrl = "https://soundcloud.com"
        private const val ApiBase = "https://api-v2.soundcloud.com"
        private const val ClientIdKey = "sc_client_id"
        private const val UnreachableKey = "unreachable"
        private const val UnreachableAtKey = "unreachable_at"
        private const val SearchLimit = 10
        private const val MaxScriptProbe = 8
        private const val MissMemoryMs = 30L * 60 * 1000
        private const val UnreachableRetryMs = 60_000L

        private val SupportedProtocols = setOf("progressive", "hls")
        private val AssetScriptPattern = Regex("""https://a-v2\.sndcdn\.com/assets/[A-Za-z0-9._-]+\.js""")
        private val ClientIdPattern = Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{16,48})["']""")
        private val ArtistTitleSeparator = Regex("""\s+[-–—]\s+""")
        private val VersionSuffixPattern = Regex(
            """^(?:remix|live|acoustic|instrumental|karaoke|sped up|slowed|nightcore|demo|cover|edit|mix|version|radio edit|official)""",
            RegexOption.IGNORE_CASE,
        )

        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private val pageHeaders = mapOf(
            "User-Agent" to UserAgent,
            "Accept-Language" to "en-US,en;q=0.9",
        )
        private val apiHeaders = mapOf(
            "Accept" to "application/json",
            "User-Agent" to UserAgent,
            "Origin" to "https://soundcloud.com",
            "Referer" to "https://soundcloud.com/",
        )
        private val streamHeaders = mapOf("User-Agent" to UserAgent)
    }
}
