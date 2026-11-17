// ZingLyricsProvider.kt

package com.mochyy.mochymusic.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder

object ZingLyricsProvider : LyricsProvider {
    override val name = "ZingMP3"

    private const val AcBaseUrl = "https://ac.zingmp3.vn"
    private const val MobileBaseUrl = "https://m.zingmp3.vn"
    private const val DesktopBaseUrl = "https://zingmp3.vn"
    private const val SearchLimit = 15
    private const val MaxCandidatesToFetch = 2

    private val EnableZingMp3LyricsKey = booleanPreferencesKey("enableZingMp3Lyrics")

    override fun isEnabled(context: Context): Boolean =
        runCatching { context.dataStore[EnableZingMp3LyricsKey] ?: true }.getOrDefault(true)

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
                fetchLyricsList(title, artist, duration, limit = 1)
                    .firstOrNull()
                    ?.let { Result.success(it) }
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
                fetchLyricsList(title, artist, duration, limit = MaxCandidatesToFetch)
                    .forEach(callback)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun fetchLyricsList(
        title: String,
        artist: String,
        duration: Int,
        limit: Int,
    ): List<String> {
        val queries = VietnameseLyricsCommon.buildSearchQueries(title, artist)
        if (queries.isEmpty()) return emptyList()

        val matchedCandidates = mutableListOf<VnTrackCandidate<String>>()
        for (query in queries) {
            val searched = searchSongs(query)
            val ranked = VietnameseLyricsCommon.rankCandidates(searched, title, artist, duration)
            if (ranked.isNotEmpty()) {
                matchedCandidates.addAll(ranked)
                break
            }
        }
        if (matchedCandidates.isEmpty()) return emptyList()

        val results = mutableListOf<String>()
        val seenIds = HashSet<String>()
        for (candidate in matchedCandidates.take(MaxCandidatesToFetch)) {
            if (!seenIds.add(candidate.ref)) continue
            val lyrics = VietnameseLyricsCommon.catchingNonCancel {
                fetchSongLyrics(candidate.ref)
            } ?: continue
            if (lyrics !in results) {
                results += lyrics
                if (results.size >= limit) break
            }
        }
        return results
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
        val songPageUrl = "$MobileBaseUrl/bai-hat/song/$songId.html"
        val html = VietnameseLyricsCommon.httpGetText(
            url = songPageUrl,
            userAgent = VietnameseLyricsCommon.MOBILE_USER_AGENT,
            referer = "$MobileBaseUrl/",
        )

        val rawDataSource = DataSourceRegex.find(html)?.groupValues?.get(1)
        if (!rawDataSource.isNullOrBlank()) {
            val sourceUrl = buildXhrSourceUrl(Parser.unescapeEntities(rawDataSource, false))
            if (sourceUrl != null) {
                val lrcUrl = fetchLrcUrlFromSource(sourceUrl, referer = songPageUrl)
                if (!lrcUrl.isNullOrBlank()) {
                    val lrcText = VietnameseLyricsCommon.catchingNonCancel {
                        VietnameseLyricsCommon.httpGetText(
                            url = lrcUrl,
                            userAgent = VietnameseLyricsCommon.MOBILE_USER_AGENT,
                            referer = songPageUrl,
                        )
                    }
                    val normalized = lrcText?.let { VietnameseLyricsCommon.normalizeLrc(it) }
                    if (normalized != null) return normalized
                }
            }
        }

        val staticHtml = runCatching {
            Jsoup.parse(html).selectFirst("p.lyrics-text")?.html()
        }.getOrNull() ?: return null
        return VietnameseLyricsCommon.normalizePlainLyrics(staticHtml)
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
