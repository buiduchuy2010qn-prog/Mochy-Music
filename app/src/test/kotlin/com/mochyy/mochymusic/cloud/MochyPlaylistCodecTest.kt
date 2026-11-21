package com.mochyy.mochymusic.cloud

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MochyPlaylistCodecTest {

    @Test
    fun urlCompactionAndExpansionRoundTripsAndPreservesRawHttpsUrls() {
        val cdnSamples = listOf(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" to "~yv/dQw4w9WgXcQ/hqdefault.jpg",
            "https://lh3.googleusercontent.com/a-/ALV-UjX123=w544-h544-l90-rj" to "~lh/a-/ALV-UjX123=w544-h544-l90-rj",
            "https://yt3.googleusercontent.com/ytc/AIdro_kAbCdEf=s900-c-k-c0x00ffffff-no-rj" to "~yt/ytc/AIdro_kAbCdEf=s900-c-k-c0x00ffffff-no-rj",
            "https://i1.sndcdn.com/artworks-000987654321-abcdef-t500x500.jpg" to "~sc/artworks-000987654321-abcdef-t500x500.jpg",
            "https://photo-resize-zmp3.zmdcdn.me/w600_r1x1_jpeg/cover/3/a/b/c/cover.jpg" to "~zm/w600_r1x1_jpeg/cover/3/a/b/c/cover.jpg",
        )

        for ((fullUrl, expectedCompact) in cdnSamples) {
            val compacted = compactCloudThumbUrl(fullUrl)
            assertEquals(expectedCompact, compacted)
            assertTrue(compacted.length < fullUrl.length)
            assertEquals(fullUrl, expandCloudThumbUrl(compacted))
            // Backward compatibility: raw uncompacted CDN URL decodes unchanged
            assertEquals(fullUrl, expandCloudThumbUrl(fullUrl))
        }

        val externalHttps = "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/cover.jpg"
        assertEquals(externalHttps, compactCloudThumbUrl(externalHttps))
        assertEquals(externalHttps, expandCloudThumbUrl(externalHttps))

        val externalHttp = "http://cdn.example.org/thumb.webp"
        assertEquals(externalHttp, compactCloudThumbUrl(externalHttp))
        assertEquals(externalHttp, expandCloudThumbUrl(externalHttp))

        assertEquals("", compactCloudThumbUrl(null))
        assertEquals("", compactCloudThumbUrl("   "))
        assertEquals("", compactCloudThumbUrl("null"))
        assertNull(expandCloudThumbUrl(null))
        assertNull(expandCloudThumbUrl(""))
        assertNull(expandCloudThumbUrl("null"))

        assertFalse(isCloudSafeTrackId("LOCAL_981723"))
        assertFalse(isCloudSafeTrackId("local_mp3_01"))
        assertFalse(isCloudSafeTrackId("LA12345"))
        assertFalse(isCloudSafeTrackId("content://media/external/audio/media/42"))
        assertFalse(isCloudSafeTrackId("file:///sdcard/Music/song.flac"))
        assertTrue(isCloudSafeTrackId("dQw4w9WgXcQ"))
        assertTrue(isCloudSafeTrackId("ZWAFU89B"))
        assertTrue(isCloudSafeTrackId("sc_1948273645"))
    }

    @Test
    fun playlistCodecFitsBudgetFor25PlaylistsBy80TracksAndRoundTripsCleanly() {
        val thumbSamples = listOf(
            { id: String -> "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
            { id: String -> "https://lh3.googleusercontent.com/img/$id=w544-h544-l90-rj" },
            { id: String -> "https://yt3.googleusercontent.com/ytc/$id=s900" },
            { id: String -> "https://i1.sndcdn.com/artworks-$id-t500x500.jpg" },
            { id: String -> "https://photo-resize-zmp3.zmdcdn.me/w600_r1x1_jpeg/cover/$id.jpg" },
            { id: String -> "https://cdn.example.com/covers/$id.webp" },
        )

        val playlists = (0 until 25).map { p ->
            val tracks = (0 until 80).map { t ->
                val songId = "yt_${p.toString().padStart(2, '0')}_${t.toString().padStart(3, '0')}"
                val makeThumb = thumbSamples[(p + t) % thumbSamples.size]
                CloudPlaylistTrack(
                    songId = songId,
                    title = "Bài Hát Yêu Thích Số ${p + 1}-${t + 1}",
                    artists = "Nghệ Sĩ Việt ${(t % 18) + 1}, Khách Mời ${(p % 7) + 1}",
                    albumName = if (t % 4 == 0) null else "Tuyển Tập Đêm Mưa ${p + 1}",
                    duration = 185 + (t * 3),
                    thumbnailUrl = makeThumb(songId),
                )
            }
            CloudPlaylistRecord(
                id = "PL_MOCHY_${p.toString().padStart(2, '0')}",
                name = "Danh Sách Phát Cá Nhân ${p + 1}",
                updatedEpochSec = 1_760_000_000L + p * 60L,
                tracks = tracks,
            )
        }

        val encoded = MochyPlaylistCodec.encode(playlists)
        assertTrue("Encoded playlists should not be blank", encoded.isNotBlank())
        assertTrue(
            "Encoded playlists length (${encoded.length}) must be <= 5600",
            encoded.length <= 5_600,
        )

        val rawWireJson = gunzipFromBase64(encoded)
        assertTrue(rawWireJson.contains("~yv/"))
        assertTrue(rawWireJson.contains("~lh/"))
        assertTrue(rawWireJson.contains("~sc/"))
        assertTrue(rawWireJson.contains("~zm/"))

        val decoded = MochyPlaylistCodec.decode(encoded)
        assertEquals(25, decoded.size)
        for (p in decoded.indices) {
            val expectedPl = playlists[p]
            val actualPl = decoded[p]
            assertEquals(expectedPl.id, actualPl.id)
            assertEquals(expectedPl.name, actualPl.name)
            assertEquals(expectedPl.updatedEpochSec, actualPl.updatedEpochSec)
            assertTrue("Decoded playlist $p must retain tracks", actualPl.tracks.isNotEmpty())
            for (t in actualPl.tracks.indices) {
                val expectedTrack = expectedPl.tracks[t]
                val actualTrack = actualPl.tracks[t]
                assertEquals(expectedTrack.songId, actualTrack.songId)
                assertEquals(expectedTrack.title, actualTrack.title)
                assertEquals(expectedTrack.artists, actualTrack.artists)
                assertEquals(expectedTrack.albumName, actualTrack.albumName)
                assertEquals(expectedTrack.duration, actualTrack.duration)
                assertEquals(expectedTrack.thumbnailUrl, actualTrack.thumbnailUrl)
                assertTrue(actualTrack.thumbnailUrl!!.startsWith("https://"))
            }
        }
    }

    @Test
    fun playlistCodecDecodesLegacyOversizedCloudPayloadWithoutDataLoss() {
        // Simulate a legacy v11.4/v11.5 payload (> 6 KB Base64, padded '=', escaped '\/' slashes, raw https URLs)
        val plsJson = (0 until 15).joinToString(",") { p ->
            val songsJson = (0 until 45).joinToString(",") { t ->
                val id = "legacy_${p}_${t}_abcdefgh"
                """["$id","Bản Nhạc Cũ $p-$t","Ca Sĩ $t","Album $p",215,"https:\/\/i.ytimg.com\/vi\/$id\/hqdefault.jpg"]"""
            }
            """{"id":"LEGACY_PL_$p","n":"Playlist Cũ $p","u":${1_750_000_000L + p},"s":[$songsJson]}"""
        }
        val legacyRaw = """{"v":1,"pls":[$plsJson]}"""
        val legacyPaddedB64 = Base64.getEncoder().encodeToString(gzipBytes(legacyRaw))
        assertTrue("Legacy fixture should exceed 6000 chars (was ${legacyPaddedB64.length})", legacyPaddedB64.length > 6_000)

        val decoded = MochyPlaylistCodec.decode(legacyPaddedB64)
        assertEquals(15, decoded.size)
        assertEquals(45, decoded.first().tracks.size)
        assertEquals(
            "https://i.ytimg.com/vi/legacy_0_0_abcdefgh/hqdefault.jpg",
            decoded.first().tracks.first().thumbnailUrl,
        )

        val reEncoded = MochyPlaylistCodec.encode(decoded)
        assertTrue(reEncoded.length <= 5_600)
    }

    @Test
    fun libraryExtraCodecFitsBudgetFor200AlbumsAnd200ArtistsWithAudioAndModules() {
        val albums = (0 until 200).map { i ->
            CloudAlbumRecord(
                id = "MPREb_album_${i.toString().padStart(3, '0')}",
                playlistId = "OLAK5uy_pl_${i.toString().padStart(3, '0')}",
                title = "Album Tuyển Chọn Số ${i + 1}",
                artists = "Nghệ Sĩ Album ${(i % 40) + 1}",
                year = 2018 + (i % 8),
                songCount = 10 + (i % 12),
                duration = 2_100 + i * 15,
                thumbnailUrl = if (i % 2 == 0) {
                    "https://lh3.googleusercontent.com/alb/cover_$i=w544-h544-l90-rj"
                } else {
                    "https://photo-resize-zmp3.zmdcdn.me/w600_r1x1_jpeg/cover/alb_$i.jpg"
                },
                bookmarkedEpochSec = 1_760_100_000L + i,
            )
        }

        val artists = (0 until 200).map { i ->
            CloudArtistRecord(
                id = "UC_artist_${i.toString().padStart(3, '0')}",
                name = "Ca Sĩ Theo Dõi ${i + 1}",
                channelId = "UC_chan_${i.toString().padStart(3, '0')}",
                thumbnailUrl = "https://yt3.googleusercontent.com/ytc/artist_$i=s900-c-k-c0x00ffffff-no-rj",
                bookmarkedEpochSec = 1_760_200_000L + i,
            )
        }

        val audio = CloudAudioConfig(
            eqSettingsJson = """{"enabled":true,"preset":"Acoustic","bands":[2.5,1.5,0.0,1.0,3.0]}""",
            audioNormalization = true,
            audioQuality = "HIGH",
            reverbJson = """{"enabled":false,"room":0.25}""",
            loudnessJson = """{"enabled":true,"gainMb":150}""",
            spatialJson = """{"enabled":false}""",
            normalizeJson = """{"targetLufs":-14.0}""",
            volumeJson = """{"boost":1.0}""",
            playbackJson = """{"speed":1.0,"pitch":1.0}""",
            skipSilence = false,
            eightDJson = """{"enabled":true,"rotationSpeedHz":0.15,"depth":0.85,"reverbLevel":0.4}""",
            playerCoverZoom = 0.75f,
            canvasBestQuality = true,
        )

        val modulesConfig = CloudModulesConfig(
            enabled = mapOf(
                "mochy.source.zingmp3" to true,
                "mochy.source.soundcloud" to true,
                "mochy.source.monochrome" to true,
            ),
            picks = mapOf("zingmp3_quality" to "320", "soundcloud_prefer" to "progressive"),
            fields = mapOf("region" to "VN"),
            moduleOrder = "mochy.source.zingmp3,mochy.source.soundcloud,mochy.source.monochrome",
            providerOrder = "zingmp3,soundcloud,monochrome,innertube",
            downloadOrder = "monochrome,zingmp3,innertube",
            webModulesJson = """[{"base":"https://raw.githubusercontent.com/buiduchuy2010qn-prog/Mochy-Music/main/modules/liquid-glass","manifest":"manifest.json"}]""",
            monochromeServersJson = """[{"url":"https://api.monochrome.tf","kind":"api"}]""",
        )

        val speedDial = (0 until 6).map { i ->
            CloudSpeedDialRecord(
                id = "sd_song_$i",
                secondaryId = "PL_SD_$i",
                title = "Ghim Nhanh ${i + 1}",
                subtitle = "Nghệ Sĩ ${i + 1}",
                thumbnailUrl = "https://i.ytimg.com/vi/sd_song_$i/hqdefault.jpg",
                type = "SONG",
                explicit = i % 2 == 1,
                createDate = 1_760_300_000_000L + i,
            )
        }

        val extra = CloudLibraryExtra(
            albums = albums,
            artists = artists,
            audio = audio,
            speedDial = speedDial,
            recognitionHistory = listOf(
                CloudRecognitionRecord(
                    trackId = "shazam_001",
                    title = "Nơi Này Có Anh",
                    artist = "Sơn Tùng M-TP",
                    album = "Single",
                    coverArtUrl = "https://i1.sndcdn.com/artworks-shazam001-t500x500.jpg",
                    coverArtHqUrl = "https://lh3.googleusercontent.com/hq/shazam001=w1000-h1000",
                    youtubeVideoId = "FN7ALfpGxiI",
                    recognizedAtEpochMs = 1_760_400_000_000L,
                    liked = true,
                ),
            ),
            lyricsOffsets = mapOf("FN7ALfpGxiI" to 250, "dQw4w9WgXcQ" to -150),
            modulesConfig = modulesConfig,
        )

        val encoded = MochyLibraryExtraCodec.encode(extra)
        assertTrue("Encoded libraryExtra should not be blank", encoded.isNotBlank())
        assertTrue(
            "Encoded libraryExtra length (${encoded.length}) must be <= 3800",
            encoded.length <= 3_800,
        )

        val decoded = MochyLibraryExtraCodec.decode(encoded)
        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.albums.isNotEmpty())
        assertTrue(decoded.artists.isNotEmpty())
        assertEquals(albums.take(decoded.albums.size), decoded.albums)
        assertEquals(artists.take(decoded.artists.size), decoded.artists)
        assertEquals(audio, decoded.audio)
        assertEquals(modulesConfig, decoded.modulesConfig)
        assertEquals(speedDial, decoded.speedDial)

        // Legacy decode compatibility: webModulesJson > 2048 chars (up to 16384) still decodes without loss
        val largeWebJson = "[" + (0 until 35).joinToString(",") { idx ->
            """{"base":"https://cdn.example.org/modules/pack-$idx-long-path-segment","manifest":"manifest.json"}"""
        } + "]"
        assertTrue(largeWebJson.length in 2_500..16_384)
        val legacyExtraGz = gzipToBase64("""{"v":2,"alb":[],"art":[],"mod":{"web":${JsonPrimitive(largeWebJson)}}}""")
        val decodedLegacyExtra = MochyLibraryExtraCodec.decode(legacyExtraGz)
        assertNotNull(decodedLegacyExtra)
        assertEquals(largeWebJson, decodedLegacyExtra!!.modulesConfig?.webModulesJson)
    }

    @Test
    fun playbackResumeCodecFitsStrictBudgetAndExpandsThumbnails() {
        val queue = (0 until 25).map { idx ->
            CloudPlaylistTrack(
                songId = "q_track_${idx.toString().padStart(2, '0')}",
                title = "Bài Tiếp Theo ${idx + 1}",
                artists = "Nghệ Sĩ Hàng Đợi ${(idx % 9) + 1}",
                albumName = "Album Hàng Đợi ${(idx % 5) + 1}",
                duration = 200 + idx * 4,
                thumbnailUrl = "https://i.ytimg.com/vi/q_track_$idx/hqdefault.jpg",
            )
        }
        val resume = CloudPlaybackResume(
            deviceId = "dev_pixel9pro_01",
            deviceName = "Pixel 9 Pro",
            songId = "main_song_id_01",
            title = "Chúng Ta Của Hiện Tại",
            artists = "Sơn Tùng M-TP",
            albumName = "Chúng Ta",
            durationSec = 301,
            thumbnailUrl = "https://lh3.googleusercontent.com/a-/resume_cover=w544-h544",
            positionMs = 142_500L,
            queueTracks = queue,
            updatedAtEpochMs = 1_760_500_000_000L,
        )

        val encoded = MochyPlaybackResumeCodec.encode(resume)
        assertTrue(encoded.isNotBlank())
        assertTrue(
            "Encoded playbackResume length (${encoded.length}) must be <= 1200",
            encoded.length <= 1_200,
        )

        val decoded = MochyPlaybackResumeCodec.decode(encoded)
        assertNotNull(decoded)
        decoded!!
        assertEquals(resume.deviceId, decoded.deviceId)
        assertEquals(resume.deviceName, decoded.deviceName)
        assertEquals(resume.songId, decoded.songId)
        assertEquals(resume.title, decoded.title)
        assertEquals(resume.artists, decoded.artists)
        assertEquals(resume.positionMs, decoded.positionMs)
        assertEquals(resume.thumbnailUrl, decoded.thumbnailUrl)
        assertTrue(decoded.queueTracks.isNotEmpty())
        assertEquals(queue.take(decoded.queueTracks.size), decoded.queueTracks)

        val maxCombinedMetadataChars =
            MochyPlaylistCodec.MaxEncodedChars +
                MochyLibraryExtraCodec.MaxEncodedChars +
                MochyPlaybackResumeCodec.MaxEncodedChars
        assertEquals(10_600, maxCombinedMetadataChars)
    }

    @Test
    fun libraryExtraCodecHandlesV12AudioFieldsAndLegacyPayloadsSafely() {
        val v12OnlyAudio = CloudAudioConfig(
            eightDJson = """{"enabled":true,"rotationSpeedHz":0.5,"depth":1.0,"reverbLevel":0.85}""",
            playerCoverZoom = 1.35f, // coerced into [0f, 1f]
            canvasBestQuality = false,
        )
        val encoded = MochyLibraryExtraCodec.encode(CloudLibraryExtra(audio = v12OnlyAudio))
        assertTrue(encoded.isNotBlank())

        val decoded = MochyLibraryExtraCodec.decode(encoded)
        val audio = requireNotNull(decoded?.audio)
        assertEquals(v12OnlyAudio.eightDJson, audio.eightDJson)
        assertEquals(1.0f, requireNotNull(audio.playerCoverZoom), 0.0001f)
        assertEquals(false, audio.canvasBestQuality)

        // Legacy payload without "ed", "cz", "cb" still decodes cleanly with null defaults
        val legacyGz = gzipToBase64("""{"v":2,"alb":[],"art":[],"aq":"HIGH","ss":true}""")
        val decodedLegacy = MochyLibraryExtraCodec.decode(legacyGz)
        val legacyAudio = requireNotNull(decodedLegacy?.audio)
        assertEquals("HIGH", legacyAudio.audioQuality)
        assertEquals(true, legacyAudio.skipSilence)
        assertNull(legacyAudio.eightDJson)
        assertNull(legacyAudio.playerCoverZoom)
        assertNull(legacyAudio.canvasBestQuality)
    }
}
