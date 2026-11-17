// ZingLyricsProvider.kt

package com.mochyy.mochymusic.lyrics

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ZingLyricsProvider : LyricsProvider {
    override val name = "ZingMP3"

    private const val AcBaseUrl = "https://ac.zingmp3.vn"
    private const val MobileBaseUrl = "https://m.zingmp3.vn"
    private const val DesktopBaseUrl = "https://zingmp3.vn"
    private const val V2LyricPath = "/api/v2/lyric/get/lyric"
    private const val V2ApiKey = "X5BM3w8N7MKozC0B85o4KMlzLZKhV00y"
    private const val V2SecretKey = "acOrvUS15XRW2o9JksiK1KgQ6Vbds8ZW"
    private const val V2Version = "1.22.4"
    private const val CookieTtlMs = 20 * 60_000L
    private const val SearchLimit = 15
    private const val MaxCandidatesToFetch = 4

    @Volatile
    private var cachedCookie: String? = null

    @Volatile
    private var cachedCookieAtMs: Long = 0L

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
                    ?: Result.failure(IllegalStateException("No matching Zing MP3 lyrics"))
            } ?: Result.failure(IOException("Zing MP3 lyrics request timed out"))
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
        val url = "$AcBaseUrl/complete?type=song&num=$SearchLimit&query=$encoded"
        val raw = VietnameseLyricsCommon.httpGetText(
            url = url,
            userAgent = VietnameseLyricsCommon.DESKTOP_USER_AGENT,
            referer = "$DesktopBaseUrl/",
        )
        val root = runCatching {
            VietnameseLyricsCommon.json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: return emptyList()

        val dataArray = root["data"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<VnTrackCandidate<String>>()

        for (group in dataArray) {
            val groupObj = group as? JsonObject ?: continue
            val songs = groupObj["song"] as? JsonArray ?: continue
            for (item in songs) {
                val obj = item as? JsonObject ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val artistStr = (obj["artist"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val durationSec = (obj["duration"] as? JsonPrimitive)?.let { prim ->
                    prim.intOrNull ?: prim.contentOrNull?.trim()?.toIntOrNull()
                } ?: 0

                out += VnTrackCandidate(
                    title = name,
                    artists = if (artistStr.isNotBlank()) listOf(artistStr) else emptyList(),
                    durationSec = durationSec,
                    ref = id,
                )
            }
        }
        return out
    }

    private suspend fun fetchSongLyrics(songId: String): String? {
        fetchV2SongLyrics(songId)?.let { return it }

        val songPageUrl = "$MobileBaseUrl/bai-hat/song/$songId.html"
        val html = VietnameseLyricsCommon.catchingNonCancel {
            VietnameseLyricsCommon.httpGetText(
                url = songPageUrl,
                userAgent = VietnameseLyricsCommon.MOBILE_USER_AGENT,
                referer = "$MobileBaseUrl/",
            )
        } ?: return null

        val rawDataSource = DataSourceRegex.find(html)?.groupValues?.get(1)
        if (!rawDataSource.isNullOrBlank()) {
            val sourceUrl = buildXhrSourceUrl(Parser.unescapeEntities(rawDataSource, false))
            if (sourceUrl != null) {
                val lrcUrl = fetchLrcUrlFromSource(sourceUrl, referer = songPageUrl)
                if (!lrcUrl.isNullOrBlank()) {
                    fetchAndNormalizeLrc(lrcUrl, referer = songPageUrl)?.let { return it }
                }
            }
        }

        val staticHtml = runCatching {
            Jsoup.parse(html).selectFirst("p.lyrics-text")?.html()
        }.getOrNull() ?: return null
        return VietnameseLyricsCommon.normalizePlainLyrics(staticHtml)
    }

    private suspend fun fetchV2SongLyrics(songId: String): String? {
        val dataObj = fetchV2LyricData(songId) ?: return null
        val sentencesArr = dataObj["sentences"] as? JsonArray
        val parsedSentences = sentencesArr?.let(::parseZingSentences)

        // 1. Word-by-word karaoke from data.sentences -> Mochy SyncKind.WORD (.lrc)
        parsedSentences?.wordSyncedLrc?.let { return it }

        // 2. Fallback to data.file (.lrc)
        val fileUrl = (dataObj["file"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let(::normalizeLyricUrl)
        if (!fileUrl.isNullOrBlank()) {
            fetchAndNormalizeLrc(fileUrl, referer = "$DesktopBaseUrl/")?.let { return it }
        }

        // 3. Fallback to line-synced or plain sentences
        parsedSentences?.lineSyncedLrc?.let { return it }
        parsedSentences?.plainLyrics?.let { return it }

        // 4. Fallback to plain lyric text if provided directly
        val rawLyric = (dataObj["lyric"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" && !it.startsWith("http", ignoreCase = true) }
        return rawLyric?.let { VietnameseLyricsCommon.normalizePlainLyrics(it) }
    }

    private suspend fun fetchV2LyricData(songId: String): JsonObject? {
        var cookie = ensureZingCookie(forceRefresh = false)
        var root = requestV2LyricJson(songId, cookie)
        val err = (root?.get("err") as? JsonPrimitive)?.intOrNull
        if (root == null || err == -201 || err == -1023) {
            cookie = ensureZingCookie(forceRefresh = true)
            root = requestV2LyricJson(songId, cookie)
        }
        val finalErr = (root?.get("err") as? JsonPrimitive)?.intOrNull
        if (finalErr != 0) return null
        return root?.get("data") as? JsonObject
    }

    private suspend fun ensureZingCookie(forceRefresh: Boolean): String? {
        val now = System.currentTimeMillis()
        val existing = cachedCookie
        if (!forceRefresh && !existing.isNullOrBlank() && now - cachedCookieAtMs < CookieTtlMs) {
            return existing
        }
        val response = VietnameseLyricsCommon.catchingNonCancel {
            VietnameseLyricsCommon.httpGetResponse(
                url = "$DesktopBaseUrl/",
                userAgent = VietnameseLyricsCommon.DESKTOP_USER_AGENT,
            )
        } ?: return existing
        val cookieHeader = response.setCookies
            .mapNotNull { raw -> raw.substringBefore(';').trim().takeIf { '=' in it } }
            .distinct()
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
        if (cookieHeader != null) {
            cachedCookie = cookieHeader
            cachedCookieAtMs = now
        }
        return cookieHeader ?: existing
    }

    private suspend fun requestV2LyricJson(songId: String, cookie: String?): JsonObject? {
        val ctime = System.currentTimeMillis() / 1000L
        val sig = computeV2Signature(songId, ctime)
        val url = "$DesktopBaseUrl$V2LyricPath?id=${URLEncoder.encode(songId, Charsets.UTF_8.name())}" +
            "&ctime=$ctime&version=$V2Version&apiKey=$V2ApiKey&sig=$sig"
        val headers = if (!cookie.isNullOrBlank()) mapOf("Cookie" to cookie) else emptyMap()
        val raw = VietnameseLyricsCommon.catchingNonCancel {
            VietnameseLyricsCommon.httpGetText(
                url = url,
                userAgent = VietnameseLyricsCommon.DESKTOP_USER_AGENT,
                referer = "$DesktopBaseUrl/",
                extraHeaders = headers,
            )
        } ?: return null
        return runCatching {
            VietnameseLyricsCommon.json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull()
    }

    private fun computeV2Signature(songId: String, ctimeSec: Long): String {
        val paramStr = "ctime=${ctimeSec}id=${songId}version=${V2Version}"
        val sha256Hex = MessageDigest.getInstance("SHA-256")
            .digest(paramStr.toByteArray(Charsets.UTF_8))
            .toHexLower()
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(V2SecretKey.toByteArray(Charsets.UTF_8), "HmacSHA512"))
        return mac.doFinal((V2LyricPath + sha256Hex).toByteArray(Charsets.UTF_8)).toHexLower()
    }

    private fun ByteArray.toHexLower(): String = buildString(size * 2) {
        for (b in this@toHexLower) {
            val v = b.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }

    private suspend fun fetchAndNormalizeLrc(lrcUrl: String, referer: String): String? {
        val lrcText = VietnameseLyricsCommon.catchingNonCancel {
            VietnameseLyricsCommon.httpGetText(
                url = lrcUrl,
                userAgent = VietnameseLyricsCommon.MOBILE_USER_AGENT,
                referer = referer,
            )
        } ?: return null
        return VietnameseLyricsCommon.normalizeLrc(lrcText)
    }

    private data class ZingWord(
        val text: String,
        val startMs: Long?,
        val endMs: Long?,
    )

    private data class ZingSentence(
        val words: List<ZingWord>,
        val lineStartMs: Long?,
        val rawText: String,
    )

    private data class ParsedZingSentences(
        val wordSyncedLrc: String? = null,
        val lineSyncedLrc: String? = null,
        val plainLyrics: String? = null,
    )

    private fun parseZingSentences(sentences: JsonArray): ParsedZingSentences? {
        val parsed = mutableListOf<ZingSentence>()
        for (item in sentences) {
            val sentObj = item as? JsonObject ?: continue
            val words = mutableListOf<ZingWord>()
            val wordsArr = sentObj["words"] as? JsonArray
            if (wordsArr != null) {
                for (wItem in wordsArr) {
                    val wObj = wItem as? JsonObject ?: continue
                    val rawWord = ((wObj["data"] as? JsonPrimitive)?.contentOrNull
                        ?: (wObj["text"] as? JsonPrimitive)?.contentOrNull)
                        .orEmpty()
                    val cleanedWord = Parser.unescapeEntities(rawWord, false)
                        .replace("<", "")
                        .replace(">", "")
                        .trim()
                    if (cleanedWord.isEmpty()) continue
                    val startMs = (wObj["startTime"] as? JsonPrimitive)?.longOrNull
                    val endMs = (wObj["endTime"] as? JsonPrimitive)?.longOrNull
                    words += ZingWord(cleanedWord, startMs, endMs)
                }
            }

            val fallbackText = ((sentObj["data"] as? JsonPrimitive)?.contentOrNull
                ?: (sentObj["text"] as? JsonPrimitive)?.contentOrNull)
                ?.let { Parser.unescapeEntities(it, false).trim() }
                .orEmpty()

            val lineText = if (words.isNotEmpty()) joinWords(words) else fallbackText
            if (lineText.isBlank()) continue

            val sentStartMs = (sentObj["startTime"] as? JsonPrimitive)?.longOrNull
                ?: words.firstNotNullOfOrNull { it.startMs?.takeIf { t -> t >= 0L } }
            parsed += ZingSentence(words = words, lineStartMs = sentStartMs, rawText = lineText)
        }
        if (parsed.isEmpty()) return null

        val wordTimedLines = parsed.count { s ->
            s.words.mapNotNull { it.startMs?.takeIf { t -> t >= 0L } }.distinct().size > 1
        }
        val wordSyncedLrc = if (wordTimedLines > 0) {
            val rawWordLrc = buildString {
                for (s in parsed) {
                    val lineStart = s.lineStartMs?.takeIf { it >= 0L } ?: continue
                    append(formatLrcStamp(lineStart, bracketed = true))
                    val timedWords = s.words.filter { it.startMs != null && it.startMs >= 0L }
                    if (s.words.isNotEmpty() && timedWords.isNotEmpty()) {
                        var lastWordMs = lineStart
                        s.words.forEachIndexed { index, word ->
                            val wordMs = word.startMs?.takeIf { it >= 0L } ?: lastWordMs
                            lastWordMs = wordMs
                            if (index > 0 && !isAttachedPunctuation(word.text)) {
                                append(' ')
                            }
                            append(formatLrcStamp(wordMs, bracketed = false))
                            append(word.text)
                        }
                    } else {
                        append(s.rawText)
                    }
                    append('\n')
                }
            }
            VietnameseLyricsCommon.normalizeLrc(rawWordLrc)
                ?.takeIf { LyricsUtils.syncKind(it) == LyricsUtils.SyncKind.WORD }
        } else {
            null
        }

        val lineTimedCount = parsed.mapNotNull { it.lineStartMs?.takeIf { t -> t >= 0L } }.distinct().size
        val lineSyncedLrc = if (lineTimedCount > 1) {
            val rawLineLrc = buildString {
                for (s in parsed) {
                    val lineStart = s.lineStartMs?.takeIf { it >= 0L } ?: continue
                    append(formatLrcStamp(lineStart, bracketed = true))
                    append(s.rawText)
                    append('\n')
                }
            }
            VietnameseLyricsCommon.normalizeLrc(rawLineLrc)
        } else {
            null
        }

        val plainLyrics = VietnameseLyricsCommon.normalizePlainLyrics(
            parsed.joinToString("\n") { it.rawText }
        )

        return ParsedZingSentences(
            wordSyncedLrc = wordSyncedLrc,
            lineSyncedLrc = lineSyncedLrc,
            plainLyrics = plainLyrics,
        )
    }

    private fun joinWords(words: List<ZingWord>): String = buildString {
        words.forEachIndexed { index, word ->
            if (index > 0 && !isAttachedPunctuation(word.text)) {
                append(' ')
            }
            append(word.text)
        }
    }

    private fun isAttachedPunctuation(token: String): Boolean =
        token.length == 1 && token[0] in ",.!?;:)]}…"

    private fun formatLrcStamp(ms: Long, bracketed: Boolean): String {
        val safeMs = ms.coerceAtLeast(0L)
        val minutes = (safeMs / 60_000L).coerceAtMost(99L)
        val seconds = (safeMs % 60_000L) / 1_000L
        val centis = (safeMs % 1_000L) / 10L
        return if (bracketed) {
            String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
        } else {
            String.format(Locale.US, "<%02d:%02d.%02d>", minutes, seconds, centis)
        }
    }

    private fun normalizeLyricUrl(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$DesktopBaseUrl$raw"
        else -> raw
    }

    private suspend fun fetchLrcUrlFromSource(sourceUrl: String, referer: String): String? {
        val rawJson = VietnameseLyricsCommon.catchingNonCancel {
            VietnameseLyricsCommon.httpGetText(
                url = sourceUrl,
                userAgent = VietnameseLyricsCommon.MOBILE_USER_AGENT,
                referer = referer,
                extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
            )
        } ?: return null

        val root = runCatching {
            VietnameseLyricsCommon.json.parseToJsonElement(rawJson) as? JsonObject
        }.getOrNull() ?: return null

        val dataObj = root["data"] as? JsonObject ?: return null
        val lyricUrl = (dataObj["lyric"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (lyricUrl.isBlank() || lyricUrl == "null") return null
        return when {
            lyricUrl.startsWith("//") -> "https:$lyricUrl"
            lyricUrl.startsWith("/") -> "$MobileBaseUrl$lyricUrl"
            else -> lyricUrl
        }
    }

    private fun buildXhrSourceUrl(rawPath: String): String? {
        val path = rawPath.trim()
        if (path.isBlank()) return null
        val normalizedPath = when {
            path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) ->
                return path.replace("/media/get-source", "/xhr/media/get-source")
            path.startsWith("/xhr/media/", ignoreCase = true) -> path
            path.startsWith("/media/", ignoreCase = true) -> "/xhr$path"
            path.startsWith("/") -> "/xhr$path"
            else -> "/xhr/$path"
        }
        return "$MobileBaseUrl$normalizedPath"
    }

    private val DataSourceRegex = Regex(
        """data-source\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
}
