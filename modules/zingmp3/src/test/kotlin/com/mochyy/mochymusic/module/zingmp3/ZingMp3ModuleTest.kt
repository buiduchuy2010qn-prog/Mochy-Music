package com.mochyy.mochymusic.module.zingmp3

import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ZingMp3ModuleTest {

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
        override val folder: File = createTempDirectory("zingmp3").toFile()
    }

    private fun module(
        streamBody: String = """{"err":0,"data":{"128":"https://cdn.zingmp3.vn/audio/128.mp3","320":"https://cdn.zingmp3.vn/audio/320.mp3"}}""",
    ): Pair<ZingMp3Module, FakeHost> {
        val searchPayload = """
            {
              "err": 0,
              "data": {
                "items": [
                  {
                    "encodeId": "Z6ABC123",
                    "title": "Chúng Ta Của Hiện Tại",
                    "artistsNames": "Sơn Tùng M-TP",
                    "album": { "title": "Chúng Ta Của Hiện Tại (Single)" },
                    "duration": 301,
                    "streamingStatus": 1
                  },
                  {
                    "encodeId": "Z6VIP999",
                    "title": "Chúng Ta Của Hiện Tại (VIP)",
                    "artistsNames": "Sơn Tùng M-TP",
                    "duration": 301,
                    "streamingStatus": 2
                  }
                ]
              }
            }
        """.trimIndent()
        val host = FakeHost { request ->
            when {
                request.url == "https://zingmp3.vn/" ->
                    HttpResponse(200, ByteArray(0), mapOf("set-cookie" to "zmp3_session=abc123; Path=/; HttpOnly"))
                "/api/v2/search?" in request.url ->
                    HttpResponse(200, searchPayload.toByteArray())
                "/api/v2/song/get/streaming?" in request.url ->
                    HttpResponse(200, streamBody.toByteArray())
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        return ZingMp3Module().also { it.attach(host) } to host
    }

    private val track = TrackQuery("Chúng Ta Của Hiện Tại", listOf("Sơn Tùng M-TP"), durationMs = 301_000)

    @Test
    fun `signature produces 128-char lowercase HMAC-SHA512 hex`() {
        val (zing, _) = module()
        val sig = zing.signature("/api/v2/search", mapOf("count" to "10", "ctime" to "1700000000", "page" to "1", "type" to "song", "version" to "1.22.4"))
        assertEquals(128, sig.length)
        assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' })
        val sig2 = zing.signature("/api/v2/search", mapOf("version" to "1.22.4", "type" to "song", "page" to "1", "ctime" to "1700000000", "count" to "10"))
        assertEquals(sig, sig2)
    }

    @Test
    fun `songsIn parses valid items and skips VIP-only entries`() {
        val (zing, _) = module()
        val root = Json.parseToJsonElement(
            """{"data":{"items":[{"encodeId":"A1","title":"Free Song","artistsNames":"Ca Sĩ","duration":200,"streamingStatus":1},{"encodeId":"V2","title":"VIP Song","artistsNames":"Ca Sĩ","duration":200,"streamingStatus":2}]}}"""
        ) as JsonObject
        val parsed = zing.songsIn(root)
        assertEquals(1, parsed.size)
        assertEquals("A1", parsed[0].id)
        assertEquals("Free Song", parsed[0].title)
        assertEquals(200_000L, parsed[0].durationMs)
    }

    @Test
    fun `resolves 320 kbps stream by default with session cookie, and 128 when selected`() = runBlocking {
        val (zing, host) = module()
        val found320 = zing.resolve(track, emptyMap()) as ResolveResult.Found
        assertEquals("https://cdn.zingmp3.vn/audio/320.mp3", found320.stream.url)
        assertEquals("MP3 320", found320.stream.qualityLabel)
        assertEquals("MP3", found320.stream.codec)
        val searchReq = host.requests.first { "/api/v2/search?" in it.url }
        assertEquals("zmp3_session=abc123", searchReq.headers["Cookie"])

        val found128 = zing.resolve(track, mapOf(ZingMp3Module.ChoiceQuality to ZingMp3Module.Option128)) as ResolveResult.Found
        assertEquals("https://cdn.zingmp3.vn/audio/128.mp3", found128.stream.url)
        assertEquals("MP3 128", found128.stream.qualityLabel)
    }

    @Test
    fun `falls back to 128 when 320 is VIP, and returns Unavailable when geo-blocked`() = runBlocking {
        val (zingVip320, _) = module("""{"err":0,"data":{"128":"https://cdn.zingmp3.vn/audio/128.mp3","320":"VIP"}}""")
        val found = zingVip320.resolve(track, mapOf(ZingMp3Module.ChoiceQuality to ZingMp3Module.Option320)) as ResolveResult.Found
        assertEquals("https://cdn.zingmp3.vn/audio/128.mp3", found.stream.url)
        assertEquals("MP3 128", found.stream.qualityLabel)

        val (zingGeo, _) = module("""{"err":-1110,"msg":"Blocked"}""")
        val blocked = zingGeo.resolve(track, emptyMap())
        assertTrue(blocked is ResolveResult.Unavailable)
    }
}
