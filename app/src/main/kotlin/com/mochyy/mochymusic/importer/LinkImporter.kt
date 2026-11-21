// LinkImporter.kt

package com.mochyy.mochymusic.importer

import com.mochyy.mochymusic.utils.AppProxy
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.utils.completed
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

enum class LinkSource(val label: String, val example: String) {
    SPOTIFY("Spotify", "https://open.spotify.com/playlist/…"),
    APPLE_MUSIC("Apple Music", "https://music.apple.com/us/playlist/…"),
    DEEZER("Deezer", "https://www.deezer.com/playlist/…"),
    YOUTUBE("YouTube Music", "https://music.youtube.com/playlist?list=…"),
    SOUNDCLOUD("SoundCloud", "https://soundcloud.com/…/sets/…"),
    ZING_MP3("Zing MP3", "https://zingmp3.vn/album/…"),
    NHACCUATUI("NhacCuaTui", "https://www.nhaccuatui.com/playlist/…"),
}

data class SharedTrackInfo(
    val title: String,
    val artist: String,
) {
    val query: String get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
}

class LinkImportException(message: String) : Exception(message)

/** reads a public playlist or album from a share link without logging in, private playlists aren't reachable so those go through tunemymusic */
object LinkImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            expectSuccess = false
            engine {
                preconfigured = AppProxy.sharedClient
            }
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val SPOTIFY_REGEX = Regex("(?:open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{22})")
    private val SPOTIFY_ANY_REGEX = Regex("(?:open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?|spotify:)(track|playlist|album)[/:]([A-Za-z0-9]{22})")
    private val SPOTIFY_TRACK_REGEX = Regex("(?:open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?|spotify:)track[/:]([A-Za-z0-9]{22})")
    private val APPLE_REGEX = Regex("music\\.apple\\.com/[a-z]{2}/(playlist|album)/(?:[^/?#]+/)?(pl\\.[A-Za-z0-9-]+|\\d+)")
    private val APPLE_TRACK_ID_REGEX = Regex("[?&]i=(\\d+)|music\\.apple\\.com/(?:[a-z]{2}/)?song/(?:[^/?#]+/)?(\\d+)")
    private val DEEZER_REGEX = Regex("deezer\\.com/(?:[a-z]{2}/)?(playlist|album)/(\\d+)")
    private val DEEZER_ANY_REGEX = Regex("deezer\\.com/(?:[a-z]{2}/)?(track|playlist|album)/(\\d+)")
    private val DEEZER_TRACK_REGEX = Regex("deezer\\.com/(?:[a-z]{2}/)?track/(\\d+)")
    private val YT_LIST_REGEX = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val YT_BROWSE_REGEX = Regex("music\\.youtube\\.com/(?:browse|playlist)/((?:VL|MPREb_)?[A-Za-z0-9_-]+)")
    private val SOUNDCLOUD_REGEX = Regex("https?://(?:www\\.|m\\.)?soundcloud\\.com/[A-Za-z0-9_.-]+/(?:sets/)?[A-Za-z0-9_.-]+")
    private val SOUNDCLOUD_TRACK_REGEX = Regex("^https?://(?:www\\.|m\\.)?soundcloud\\.com/([^/?#]+)/([^/?#]+)")
    private val ZING_REGEX = Regex("https?://(?:www\\.|m\\.)?(?:zingmp3\\.vn|mp3\\.zing\\.vn)/([^?#]+)")
    private val NCT_REGEX = Regex("https?://(?:www\\.|m\\.)?(?:nhaccuatui\\.com|nct\\.vn)/([^?#]+)")
    private val SHORT_LINK_HOSTS = listOf("spotify.link", "spotify.app.link", "deezer.page.link", "link.deezer.com", "on.soundcloud.com", "soundcloud.app.goo.gl")

    @Volatile
    private var cachedScClientId: String? = null

    /** works out which service a pasted link belongs to, or null if it isn't one we read */
    fun detect(link: String): LinkSource? {
        val text = link.trim().lowercase()
        return when {
            text.contains("spotify") -> LinkSource.SPOTIFY
            text.contains("music.apple.com") -> LinkSource.APPLE_MUSIC
            text.contains("deezer") -> LinkSource.DEEZER
            text.contains("youtube.com") || text.contains("youtu.be") -> LinkSource.YOUTUBE
            text.contains("soundcloud.com") || text.contains("on.soundcloud.com") || text.contains("soundcloud.app.goo.gl") -> LinkSource.SOUNDCLOUD
            text.contains("zingmp3.vn") || text.contains("mp3.zing.vn") -> LinkSource.ZING_MP3
            text.contains("nhaccuatui.com") || text.contains("nct.vn") -> LinkSource.NHACCUATUI
            else -> null
        }
    }

    suspend fun fetch(link: String): ParsedImport {
        val text = extractUrl(link.trim()) ?: throw LinkImportException("Liên kết không hợp lệ.")
        val resolved = resolveShareLink(text)
        return when (detect(resolved) ?: detect(text)) {
            LinkSource.SPOTIFY -> fetchSpotify(resolved)
            LinkSource.APPLE_MUSIC -> fetchApple(resolved)
            LinkSource.DEEZER -> fetchDeezer(resolved)
            LinkSource.YOUTUBE -> fetchYouTube(resolved)
            LinkSource.SOUNDCLOUD -> fetchSoundCloud(resolved)
            LinkSource.ZING_MP3 -> fetchZingMp3(resolved)
            LinkSource.NHACCUATUI -> fetchNhacCuaTui(resolved)
            null -> throw LinkImportException("Hãy dán liên kết Spotify, Apple Music, Deezer, SoundCloud, Zing MP3, NhacCuaTui hoặc YouTube Music.")
        }
    }

    /** share sheets often paste "check out this playlist! https://…" — take just the url */
    fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return Regex("""(?:https?://\S+|spotify:\S+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.value
            ?.trimEnd('.', ',', ')', '"', '\'')
    }

    fun isShortLink(url: String): Boolean {
        val lower = url.lowercase()
        return SHORT_LINK_HOSTS.any { lower.contains(it) }
    }

    suspend fun resolveShareLink(url: String): String =
        if (isShortLink(url)) resolveShortLink(url) else url

    /** short links redirect to the real page; some go through an html page that holds the real url */
    private suspend fun resolveShortLink(url: String): String {
        val response = get(url)
        val finalUrl = response.call.request.url.toString()
        if (listOf(SPOTIFY_ANY_REGEX, DEEZER_ANY_REGEX, APPLE_REGEX, APPLE_TRACK_ID_REGEX, YT_LIST_REGEX, SOUNDCLOUD_REGEX, ZING_REGEX, NCT_REGEX).any { it.containsMatchIn(finalUrl) }) {
            return finalUrl
        }
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        SPOTIFY_ANY_REGEX.find(body)?.let { return "https://open.spotify.com/${it.groupValues[1]}/${it.groupValues[2]}" }
        DEEZER_ANY_REGEX.find(body)?.let { return "https://www.deezer.com/${it.groupValues[1]}/${it.groupValues[2]}" }
        SOUNDCLOUD_REGEX.find(body)?.let { return it.value }
        extractMetaContent(body, "og:url")?.let { og ->
            if (detect(og) != null) return og
        }
        return finalUrl
    }

    fun isSingleTrackUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (SPOTIFY_TRACK_REGEX.containsMatchIn(lower)) return true
        if (DEEZER_TRACK_REGEX.containsMatchIn(lower)) return true
        if (lower.contains("music.apple.com/") && (lower.contains("/song/") || Regex("""[?&]i=\d+""").containsMatchIn(lower))) return true
        if ((lower.contains("zingmp3.vn/") || lower.contains("mp3.zing.vn/")) && lower.contains("/bai-hat/")) return true
        if ((lower.contains("nhaccuatui.com/") || lower.contains("nct.vn/")) && (lower.contains("/bai-hat/") || lower.contains("/song/"))) return true
        if (lower.contains("soundcloud.com/") && !lower.contains("/sets/")) {
            val match = SOUNDCLOUD_TRACK_REGEX.find(lower.substringBefore('?').substringBefore('#').trimEnd('/')) ?: return false
            val user = match.groupValues[1]
            val slug = match.groupValues[2]
            val reservedUsers = setOf("discover", "charts", "search", "you", "pages", "stations", "stream", "upload", "pro")
            val reservedSlugs = setOf("sets", "likes", "tracks", "albums", "reposts", "followers", "following", "comments")
            return user !in reservedUsers && slug !in reservedSlugs
        }
        return false
    }

    private suspend fun get(url: String): HttpResponse = try {
        client.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "en-US,en;q=0.9")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw LinkImportException("Couldn't reach the server — check your connection.")
    }

    private suspend fun getOk(url: String, what: String): String {
        val response = get(url)
        when (response.status.value) {
            in 200..299 -> return response.bodyAsText()
            404 -> throw LinkImportException("$what wasn't found. Is it public?")
            429 -> throw LinkImportException("$what is rate-limiting us. Try again in a minute.")
            else -> throw LinkImportException("$what answered with an error (${response.status.value}).")
        }
    }

    // spotify: the embed player page carries the track list for logged-out visitors

    private suspend fun fetchSpotify(url: String): ParsedImport {
        val match = SPOTIFY_REGEX.find(url)
            ?: throw LinkImportException("Paste a Spotify playlist or album link.")
        val (type, id) = match.destructured
        return parseSpotifyEmbed(getOk("https://open.spotify.com/embed/$type/$id", "Spotify"), type)
            .from(PlaylistOrigin(remoteId = id, remoteUrl = "https://open.spotify.com/$type/$id"))
    }

    internal fun parseSpotifyEmbed(html: String, type: String): ParsedImport {
        val data = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw LinkImportException("Spotify changed its page — use TuneMyMusic for now.")
        val entity = json.parseToJsonElement(data)
            .path("props", "pageProps", "state", "data", "entity") as? JsonObject
            ?: throw LinkImportException("That Spotify $type is private or doesn't exist.")

        val name = entity.string("name") ?: entity.string("title") ?: "Spotify $type"
        val albumName = if (type == "album") name else null
        val tracks = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.string("title") ?: return@mapNotNull null
            ImportedTrack(
                title = title,
                artist = obj.string("subtitle").orEmpty().replace(' ', ' '),
                album = albumName,
                durationMs = obj.long("duration"),
                sourceId = obj.string("uri")?.takeIf { it.startsWith("spotify:track:") },
            )
        }
        if (tracks.isEmpty()) throw LinkImportException("That Spotify $type has no songs we can read.")

        val warnings = buildList {
            if (type == "playlist" && tracks.size >= SPOTIFY_EMBED_LIMIT) {
                add("Spotify only shares the first $SPOTIFY_EMBED_LIMIT songs of a playlist through links. For the whole playlist, use the TuneMyMusic CSV option.")
            }
        }
        val cover = (entity.path("coverArt", "sources") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .maxByOrNull { it.long("width") ?: 0 }
            ?.string("url")
        return ParsedImport(
            likedSongs = emptyList(),
            playlists = mapOf(name to tracks),
            warnings = warnings,
            provider = ImportSource.SPOTIFY,
            playlistCovers = coverFor(name, cover),
        )
    }

    internal const val SPOTIFY_EMBED_LIMIT = 100

    // apple music: the web page embeds its own data as serialized-server-data

    private suspend fun fetchApple(url: String): ParsedImport {
        val match = APPLE_REGEX.find(url)
            ?: throw LinkImportException("Paste an Apple Music playlist or album link.")
        val type = match.groupValues[1]
        val page = url.substringBefore('#')
        return parseApplePage(getOk(page, "Apple Music"), type)
            .from(PlaylistOrigin(remoteId = match.groupValues[2], remoteUrl = page))
    }

    internal fun parseApplePage(html: String, type: String): ParsedImport {
        val data = Regex("<script[^>]*id=\"serialized-server-data\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw LinkImportException("Apple Music changed its page — use TuneMyMusic for now.")
        val root = json.parseToJsonElement(data)
        val page = (root.path("data") as? JsonArray)?.firstOrNull()?.path("data")
            ?: (root as? JsonArray)?.firstOrNull()?.path("data")
            ?: throw LinkImportException("That Apple Music $type is private or doesn't exist.")
        val sections = (page.path("sections") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        val header = sections.firstOrNull { it.string("itemKind") == "containerDetailHeaderLockup" }
            ?.let { (it["items"] as? JsonArray)?.firstOrNull() as? JsonObject }
        val name = header?.string("title") ?: "Apple Music $type"
        val albumArtist = header?.let { (it["subtitleLinks"] as? JsonArray)?.firstOrNull()?.path("title") as? JsonPrimitive }?.contentOrNull
        val declaredCount = header?.long("trackCount")?.toInt()

        val tracks = sections.filter { it.string("itemKind") == "trackLockup" }
            .flatMap { (it["items"] as? JsonArray).orEmpty() }
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val title = obj.string("title") ?: return@mapNotNull null
                val artists = (obj["subtitleLinks"] as? JsonArray).orEmpty()
                    .mapNotNull { (it.path("title") as? JsonPrimitive)?.contentOrNull }
                ImportedTrack(
                    title = title,
                    artist = obj.string("artistName") ?: artists.joinToString(", ").ifEmpty { albumArtist.orEmpty() },
                    album = if (type == "album") name
                    else (obj["tertiaryLinks"] as? JsonArray)?.firstOrNull()?.let { (it.path("title") as? JsonPrimitive)?.contentOrNull },
                    durationMs = obj.long("duration"),
                )
            }
        if (tracks.isEmpty()) throw LinkImportException("That Apple Music $type has no songs we can read.")

        val warnings = buildList {
            if (declaredCount != null && declaredCount > tracks.size) {
                add("Apple Music's page only listed ${tracks.size} of $declaredCount songs. For the whole playlist, use the TuneMyMusic CSV option.")
            }
        }
        // a template: {w}x{h}, and {c}/{f} for crop and format
        val cover = (header?.path("artwork", "dictionary", "url") as? JsonPrimitive)?.contentOrNull
            ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{c}", "bb")?.replace("{f}", "jpg")
        return ParsedImport(
            likedSongs = emptyList(),
            playlists = mapOf(name to tracks),
            warnings = warnings,
            provider = ImportSource.APPLE_MUSIC,
            playlistCovers = coverFor(name, cover),
        )
    }

    // deezer: public api, no key needed

    private suspend fun fetchDeezer(url: String): ParsedImport {
        val match = DEEZER_REGEX.find(url)
            ?: throw LinkImportException("Paste a Deezer playlist or album link.")
        val (type, id) = match.destructured
        val info = deezerJson("https://api.deezer.com/$type/$id")
        val name = info.string("title") ?: "Deezer $type"
        val albumName = if (type == "album") name else null

        val tracks = mutableListOf<ImportedTrack>()
        var next: String? = "https://api.deezer.com/$type/$id/tracks?limit=100"
        var pages = 0
        while (next != null && pages < 100) {
            val page = deezerJson(next)
            (page["data"] as? JsonArray).orEmpty().forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val title = obj.string("title") ?: return@forEach
                tracks += ImportedTrack(
                    title = title,
                    artist = obj["artist"]?.let { (it.path("name") as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    album = albumName ?: obj["album"]?.let { (it.path("title") as? JsonPrimitive)?.contentOrNull },
                    durationMs = obj.long("duration")?.times(1000),
                )
            }
            next = page.string("next")?.takeIf { it.startsWith("https://api.deezer.com/") }
            pages++
        }
        if (tracks.isEmpty()) throw LinkImportException("That Deezer $type has no songs we can read.")
        val cover = info.string("picture_xl") ?: info.string("cover_xl")
        return ParsedImport(likedSongs = emptyList(), playlists = mapOf(name to tracks), provider = ImportSource.DEEZER, playlistCovers = coverFor(name, cover))
            .from(PlaylistOrigin(remoteId = id, remoteUrl = "https://www.deezer.com/$type/$id"))
    }

    private suspend fun deezerJson(url: String): JsonObject {
        val obj = json.parseToJsonElement(getOk(url, "Deezer")) as? JsonObject
            ?: throw LinkImportException("Deezer sent something we couldn't read.")
        obj["error"]?.let { error ->
            val message = (error.path("message") as? JsonPrimitive)?.contentOrNull
            throw LinkImportException(if (message?.contains("no data", true) == true) "That Deezer playlist is private or doesn't exist." else "Deezer: ${message ?: "unknown error"}")
        }
        return obj
    }

    // youtube / youtube music: our own client, ids are exact so nothing needs matching

    private suspend fun fetchYouTube(url: String): ParsedImport {
        val browseId = YT_BROWSE_REGEX.find(url)?.groupValues?.get(1)
        val listId = YT_LIST_REGEX.find(url)?.groupValues?.get(1)
        return when {
            browseId != null && browseId.startsWith("MPREb_") -> fetchYouTubeAlbum(browseId)
            listId != null -> fetchYouTubePlaylist(listId)
            browseId != null -> fetchYouTubePlaylist(browseId.removePrefix("VL"))
            else -> throw LinkImportException("Paste a YouTube playlist or album link.")
        }
    }

    suspend fun fetchYouTubePlaylist(playlistId: String): ParsedImport {
        if (playlistId.startsWith("RD")) throw LinkImportException("That's a YouTube mix, which changes every time — save it as a playlist first.")
        val page = YouTube.playlist(playlistId).completed().getOrElse {
            throw LinkImportException("Couldn't open that YouTube playlist. Is it public or unlisted?")
        }
        val tracks = page.songs.map { it.toImportedTrack() }
        if (tracks.isEmpty()) throw LinkImportException("That YouTube playlist is empty.")
        return ParsedImport(
            likedSongs = emptyList(),
            playlists = mapOf(page.playlist.title to tracks),
            provider = ImportSource.YOUTUBE_MUSIC,
            playlistCovers = coverFor(page.playlist.title, page.playlist.thumbnail),
        ).from(PlaylistOrigin(remoteId = playlistId, remoteUrl = "https://music.youtube.com/playlist?list=$playlistId"))
    }

    private suspend fun fetchYouTubeAlbum(browseId: String): ParsedImport {
        val page = YouTube.album(browseId).getOrElse {
            throw LinkImportException("Couldn't open that YouTube Music album.")
        }
        val tracks = page.songs.map { it.toImportedTrack() }
        if (tracks.isEmpty()) throw LinkImportException("That album has no songs.")
        return ParsedImport(
            likedSongs = emptyList(),
            playlists = mapOf(page.album.title to tracks),
            provider = ImportSource.YOUTUBE_MUSIC,
            playlistCovers = coverFor(page.album.title, page.album.thumbnail),
        ).from(PlaylistOrigin(remoteId = browseId, remoteUrl = "https://music.youtube.com/browse/$browseId"))
    }

    private fun coverFor(name: String, cover: String?): Map<String, String> =
        if (cover.isNullOrBlank()) emptyMap() else mapOf(name to cover)

    /** a link reads one playlist, so origin is where that one lives */
    private fun ParsedImport.from(origin: PlaylistOrigin) = copy(playlistOrigins = playlists.keys.associateWith { origin })

    // soundcloud: reads window.__sc_hydration from public playlist/track page, resolves stub track IDs via api-v2

    private suspend fun fetchSoundCloud(url: String): ParsedImport {
        val canonicalUrl = url.replace("://m.soundcloud.com", "://soundcloud.com")
        val response = try {
            client.get(canonicalUrl) {
                header("User-Agent", DESKTOP_USER_AGENT)
                header("Accept-Language", "en-US,en;q=0.9")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LinkImportException("Không thể kết nối SoundCloud — hãy kiểm tra mạng.")
        }
        val html = when (response.status.value) {
            in 200..299 -> response.bodyAsText()
            404 -> throw LinkImportException("Không tìm thấy SoundCloud playlist/bài hát (hoặc đang đặt riêng tư).")
            429 -> throw LinkImportException("SoundCloud đang giới hạn truy cập tạm thời, thử lại sau ít phút.")
            else -> throw LinkImportException("SoundCloud phản hồi lỗi (${response.status.value}).")
        }

        val hydrationJson = extractScHydrationArray(html)
            ?: throw LinkImportException("Không đọc được dữ liệu SoundCloud. Hãy dán link playlist (/sets/...) hoặc bài hát công khai.")
        val items = (json.parseToJsonElement(hydrationJson) as? JsonArray).orEmpty()

        val playlistData = items.firstNotNullOfOrNull { el ->
            val obj = el.obj() ?: return@firstNotNullOfOrNull null
            if (obj.string("hydratable") == "playlist") obj["data"].obj() else null
        }
        val singleTrackData = items.firstNotNullOfOrNull { el ->
            val obj = el.obj() ?: return@firstNotNullOfOrNull null
            if (obj.string("hydratable") == "sound") obj["data"].obj() else null
        }

        if (playlistData == null && singleTrackData != null) {
            val track = parseSoundCloudTrack(singleTrackData)
                ?: throw LinkImportException("Bài hát SoundCloud này không có dữ liệu công khai.")
            val name = track.title
            return ParsedImport(
                likedSongs = emptyList(),
                playlists = mapOf(name to listOf(track)),
                provider = ImportSource.SOUNDCLOUD,
                playlistCovers = coverFor(name, track.thumbnailUrl),
            ).from(PlaylistOrigin(remoteId = singleTrackData.idString("id"), remoteUrl = canonicalUrl))
        }

        val data = playlistData
            ?: throw LinkImportException("Hãy dán link playlist (/sets/...) hoặc bài hát công khai trên SoundCloud.")

        val rawTracks = data["tracks"].array().mapNotNull { it.obj() }
        val resolvedById = mutableMapOf<String, ImportedTrack>()
        val missingIds = mutableListOf<String>()

        for (t in rawTracks) {
            val tid = t.idString("id") ?: continue
            val parsed = parseSoundCloudTrack(t)
            if (parsed != null) {
                resolvedById[tid] = parsed
            } else {
                missingIds += tid
            }
        }

        if (missingIds.isNotEmpty()) {
            resolveSoundCloudTrackStubs(html, missingIds).forEach { (id, tr) ->
                resolvedById[id] = tr
            }
        }

        val orderedTracks = rawTracks.mapNotNull { t -> t.idString("id")?.let(resolvedById::get) }
        if (orderedTracks.isEmpty()) {
            throw LinkImportException("Danh sách phát SoundCloud này trống hoặc ở chế độ riêng tư.")
        }

        val playlistName = data.string("title") ?: "SoundCloud Playlist"
        val coverUrl = data.string("artwork_url")?.replace("-large", "-t500x500")
            ?: orderedTracks.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl

        return ParsedImport(
            likedSongs = emptyList(),
            playlists = mapOf(playlistName to orderedTracks),
            provider = ImportSource.SOUNDCLOUD,
            playlistCovers = coverFor(playlistName, coverUrl),
        ).from(PlaylistOrigin(remoteId = data.idString("id"), remoteUrl = data.string("permalink_url") ?: canonicalUrl))
    }

    private fun parseSoundCloudTrack(obj: JsonObject): ImportedTrack? {
        val rawTitle = obj.string("title") ?: return null
        val uploader = obj.path("user").obj()?.string("username").orEmpty()
        val (artist, title) = splitSoundCloudArtistTitle(rawTitle, uploader)
        val durationMs = obj.long("duration")?.takeIf { it > 0L }
        val artwork = obj.string("artwork_url")?.replace("-large", "-t500x500")
            ?: obj.path("user").obj()?.string("avatar_url")?.replace("-large", "-t500x500")
        val id = obj.idString("id")
        return ImportedTrack(
            title = title,
            artist = artist,
            album = null,
            durationMs = durationMs,
            thumbnailUrl = artwork,
            sourceId = id?.let { "soundcloud:track:$it" },
        )
    }

    private fun splitSoundCloudArtistTitle(rawTitle: String, uploader: String): Pair<String, String> {
        val parts = rawTitle.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        return if (parts.size == 2 && parts[0].length in 2..45 && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            uploader.ifBlank { "SoundCloud" } to rawTitle.trim()
        }
    }

    private suspend fun resolveSoundCloudTrackStubs(
        pageHtml: String,
        trackIds: List<String>,
    ): Map<String, ImportedTrack> {
        val clientId = obtainSoundCloudClientId(pageHtml) ?: return emptyMap()
        val out = mutableMapOf<String, ImportedTrack>()
        for (chunk in trackIds.chunked(45)) {
            val url = "https://api-v2.soundcloud.com/tracks?ids=${chunk.joinToString(",")}&client_id=$clientId"
            val resp = runCatching { get(url) }.getOrNull() ?: continue
            if (resp.status.value == 401 || resp.status.value == 403) {
                cachedScClientId = null
                break
            }
            if (resp.status.value !in 200..299) continue
            val text = runCatching { resp.bodyAsText() }.getOrNull() ?: continue
            val arr = (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray).orEmpty()
            for (el in arr) {
                val obj = el.obj() ?: continue
                val id = obj.idString("id") ?: continue
                parseSoundCloudTrack(obj)?.let { out[id] = it }
            }
        }
        return out
    }

    private suspend fun obtainSoundCloudClientId(pageHtml: String): String? {
        cachedScClientId?.let { return it }
        val scriptUrls = Regex("""https://a-v2\.sndcdn\.com/assets/[A-Za-z0-9._-]+\.js""")
            .findAll(pageHtml)
            .map { it.value }
            .toList()
            .asReversed()
            .distinct()
            .take(6)
        val cidRegex = Regex("""client_id\s*[:=]\s*["']([A-Za-z0-9]{20,40})["']""")
        for (jsUrl in scriptUrls) {
            val body = runCatching {
                client.get(jsUrl) { header("User-Agent", DESKTOP_USER_AGENT) }.bodyAsText()
            }.getOrNull() ?: continue
            val found = cidRegex.find(body)?.groupValues?.get(1)
            if (!found.isNullOrBlank()) {
                cachedScClientId = found
                return found
            }
        }
        return null
    }

    private fun extractScHydrationArray(html: String): String? {
        val markerIdx = html.indexOf("window.__sc_hydration")
        if (markerIdx < 0) return null
        val start = html.indexOf('[', markerIdx)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until html.length) {
            val c = html[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // zing mp3 & nhaccuatui public playlist / album importers

    private suspend fun <T> catchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun getHtml(url: String, userAgent: String, acceptLanguage: String = "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"): HttpResponse = try {
        client.get(url) {
            header("User-Agent", userAgent)
            header("Accept-Language", acceptLanguage)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        throw LinkImportException("Không thể kết nối máy chủ — hãy kiểm tra mạng.")
    }

    private fun toZingMobileUrl(url: String): String =
        url.substringBefore('#')
            .replace(Regex("^https?://(?:www\\.|m\\.)?(?:zingmp3\\.vn|mp3\\.zing\\.vn)", RegexOption.IGNORE_CASE), "https://m.zingmp3.vn")

    private fun toZingDesktopUrl(url: String): String =
        url.substringBefore('#')
            .replace(Regex("^https?://(?:www\\.|m\\.)?(?:zingmp3\\.vn|mp3\\.zing\\.vn)", RegexOption.IGNORE_CASE), "https://zingmp3.vn")

    private fun toNctDesktopUrl(url: String): String =
        url.substringBefore('#')
            .replace(Regex("^https?://(?:www\\.|m\\.)?(?:nhaccuatui\\.com|nct\\.vn)", RegexOption.IGNORE_CASE), "https://www.nhaccuatui.com")

    private suspend fun fetchZingMp3(url: String): ParsedImport {
        val cleanUrl = toZingDesktopUrl(url)
        val mobileUrl = toZingMobileUrl(url)
        val remoteId = Regex("/([A-Za-z0-9]+)\\.html(?:\\?.*)?$").find(cleanUrl)?.groupValues?.get(1)
            ?: cleanUrl.trimEnd('/').substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }

        var html = ""
        val mobileResp = catchingNonCancel { getHtml(mobileUrl, USER_AGENT) }
        if (mobileResp != null && mobileResp.status.value in 200..299) {
            html = catchingNonCancel { mobileResp.bodyAsText() }.orEmpty()
        }
        if (html.isBlank()) {
            val desktopResp = getHtml(cleanUrl, DESKTOP_USER_AGENT)
            when (desktopResp.status.value) {
                in 200..299 -> html = desktopResp.bodyAsText()
                404 -> throw LinkImportException("Không tìm thấy playlist/album Zing MP3 (hoặc đang đặt riêng tư).")
                429 -> throw LinkImportException("Zing MP3 đang giới hạn truy cập tạm thời, thử lại sau.")
                else -> throw LinkImportException("Zing MP3 phản hồi lỗi (${desktopResp.status.value}).")
            }
        }

        parseZingHtml(html)?.let { parsed ->
            return parsed.from(PlaylistOrigin(remoteId = remoteId, remoteUrl = cleanUrl))
        }

        // Desktop fallback in case mobile page didn't expose LD+JSON
        if (mobileUrl != cleanUrl) {
            val desktopResp = catchingNonCancel { getHtml(cleanUrl, DESKTOP_USER_AGENT) }
            if (desktopResp != null && desktopResp.status.value in 200..299) {
                val desktopHtml = catchingNonCancel { desktopResp.bodyAsText() }.orEmpty()
                if (desktopHtml.isNotBlank()) {
                    parseZingHtml(desktopHtml)?.let { parsed ->
                        return parsed.from(PlaylistOrigin(remoteId = remoteId, remoteUrl = cleanUrl))
                    }
                    if (html.isBlank()) html = desktopHtml
                }
            }
        }

        fallbackYouTubeByOgTitle(
            html = html,
            source = ImportSource.ZING_MP3,
            remoteUrl = cleanUrl,
            remoteId = remoteId,
            isAlbumHint = cleanUrl.contains("/album/", ignoreCase = true),
        )?.let { return it }

        throw LinkImportException("Không đọc được danh sách phát Zing MP3 này. Hãy dùng liên kết playlist/album công khai.")
    }

    private fun parseZingHtml(html: String): ParsedImport? {
        val ldParsed = parseLdJsonCollection(html)
        val cardTracks = parseZingMobileCards(html)
        val ogTitle = cleanZingTitle(extractOgOrTitle(html))

        if (cardTracks.isNotEmpty() && cardTracks.size >= (ldParsed?.tracks?.size ?: 0)) {
            val ldByTitle = ldParsed?.tracks.orEmpty().associateBy { normalizeMatchKey(it.title) }
            val merged = cardTracks.map { card ->
                val match = ldByTitle[normalizeMatchKey(card.title)]
                if (match != null) {
                    card.copy(
                        durationMs = card.durationMs ?: match.durationMs,
                        thumbnailUrl = card.thumbnailUrl ?: match.thumbnailUrl,
                        album = card.album ?: match.album,
                    )
                } else {
                    card
                }
            }
            val name = ldParsed?.title?.takeIf { it.isNotBlank() }
                ?: ogTitle?.takeIf { it.isNotBlank() }
                ?: "Zing MP3 Playlist"
            return ParsedImport(
                likedSongs = emptyList(),
                playlists = mapOf(name to merged),
                provider = ImportSource.ZING_MP3,
                playlistCovers = coverFor(name, ldParsed?.coverUrl ?: extractMetaContent(html, "og:image")),
            )
        }

        if (ldParsed != null && ldParsed.tracks.isNotEmpty()) {
            val name = ldParsed.title.ifBlank { ogTitle ?: "Zing MP3 Playlist" }
            return ParsedImport(
                likedSongs = emptyList(),
                playlists = mapOf(name to ldParsed.tracks),
                provider = ImportSource.ZING_MP3,
                playlistCovers = coverFor(name, ldParsed.coverUrl ?: extractMetaContent(html, "og:image")),
            )
        }

        return null
    }

    private fun parseZingMobileCards(html: String): List<ImportedTrack> {
        val blocks = Regex("""<div class="card-info">(.*?)</li>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html)
            .toList()
        if (blocks.isEmpty()) return emptyList()
        val titleAnchor = Regex("""<p class="title"[^>]*>.*?<a[^>]*href="[^"]*/bai-hat/[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val artistBlock = Regex("""<div class="artist[^"]*"[^>]*>(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val anchorText = Regex("""<a[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        return blocks.mapNotNull { blockMatch ->
            val block = blockMatch.groupValues[1]
            val rawTitle = titleAnchor.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = cleanHtmlText(rawTitle).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artistsHtml = artistBlock.find(block)?.groupValues?.get(1).orEmpty()
            val artist = anchorText.findAll(artistsHtml)
                .map { cleanHtmlText(it.groupValues[1]) }
                .filter { it.isNotBlank() }
                .joinToString(", ")
            ImportedTrack(
                title = title,
                artist = artist,
                album = null,
            )
        }
    }

    private suspend fun fetchNhacCuaTui(url: String): ParsedImport {
        val cleanUrl = toNctDesktopUrl(url)
        val remoteId = Regex("""\.([A-Za-z0-9]+)\.html(?:\?.*)?$""").find(cleanUrl)?.groupValues?.get(1)
            ?: cleanUrl.trimEnd('/').substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }

        val response = getHtml(cleanUrl, DESKTOP_USER_AGENT)
        val html = when (response.status.value) {
            in 200..299 -> response.bodyAsText()
            404 -> throw LinkImportException("Không tìm thấy playlist/album NhacCuaTui (hoặc đang đặt riêng tư).")
            429 -> throw LinkImportException("NhacCuaTui đang giới hạn truy cập tạm thời, thử lại sau.")
            else -> throw LinkImportException("NhacCuaTui phản hồi lỗi (${response.status.value}).")
        }

        parseNctNuxtPlaylist(html)?.let { parsed ->
            return parsed.from(PlaylistOrigin(remoteId = remoteId, remoteUrl = cleanUrl))
        }

        parseLdJsonCollection(html)?.takeIf { it.tracks.isNotEmpty() }?.let { ld ->
            val name = ld.title.ifBlank { cleanNctTitle(extractOgOrTitle(html)) ?: "NhacCuaTui Playlist" }
            return ParsedImport(
                likedSongs = emptyList(),
                playlists = mapOf(name to ld.tracks),
                provider = ImportSource.NHACCUATUI,
                playlistCovers = coverFor(name, ld.coverUrl ?: extractMetaContent(html, "og:image")),
            ).from(PlaylistOrigin(remoteId = remoteId, remoteUrl = cleanUrl))
        }

        fallbackYouTubeByOgTitle(
            html = html,
            source = ImportSource.NHACCUATUI,
            remoteUrl = cleanUrl,
            remoteId = remoteId,
            isAlbumHint = cleanUrl.contains("/album/", ignoreCase = true),
        )?.let { return it }

        throw LinkImportException("Không đọc được danh sách phát NhacCuaTui này. Hãy dùng liên kết playlist/album công khai.")
    }

    private fun parseNctNuxtPlaylist(html: String): ParsedImport? {
        val raw = Regex("""<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null

        val detailObj = arr.firstNotNullOfOrNull { el ->
            val obj = el as? JsonObject ?: return@firstNotNullOfOrNull null
            if (obj.containsKey("listSong") && obj.containsKey("name")) obj else null
        }
        if (detailObj != null) {
            val playlistName = detailObj.nuxtString(arr, "name") ?: "NhacCuaTui Playlist"
            val coverUrl = detailObj.nuxtString(arr, "image")
            val isAlbum = detailObj.nuxtBoolean(arr, "isAlbum") == true
            val songArray = detailObj.nuxtElement(arr, "listSong") as? JsonArray ?: return null
            val tracks = songArray.mapNotNull { ref ->
                val idx = (ref as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val songObj = resolveNuxtElement(arr, idx) as? JsonObject ?: return@mapNotNull null
                val title = songObj.nuxtString(arr, "name") ?: return@mapNotNull null
                val artist = songObj.nuxtString(arr, "artistName")
                    ?: (songObj.nuxtElement(arr, "artist") as? JsonArray).orEmpty().mapNotNull { artistRef ->
                        val aIdx = (artistRef as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                        (resolveNuxtElement(arr, aIdx) as? JsonObject)?.nuxtString(arr, "name")
                    }.joinToString(", ")
                val durationMs = songObj.nuxtLong(arr, "duration")?.takeIf { it > 0L }?.times(1000L)
                val albumName = if (isAlbum) playlistName else (songObj.nuxtElement(arr, "album") as? JsonObject)?.nuxtString(arr, "name")
                val thumb = songObj.nuxtString(arr, "image")
                val key = songObj.nuxtString(arr, "key")
                ImportedTrack(
                    title = title,
                    artist = artist,
                    album = albumName,
                    durationMs = durationMs,
                    thumbnailUrl = thumb,
                    sourceId = key?.let { "nhaccuatui:song:$it" },
                )
            }
            if (tracks.isEmpty()) return null
            return ParsedImport(
                likedSongs = emptyList(),
                playlists = mapOf(playlistName to tracks),
                provider = ImportSource.NHACCUATUI,
                playlistCovers = coverFor(playlistName, coverUrl),
            )
        }

        // Single song page in __NUXT_DATA__
        val songObj = arr.firstNotNullOfOrNull { el ->
            val obj = el as? JsonObject ?: return@firstNotNullOfOrNull null
            if (obj.containsKey("name") && obj.containsKey("artistName") && obj.containsKey("duration") && obj.containsKey("key")) obj else null
        } ?: return null
        val title = songObj.nuxtString(arr, "name") ?: return null
        val artist = songObj.nuxtString(arr, "artistName").orEmpty()
        val durationMs = songObj.nuxtLong(arr, "duration")?.takeIf { it > 0L }?.times(1000L)
        val thumb = songObj.nuxtString(arr, "image")
        val key = songObj.nuxtString(arr, "key")
        return ParsedImport(
            likedSongs = emptyList(),
            playlists = mapOf(
                title to listOf(
                    ImportedTrack(
                        title = title,
                        artist = artist,
                        album = null,
                        durationMs = durationMs,
                        thumbnailUrl = thumb,
                        sourceId = key?.let { "nhaccuatui:song:$it" },
                    ),
                ),
            ),
            provider = ImportSource.NHACCUATUI,
            playlistCovers = coverFor(title, thumb),
        )
    }

    private fun resolveNuxtElement(arr: JsonArray, index: Int, depth: Int = 0): JsonElement? {
        if (depth > 12 || index !in arr.indices) return null
        val el = arr[index]
        if (el is JsonArray && el.size >= 2) {
            val tag = (el[0] as? JsonPrimitive)?.contentOrNull
            if (tag in setOf("ShallowReactive", "Reactive", "Ref", "ShallowRef")) {
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

    private fun JsonObject.nuxtLong(arr: JsonArray, key: String): Long? =
        (nuxtElement(arr, key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.nuxtBoolean(arr: JsonArray, key: String): Boolean? =
        (nuxtElement(arr, key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private class LdJsonCollection(
        val title: String,
        val coverUrl: String?,
        val tracks: List<ImportedTrack>,
    )

    private fun parseLdJsonCollection(html: String): LdJsonCollection? {
        val scriptRegex = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in scriptRegex.findAll(html)) {
            val rawJson = match.groupValues[1].trim()
            if (rawJson.isBlank()) continue
            val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: continue
            val candidates = when (root) {
                is JsonObject -> listOf(root)
                is JsonArray -> root.mapNotNull { it as? JsonObject }
                else -> emptyList()
            }
            for (obj in candidates) {
                val type = obj.string("@type").orEmpty()
                if (type.equals("MusicPlaylist", ignoreCase = true) || type.equals("MusicAlbum", ignoreCase = true)) {
                    val name = unescapeHtml(obj.string("name").orEmpty())
                    val cover = obj.string("image") ?: obj.string("thumbnailUrl")
                    val albumName = if (type.equals("MusicAlbum", ignoreCase = true)) name.ifBlank { null } else null
                    val trackField = obj["track"]
                    val trackNodes: List<JsonObject> = when (trackField) {
                        is JsonArray -> trackField.mapNotNull { it.obj() }
                        is JsonObject -> (trackField["itemListElement"] as? JsonArray).orEmpty().mapNotNull { el ->
                            val itemObj = el.obj() ?: return@mapNotNull null
                            itemObj["item"].obj() ?: itemObj
                        }
                        else -> emptyList()
                    }
                    val tracks = trackNodes.mapNotNull { t ->
                        val title = unescapeHtml(t.string("name").orEmpty()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val artist = extractLdArtists(t["byArtist"])
                        val durationMs = parseIsoDurationMs(t.string("duration"))
                        val thumb = t.string("image") ?: t.string("thumbnailUrl")
                        val trackAlbum = albumName ?: when (val alb = t["inAlbum"]) {
                            is JsonPrimitive -> alb.contentOrNull?.let(::unescapeHtml)?.takeIf { it.isNotBlank() }
                            is JsonObject -> alb.string("name")?.let(::unescapeHtml)
                            else -> null
                        }
                        ImportedTrack(
                            title = title,
                            artist = artist,
                            album = trackAlbum,
                            durationMs = durationMs,
                            thumbnailUrl = thumb,
                        )
                    }
                    if (tracks.isNotEmpty()) {
                        return LdJsonCollection(title = name, coverUrl = cover, tracks = tracks)
                    }
                } else if (type.equals("MusicRecording", ignoreCase = true)) {
                    val track = parseLdRecording(obj) ?: continue
                    return LdJsonCollection(
                        title = track.title,
                        coverUrl = track.thumbnailUrl,
                        tracks = listOf(track),
                    )
                }
            }
        }
        return null
    }

    private fun parseLdRecording(obj: JsonObject): ImportedTrack? {
        val title = unescapeHtml(obj.string("name").orEmpty()).takeIf { it.isNotBlank() } ?: return null
        val artist = extractLdArtists(obj["byArtist"])
        val albumName = when (val alb = obj["inAlbum"]) {
            is JsonPrimitive -> alb.contentOrNull?.let(::unescapeHtml)?.takeIf { it.isNotBlank() }
            is JsonObject -> alb.string("name")?.let(::unescapeHtml)
            else -> null
        }
        return ImportedTrack(
            title = title,
            artist = artist,
            album = albumName,
            durationMs = parseIsoDurationMs(obj.string("duration")),
            thumbnailUrl = obj.string("image") ?: obj.string("thumbnailUrl"),
        )
    }

    private fun extractLdArtists(el: JsonElement?): String = when (el) {
        is JsonArray -> el.mapNotNull { item ->
            when (item) {
                is JsonObject -> item.string("name")?.let(::unescapeHtml)
                is JsonPrimitive -> item.contentOrNull?.let(::unescapeHtml)
                else -> null
            }
        }.filter { it.isNotBlank() }.joinToString(", ")
        is JsonObject -> el.string("name")?.let(::unescapeHtml).orEmpty()
        is JsonPrimitive -> el.contentOrNull?.let(::unescapeHtml).orEmpty()
        else -> ""
    }

    private fun parseIsoDurationMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val match = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""", RegexOption.IGNORE_CASE).find(iso.trim()) ?: return null
        val h = match.groupValues[1].toLongOrNull() ?: 0L
        val m = match.groupValues[2].toLongOrNull() ?: 0L
        val s = match.groupValues[3].toLongOrNull() ?: 0L
        val total = h * 3600L + m * 60L + s
        return if (total > 0L) total * 1000L else null
    }

    private suspend fun fallbackYouTubeByOgTitle(
        html: String,
        source: ImportSource,
        remoteUrl: String,
        remoteId: String?,
        isAlbumHint: Boolean,
    ): ParsedImport? {
        val rawTitle = extractOgOrTitle(html) ?: return null
        val cleaned = when (source) {
            ImportSource.ZING_MP3 -> cleanZingTitle(rawTitle)
            ImportSource.NHACCUATUI -> cleanNctTitle(rawTitle)
            else -> rawTitle.trim()
        }?.takeIf { it.isNotBlank() } ?: return null

        val lower = cleaned.lowercase()
        if (lower.startsWith("zing mp3") || lower.startsWith("nhaccuatui")) return null

        if (isAlbumHint) {
            val albumItem = YouTube.search(cleaned, YouTube.SearchFilter.FILTER_ALBUM)
                .getOrNull()?.items?.filterIsInstance<AlbumItem>()?.firstOrNull()
            if (albumItem != null) {
                val fetched = catchingNonCancel { fetchYouTubeAlbum(albumItem.browseId) }
                if (fetched != null && fetched.totalSongs > 0) {
                    return fetched.copy(
                        provider = source,
                        warnings = listOf("${source.label} chặn đọc danh sách trực tiếp — đã tìm album tương ứng trên YouTube Music."),
                    ).from(PlaylistOrigin(remoteId = remoteId, remoteUrl = remoteUrl))
                }
            }
        }

        val filters = listOf(
            YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
            YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST,
        )
        for (filter in filters) {
            val playlistItem = YouTube.search(cleaned, filter)
                .getOrNull()?.items?.filterIsInstance<PlaylistItem>()?.firstOrNull() ?: continue
            val fetched = catchingNonCancel { fetchYouTubePlaylist(playlistItem.id) }
            if (fetched != null && fetched.totalSongs > 0) {
                return fetched.copy(
                    provider = source,
                    warnings = listOf("${source.label} chặn đọc danh sách trực tiếp — đã tìm playlist tương ứng trên YouTube Music."),
                ).from(PlaylistOrigin(remoteId = remoteId, remoteUrl = remoteUrl))
            }
        }
        return null
    }

    /** Resolves artist + title metadata for a single shared music track link (for instant playback). */
    suspend fun resolveSharedTrack(rawUrl: String): SharedTrackInfo? {
        val url = resolveShareLink(rawUrl)
        return when (detect(url)) {
            LinkSource.SPOTIFY -> resolveSpotifyTrack(url)
            LinkSource.DEEZER -> resolveDeezerTrack(url)
            LinkSource.SOUNDCLOUD -> resolveSoundCloudTrack(url)
            LinkSource.APPLE_MUSIC -> resolveAppleTrack(url)
            LinkSource.ZING_MP3 -> resolveZingTrack(url)
            LinkSource.NHACCUATUI -> resolveNctTrack(url)
            LinkSource.YOUTUBE, null -> null
        }
    }

    private suspend fun resolveSpotifyTrack(url: String): SharedTrackInfo? {
        val trackId = SPOTIFY_TRACK_REGEX.find(url)?.groupValues?.get(1) ?: return null
        val embedHtml = catchingNonCancel { get("https://open.spotify.com/embed/track/$trackId").bodyAsText() }
        if (!embedHtml.isNullOrBlank()) {
            val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(embedHtml)?.groupValues?.get(1)
            if (!data.isNullOrBlank()) {
                val entity = runCatching {
                    json.parseToJsonElement(data).path("props", "pageProps", "state", "data", "entity") as? JsonObject
                }.getOrNull()
                if (entity != null) {
                    val title = entity.string("name") ?: entity.string("title")
                    val artists = (entity["artists"] as? JsonArray).orEmpty()
                        .mapNotNull { it.obj()?.string("name") }
                        .joinToString(", ")
                        .ifBlank { entity.string("subtitle").orEmpty().replace('\u00A0', ' ') }
                    if (!title.isNullOrBlank()) {
                        return SharedTrackInfo(title = title, artist = artists)
                    }
                }
            }
        }

        val canonical = "https://open.spotify.com/track/$trackId"
        val oembedRaw = catchingNonCancel {
            get("https://open.spotify.com/oembed?url=${URLEncoder.encode(canonical, "UTF-8")}").bodyAsText()
        }
        val oembedTitle = oembedRaw?.let {
            runCatching { (json.parseToJsonElement(it) as? JsonObject)?.string("title") }.getOrNull()
        }
        val pageHtml = catchingNonCancel { getHtml(canonical, DESKTOP_USER_AGENT, "en-US,en;q=0.9").bodyAsText() }
        val pageTitleTag = pageHtml?.let(::extractHtmlTitleTag)
        val byMatch = pageTitleTag?.let { Regex("""^(.*?)\s*-\s*song(?:\s+and\s+lyrics)?\s+by\s+(.+?)\s*\|\s*Spotify$""", RegexOption.IGNORE_CASE).find(it) }
        if (byMatch != null) {
            return SharedTrackInfo(title = byMatch.groupValues[1].trim(), artist = byMatch.groupValues[2].trim())
        }
        val ogTitle = pageHtml?.let { extractMetaContent(it, "og:title") } ?: oembedTitle
        if (!ogTitle.isNullOrBlank()) {
            val artistHint = pageHtml?.let { extractMetaContent(it, "og:description") }
                ?.substringBefore('·')?.trim().orEmpty()
            return SharedTrackInfo(title = ogTitle, artist = artistHint)
        }
        return null
    }

    private suspend fun resolveDeezerTrack(url: String): SharedTrackInfo? {
        val trackId = DEEZER_TRACK_REGEX.find(url)?.groupValues?.get(1) ?: return null
        val apiRaw = catchingNonCancel { get("https://api.deezer.com/track/$trackId").bodyAsText() }
        if (!apiRaw.isNullOrBlank()) {
            val obj = runCatching { json.parseToJsonElement(apiRaw) as? JsonObject }.getOrNull()
            if (obj != null && obj["error"] == null) {
                val title = obj.string("title")
                val artist = obj["artist"]?.obj()?.string("name").orEmpty()
                if (!title.isNullOrBlank()) {
                    return SharedTrackInfo(title = title, artist = artist)
                }
            }
        }
        val html = catchingNonCancel { getHtml(url, DESKTOP_USER_AGENT).bodyAsText() } ?: return null
        val rawTitle = extractOgOrTitle(html)?.replace(Regex("""\s*[-|]\s*Deezer.*$""", RegexOption.IGNORE_CASE), "")?.trim()
            ?: return null
        return splitTitleAndArtist(rawTitle)
    }

    private suspend fun resolveSoundCloudTrack(url: String): SharedTrackInfo? {
        val canonicalUrl = url.replace("://m.soundcloud.com", "://soundcloud.com")
        val oembedRaw = catchingNonCancel {
            get("https://soundcloud.com/oembed?format=json&url=${URLEncoder.encode(canonicalUrl, "UTF-8")}").bodyAsText()
        }
        if (!oembedRaw.isNullOrBlank()) {
            val obj = runCatching { json.parseToJsonElement(oembedRaw) as? JsonObject }.getOrNull()
            val rawTitle = obj?.string("title")
            val author = obj?.string("author_name").orEmpty()
            if (!rawTitle.isNullOrBlank()) {
                val stripped = if (author.isNotBlank() && rawTitle.endsWith(" by $author", ignoreCase = true)) {
                    rawTitle.substring(0, rawTitle.length - (" by $author").length).trim()
                } else {
                    rawTitle
                }
                val (artist, title) = splitSoundCloudArtistTitle(stripped, author)
                return SharedTrackInfo(title = title, artist = artist)
            }
        }
        val html = catchingNonCancel { getHtml(canonicalUrl, DESKTOP_USER_AGENT, "en-US,en;q=0.9").bodyAsText() } ?: return null
        val hydration = extractScHydrationArray(html)?.let {
            runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull()
        }
        val soundData = hydration?.firstNotNullOfOrNull { el ->
            val obj = el.obj() ?: return@firstNotNullOfOrNull null
            if (obj.string("hydratable") == "sound") obj["data"].obj() else null
        }
        if (soundData != null) {
            parseSoundCloudTrack(soundData)?.let { return SharedTrackInfo(title = it.title, artist = it.artist) }
        }
        val ogTitle = extractOgOrTitle(html)?.replace(Regex("""\s*\|\s*Listen online.*$""", RegexOption.IGNORE_CASE), "")?.trim()
            ?: return null
        return splitTitleAndArtist(ogTitle)
    }

    private suspend fun resolveAppleTrack(url: String): SharedTrackInfo? {
        val trackId = APPLE_TRACK_ID_REGEX.find(url)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }
        }
        if (!trackId.isNullOrBlank()) {
            val lookupRaw = catchingNonCancel { get("https://itunes.apple.com/lookup?id=$trackId").bodyAsText() }
            if (!lookupRaw.isNullOrBlank()) {
                val first = runCatching {
                    (json.parseToJsonElement(lookupRaw).path("results") as? JsonArray)?.firstOrNull()?.obj()
                }.getOrNull()
                val title = first?.string("trackName")
                val artist = first?.string("artistName").orEmpty()
                if (!title.isNullOrBlank()) {
                    return SharedTrackInfo(title = title, artist = artist)
                }
            }
        }
        val html = catchingNonCancel { getHtml(url, DESKTOP_USER_AGENT, "en-US,en;q=0.9").bodyAsText() } ?: return null
        val rawTitle = extractOgOrTitle(html)
            ?.replace(Regex("""\s+on\s+Apple\s+Music$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s*-\s*Single\s*$""", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: return null
        val byMatch = Regex("""^(.+?)\s+(?:by|-)\s+(.+)$""", RegexOption.IGNORE_CASE).find(rawTitle)
        return if (byMatch != null) {
            SharedTrackInfo(title = byMatch.groupValues[1].trim(), artist = byMatch.groupValues[2].trim())
        } else {
            SharedTrackInfo(title = rawTitle, artist = "")
        }
    }

    private suspend fun resolveZingTrack(url: String): SharedTrackInfo? {
        val mobileUrl = toZingMobileUrl(url)
        val html = catchingNonCancel { getHtml(mobileUrl, USER_AGENT).bodyAsText() }
            ?: catchingNonCancel { getHtml(toZingDesktopUrl(url), DESKTOP_USER_AGENT).bodyAsText() }
            ?: return null
        parseLdJsonCollection(html)?.tracks?.firstOrNull()?.let {
            return SharedTrackInfo(title = it.title, artist = it.artist)
        }
        val cleaned = cleanZingTitle(extractOgOrTitle(html)) ?: return null
        val parenArtist = Regex("""^(.+?)\s*\(([^()]{2,60})\)\s*$""").find(cleaned)
        return if (parenArtist != null) {
            SharedTrackInfo(title = parenArtist.groupValues[1].trim(), artist = parenArtist.groupValues[2].trim())
        } else {
            splitTitleAndArtist(cleaned)
        }
    }

    private suspend fun resolveNctTrack(url: String): SharedTrackInfo? {
        val html = catchingNonCancel { getHtml(toNctDesktopUrl(url), DESKTOP_USER_AGENT).bodyAsText() } ?: return null
        parseLdJsonCollection(html)?.tracks?.firstOrNull()?.let {
            return SharedTrackInfo(title = it.title, artist = it.artist)
        }
        parseNctNuxtPlaylist(html)?.playlists?.values?.firstOrNull()?.firstOrNull()?.let {
            return SharedTrackInfo(title = it.title, artist = it.artist)
        }
        val cleaned = cleanNctTitle(extractOgOrTitle(html)) ?: return null
        return splitTitleAndArtist(cleaned)
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

    private fun extractHtmlTitleTag(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)?.let(::cleanHtmlText)?.takeIf { it.isNotBlank() }

    private fun extractOgOrTitle(html: String): String? =
        extractMetaContent(html, "og:title") ?: extractHtmlTitleTag(html)

    private fun cleanZingTitle(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return s
            .replace(Regex("""\s*-\s*Lời bài hát,\s*tải nhạc\s*Zing\s*MP3\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(?:Playlist\s+)?Zing\s*MP3\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Nhiều nghệ sĩ\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() && !it.startsWith("Zing MP3 -", ignoreCase = true) }
    }

    private fun cleanNctTitle(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return s
            .replace(Regex("""\s*-\s*mp3\s*download\s*\|\s*lyric\s*-\s*NhacCuaTui\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*NhacCuaTui\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() && !it.startsWith("NhacCuaTui -", ignoreCase = true) && !it.startsWith("-  -") }
    }

    private fun splitTitleAndArtist(text: String): SharedTrackInfo {
        val parts = text.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            SharedTrackInfo(title = parts[0].trim(), artist = parts[1].trim())
        } else {
            SharedTrackInfo(title = text.trim(), artist = "")
        }
    }

    private fun cleanHtmlText(raw: String): String =
        unescapeHtml(raw.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

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

    private fun normalizeMatchKey(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
