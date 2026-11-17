// NhacCuaTuiLyricsProvider.kt

package com.mochyy.mochymusic.lyrics

import android.content.Context
import com.mochyy.mochymusic.utils.followAppProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

object NhacCuaTuiLyricsProvider : LyricsProvider {
    override val name = "NhacCuaTui"

    private const val BaseUrl = "https://www.nhaccuatui.com"
    private const val DefaultDecryptKey = "Lyr1cjust4nct"
    private const val MaxCandidatesToFetch = 4

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        if (title.isBlank()) return Result.failure(IllegalArgumentException("Blank song title"))
        return try {
            withTimeoutOrNull(VietnameseLyricsCommon.TIMEOUT_MS) {
                VietnameseLyricsCommon.fetchRankedLyrics(
                    title = title,
                    artist = artist,
                    durationSec = duration,
                    maxFetches = MaxCandidatesToFetch,
                    preferSyncedOnly = true,
                    search = ::searchSongs,
                    fetchOne = ::fetchSongLyrics,
                ).firstOrNull()?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("No matching NhacCuaTui lyrics"))
            } ?: Result.failure(IOException("NhacCuaTui lyrics request timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        if (title.isBlank()) return
        try {
            withTimeoutOrNull(VietnameseLyricsCommon.TIMEOUT_MS) {
                VietnameseLyricsCommon.fetchRankedLyrics(
                    title = title,
                    artist = artist,
                    durationSec = duration,
                    maxFetches = MaxCandidatesToFetch,
                    preferSyncedOnly = false,
                    search = ::searchSongs,
                    fetchOne = ::fetchSongLyrics,
                ).forEach(callback)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun searchSongs(query: String): List<VnTrackCandidate<String>> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "$BaseUrl/tim-kiem/bai-hat?q=$encoded"
        val html = VietnameseLyricsCommon.httpGetText(
            url = url,
            userAgent = VietnameseLyricsCommon.DESKTOP_USER_AGENT,
            referer = "$BaseUrl/",
        )
        val arr = extractNuxtArray(html) ?: return emptyList()

        val songsArray = findSearchSongsArray(arr) ?: return emptyList()
        return songsArray.mapNotNull { ref ->
            val idx = (ref as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val songObj = resolveNuxtElement(arr, idx) as? JsonObject ?: return@mapNotNull null
            val key = songObj.nuxtString(arr, "key") ?: return@mapNotNull null
            val name = songObj.nuxtString(arr, "name") ?: return@mapNotNull null

            val artistNames = mutableListOf<String>()
            songObj.nuxtString(arr, "artistName")?.let { artistNames += it }
            (songObj.nuxtElement(arr, "artist") as? JsonArray)?.forEach { artistRef ->
                val aIdx = (artistRef as? JsonPrimitive)?.intOrNull ?: return@forEach
                (resolveNuxtElement(arr, aIdx) as? JsonObject)?.nuxtString(arr, "name")?.let {
                    artistNames += it
                }
            }

            val durationSec = parseDurationSeconds(songObj.nuxtElement(arr, "duration"))
            VnTrackCandidate(
                title = name,
                artists = artistNames.distinct(),
                durationSec = durationSec,
                ref = key,
            )
        }
    }

    private suspend fun fetchSongLyrics(songKey: String): String? {
        val songUrl = "$BaseUrl/song/$songKey"
        val html = VietnameseLyricsCommon.httpGetText(
            url = songUrl,
            userAgent = VietnameseLyricsCommon.DESKTOP_USER_AGENT,
            referer = "$BaseUrl/",
        )
        val arr = extractNuxtArray(html) ?: return null
        val lyricObj = findLyricDetailObject(arr, songKey) ?: return null

        val timedUrl = lyricObj.nuxtString(arr, "timedLyric")?.let(::normalizeUrl)
        val decryptKey = lyricObj.nuxtString(arr, "keyDecryptLyric").orEmpty().ifBlank { DefaultDecryptKey }

        if (!timedUrl.isNullOrBlank()) {
            val lrcPayload = VietnameseLyricsCommon.catchingNonCancel {
                VietnameseLyricsCommon.httpGetBytes(
                    url = timedUrl,
                    userAgent = VietnameseLyricsCommon.DESKTOP_USER_AGENT,
                    referer = songUrl,
                )
            }
            if (lrcPayload != null) {
                val decoded = decodeNctLrcPayload(lrcPayload, decryptKey)
                val normalized = decoded?.let { VietnameseLyricsCommon.normalizeLrc(it) }
                if (normalized != null) return normalized
            }
        }

        val plainContent = lyricObj.nuxtString(arr, "content") ?: return null
        return VietnameseLyricsCommon.normalizePlainLyrics(plainContent)
    }

    private fun decodeNctLrcPayload(bytes: ByteArray, key: String): String? {
        val raw = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
        if (raw.isBlank()) return null

        if (raw.length % 2 == 0 && HexRegex.matches(raw)) {
            val encrypted = ByteArray(raw.length / 2)
            for (i in encrypted.indices) {
                val hi = Character.digit(raw[i * 2], 16)
                val lo = Character.digit(raw[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                encrypted[i] = ((hi shl 4) or lo).toByte()
            }
            val decrypted = rc4(encrypted, key.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
            if (decrypted.isNotBlank()) return decrypted
        }

        return raw
    }

    private fun rc4(data: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return data
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val t = (s[i] + s[j]) and 0xFF
            out[k] = (data[k].toInt() xor s[t]).toByte()
        }
        return out
    }

    private fun extractNuxtArray(html: String): JsonArray? {
        val raw = NuxtScriptRegex.find(html)?.groupValues?.get(1) ?: return null
        return runCatching { VietnameseLyricsCommon.json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
    }

    private fun findSearchSongsArray(arr: JsonArray): JsonArray? {
        val rootObj = arr.getOrNull(1) as? JsonObject
        val rootData = rootObj?.nuxtElement(arr, "data") as? JsonObject
        val scr = rootData?.nuxtElement(arr, "search-category-result") as? JsonObject
        val scrData = scr?.nuxtElement(arr, "data") as? JsonObject
        (scrData?.nuxtElement(arr, "songs") as? JsonArray)?.let { return it }

        val fallbackObj = arr.firstNotNullOfOrNull { el ->
            val obj = el as? JsonObject ?: return@firstNotNullOfOrNull null
            if (obj.containsKey("songs") && obj.containsKey("albums")) obj else null
        }
        return fallbackObj?.nuxtElement(arr, "songs") as? JsonArray
    }

    private fun findLyricDetailObject(arr: JsonArray, songKey: String): JsonObject? {
        val rootObj = arr.getOrNull(1) as? JsonObject
        val rootData = rootObj?.nuxtElement(arr, "data") as? JsonObject
        val detailKey = rootData?.keys?.firstOrNull { it == "dataDetail:$songKey" || it.startsWith("dataDetail:") }
        val detailObj = detailKey?.let { rootData.nuxtElement(arr, it) as? JsonObject }
        (detailObj?.nuxtElement(arr, "LyricDetail") as? JsonObject)?.let { return it }

        return arr.firstNotNullOfOrNull { el ->
            val obj = el as? JsonObject ?: return@firstNotNullOfOrNull null
            if (obj.containsKey("timedLyric") && obj.containsKey("keyDecryptLyric")) obj else null
        }
    }

    private fun resolveNuxtElement(arr: JsonArray, index: Int, depth: Int = 0): JsonElement? {
        if (depth > 12 || index !in arr.indices) return null
        val el = arr[index]
        if (el is JsonArray && el.size >= 2) {
            val tag = (el[0] as? JsonPrimitive)?.contentOrNull
            if (tag in NuxtRefTags) {
                val targetIdx = (el[1] as? JsonPrimitive)?.intOrNull
                if (targetIdx != null) return resolveNuxtElement(arr, targetIdx, depth + 1)
            }
        }
        return el
    }

    private fun JsonObject.nuxtElement(arr: JsonArray, key: String): JsonElement? {
        val raw = this[key] ?: return null
        val idx = (raw as? JsonPrimitive)?.intOrNull ?: return raw
        return resolveNuxtElement(arr, idx)
    }

    private fun JsonObject.nuxtString(arr: JsonArray, key: String): String? =
        (nuxtElement(arr, key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun parseDurationSeconds(el: JsonElement?): Int {
        val prim = el as? JsonPrimitive ?: return 0
        prim.longOrNull?.let { raw ->
            return if (raw > 10_000L) (raw / 1000L).toInt() else raw.toInt()
        }
        val str = prim.contentOrNull?.trim().orEmpty()
        str.toIntOrNull()?.let { return it }
        val parts = str.split(":")
        if (parts.size == 2) {
            val min = parts[0].toIntOrNull() ?: return 0
            val sec = parts[1].toIntOrNull() ?: return 0
            return min * 60 + sec
        }
        return 0
    }

    private fun normalizeUrl(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$BaseUrl$raw"
        else -> raw
    }

    private val NuxtScriptRegex = Regex(
        """<script[^>]*id\s*=\s*["']__NUXT_DATA__["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val HexRegex = Regex("^[0-9a-fA-F]+$")
    private val NuxtRefTags = setOf("ShallowReactive", "Reactive", "Ref", "ShallowRef")
}

internal data class VnTrackCandidate<T>(
    val title: String,
    val artists: List<String>,
    val durationSec: Int,
    val ref: T,
)

internal object VietnameseLyricsCommon {
    const val TIMEOUT_MS = 8_000L
    private const val MAX_DURATION_DIFF_SEC = 15

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followAppProxy()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun httpGetBytes(
        url: String,
        userAgent: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val call = httpClient.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            if (cont.isActive) cont.resumeWithException(IOException("HTTP ${res.code}"))
                            return
                        }
                        val bytes = res.body.bytes()
                        if (cont.isActive) cont.resume(bytes)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        })
    }

    suspend fun httpGetText(
        url: String,
        userAgent: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = httpGetBytes(url, userAgent, referer, extraHeaders).toString(Charsets.UTF_8)

    suspend inline fun <T> catchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun buildSearchQueries(rawTitle: String, rawArtist: String): List<String> {
        val wantedArtists = splitArtists(rawArtist)
        val cleaned = cleanTitle(rawTitle, wantedArtists)
        val core = stripAllBrackets(cleaned).trim()
        val primaryArtist = wantedArtists.firstOrNull().orEmpty()

        val queries = linkedSetOf<String>()
        if (cleaned.isNotBlank() && primaryArtist.isNotBlank()) {
            queries += "$cleaned $primaryArtist"
        }
        if (core.isNotBlank() && core != cleaned && primaryArtist.isNotBlank()) {
            queries += "$core $primaryArtist"
        }
        if (cleaned.isNotBlank()) {
            queries += cleaned
        }
        if (core.isNotBlank()) {
            queries += core
        }
        return queries.toList()
    }

    fun <T> rankCandidates(
        candidates: List<VnTrackCandidate<T>>,
        rawTitle: String,
        rawArtist: String,
        durationSec: Int,
    ): List<VnTrackCandidate<T>> {
        if (candidates.isEmpty()) return emptyList()

        val wantedArtists = splitArtists(rawArtist)
        val wantedArtistNorms = wantedArtists.map(::normalizeArtistToken).filter { it.folded.isNotEmpty() }
        val wantedClean = cleanTitle(rawTitle, wantedArtists)
        val wantedCore = stripAllBrackets(wantedClean)

        val wCleanFold = foldVietnamese(wantedClean)
        val wCoreFold = foldVietnamese(wantedCore)
        val wCleanAccented = normalizeAccented(wantedClean)
        val wCoreAccented = normalizeAccented(wantedCore)
        if (wCoreFold.isEmpty()) return emptyList()

        val requestedVariants = extractVariantTags(rawTitle)

        return candidates.mapNotNull { candidate ->
            if (durationSec > 0 && candidate.durationSec > 0) {
                if (abs(candidate.durationSec - durationSec) > MAX_DURATION_DIFF_SEC) {
                    return@mapNotNull null
                }
            }

            val candVariants = extractVariantTags(candidate.title)
            if ((candVariants - requestedVariants).isNotEmpty()) {
                return@mapNotNull null
            }

            val candArtistTokens = candidate.artists
                .flatMap(::splitArtists)
                .map(::normalizeArtistToken)
                .filter { it.folded.isNotEmpty() }

            val candClean = cleanTitle(candidate.title, candidate.artists.flatMap(::splitArtists))
            val candCore = stripAllBrackets(candClean)
            val cCleanFold = foldVietnamese(candClean)
            val cCoreFold = foldVietnamese(candCore)
            val cCleanAccented = normalizeAccented(candClean)
            val cCoreAccented = normalizeAccented(candCore)

            val titleScore = when {
                cCleanFold == wCleanFold && wCleanFold.isNotEmpty() ->
                    100 + if (cCleanAccented == wCleanAccented) 15 else 0
                cCoreFold == wCoreFold && wCoreFold.isNotEmpty() ->
                    70 + if (cCoreAccented == wCoreAccented) 10 else 0
                else -> return@mapNotNull null
            }

            val artistScore = if (wantedArtistNorms.isNotEmpty()) {
                if (candArtistTokens.isEmpty()) return@mapNotNull null
                var exactHits = 0
                var accentedHits = 0
                for (w in wantedArtistNorms) {
                    val match = candArtistTokens.firstOrNull { c -> artistsMatch(w, c) }
                    if (match != null) {
                        exactHits++
                        if (w.accented == match.accented) accentedHits++
                    }
                }
                if (exactHits == 0) return@mapNotNull null
                exactHits * 40 + accentedHits * 10
            } else {
                // Without an artist constraint, only accept exact cleaned title and tight duration.
                if (cCleanFold != wCleanFold) return@mapNotNull null
                if (durationSec > 0 && candidate.durationSec > 0 && abs(candidate.durationSec - durationSec) > 5) {
                    return@mapNotNull null
                }
                0
            }

            val durationScore = if (durationSec > 0 && candidate.durationSec > 0) {
                val diff = abs(candidate.durationSec - durationSec)
                when {
                    diff <= 3 -> 30
                    diff <= 8 -> 15
                    else -> (5 - diff / 3).coerceAtLeast(0)
                }
            } else {
                0
            }

            val variantBonus = if (requestedVariants.isNotEmpty() && requestedVariants.all { it in candVariants }) 20 else 0
            candidate to (titleScore + artistScore + durationScore + variantBonus)
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun cleanTitle(raw: String, knownArtists: List<String>): String {
        var s = Parser.unescapeEntities(raw, false).trim()
        s = stripArtistAffix(s, knownArtists)
        s = NoiseBracketRegex.replace(s, " ")
        s = TrailingFeatureRegex.replace(s, " ")
        s = TrailingNoiseSuffixRegex.replace(s, " ")
        s = stripArtistAffix(s, knownArtists)
        return s.replace(MultiSpaceRegex, " ").trim().trim('-', '|', '/', '–', '—')
    }

    private fun stripArtistAffix(title: String, knownArtists: List<String>): String {
        if (knownArtists.isEmpty()) return title
        val artistFoldSet = knownArtists.map(::foldVietnamese).filter { it.length >= 2 }.toSet()
        if (artistFoldSet.isEmpty()) return title

        var current = title.trim()
        val prefixMatch = LeadingDashSepRegex.find(current)
        if (prefixMatch != null) {
            val headFold = foldVietnamese(prefixMatch.groupValues[1])
            val tail = prefixMatch.groupValues[2].trim()
            if (tail.isNotEmpty() && artistFoldSet.any { headFold == it || headFold.contains(it) && headFold.length <= it.length + 12 }) {
                current = tail
            }
        }
        val suffixMatch = TrailingDashSepRegex.find(current)
        if (suffixMatch != null) {
            val head = suffixMatch.groupValues[1].trim()
            val tailFold = foldVietnamese(suffixMatch.groupValues[2])
            if (head.isNotEmpty() && artistFoldSet.any { tailFold == it }) {
                current = head
            }
        }
        return current
    }

    private fun stripAllBrackets(input: String): String =
        AllBracketsRegex.replace(input, " ").replace(MultiSpaceRegex, " ").trim()

    fun splitArtists(rawArtist: String): List<String> {
        if (rawArtist.isBlank()) return emptyList()
        val unescaped = Parser.unescapeEntities(rawArtist, false)
            .replace(ArtistTopicSuffixRegex, "")
            .trim()
        return ArtistSplitRegex.split(unescaped)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private data class ArtistNorm(
        val folded: String,
        val compact: String,
        val accented: String,
        val words: List<String>,
    )

    private fun normalizeArtistToken(name: String): ArtistNorm {
        val cleaned = HonorificPrefixRegex.replace(name.trim(), "")
        val folded = foldVietnamese(cleaned)
        val accented = normalizeAccented(cleaned)
        val words = folded.split(' ').filter { it.isNotEmpty() }
        return ArtistNorm(
            folded = folded,
            compact = folded.replace(" ", ""),
            accented = accented,
            words = words,
        )
    }

    private fun artistsMatch(wanted: ArtistNorm, candidate: ArtistNorm): Boolean {
        if (wanted.folded == candidate.folded || wanted.compact == candidate.compact) return true
        if (wanted.words.size >= 2 && candidate.words.size >= 2) {
            if (candidate.words.containsAll(wanted.words) || wanted.words.containsAll(candidate.words)) {
                return true
            }
        }
        return false
    }

    private fun extractVariantTags(title: String): Set<String> {
        val folded = foldVietnamese(title)
        val tags = HashSet<String>()
        for ((key, regex) in AlwaysVariantRegexes) {
            if (regex.containsMatchIn(folded)) tags += key
        }
        if (LiveContextRegex.containsMatchIn(title)) tags += "live"
        if (BeatContextRegex.containsMatchIn(title)) tags += "beat"
        if (MixContextRegex.containsMatchIn(title)) tags += "mix"
        return tags
    }

    fun foldVietnamese(input: String): String {
        val noD = input.replace('đ', 'd').replace('Đ', 'D')
        val decomposed = Normalizer.normalize(noD, Normalizer.Form.NFD)
        val noMarks = CombiningMarksRegex.replace(decomposed, "")
        return NonAlphaNumRegex.replace(noMarks.lowercase(Locale.ROOT), " ")
            .replace(MultiSpaceRegex, " ")
            .trim()
    }

    private fun normalizeAccented(input: String): String {
        val nfc = Normalizer.normalize(input, Normalizer.Form.NFC)
        return NonAlphaNumRegex.replace(nfc.lowercase(Locale.ROOT), " ")
            .replace(MultiSpaceRegex, " ")
            .trim()
    }

    fun normalizeLrc(raw: String): String? {
        val cleaned = raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = cleaned.lines()

        var threeDigitCount = 0
        var twoDigitCount = 0
        for (line in lines) {
            for (m in TimeTagRegex.findAll(line)) {
                when (m.groupValues[3].length) {
                    3 -> threeDigitCount++
                    2 -> twoDigitCount++
                }
            }
        }
        val preferThreeDigits = threeDigitCount > 0 && threeDigitCount >= twoDigitCount

        val outLines = mutableListOf<String>()
        val distinctTimestamps = HashSet<Long>()
        var sungLineCount = 0

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || MetaTagLineRegex.matches(trimmed)) continue

            val lineMatch = LrcTimedLineRegex.matchEntire(trimmed) ?: continue
            val tagsPart = lineMatch.groupValues[1]
            val bodyText = Parser.unescapeEntities(lineMatch.groupValues[2], false).trim()
            if (bodyText.isEmpty()) continue

            val formattedTags = StringBuilder()
            var minTimeMs = Long.MAX_VALUE
            for (tag in TimeTagRegex.findAll(tagsPart)) {
                val min = tag.groupValues[1].toLongOrNull() ?: continue
                val sec = tag.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = tag.groupValues[3]
                val fracMillis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val normalizedFrac = when {
                    preferThreeDigits -> fracMillis.toString().padStart(3, '0')
                    fracMillis % 10L == 0L -> (fracMillis / 10L).toString().padStart(2, '0')
                    else -> fracMillis.toString().padStart(3, '0')
                }
                val timeMs = min * 60_000L + sec * 1_000L + fracMillis
                if (timeMs < minTimeMs) minTimeMs = timeMs
                distinctTimestamps += timeMs
                formattedTags.append('[')
                    .append(min.toString().padStart(2, '0'))
                    .append(':')
                    .append(sec.toString().padStart(2, '0'))
                    .append('.')
                    .append(normalizedFrac)
                    .append(']')
            }
            if (formattedTags.isEmpty()) continue

            if (minTimeMs <= 10_000L && IntroCreditLineRegex.containsMatchIn(bodyText)) continue
            if (isPlaceholderLyricLine(bodyText)) return null

            if (isRealSungLine(bodyText)) {
                sungLineCount++
            }
            outLines += "$formattedTags$bodyText"
        }

        if (sungLineCount < 2 || outLines.isEmpty()) return null
        if (distinctTimestamps.size <= 1) {
            val plain = outLines.joinToString("\n") { line ->
                TimeTagRegex.replace(line, "").trim()
            }
            return normalizePlainLyrics(plain)
        }
        return outLines.joinToString("\n")
    }

    fun isSyncedLrc(text: String): Boolean =
        text.lineSequence().any { LrcTimedLineRegex.matches(it.trim()) }

    suspend fun fetchRankedLyrics(
        title: String,
        artist: String,
        durationSec: Int,
        maxFetches: Int = 4,
        preferSyncedOnly: Boolean,
        search: suspend (String) -> List<VnTrackCandidate<String>>,
        fetchOne: suspend (String) -> String?,
    ): List<String> {
        val queries = buildSearchQueries(title, artist)
        if (queries.isEmpty()) return emptyList()

        val synced = mutableListOf<String>()
        val plain = mutableListOf<String>()
        val seenRefs = HashSet<String>()
        val seenLyrics = HashSet<String>()
        val deferred = mutableListOf<VnTrackCandidate<String>>()
        var fetchCount = 0
        val perQueryFirstPass = if (queries.size > 1) 2 else maxFetches

        suspend fun inspect(candidate: VnTrackCandidate<String>): Boolean {
            if (fetchCount >= maxFetches || !seenRefs.add(candidate.ref)) return false
            fetchCount++
            val lyrics = catchingNonCancel { fetchOne(candidate.ref) } ?: return false
            if (!seenLyrics.add(lyrics)) return false
            if (isSyncedLrc(lyrics)) {
                synced += lyrics
                if (preferSyncedOnly) return true
            } else {
                plain += lyrics
            }
            return false
        }

        for (query in queries) {
            if (fetchCount >= maxFetches) break
            val searched = catchingNonCancel { search(query) }.orEmpty()
            val ranked = rankCandidates(searched, title, artist, durationSec)
            ranked.forEachIndexed { idx, candidate ->
                if (idx >= perQueryFirstPass) deferred += candidate
            }
            for (candidate in ranked.take(perQueryFirstPass)) {
                if (inspect(candidate)) return synced
            }
        }
        for (candidate in deferred) {
            if (fetchCount >= maxFetches) break
            if (inspect(candidate)) return synced
        }
        return synced + plain
    }

    fun normalizePlainLyrics(raw: String): String? {
        val withNewlines = HtmlBreakRegex.replace(raw, "\n")
        val strippedTags = HtmlTagRegex.replace(withNewlines, "")
        val unescaped = Parser.unescapeEntities(strippedTags, false)
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val outLines = mutableListOf<String>()
        var sungCount = 0
        var prevBlank = true

        for (rawLine in unescaped.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (!prevBlank) {
                    outLines += ""
                    prevBlank = true
                }
                continue
            }
            if (isPlaceholderLyricLine(line)) return null
            if (outLines.isEmpty() && IntroCreditLineRegex.containsMatchIn(line)) continue
            outLines += line
            prevBlank = false
            if (isRealSungLine(line)) sungCount++
        }

        while (outLines.isNotEmpty() && outLines.last().isEmpty()) {
            outLines.removeAt(outLines.lastIndex)
        }
        if (sungCount < 2) return null
        return outLines.joinToString("\n")
    }

    private fun isRealSungLine(line: String): Boolean =
        line.any { it.isLetter() } && !SectionLabelOnlyRegex.matches(line)

    private fun isPlaceholderLyricLine(line: String): Boolean {
        val folded = foldVietnamese(line)
        return folded.contains("chua co loi bai hat") ||
            folded.contains("hien chua co loi") ||
            folded.contains("dang cap nhat loi") ||
            folded.contains("loi bai hat dang duoc cap nhat")
    }

    private val CombiningMarksRegex = Regex("\\p{Mn}+")
    private val NonAlphaNumRegex = Regex("[^\\p{L}\\p{N}]+")
    private val MultiSpaceRegex = Regex("\\s+")
    private val AllBracketsRegex = Regex("""\s*[\(\[（【「『《][^\(\[（【「『《\)\]）】」』》]*[\)\]）】」』》]""")

    private val NoiseBracketRegex = Regex(
        """\s*[\(\[（【][^\)\]）】]*(?:\bofficial\b|\bmv\b|\bm/v\b|\bmusic\s+video\b|\blyrics?\s+video\b|\blyrics?\b|\baudio\b|\bvideo\b|\bvisualizer\b|\bfull\s+(?:audio|mv|video)\b|\bhd\b|\b4k\b|\bprod\.?\b|\bft\.?\b|\bfeat\.?\b|\bfeaturing\b|\bremaster(?:ed)?\b|\bexplicit\b|\bclean\b|loi\s+bai\s+hat|lời\s+bài\s+hát)[^\)\]）】]*[\)\]）】]""",
        RegexOption.IGNORE_CASE,
    )
    private val TrailingFeatureRegex = Regex(
        """(?:\s+[-–—|]\s*|\s+)(?:ft\.?|feat\.?|featuring|prod\.?(?:\s+by)?)\s+.+$""",
        RegexOption.IGNORE_CASE,
    )
    private val TrailingNoiseSuffixRegex = Regex(
        """\s*[-–—|/]+\s*(?:official\s+(?:music\s+)?(?:mv|video|audio|lyrics?(?:\s+video)?|visualizer)|lyrics?\s+video|m/v|mv|audio|video)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val LeadingDashSepRegex = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")
    private val TrailingDashSepRegex = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")

    private val ArtistTopicSuffixRegex = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private val ArtistSplitRegex = Regex("""\s*(?:,|;|&|\+|/|\bx\b|\bft\.?\b|\bfeat\.?\b|\bfeaturing\b|\bwith\b|\bvs\.?\b|\bvà\b)\s*""", RegexOption.IGNORE_CASE)
    private val HonorificPrefixRegex = Regex("""^(?:nsut|nsnd|nsưt|ca\s+si|ca\s+sĩ|rapper|dj|mc)\s+""", RegexOption.IGNORE_CASE)

    private val AlwaysVariantRegexes: Map<String, Regex> = mapOf(
        "remix" to Regex("""(?<!\p{L})(?:remix|vinahouse|nightcore|8d)(?!\p{L})"""),
        "cover" to Regex("""(?<!\p{L})(?:cover|parody|nhac che)(?!\p{L})"""),
        "mashup" to Regex("""(?<!\p{L})mashup(?!\p{L})"""),
        "speedup" to Regex("""(?<!\p{L})(?:speed\s*up|sped\s*up|slowed)(?!\p{L})"""),
        "lofi" to Regex("""(?<!\p{L})(?:lofi|lo\s*fi|edm|acoustic|unplugged)(?!\p{L})"""),
        "karaoke" to Regex("""(?<!\p{L})(?:karaoke|instrumental|playback|tone\s+nam|tone\s+nu)(?!\p{L})"""),
        "translation" to Regex("""(?<!\p{L})(?:loi\s+viet|vietsub|vietnamese\s+ver)(?!\p{L})"""),
    )
    private val LiveContextRegex = Regex(
        """(?:[\(\[（【]\s*(?:live|liveshow)[^\)\]）】]*[\)\]）】]|[-–—|]\s*(?:live|liveshow)\b|\blive\s+(?:at|in|version|session|concert)\b|\bliveshow\b|\bsky\s+tour\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val BeatContextRegex = Regex(
        """(?:[\(\[（【]\s*beat[^\)\]）】]*[\)\]）】]|[-–—|]\s*beat\b|\bbeat\s+(?:chuan|chuẩn|goc|gốc|nhac|nhạc)\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val MixContextRegex = Regex(
        """(?:[\(\[（【][^\)\]）】]*\bmix\b[^\)\]）】]*[\)\]）】]|\bdj\s+[^-–—]+\bmix\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val TimeTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val LrcTimedLineRegex = Regex("""^((?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]\s*)+)(.*)$""")
    private val MetaTagLineRegex = Regex("""^\[[a-zA-Z#]+:.*\]$""")
    private val IntroCreditLineRegex = Regex("""^(?:Bài hát|Ca sĩ|Sáng tác|Nhạc sĩ|Album|LRC|Lyric|Upload|Contributor)\s*[:：]""", RegexOption.IGNORE_CASE)
    private val HtmlBreakRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val HtmlTagRegex = Regex("""<[^>]+>""")
    private val SectionLabelOnlyRegex = Regex("""^\[[A-Za-z0-9\s/_-]{1,24}\]$""")
}
