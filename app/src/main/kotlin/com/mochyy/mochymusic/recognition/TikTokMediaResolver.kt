// TikTokMediaResolver.kt

package com.mochyy.mochymusic.recognition

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.mochyy.mochymusic.importer.LinkImporter
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ResolvedVideoMusic(
    val title: String,
    val artist: String,
    val sourceLabel: String,
    val resolvedByShazam: Boolean,
    val youtubeVideoId: String? = null,
) {
    val searchQuery: String
        get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
}

/**
 * Bóc thông tin bài hát từ liên kết video ngắn chia sẻ sang Mochy
 * (TikTok, Instagram Reels, Facebook Reels / fb.watch, YouTube Shorts).
 *
 * Bước 1: Theo redirect và đọc JSON nhúng / oEmbed / OpenGraph, lọc bỏ nhãn chung
 * như "nhạc nền", "âm thanh gốc", "original sound", "original audio", "suara asli".
 * Bước 2: Nếu chỉ là âm thanh gốc hoặc thiếu tên bài, bóc URL stream phát kèm header/cookie,
 * tải tối đa ~800 KB / 10 giây đầu vào file tạm trong cacheDir, giải mã PCM 16-bit bằng
 * MediaExtractor + MediaCodec rồi nhận diện qua MusicRecognitionService.recognizePcmBytes().
 */
object TikTokMediaResolver {

    private const val TAG = "TikTokMediaResolver"

    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val MAX_DOWNLOAD_BYTES = 850 * 1024L
    private const val MAX_DECODE_DURATION_US = 10_000_000L
    private const val CODEC_DECODE_TIMEOUT_MS = 15_000L

    private val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val GENERIC_EXACT_LABELS = setOf(
        "nhac nen",
        "am thanh goc",
        "original sound",
        "original audio",
        "suara asli",
        "sonido original",
        "som original",
        "son original",
        "audio original",
        "originalton",
        "original",
        "sound",
        "audio",
        "music",
        "nhac",
    )

    private val GENERIC_PREFIXES = listOf(
        "nhac nen -",
        "nhac nen –",
        "nhac nen cua ",
        "am thanh goc -",
        "am thanh goc –",
        "am thanh goc cua ",
        "original sound -",
        "original sound –",
        "original sound by ",
        "original audio -",
        "original audio –",
        "suara asli -",
        "suara asli –",
        "sonido original -",
        "som original -",
        "son original -",
        "originalton -",
    )

    private val PLATFORM_JUNK_TITLES = setOf(
        "tiktok",
        "tiktok - make your day",
        "instagram",
        "facebook",
        "watch",
        "facebook watch",
        "reels",
        "youtube",
        "youtube shorts",
    )

    private enum class VideoPlatform(val label: String) {
        TIKTOK("TikTok"),
        INSTAGRAM_REELS("Instagram Reels"),
        FACEBOOK_REELS("Facebook Reels"),
        YOUTUBE_SHORTS("YouTube Shorts"),
    }

    fun isSupportedVideoUrl(rawInput: String): Boolean {
        val url = LinkImporter.extractUrl(rawInput) ?: rawInput.trim()
        return detectPlatform(url) != null
    }

    private fun detectPlatform(url: String): VideoPlatform? {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.encodedPath?.lowercase().orEmpty()
        return when {
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> VideoPlatform.TIKTOK
            (host == "instagram.com" || host.endsWith(".instagram.com")) &&
                (path.startsWith("/reel/") || path.startsWith("/reels/")) -> VideoPlatform.INSTAGRAM_REELS
            host == "fb.watch" || host.endsWith(".fb.watch") -> VideoPlatform.FACEBOOK_REELS
            (host == "facebook.com" || host.endsWith(".facebook.com")) &&
                (path.startsWith("/reel/") ||
                    path.startsWith("/reels/") ||
                    path.startsWith("/share/r/") ||
                    path.startsWith("/share/v/")) -> VideoPlatform.FACEBOOK_REELS
            (host == "youtube.com" || host.endsWith(".youtube.com")) &&
                path.startsWith("/shorts/") -> VideoPlatform.YOUTUBE_SHORTS
            else -> null
        }
    }

    suspend fun resolve(
        context: Context,
        rawInput: String,
        onShazamFallback: suspend () -> Unit = {},
    ): Result<ResolvedVideoMusic> = withContext(Dispatchers.IO) {
        val url = LinkImporter.extractUrl(rawInput) ?: rawInput.trim()
        val initialPlatform = detectPlatform(url)
            ?: return@withContext Result.failure(IllegalArgumentException("Liên kết video ngắn chưa được hỗ trợ."))

        val cookieJar = InMemoryCookieJar()
        val client = baseHttpClient.newBuilder().cookieJar(cookieJar).build()

        try {
            if (initialPlatform == VideoPlatform.YOUTUBE_SHORTS) {
                return@withContext resolveYouTubeShorts(context, client, url, onShazamFallback)
            }

            val page = fetchPage(client, url, DESKTOP_USER_AGENT)
            val resolvedUrl = page?.finalUrl ?: url
            val platform = detectPlatform(resolvedUrl) ?: initialPlatform

            if (platform == VideoPlatform.YOUTUBE_SHORTS) {
                return@withContext resolveYouTubeShorts(context, client, resolvedUrl, onShazamFallback)
            }

            val html = page?.body.orEmpty()
            val streamCandidates = LinkedHashSet<StreamCandidate>()

            // Bước 1: Đọc JSON nhúng / oEmbed / OpenGraph
            when (platform) {
                VideoPlatform.TIKTOK -> {
                    val tkData = parseTikTokPage(html)
                    tkData.cleanMusic?.let { return@withContext Result.success(it) }
                    streamCandidates.addAll(tkData.streams)

                    // Thử lại với mobile UA hoặc embed v2 nếu trang chính không có dữ liệu
                    if (tkData.cleanMusic == null && streamCandidates.isEmpty()) {
                        val mobilePage = fetchPage(client, resolvedUrl, MOBILE_USER_AGENT)
                        if (mobilePage != null && mobilePage.body.isNotBlank()) {
                            val mobileTk = parseTikTokPage(mobilePage.body)
                            mobileTk.cleanMusic?.let { return@withContext Result.success(it) }
                            streamCandidates.addAll(mobileTk.streams)
                        }
                    }

                    if (streamCandidates.isEmpty()) {
                        extractTikTokVideoId(resolvedUrl)?.let { videoId ->
                            val embedUrl = "https://www.tiktok.com/embed/v2/$videoId"
                            fetchPage(client, embedUrl, DESKTOP_USER_AGENT)?.let { embedPage ->
                                val embedTk = parseTikTokPage(embedPage.body)
                                embedTk.cleanMusic?.let { return@withContext Result.success(it) }
                                streamCandidates.addAll(embedTk.streams)
                            }
                        }
                    }

                    // oEmbed / OpenGraph dự phòng cho TikTok nếu có dạng "Bài hát - Ca sĩ" sạch
                    if (streamCandidates.isEmpty()) {
                        resolveTikTokOembedOrOg(client, resolvedUrl, html)?.let {
                            return@withContext Result.success(it)
                        }
                    }
                }

                VideoPlatform.INSTAGRAM_REELS -> {
                    val igData = parseInstagramPage(html)
                    igData.cleanMusic?.let { return@withContext Result.success(it) }
                    streamCandidates.addAll(igData.streams)
                }

                VideoPlatform.FACEBOOK_REELS -> {
                    val fbData = parseFacebookPage(html)
                    fbData.cleanMusic?.let { return@withContext Result.success(it) }
                    streamCandidates.addAll(fbData.streams)
                }

                VideoPlatform.YOUTUBE_SHORTS -> Unit
            }

            // Bước 2: Fallback sang Shazam từ ~800 KB / 10s đầu audio của video
            if (streamCandidates.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Không tìm thấy tên nhạc hoặc luồng âm thanh công khai từ ${platform.label}."),
                )
            }

            onShazamFallback()
            val shazamTrack = recognizeFromStreamCandidates(
                context = context,
                client = client,
                candidates = streamCandidates.toList(),
                sourceLabel = platform.label,
            )
            if (shazamTrack != null) {
                return@withContext Result.success(shazamTrack)
            }

            Result.failure(
                IllegalStateException("Không nhận diện được giai điệu từ video ${platform.label} này."),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to resolve short video URL: $url")
            Result.failure(
                IllegalStateException(e.message?.takeIf { it.isNotBlank() } ?: "Không thể xử lý liên kết video này."),
            )
        }
    }

    private suspend fun resolveYouTubeShorts(
        context: Context,
        client: OkHttpClient,
        url: String,
        onShazamFallback: suspend () -> Unit,
    ): Result<ResolvedVideoMusic> {
        val videoId = extractYouTubeShortsId(url)
            ?: return Result.failure(IllegalArgumentException("Không đọc được mã YouTube Shorts."))

        // Bước 1: Kiểm tra metadata âm thanh gắn kèm trong trang Shorts hoặc oEmbed/MediaInfo
        val html = fetchPage(client, "https://www.youtube.com/shorts/$videoId", DESKTOP_USER_AGENT)?.body.orEmpty()
        parseYouTubeShortsMusicFromHtml(html)?.let { return Result.success(it) }

        val mediaInfo = runCatching { YouTube.getMediaInfo(videoId).getOrNull() }.getOrNull()
        val infoTitle = mediaInfo?.title?.trim().orEmpty()
        val infoAuthor = mediaInfo?.author?.trim().orEmpty()
        if (infoAuthor.endsWith(" - Topic", ignoreCase = true) && infoTitle.isNotBlank() && !isGenericSoundLabel(infoTitle)) {
            return Result.success(
                ResolvedVideoMusic(
                    title = infoTitle,
                    artist = infoAuthor.removeSuffix(" - Topic").trim(),
                    sourceLabel = VideoPlatform.YOUTUBE_SHORTS.label,
                    resolvedByShazam = false,
                    youtubeVideoId = videoId,
                ),
            )
        }

        // Bước 2: Bóc stream audio của YouTube Shorts rồi nhận diện bằng Shazam
        onShazamFallback()
        val candidates = collectYouTubeAudioCandidates(videoId)
        if (candidates.isNotEmpty()) {
            recognizeFromStreamCandidates(
                context = context,
                client = client,
                candidates = candidates,
                sourceLabel = VideoPlatform.YOUTUBE_SHORTS.label,
            )?.let { return Result.success(it) }
        }

        // Dự phòng cuối cùng nếu tiêu đề Shorts có định dạng "Tên bài - Ca sĩ" rõ ràng
        extractArtistDashTitle(infoTitle)?.let { (artist, title) ->
            return Result.success(
                ResolvedVideoMusic(
                    title = title,
                    artist = artist,
                    sourceLabel = VideoPlatform.YOUTUBE_SHORTS.label,
                    resolvedByShazam = false,
                    youtubeVideoId = videoId,
                ),
            )
        }

        return Result.failure(IllegalStateException("Không nhận diện được bài hát trong YouTube Shorts này."))
    }

    private suspend fun collectYouTubeAudioCandidates(videoId: String): List<StreamCandidate> {
        val out = mutableListOf<StreamCandidate>()
        val clients = listOf(YouTubeClient.VISIONOS, YouTubeClient.ANDROID_VR_1_61_48, YouTubeClient.IOS)
        for (ytClient in clients) {
            coroutineContext.ensureActive()
            val response = runCatching { YouTube.player(videoId, client = ytClient).getOrNull() }.getOrNull() ?: continue
            val streaming = response.streamingData ?: continue
            val headers = mapOf(
                "User-Agent" to ytClient.userAgent,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
            )
            streaming.adaptiveFormats
                .filter { it.isAudio && it.isOriginal && !it.url.isNullOrBlank() }
                .sortedWith(
                    compareBy<com.music.innertube.models.response.PlayerResponse.StreamingData.Format>(
                        { if (it.mimeType.contains("audio/mp4", ignoreCase = true)) 0 else 1 },
                        { it.bitrate },
                    ),
                )
                .take(3)
                .forEach { fmt ->
                    fmt.url?.let { out.add(StreamCandidate(url = it, headers = headers, extensionHint = if (fmt.mimeType.contains("mp4")) ".m4a" else ".webm")) }
                }
            if (out.isNotEmpty()) break

            streaming.formats
                .orEmpty()
                .filter { !it.url.isNullOrBlank() }
                .sortedBy { it.bitrate }
                .take(2)
                .forEach { fmt ->
                    fmt.url?.let { out.add(StreamCandidate(url = it, headers = headers, extensionHint = ".mp4")) }
                }
            if (out.isNotEmpty()) break
        }
        return out
    }

    private data class PageParseResult(
        val cleanMusic: ResolvedVideoMusic?,
        val streams: List<StreamCandidate>,
    )

    private data class StreamCandidate(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val extensionHint: String = ".mp4",
    )

    private data class FetchedPage(
        val finalUrl: String,
        val body: String,
    )

    private fun parseTikTokPage(html: String): PageParseResult {
        if (html.isBlank()) return PageParseResult(null, emptyList())
        val streams = mutableListOf<StreamCandidate>()
        val tikTokHeaders = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://www.tiktok.com/",
            "Origin" to "https://www.tiktok.com",
        )

        val rehydrationJson = extractScriptJsonById(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")
            ?: extractScriptJsonById(html, "SIGI_STATE")
            ?: extractScriptJsonById(html, "front-data")

        if (!rehydrationJson.isNullOrBlank()) {
            val root = runCatching { JSONObject(rehydrationJson) }.getOrNull()
            if (root != null) {
                val musicObj = findNamedJsonObject(root, "music")
                    ?: findNamedJsonObject(root, "musicInfo")
                    ?: findFirstJsonObjectWithKeys(root, setOf("title", "authorName"))
                val videoObj = findNamedJsonObject(root, "video")

                val rawTitle = musicObj?.optString("title")?.trim().orEmpty()
                val rawAuthor = musicObj?.optString("authorName")?.trim()
                    ?.ifBlank { musicObj.optString("author").trim() }
                    .orEmpty()
                val isOriginalFlag = musicObj?.optBoolean("original", false) ?: false

                // Lấy URL âm thanh music.playUrl trước (tải nhanh & không bị kẹt moov atom)
                musicObj?.optString("playUrl")?.takeIf { it.startsWith("http") }?.let { playUrl ->
                    streams.add(StreamCandidate(url = playUrl, headers = tikTokHeaders, extensionHint = ".mp3"))
                }
                if (videoObj != null) {
                    collectTikTokVideoUrls(videoObj).forEach { vUrl ->
                        streams.add(StreamCandidate(url = vUrl, headers = tikTokHeaders, extensionHint = ".mp4"))
                    }
                }

                if (rawTitle.isNotBlank() && !isGenericSoundLabel(rawTitle, rawAuthor)) {
                    val cleanedArtist = if (isGenericSoundLabel(rawAuthor)) "" else rawAuthor
                    // Nếu đánh dấu original=true mà title có chứa tên tác giả kiểu "nhạc của X" thì bỏ qua để Shazam kiểm tra
                    val looksLikeGenericAuthorTag = isOriginalFlag && cleanedArtist.isBlank()
                    if (!looksLikeGenericAuthorTag) {
                        return PageParseResult(
                            cleanMusic = ResolvedVideoMusic(
                                title = rawTitle,
                                artist = cleanedArtist,
                                sourceLabel = VideoPlatform.TIKTOK.label,
                                resolvedByShazam = false,
                            ),
                            streams = streams,
                        )
                    }
                }
            }
        }

        // Bổ sung regex dự phòng khi TikTok nhúng JSON kiểu escape trong HTML
        if (streams.isEmpty()) {
            val regexPlayAddr = Regex("""["'](?:playAddr|downloadAddr)["']\s*:\s*["'](https?:[^"']+)["']""")
            regexPlayAddr.findAll(html).forEach { m ->
                val url = unescapeJsonUrl(m.groupValues[1])
                if (url.startsWith("http")) {
                    streams.add(StreamCandidate(url = url, headers = tikTokHeaders, extensionHint = ".mp4"))
                }
            }
            val urlListRegex = Regex("""["']UrlList["']\s*:\s*\[\s*["'](https?:[^"']+)["']""")
            urlListRegex.findAll(html).forEach { m ->
                val url = unescapeJsonUrl(m.groupValues[1])
                if (url.startsWith("http")) {
                    streams.add(StreamCandidate(url = url, headers = tikTokHeaders, extensionHint = ".mp4"))
                }
            }
        }

        // Meta music:song / og:audio:title nếu trang có gắn thẻ bài hát sạch
        val metaSong = extractMetaContent(html, "music:song")
            ?: extractMetaContent(html, "og:audio:title")
        val metaArtist = extractMetaContent(html, "music:musician")
            ?: extractMetaContent(html, "og:audio:artist")
            .orEmpty()
        if (!metaSong.isNullOrBlank() && !isGenericSoundLabel(metaSong, metaArtist)) {
            return PageParseResult(
                cleanMusic = ResolvedVideoMusic(
                    title = metaSong,
                    artist = if (isGenericSoundLabel(metaArtist)) "" else metaArtist,
                    sourceLabel = VideoPlatform.TIKTOK.label,
                    resolvedByShazam = false,
                ),
                streams = streams,
            )
        }

        return PageParseResult(null, streams)
    }

    private fun resolveTikTokOembedOrOg(
        client: OkHttpClient,
        finalUrl: String,
        html: String,
    ): ResolvedVideoMusic? {
        val oembedUrl = "https://www.tiktok.com/oembed?url=${URLEncoder.encode(finalUrl, "UTF-8")}"
        val oembedBody = fetchPage(client, oembedUrl, DESKTOP_USER_AGENT)?.body
        if (!oembedBody.isNullOrBlank()) {
            val obj = runCatching { JSONObject(oembedBody) }.getOrNull()
            val musicTitle = obj?.optString("music_title")?.trim().orEmpty()
            val musicArtist = obj?.optString("music_author_name")?.trim().orEmpty()
            if (musicTitle.isNotBlank() && !isGenericSoundLabel(musicTitle, musicArtist)) {
                return ResolvedVideoMusic(
                    title = musicTitle,
                    artist = if (isGenericSoundLabel(musicArtist)) "" else musicArtist,
                    sourceLabel = VideoPlatform.TIKTOK.label,
                    resolvedByShazam = false,
                )
            }
        }
        val ogTitle = extractMetaContent(html, "og:title") ?: return null
        val parsed = extractArtistDashTitle(ogTitle) ?: return null
        if (!isGenericSoundLabel(parsed.second, parsed.first)) {
            return ResolvedVideoMusic(
                title = parsed.second,
                artist = parsed.first,
                sourceLabel = VideoPlatform.TIKTOK.label,
                resolvedByShazam = false,
            )
        }
        return null
    }

    private fun parseInstagramPage(html: String): PageParseResult {
        if (html.isBlank()) return PageParseResult(null, emptyList())
        val igHeaders = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://www.instagram.com/",
        )
        val streams = mutableListOf<StreamCandidate>()

        val songMatch = Regex(""""(?:song_name|music_title)"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unescapeJsonString)
        val artistMatch = Regex(""""(?:artist_name|display_artist)"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unescapeJsonString)
        if (!songMatch.isNullOrBlank() && !isGenericSoundLabel(songMatch, artistMatch.orEmpty())) {
            return PageParseResult(
                cleanMusic = ResolvedVideoMusic(
                    title = songMatch,
                    artist = artistMatch.orEmpty().takeUnless { isGenericSoundLabel(it) }.orEmpty(),
                    sourceLabel = VideoPlatform.INSTAGRAM_REELS.label,
                    resolvedByShazam = false,
                ),
                streams = emptyList(),
            )
        }

        Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""").findAll(html).forEach { m ->
            streams.add(StreamCandidate(unescapeJsonUrl(m.groupValues[1]), igHeaders, ".mp4"))
        }
        Regex(""""video_versions"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"(https?:[^"]+)"""").findAll(html).forEach { m ->
            streams.add(StreamCandidate(unescapeJsonUrl(m.groupValues[1]), igHeaders, ".mp4"))
        }
        (extractMetaContent(html, "og:video:secure_url") ?: extractMetaContent(html, "og:video"))
            ?.takeIf { it.startsWith("http") }
            ?.let { streams.add(StreamCandidate(it, igHeaders, ".mp4")) }

        return PageParseResult(null, streams)
    }

    private fun parseFacebookPage(html: String): PageParseResult {
        if (html.isBlank()) return PageParseResult(null, emptyList())
        val fbHeaders = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://www.facebook.com/",
        )
        val streams = mutableListOf<StreamCandidate>()

        val trackTitle = Regex(""""track_title"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unescapeJsonString)
        val artistName = Regex(""""(?:artist_name|music_artist_name)"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unescapeJsonString)
        if (!trackTitle.isNullOrBlank() && !isGenericSoundLabel(trackTitle, artistName.orEmpty())) {
            return PageParseResult(
                cleanMusic = ResolvedVideoMusic(
                    title = trackTitle,
                    artist = artistName.orEmpty().takeUnless { isGenericSoundLabel(it) }.orEmpty(),
                    sourceLabel = VideoPlatform.FACEBOOK_REELS.label,
                    resolvedByShazam = false,
                ),
                streams = emptyList(),
            )
        }

        val urlKeys = listOf(
            "playable_url_quality_hd",
            "playable_url",
            "browser_native_hd_url",
            "browser_native_sd_url",
        )
        for (key in urlKeys) {
            Regex(""""$key"\s*:\s*"(https?:[^"]+)"""").findAll(html).forEach { m ->
                streams.add(StreamCandidate(unescapeJsonUrl(m.groupValues[1]), fbHeaders, ".mp4"))
            }
        }
        (extractMetaContent(html, "og:video:secure_url") ?: extractMetaContent(html, "og:video"))
            ?.takeIf { it.startsWith("http") }
            ?.let { streams.add(StreamCandidate(it, fbHeaders, ".mp4")) }

        return PageParseResult(null, streams)
    }

    private fun parseYouTubeShortsMusicFromHtml(html: String): ResolvedVideoMusic? {
        if (html.isBlank()) return null
        val patterns = listOf(
            Regex(""""soundMetadata"\s*:\s*\{[^}]*"title"\s*:\s*"([^"]+)"[^}]*"subtitle"\s*:\s*"([^"]*)""""),
            Regex(""""videoAttributeViewModel"\s*:\s*\{[^}]*"title"\s*:\s*"([^"]+)"[^}]*"subtitle"\s*:\s*"([^"]*)""""),
        )
        for (pattern in patterns) {
            val m = pattern.find(html) ?: continue
            val soundTitle = unescapeJsonString(m.groupValues[1]).trim()
            val soundArtist = unescapeJsonString(m.groupValues[2]).trim()
            if (soundTitle.isNotBlank() && !isGenericSoundLabel(soundTitle, soundArtist)) {
                return ResolvedVideoMusic(
                    title = soundTitle,
                    artist = if (isGenericSoundLabel(soundArtist)) "" else soundArtist,
                    sourceLabel = VideoPlatform.YOUTUBE_SHORTS.label,
                    resolvedByShazam = false,
                )
            }
        }
        return null
    }

    private suspend fun recognizeFromStreamCandidates(
        context: Context,
        client: OkHttpClient,
        candidates: List<StreamCandidate>,
        sourceLabel: String,
    ): ResolvedVideoMusic? {
        for (candidate in candidates.distinctBy { it.url }) {
            coroutineContext.ensureActive()
            val tempFile = File.createTempFile("mochy_shazam_", candidate.extensionHint, context.cacheDir)
            try {
                val downloaded = downloadStreamPrefix(client, candidate, tempFile)
                if (!downloaded || tempFile.length() < 2048L) continue

                val decoded = decodeFirstSecondsToMonoPcm16(tempFile) ?: continue
                if (decoded.pcm.size < decoded.sampleRate) continue

                val status = MusicRecognitionService.recognizePcmBytes(
                    pcmData = decoded.pcm,
                    sampleRate = decoded.sampleRate,
                    channelCount = 1,
                )
                if (status is RecognitionStatus.Success) {
                    val result = status.result
                    if (result.title.isNotBlank()) {
                        return ResolvedVideoMusic(
                            title = result.title,
                            artist = result.artist,
                            sourceLabel = sourceLabel,
                            resolvedByShazam = true,
                            youtubeVideoId = result.youtubeVideoId,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Stream candidate failed: ${candidate.url.take(80)}")
            } finally {
                runCatching { tempFile.delete() }
            }
        }
        return null
    }

    private fun downloadStreamPrefix(
        client: OkHttpClient,
        candidate: StreamCandidate,
        destFile: File,
    ): Boolean {
        val reqBuilder = Request.Builder()
            .url(candidate.url)
            .header("Accept", "*/*")
            .header("Range", "bytes=0-${MAX_DOWNLOAD_BYTES - 1}")
        for ((k, v) in candidate.headers) {
            reqBuilder.header(k, v)
        }
        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return false
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                return false
            }
            val body = response.body ?: return false
            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (total < MAX_DOWNLOAD_BYTES) {
                        val toRead = minOf(buf.size.toLong(), MAX_DOWNLOAD_BYTES - total).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        total += n
                    }
                    return total > 2048L
                }
            }
        }
    }

    private class DecodedMonoPcm(
        val pcm: ByteArray,
        val sampleRate: Int,
    )

    private suspend fun decodeFirstSecondsToMonoPcm16(mediaFile: File): DecodedMonoPcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(mediaFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val startedAtMs = System.currentTimeMillis()
            val maxDecodedBytes = (sampleRate.toLong() * channelCount * 2L * 11L).toInt()

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (System.currentTimeMillis() - startedAtMs > CODEC_DECODE_TIMEOUT_MS) break

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleSize < 0 || sampleTimeUs > MAX_DECODE_DURATION_US) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val rawChunk = ByteArray(bufferInfo.size)
                            outBuf.get(rawChunk)
                            val pcm16Chunk = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                floatPcmTo16Bit(rawChunk)
                            } else {
                                rawChunk
                            }
                            pcmOut.write(pcm16Chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            pcmOut.size() >= maxDecodedBytes
                        ) {
                            outputDone = true
                        }
                    }
                }
            }

            val rawPcm = pcmOut.toByteArray()
            if (rawPcm.size < 2 || sampleRate <= 0 || channelCount <= 0) return null
            val mono = if (channelCount > 1) downmixToMono16Bit(rawPcm, channelCount) else rawPcm
            return DecodedMonoPcm(mono, sampleRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun floatPcmTo16Bit(floatBytes: ByteArray): ByteArray {
        val sampleCount = floatBytes.size / 4
        if (sampleCount <= 0) return ByteArray(0)
        val fb = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val s = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt()
            val idx = i * 2
            out[idx] = (s and 0xFF).toByte()
            out[idx + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun downmixToMono16Bit(pcmData: ByteArray, channelCount: Int): ByteArray {
        val bytesPerFrame = channelCount * 2
        val frameCount = pcmData.size / bytesPerFrame
        if (frameCount <= 0) return ByteArray(0)
        val out = ByteArray(frameCount * 2)
        for (frame in 0 until frameCount) {
            var sum = 0
            val frameBase = frame * bytesPerFrame
            for (ch in 0 until channelCount) {
                val idx = frameBase + ch * 2
                val lo = pcmData[idx].toInt() and 0xFF
                val hi = pcmData[idx + 1].toInt()
                sum += (hi shl 8) or lo
            }
            val mono = (sum / channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val outIdx = frame * 2
            out[outIdx] = (mono and 0xFF).toByte()
            out[outIdx + 1] = ((mono shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun fetchPage(client: OkHttpClient, url: String, userAgent: String): FetchedPage? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val body = resp.body?.string().orEmpty()
                FetchedPage(finalUrl = finalUrl, body = body)
            }
        }.getOrNull()
    }

    private fun collectTikTokVideoUrls(videoObj: JSONObject): List<String> {
        val urls = LinkedHashSet<String>()
        videoObj.optString("playAddr").takeIf { it.startsWith("http") }?.let(urls::add)
        videoObj.optJSONObject("playAddr")?.optJSONArray("UrlList")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.startsWith("http") }?.let(urls::add)
            }
        }
        videoObj.optJSONObject("PlayAddrStruct")?.optJSONArray("UrlList")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.startsWith("http") }?.let(urls::add)
            }
        }
        videoObj.optJSONArray("bitrateInfo")?.let { arr ->
            for (i in 0 until arr.length()) {
                val info = arr.optJSONObject(i) ?: continue
                val urlList = info.optJSONObject("PlayAddr")?.optJSONArray("UrlList") ?: continue
                for (j in 0 until urlList.length()) {
                    urlList.optString(j).takeIf { it.startsWith("http") }?.let(urls::add)
                }
            }
        }
        videoObj.optString("downloadAddr").takeIf { it.startsWith("http") }?.let(urls::add)
        return urls.toList()
    }

    private fun extractScriptJsonById(html: String, id: String): String? {
        val regex = Regex(
            """<script[^>]*\bid=["']${Regex.escape(id)}["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        return regex.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.startsWith("{") }
    }

    private fun findNamedJsonObject(root: Any?, targetKey: String, depth: Int = 0): JSONObject? {
        if (depth > 8 || root == null) return null
        when (root) {
            is JSONObject -> {
                root.optJSONObject(targetKey)?.let { return it }
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findNamedJsonObject(root.opt(key), targetKey, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    findNamedJsonObject(root.opt(i), targetKey, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findFirstJsonObjectWithKeys(root: Any?, requiredKeys: Set<String>, depth: Int = 0): JSONObject? {
        if (depth > 8 || root == null) return null
        when (root) {
            is JSONObject -> {
                if (requiredKeys.all { root.has(it) && root.optString(it).isNotBlank() }) {
                    return root
                }
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findFirstJsonObjectWithKeys(root.opt(key), requiredKeys, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    findFirstJsonObjectWithKeys(root.opt(i), requiredKeys, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractMetaContent(html: String, attrValue: String): String? {
        val metaRegex = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        val keyRegex = Regex("""\b(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val contentRegex = Regex("""\bcontent\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        for (tag in metaRegex.findAll(html)) {
            val key = keyRegex.find(tag.value)?.groupValues?.get(1) ?: continue
            if (!key.equals(attrValue, ignoreCase = true)) continue
            val contentMatch = contentRegex.find(tag.value) ?: continue
            val raw = contentMatch.groupValues[1].ifEmpty { contentMatch.groupValues[2] }
            val decoded = unescapeHtml(raw).trim()
            if (decoded.isNotEmpty()) return decoded
        }
        return null
    }

    private fun extractTikTokVideoId(url: String): String? =
        Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)

    private fun extractYouTubeShortsId(url: String): String? =
        Regex("""/shorts/([A-Za-z0-9_-]{6,})""").find(url)?.groupValues?.get(1)

    private fun extractArtistDashTitle(raw: String): Pair<String, String>? {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return null
        val parts = cleaned.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (!isGenericSoundLabel(left) && !isGenericSoundLabel(right)) {
                return left to right
            }
        }
        return null
    }

    private fun isGenericSoundLabel(label: String, extraContext: String = ""): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return true
        val norm = normalizeNoDiacritics(trimmed)
        if (norm in PLATFORM_JUNK_TITLES || norm in GENERIC_EXACT_LABELS) return true
        if (GENERIC_PREFIXES.any { norm.startsWith(it) }) return true
        if (extraContext.isNotBlank() && norm == "nhac nen - ${normalizeNoDiacritics(extraContext)}") return true
        return false
    }

    private fun normalizeNoDiacritics(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun unescapeJsonUrl(raw: String): String =
        unescapeJsonString(raw).replace("&amp;", "&")

    private fun unescapeJsonString(raw: String): String {
        var out = raw
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u0025", "%", ignoreCase = true)
            .replace("\\\"", "\"")
        out = Regex("""\\u([0-9a-fA-F]{4})""").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { code -> runCatching { code.toChar().toString() }.getOrNull() } ?: m.value
        }
        return out
    }

    private fun unescapeHtml(input: String): String {
        var out = input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        out = Regex("""&#(\d+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { code -> runCatching { code.toChar().toString() }.getOrNull() } ?: m.value
        }
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { code -> runCatching { code.toChar().toString() }.getOrNull() } ?: m.value
        }
        return out
    }

    private class InMemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = store[host].orEmpty().associateBy { "${it.name}|${it.path}" }.toMutableMap()
            for (cookie in cookies) {
                existing["${cookie.name}|${cookie.path}"] = cookie
            }
            store[host] = existing.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val out = mutableListOf<Cookie>()
            for ((_, cookies) in store) {
                for (cookie in cookies) {
                    if (cookie.expiresAt > now && cookie.matches(url)) {
                        out.add(cookie)
                    }
                }
            }
            return out
        }
    }
}
