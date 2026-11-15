// LinkImporter.kt

package com.mochyy.mochymusic.importer

import com.music.innertube.YouTube
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

enum class LinkSource(val label: String, val example: String) {
    SPOTIFY("Spotify", "https://open.spotify.com/playlist/…"),
    APPLE_MUSIC("Apple Music", "https://music.apple.com/us/playlist/…"),
    DEEZER("Deezer", "https://www.deezer.com/playlist/…"),
    YOUTUBE("YouTube Music", "https://music.youtube.com/playlist?list=…"),
    SOUNDCLOUD("SoundCloud", "https://soundcloud.com/…/sets/…"),
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
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val SPOTIFY_REGEX = Regex("(?:open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{22})")
    private val APPLE_REGEX = Regex("music\\.apple\\.com/[a-z]{2}/(playlist|album)/(?:[^/?#]+/)?(pl\\.[A-Za-z0-9-]+|\\d+)")
    private val DEEZER_REGEX = Regex("deezer\\.com/(?:[a-z]{2}/)?(playlist|album)/(\\d+)")
    private val YT_LIST_REGEX = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val YT_BROWSE_REGEX = Regex("music\\.youtube\\.com/(?:browse|playlist)/((?:VL|MPREb_)?[A-Za-z0-9_-]+)")
    private val SOUNDCLOUD_REGEX = Regex("https?://(?:www\\.|m\\.)?soundcloud\\.com/[A-Za-z0-9_.-]+/(?:sets/)?[A-Za-z0-9_.-]+")
    private val SHORT_LINK_HOSTS = listOf("spotify.link", "spotify.app.link", "deezer.page.link", "link.deezer.com", "on.soundcloud.com", "soundcloud.app.goo.gl")

    @Volatile
    private var cachedScClientId: String? = null

    /** works out which service a pasted link belongs to, or null if it isn't one we read */
    fun detect(link: String): LinkSource? {
        val text = link.trim()
        return when {
            text.contains("spotify") -> LinkSource.SPOTIFY
            text.contains("music.apple.com") -> LinkSource.APPLE_MUSIC
            text.contains("deezer") -> LinkSource.DEEZER
            text.contains("youtube.com") || text.contains("youtu.be") -> LinkSource.YOUTUBE
            text.contains("soundcloud.com") || text.contains("on.soundcloud.com") || text.contains("soundcloud.app.goo.gl") -> LinkSource.SOUNDCLOUD
            else -> null
        }
    }

    suspend fun fetch(link: String): ParsedImport {
        val text = extractUrl(link.trim()) ?: throw LinkImportException("That doesn't look like a link.")
        val resolved = if (SHORT_LINK_HOSTS.any { text.contains(it) }) resolveShortLink(text) else text
        return when (detect(resolved) ?: detect(text)) {
            LinkSource.SPOTIFY -> fetchSpotify(resolved)
            LinkSource.APPLE_MUSIC -> fetchApple(resolved)
            LinkSource.DEEZER -> fetchDeezer(resolved)
            LinkSource.YOUTUBE -> fetchYouTube(resolved)
            LinkSource.SOUNDCLOUD -> fetchSoundCloud(resolved)
            null -> throw LinkImportException("Paste a Spotify, Apple Music, Deezer, SoundCloud or YouTube Music link.")
        }
    }

    /** share sheets often paste "check out this playlist! https://…" — take just the url */
    private fun extractUrl(text: String): String? {
        if (text.startsWith("spotify:")) return text
        return Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
    }

    /** short links redirect to the real page; some go through an html page that holds the real url */
    private suspend fun resolveShortLink(url: String): String {
        val response = get(url)
        val finalUrl = response.call.request.url.toString()
        if (listOf(SPOTIFY_REGEX, DEEZER_REGEX, YT_LIST_REGEX, SOUNDCLOUD_REGEX).any { it.containsMatchIn(finalUrl) }) return finalUrl
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        SPOTIFY_REGEX.find(body)?.let { return "https://open.spotify.com/${it.groupValues[1]}/${it.groupValues[2]}" }
        DEEZER_REGEX.find(body)?.let { return "https://www.deezer.com/${it.groupValues[1]}/${it.groupValues[2]}" }
        SOUNDCLOUD_REGEX.find(body)?.let { return it.value }
        return finalUrl
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
}
