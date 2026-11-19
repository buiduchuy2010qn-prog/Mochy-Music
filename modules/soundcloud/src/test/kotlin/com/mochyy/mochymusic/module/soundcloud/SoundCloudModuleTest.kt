package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SoundCloudModuleTest {

    private class FakeHost(val routes: (HttpRequest) -> HttpResponse) : ModuleHost {
        val requests = ArrayList<HttpRequest>()
        override val storage = object : ModuleStorage {
            val map = HashMap<String, String>()
            override fun get(key: String) = map[key]
            override fun set(key: String, value: String?) {
                if (value == null) map.remove(key) else map[key] = value
            }
        }
        override fun http(request: HttpRequest): HttpResponse {
            requests += request
            return routes(request)
        }
        override fun log(message: String) = Unit
        override val folder: File = createTempDirectory("soundcloud").toFile()
    }

    private val track = TrackQuery("Nơi Này Có Anh", listOf("Sơn Tùng M-TP"), durationMs = 260_000L)

    private fun searchJson(includeProgressive: Boolean = true, includeHls: Boolean = true): String {
        val transcodings = buildList {
            if (includeHls) {
                add(
                    """
                    {
                      "url": "https://api-v2.soundcloud.com/media/soundcloud:tracks:101/hls-aac/stream/hls",
                      "preset": "aac_160k",
                      "quality": "sq",
                      "snipped": false,
                      "format": {
                        "protocol": "hls",
                        "mime_type": "audio/mp4; codecs=\"mp4a.40.2\""
                      }
                    }
                    """.trimIndent(),
                )
            }
            if (includeProgressive) {
                add(
                    """
                    {
                      "url": "https://api-v2.soundcloud.com/media/soundcloud:tracks:101/prog-mp3/stream/progressive",
                      "preset": "mp3_0_1",
                      "quality": "sq",
                      "snipped": false,
                      "format": {
                        "protocol": "progressive",
                        "mime_type": "audio/mpeg"
                      }
                    }
                    """.trimIndent(),
                )
            }
        }.joinToString(",\n")

        return """
            {
              "collection": [
                {
                  "id": 101,
                  "title": "Nơi Này Có Anh",
                  "duration": 260000,
                  "full_duration": 260000,
                  "streamable": true,
                  "policy": "ALLOW",
                  "track_authorization": "sc_auth_tok",
                  "user": { "username": "Sơn Tùng M-TP Official" },
                  "publisher_metadata": {
                    "artist": "Sơn Tùng M-TP",
                    "album_title": "Nơi Này Có Anh (Single)"
                  },
                  "media": {
                    "transcodings": [
                      $transcodings
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun `scrapes client_id dynamically from HTML and asset js bundles and caches it`() {
        val homeHtml = """
            <!doctype html>
            <html>
              <head>
                <script crossorigin src="https://a-v2.sndcdn.com/assets/0-core-1a2b3c.js"></script>
                <script crossorigin src="https://a-v2.sndcdn.com/assets/49-app-9f8e7d.js"></script>
              </head>
            </html>
        """.trimIndent()
        val host = FakeHost { request ->
            when (request.url) {
                "https://soundcloud.com" -> HttpResponse(200, homeHtml.toByteArray())
                "https://a-v2.sndcdn.com/assets/49-app-9f8e7d.js" ->
                    HttpResponse(200, """window.__sc_hydration=[];""".toByteArray())
                "https://a-v2.sndcdn.com/assets/0-core-1a2b3c.js" ->
                    HttpResponse(200, """var cfg={client_id:"ScClientId1234567890AbCdEfGhIjKl"};""".toByteArray())
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val sc = SoundCloudModule().also { it.attach(host) }

        val first = sc.obtainClientId(forceRefresh = false)
        assertEquals("ScClientId1234567890AbCdEfGhIjKl", first)
        assertEquals("ScClientId1234567890AbCdEfGhIjKl", host.storage.get("sc_client_id"))
        val initialRequestCount = host.requests.size
        assertEquals(3, initialRequestCount)

        val cached = sc.obtainClientId(forceRefresh = false)
        assertEquals("ScClientId1234567890AbCdEfGhIjKl", cached)
        assertEquals(initialRequestCount, host.requests.size)

        sc.signOut()
        assertNull(host.storage.get("sc_client_id"))
    }

    @Test
    fun `tracksIn skips Go+ snippets and non-streamable tracks`() {
        val host = FakeHost { HttpResponse(404, ByteArray(0)) }
        val sc = SoundCloudModule().also { it.attach(host) }
        val payload = """
            {
              "collection": [
                {
                  "id": 101,
                  "title": "Nơi Này Có Anh",
                  "duration": 260000,
                  "full_duration": 260000,
                  "streamable": true,
                  "policy": "ALLOW",
                  "user": { "username": "Sơn Tùng M-TP" },
                  "media": {
                    "transcodings": [
                      {
                        "url": "https://api-v2.soundcloud.com/media/soundcloud:tracks:101/prog/stream/progressive",
                        "preset": "mp3_0_1",
                        "quality": "sq",
                        "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
                      }
                    ]
                  }
                },
                {
                  "id": 102,
                  "title": "Go+ Snip Policy",
                  "duration": 260000,
                  "full_duration": 260000,
                  "streamable": true,
                  "policy": "SNIP",
                  "media": {
                    "transcodings": [
                      {
                        "url": "https://api-v2.soundcloud.com/media/soundcloud:tracks:102/prog/stream/progressive",
                        "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
                      }
                    ]
                  }
                },
                {
                  "id": 103,
                  "title": "Preview 30s Snippet",
                  "duration": 30000,
                  "full_duration": 240000,
                  "streamable": true,
                  "policy": "ALLOW",
                  "media": {
                    "transcodings": [
                      {
                        "url": "https://api-v2.soundcloud.com/media/soundcloud:tracks:103/prog/stream/progressive",
                        "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
                      }
                    ]
                  }
                },
                {
                  "id": 104,
                  "title": "Blocked Track",
                  "duration": 210000,
                  "streamable": false,
                  "policy": "ALLOW",
                  "media": {
                    "transcodings": [
                      {
                        "url": "https://api-v2.soundcloud.com/media/soundcloud:tracks:104/prog/stream/progressive",
                        "format": { "protocol": "progressive", "mime_type": "audio/mpeg" }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = sc.tracksIn(payload)
        assertEquals(1, parsed.size)
        assertEquals("101", parsed[0].catalog.id)
        assertEquals("Nơi Này Có Anh", parsed[0].catalog.title)
    }

    @Test
    fun `prefers progressive MP3 over HLS when available and falls back cleanly to HLS`() = runBlocking {
        val bothPayload = searchJson(includeProgressive = true, includeHls = true)
        val hostBoth = FakeHost { request ->
            when {
                request.url == "https://soundcloud.com" ->
                    HttpResponse(200, """<script src="https://a-v2.sndcdn.com/assets/49-app.js"></script>""".toByteArray())
                request.url == "https://a-v2.sndcdn.com/assets/49-app.js" ->
                    HttpResponse(200, """var cfg={client_id:"ScClientId1234567890AbCdEfGhIjKl"};""".toByteArray())
                "/search/tracks?" in request.url ->
                    HttpResponse(200, bothPayload.toByteArray())
                "/prog-mp3/stream/progressive?" in request.url ->
                    HttpResponse(200, """{"url":"https://cf-media.sndcdn.com/101.128.mp3"}""".toByteArray())
                "/hls-aac/stream/hls?" in request.url ->
                    HttpResponse(200, """{"url":"https://cf-media.sndcdn.com/101.m3u8"}""".toByteArray())
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val scBoth = SoundCloudModule().also { it.attach(hostBoth) }

        val foundMp3 = scBoth.resolve(track, emptyMap()) as ResolveResult.Found
        assertEquals("https://cf-media.sndcdn.com/101.128.mp3", foundMp3.stream.url)
        assertEquals(StreamFormat.Progressive, foundMp3.stream.format)
        assertEquals("MP3", foundMp3.stream.codec)
        assertEquals("MP3 128", foundMp3.stream.qualityLabel)

        val foundExplicitHls = scBoth.resolve(
            track,
            mapOf(SoundCloudModule.ChoiceStreamPref to SoundCloudModule.OptionHls),
        ) as ResolveResult.Found
        assertEquals("https://cf-media.sndcdn.com/101.m3u8", foundExplicitHls.stream.url)
        assertEquals(StreamFormat.Hls, foundExplicitHls.stream.format)
        assertEquals("AAC", foundExplicitHls.stream.codec)
        assertEquals("HLS AAC", foundExplicitHls.stream.qualityLabel)

        // Fallback to HLS when progressive MP3 transcoding is not listed
        val hlsOnlyPayload = searchJson(includeProgressive = false, includeHls = true)
        val hostHlsOnly = FakeHost { request ->
            when {
                request.url == "https://soundcloud.com" ->
                    HttpResponse(200, """<script src="https://a-v2.sndcdn.com/assets/49-app.js"></script>""".toByteArray())
                request.url == "https://a-v2.sndcdn.com/assets/49-app.js" ->
                    HttpResponse(200, """var cfg={client_id:"ScClientId1234567890AbCdEfGhIjKl"};""".toByteArray())
                "/search/tracks?" in request.url ->
                    HttpResponse(200, hlsOnlyPayload.toByteArray())
                "/hls-aac/stream/hls?" in request.url ->
                    HttpResponse(200, """{"url":"https://cf-media.sndcdn.com/101-fallback.m3u8"}""".toByteArray())
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val scHlsOnly = SoundCloudModule().also { it.attach(hostHlsOnly) }
        val fallbackStream = (scHlsOnly.resolve(track, emptyMap()) as ResolveResult.Found).stream
        assertEquals("https://cf-media.sndcdn.com/101-fallback.m3u8", fallbackStream.url)
        assertEquals(StreamFormat.Hls, fallbackStream.format)
        assertEquals("AAC", fallbackStream.codec)
        assertEquals("HLS AAC", fallbackStream.qualityLabel)
    }

    @Test
    fun `refreshes client_id and retries when API returns 401 or 403`() = runBlocking {
        val payload = searchJson(includeProgressive = true, includeHls = false)

        // 401 on search triggers client_id re-scrape and retry
        val searchRetryHost = FakeHost { request ->
            when {
                request.url == "https://soundcloud.com" ->
                    HttpResponse(200, """<script src="https://a-v2.sndcdn.com/assets/49-app.js"></script>""".toByteArray())
                request.url == "https://a-v2.sndcdn.com/assets/49-app.js" ->
                    HttpResponse(200, """var cfg={client_id:"FreshClientId999999999999999999"};""".toByteArray())
                "/search/tracks?" in request.url -> {
                    if ("client_id=StaleClientId000000000000000001" in request.url) {
                        HttpResponse(401, ByteArray(0))
                    } else {
                        HttpResponse(200, payload.toByteArray())
                    }
                }
                "/prog-mp3/stream/progressive?" in request.url -> {
                    if ("client_id=FreshClientId999999999999999999" in request.url) {
                        HttpResponse(200, """{"url":"https://cf-media.sndcdn.com/101-fresh.mp3"}""".toByteArray())
                    } else {
                        HttpResponse(401, ByteArray(0))
                    }
                }
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        searchRetryHost.storage.set("sc_client_id", "StaleClientId000000000000000001")
        val scSearchRetry = SoundCloudModule().also { it.attach(searchRetryHost) }

        val foundAfter401 = scSearchRetry.resolve(track, emptyMap()) as ResolveResult.Found
        assertEquals("https://cf-media.sndcdn.com/101-fresh.mp3", foundAfter401.stream.url)
        assertEquals("FreshClientId999999999999999999", searchRetryHost.storage.get("sc_client_id"))
        val searchCalls = searchRetryHost.requests.filter { "/search/tracks?" in it.url }
        assertEquals(2, searchCalls.size)
        assertTrue("client_id=StaleClientId000000000000000001" in searchCalls[0].url)
        assertTrue("client_id=FreshClientId999999999999999999" in searchCalls[1].url)

        // 403 on transcoding stream resolution triggers client_id re-scrape and retry
        val streamRetryHost = FakeHost { request ->
            when {
                request.url == "https://soundcloud.com" ->
                    HttpResponse(200, """<script src="https://a-v2.sndcdn.com/assets/49-app.js"></script>""".toByteArray())
                request.url == "https://a-v2.sndcdn.com/assets/49-app.js" ->
                    HttpResponse(200, """var cfg={client_id:"RotatedFreshId99999999999999999"};""".toByteArray())
                "/search/tracks?" in request.url ->
                    HttpResponse(200, payload.toByteArray())
                "/prog-mp3/stream/progressive?" in request.url -> {
                    if ("client_id=RotateMidwayId00000000000000001" in request.url) {
                        HttpResponse(403, ByteArray(0))
                    } else {
                        HttpResponse(200, """{"url":"https://cf-media.sndcdn.com/101-rotated.mp3"}""".toByteArray())
                    }
                }
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        streamRetryHost.storage.set("sc_client_id", "RotateMidwayId00000000000000001")
        val scStreamRetry = SoundCloudModule().also { it.attach(streamRetryHost) }

        val foundAfter403 = scStreamRetry.resolve(track, emptyMap()) as ResolveResult.Found
        assertEquals("https://cf-media.sndcdn.com/101-rotated.mp3", foundAfter403.stream.url)
        assertEquals("RotatedFreshId99999999999999999", streamRetryHost.storage.get("sc_client_id"))
        val transcodeCalls = streamRetryHost.requests.filter { "/prog-mp3/stream/progressive?" in it.url }
        assertEquals(2, transcodeCalls.size)
        assertTrue("client_id=RotateMidwayId00000000000000001" in transcodeCalls[0].url)
        assertTrue("client_id=RotatedFreshId99999999999999999" in transcodeCalls[1].url)
    }

    @Test
    fun `unreachable cooldown expires after 60 seconds`() {
        val host = FakeHost { HttpResponse(404, ByteArray(0)) }
        val sc = SoundCloudModule().also { it.attach(host) }
        host.storage.set("unreachable", "SoundCloud không phản hồi")
        host.storage.set("unreachable_at", (System.currentTimeMillis() - 30_000L).toString())
        assertTrue(sc.accountState() is AccountState.Expired)
        host.storage.set("unreachable_at", (System.currentTimeMillis() - 61_000L).toString())
        assertTrue(sc.accountState() is AccountState.SignedIn)
    }
}
