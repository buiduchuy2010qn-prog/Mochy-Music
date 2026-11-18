package com.mochyy.mochymusic.module.zingmp3

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * zing mp3's catalog as plain mp3 files, 128 kbps for every free song and 320 kbps where zing
 * hands it out without vip; no account and no drm. zing only streams to vietnamese addresses, so
 * outside vietnam (or behind a foreign vpn) every song says so and the next provider plays it.
 */
class ZingMp3Module : SourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "Zing MP3",
        version = "1.0",
        description = "Kho nhạc Việt Zing MP3 định dạng MP3 128–320 kbps, không cần tài khoản. Mạnh nhất về V-Pop, chỉ phát được khi đang ở Việt Nam",
        sourceUrl = "https://zingmp3.vn",
    )

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val matches = ConcurrentHashMap<String, CatalogTrack>()
    private val misses = ConcurrentHashMap<String, Long>()

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var cookieAt = 0L

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val choices = listOf(
        ModuleChoice(
            id = ChoiceQuality,
            label = "Luồng phát",
            options = listOf(
                StreamOption(Option320, "MP3 320", "Chất lượng cao nhất Zing mở miễn phí, bài chỉ dành cho VIP tự dùng 128 kbps"),
                StreamOption(Option128, "MP3 128", "Tiết kiệm dữ liệu, mở bài nhanh nhất"),
            ),
        ),
    )

    // the same short pause the other modules take: a server that didn't answer is left alone for a bit
    override fun accountState(): AccountState {
        val reason = host.storage.get(UnreachableKey) ?: return AccountState.SignedIn(NO_ACCOUNT_LABEL)
        val markedAt = host.storage.get(UnreachableAtKey)?.toLongOrNull() ?: 0L
        return if (System.currentTimeMillis() - markedAt < UnreachableRetryMs) AccountState.Expired(reason) else AccountState.SignedIn(NO_ACCOUNT_LABEL)
    }

    override suspend fun verifyAccount(): AccountState {
        val root = runCatching { search("Sơn Tùng", 1) }.getOrNull()
        val err = root?.int("err")
        return if (err == 0) {
            host.storage.set(UnreachableKey, null)
            AccountState.SignedIn("$NO_ACCOUNT_LABEL · Zing MP3 phản hồi tốt")
        } else {
            val reason = "Zing MP3 không phản hồi (${err ?: "ngoại tuyến"})"
            markUnreachable(reason)
            AccountState.Expired(reason)
        }
    }

    override fun signOut() {
        matches.clear()
        misses.clear()
        cookie = null
        cookieAt = 0L
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        val key = "${track.title}|${track.artists.joinToString(",")}"
        val missedAt = misses[key]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MissMemoryMs) {
            report(DiagnosticStep("Tìm bài hát", false, "Không có trên Zing MP3"))
            return ResolveResult.NotFound("Không có trên Zing MP3")
        }

        report(DiagnosticStep("Tìm bài hát", null, "Đang tìm trên Zing MP3…"))
        val found = matches[key] ?: run {
            val query = "${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
            val root = runCatching { search(query, SearchLimit) }.recoverCatching { search(query, SearchLimit) }.getOrElse {
                report(DiagnosticStep("Tìm bài hát", false, "Zing MP3 không phản hồi"))
                markUnreachable("Zing MP3 không phản hồi")
                return ResolveResult.Unavailable("Zing MP3 không phản hồi")
            }
            val err = root.int("err")
            if (err != 0) {
                val reason = "Zing MP3 từ chối tìm kiếm (${root.string("msg") ?: err})"
                report(DiagnosticStep("Tìm bài hát", false, reason))
                return ResolveResult.Unavailable(reason)
            }
            host.storage.set(UnreachableKey, null)
            val best = TrackMatcher.ranked(track, songsIn(root)).firstOrNull()
            if (best == null) {
                misses[key] = System.currentTimeMillis()
                report(DiagnosticStep("Tìm bài hát", false, "Không tìm thấy bản thu khớp với “${track.title}”"))
                return ResolveResult.NotFound("Không tìm thấy bản thu khớp trên Zing MP3")
            }
            matches[key] = best.track
            best.track
        }
        report(DiagnosticStep("Tìm bài hát", true, "${found.title} · ${found.artist}"))

        report(DiagnosticStep("Luồng phát", null, "Đang mở luồng phát…"))
        val root = runCatching { signedGet(StreamPath, mapOf("id" to found.id)) }.getOrNull()
        val err = root?.int("err")
        if (root == null || err != 0) {
            // -1110: zing only streams inside vietnam
            val reason = when {
                root == null -> "Zing MP3 không trả về luồng phát"
                err == GeoBlocked -> "Zing MP3 chỉ phát được tại Việt Nam"
                else -> "Zing MP3: ${root?.string("msg") ?: "lỗi $err"}"
            }
            report(DiagnosticStep("Luồng phát", false, reason))
            return ResolveResult.Unavailable(reason)
        }
        val data = root["data"] as? JsonObject
        val wants320 = (picks[ChoiceQuality] ?: Option320) == Option320
        val url320 = data?.string("320")?.takeIf { it.startsWith("http") }
        val url128 = data?.string("128")?.takeIf { it.startsWith("http") }
        val (url, kbps) = when {
            wants320 && url320 != null -> url320 to 320
            url128 != null -> url128 to 128
            url320 != null -> url320 to 320
            else -> {
                report(DiagnosticStep("Luồng phát", false, "Bài này chỉ dành cho tài khoản VIP"))
                return ResolveResult.Unavailable("Bài này chỉ dành cho tài khoản Zing MP3 VIP")
            }
        }
        report(DiagnosticStep("Luồng phát", true, "MP3 $kbps kbps"))

        return ResolveResult.Found(
            SourceStream(
                url = url,
                format = StreamFormat.Progressive,
                headers = streamHeaders,
                sourceLabel = "Zing MP3",
                qualityLabel = "MP3 $kbps",
                codec = "MP3",
                sampleRateHz = 44_100,
                matched = MatchedTrack(
                    id = found.id,
                    title = found.title,
                    artist = found.artist,
                    album = found.album,
                    durationMs = found.durationMs,
                    confidence = TrackMatcher.score(track, found),
                ),
            )
        )
    }

    internal fun songsIn(root: JsonObject): List<CatalogTrack> {
        val items = (root["data"] as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            // 2 is vip-only, it would only ever fail
            if (song.int("streamingStatus") == VipOnly) return@mapNotNull null
            CatalogTrack(
                id = song.string("encodeId") ?: return@mapNotNull null,
                title = song.string("title") ?: return@mapNotNull null,
                artist = song.string("artistsNames").orEmpty(),
                album = (song["album"] as? JsonObject)?.string("title"),
                durationMs = (song["duration"] as? JsonPrimitive)?.longOrNull?.times(1000),
            )
        }
    }

    private fun search(query: String, count: Int): JsonObject =
        signedGet(SearchPath, mapOf("type" to "song", "page" to "1", "count" to "$count"), extra = "q=${enc(query)}")

    /**
     * zing's web api: every call carries ctime, version and a signature, an hmac-sha512 of the path
     * and the sha256 of the signed parameters (sorted, joined without separators); the query
     * itself isn't signed
     */
    private fun signedGet(path: String, params: Map<String, String>, extra: String? = null): JsonObject {
        val first = executeSignedGet(path, params, extra)
        if (first.int("err") !in SessionExpiredErrors) return first
        // Zing invalidates session cookies early with -201 / -1023; clear, re-warm, re-sign, and retry once
        cookie = null
        cookieAt = 0L
        return executeSignedGet(path, params, extra)
    }

    private fun executeSignedGet(path: String, params: Map<String, String>, extra: String? = null): JsonObject {
        val session = sessionCookie()
        val ctime = (System.currentTimeMillis() / 1000).toString()
        val signed = params + mapOf("ctime" to ctime, "version" to Version)
        val sig = signature(path, signed)
        val query = buildList {
            extra?.let(::add)
            signed.forEach { (k, v) -> add("$k=${enc(v)}") }
            add("apiKey=$ApiKey")
            add("sig=$sig")
        }.joinToString("&")
        val headers = buildMap {
            putAll(apiHeaders)
            session?.let { put("Cookie", it) }
        }
        val response = host.http(HttpRequest("$Site$path?$query", headers = headers))
        return json.parseToJsonElement(response.text) as JsonObject
    }

    internal fun signature(path: String, params: Map<String, String>): String {
        val joined = params.toSortedMap().entries.joinToString("") { (k, v) -> "$k=$v" }
        val hash = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray()).hex()
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(SecretKey.toByteArray(), "HmacSHA512"))
        return mac.doFinal((path + hash).toByteArray()).hex()
    }

    // zing wants the cookies its home page sets before it answers the api; they last a while
    private fun sessionCookie(): String? {
        val now = System.currentTimeMillis()
        cookie?.takeIf { now - cookieAt < CookieTtlMs }?.let { return it }
        val response = runCatching { host.http(HttpRequest("$Site/", headers = apiHeaders)) }.getOrNull() ?: return cookie
        val fresh = response.header("set-cookie").orEmpty()
            .split('\n')
            .mapNotNull { line -> line.substringBefore(';').trim().takeIf { '=' in it } }
            .distinct()
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
        if (fresh != null) {
            cookie = fresh
            cookieAt = now
        }
        return fresh ?: cookie
    }

    private fun markUnreachable(reason: String) {
        host.storage.set(UnreachableKey, reason)
        host.storage.set(UnreachableAtKey, System.currentTimeMillis().toString())
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        const val Id = "zingmp3"
        const val ChoiceQuality = "quality"
        const val Option320 = "320"
        const val Option128 = "128"
        private const val Site = "https://zingmp3.vn"
        private const val SearchPath = "/api/v2/search"
        private const val StreamPath = "/api/v2/song/get/streaming"
        // the keys zing's own web player signs with, the same ones the zing lyrics source uses
        private const val ApiKey = "X5BM3w8N7MKozC0B85o4KMlzLZKhV00y"
        private const val SecretKey = "acOrvUS15XRW2o9JksiK1KgQ6Vbds8ZW"
        private const val Version = "1.22.4"
        private const val GeoBlocked = -1110
        private const val VipOnly = 2
        private const val SearchLimit = 10
        private val SessionExpiredErrors = setOf(-201, -1023)
        private const val UnreachableKey = "unreachable"
        private const val UnreachableAtKey = "unreachable_at"
        private const val CookieTtlMs = 20L * 60 * 1000
        private const val MissMemoryMs = 30L * 60 * 1000
        private const val UnreachableRetryMs = 60_000L
        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private val apiHeaders = mapOf("Accept" to "application/json", "User-Agent" to UserAgent, "Referer" to "$Site/")
        private val streamHeaders = mapOf("User-Agent" to UserAgent, "Referer" to "$Site/")
    }
}
