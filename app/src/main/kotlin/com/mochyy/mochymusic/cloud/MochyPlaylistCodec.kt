package com.mochyy.mochymusic.cloud

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CloudPlaylistTrack(
    val songId: String,
    val title: String,
    val artists: String,
    val albumName: String?,
    val duration: Int,
    val thumbnailUrl: String?,
)

data class CloudPlaylistRecord(
    val id: String,
    val name: String,
    val updatedEpochSec: Long,
    val tracks: List<CloudPlaylistTrack>,
)

private const val MaxGzipEncodedChars = 16_384
private const val MaxGzipDecodedBytes = 512 * 1024

internal fun gzipBytes(rawUtf8: String): ByteArray {
    val baos = ByteArrayOutputStream()
    GZIPOutputStream(baos).use { gz ->
        gz.write(rawUtf8.toByteArray(Charsets.UTF_8))
    }
    return baos.toByteArray()
}

internal fun gzipToBase64(rawUtf8: String): String =
    Base64.encodeToString(gzipBytes(rawUtf8), Base64.NO_WRAP)

internal fun gunzipFromBase64(encoded: String): String {
    val compressed = Base64.decode(encoded.trim(), Base64.NO_WRAP)
    return GZIPInputStream(ByteArrayInputStream(compressed)).use { gz ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val read = gz.read(buf)
            if (read <= 0) break
            total += read
            if (total > MaxGzipDecodedBytes) {
                error("Dữ liệu nén vượt ngưỡng an toàn")
            }
            out.write(buf, 0, read)
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }
}

/**
 * Đóng gói Playlist cá nhân siêu nhẹ bằng JSON + GZIP + Base64 để lưu trực tiếp
 * vào `user_metadata.playlists_gz` của Mochy Cloud mà không cần thêm bảng SQL.
 */
object MochyPlaylistCodec {

    private const val MaxPlaylists = 25
    private const val MaxEncodedChars = MaxGzipEncodedChars
    private val TrackLimits = intArrayOf(200, 120, 60, 30, 15)

    fun encode(playlists: List<CloudPlaylistRecord>): String {
        val cappedPlaylists = playlists.take(MaxPlaylists)
        if (cappedPlaylists.isEmpty()) return ""
        for (limit in TrackLimits) {
            val candidate = compressToBase64(cappedPlaylists, limit)
            if (candidate.length <= MaxEncodedChars) {
                return candidate
            }
        }
        // Giảm dần số playlist nếu người dùng có nhiều danh sách dài
        var subset = cappedPlaylists
        while (subset.size > 1) {
            subset = subset.dropLast(1)
            val candidate = compressToBase64(subset, 25)
            if (candidate.length <= MaxEncodedChars) {
                return candidate
            }
        }
        val tenTrack = compressToBase64(subset, 10)
        if (tenTrack.length <= MaxEncodedChars) return tenTrack
        return compressToBase64(subset, 5).takeIf { it.length <= MaxEncodedChars }.orEmpty()
    }

    fun decode(playlistsGz: String?): List<CloudPlaylistRecord> {
        if (playlistsGz.isNullOrBlank()) return emptyList()
        return runCatching {
            val jsonStr = gunzipFromBase64(playlistsGz)
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("pls") ?: return@runCatching emptyList()
            buildList(minOf(arr.length(), MaxPlaylists)) {
                for (i in 0 until minOf(arr.length(), MaxPlaylists)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    val name = o.optString("n").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    val updatedSec = o.optLong("u", 0L).coerceAtLeast(0L)
                    val songsArr = o.optJSONArray("s") ?: JSONArray()
                    val tracks = buildList(minOf(songsArr.length(), 200)) {
                        for (j in 0 until minOf(songsArr.length(), 200)) {
                            val row = songsArr.optJSONArray(j) ?: continue
                            val songId = row.optString(0).trim()
                            if (songId.isBlank()) continue
                            add(
                                CloudPlaylistTrack(
                                    songId = songId,
                                    title = row.optString(1),
                                    artists = row.optString(2),
                                    albumName = row.optString(3).takeIf { it.isNotBlank() && it != "null" },
                                    duration = row.optInt(4, -1),
                                    thumbnailUrl = row.optString(5).takeIf { it.isNotBlank() && it != "null" },
                                ),
                            )
                        }
                    }
                    add(
                        CloudPlaylistRecord(
                            id = id,
                            name = name,
                            updatedEpochSec = updatedSec,
                            tracks = tracks,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun compressToBase64(playlists: List<CloudPlaylistRecord>, maxTracksPerPlaylist: Int): String {
        val plsArr = JSONArray()
        for (pl in playlists) {
            val songsArr = JSONArray()
            for (t in pl.tracks.take(maxTracksPerPlaylist)) {
                songsArr.put(
                    JSONArray().apply {
                        put(t.songId.take(64))
                        put(t.title.take(120))
                        put(t.artists.take(120))
                        put(t.albumName.orEmpty().take(120))
                        put(t.duration.coerceIn(-1, 86400))
                        put(t.thumbnailUrl.orEmpty().take(320))
                    },
                )
            }
            plsArr.put(
                JSONObject().apply {
                    put("id", pl.id.take(48))
                    put("n", pl.name.take(120))
                    put("u", pl.updatedEpochSec)
                    put("s", songsArr)
                },
            )
        }
        val root = JSONObject().apply {
            put("v", 1)
            put("pls", plsArr)
        }.toString()
        return gzipToBase64(root)
    }
}

data class CloudAlbumRecord(
    val id: String,
    val playlistId: String?,
    val title: String,
    val artists: String,
    val year: Int?,
    val songCount: Int,
    val duration: Int,
    val thumbnailUrl: String?,
    val bookmarkedEpochSec: Long,
)

data class CloudArtistRecord(
    val id: String,
    val name: String,
    val channelId: String?,
    val thumbnailUrl: String?,
    val bookmarkedEpochSec: Long,
)

data class CloudAudioConfig(
    val eqSettingsJson: String? = null,
    val audioNormalization: Boolean? = null,
    val audioQuality: String? = null,
    val reverbJson: String? = null,
    val loudnessJson: String? = null,
    val spatialJson: String? = null,
    val normalizeJson: String? = null,
    val volumeJson: String? = null,
    val playbackJson: String? = null,
    val skipSilence: Boolean? = null,
)

data class CloudSpeedDialRecord(
    val id: String,
    val secondaryId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val type: String,
    val explicit: Boolean = false,
    val createDate: Long = 0L,
)

data class CloudRecognitionRecord(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val coverArtHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val shazamUrl: String? = null,
    val appleMusicUrl: String? = null,
    val spotifyUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null,
    val recognizedAtEpochMs: Long = 0L,
    val liked: Boolean = false,
)

data class CloudModulesConfig(
    val enabled: Map<String, Boolean> = emptyMap(),
    val picks: Map<String, String> = emptyMap(),
    val fields: Map<String, String> = emptyMap(),
    val moduleOrder: String? = null,
    val providerOrder: String? = null,
    val downloadOrder: String? = null,
    val webModulesJson: String? = null,
    val monochromeServersJson: String? = null,
)

data class CloudLibraryExtra(
    val albums: List<CloudAlbumRecord> = emptyList(),
    val artists: List<CloudArtistRecord> = emptyList(),
    val audio: CloudAudioConfig? = null,
    val albumsTruncated: Boolean = false,
    val artistsTruncated: Boolean = false,
    val speedDial: List<CloudSpeedDialRecord> = emptyList(),
    val recognitionHistory: List<CloudRecognitionRecord> = emptyList(),
    val lyricsOffsets: Map<String, Int> = emptyMap(),
    val modulesConfig: CloudModulesConfig? = null,
    val speedDialTruncated: Boolean = false,
    val recognitionHistoryTruncated: Boolean = false,
    val lyricsOffsetsTruncated: Boolean = false,
)

typealias MochyLibraryExtraPayload = CloudLibraryExtra

/**
 * Đóng gói Album đã lưu, Nghệ sĩ theo dõi, Equalizer, Speed Dial, Nhận diện nhạc,
 * độ lệch lời & cấu hình Module bằng JSON + GZIP + Base64 vào `user_metadata.library_extra_gz`
 * (giữ gzipped < 24 KB và Base64 <= 16.384 ký tự, không cần bảng Supabase mới).
 */
object MochyLibraryExtraCodec {

    private const val MaxEncodedChars = MaxGzipEncodedChars
    private const val MaxGzipBytes = 24_576
    private const val MaxSpeedDialItems = 9
    private const val MaxRecognitionItems = 35
    private const val MaxLyricsOffsets = 300
    private const val MaxWebModulesChars = 16_384
    private val Caps = intArrayOf(200, 140, 90, 50, 25, 10, 0)
    private val ExtraStepCaps = listOf(
        MaxRecognitionItems to MaxLyricsOffsets,
        20 to 150,
        10 to 60,
        0 to 0,
    )

    fun encode(extra: CloudLibraryExtra): String {
        val hasAudio = extra.audio != null && (
            !extra.audio.eqSettingsJson.isNullOrBlank() ||
                extra.audio.audioNormalization != null ||
                !extra.audio.audioQuality.isNullOrBlank() ||
                !extra.audio.reverbJson.isNullOrBlank() ||
                !extra.audio.loudnessJson.isNullOrBlank() ||
                !extra.audio.spatialJson.isNullOrBlank() ||
                !extra.audio.normalizeJson.isNullOrBlank() ||
                !extra.audio.volumeJson.isNullOrBlank() ||
                !extra.audio.playbackJson.isNullOrBlank() ||
                extra.audio.skipSilence != null
            )
        val hasModules = extra.modulesConfig != null && (
            extra.modulesConfig.enabled.isNotEmpty() ||
                extra.modulesConfig.picks.isNotEmpty() ||
                extra.modulesConfig.fields.isNotEmpty() ||
                !extra.modulesConfig.moduleOrder.isNullOrBlank() ||
                !extra.modulesConfig.providerOrder.isNullOrBlank() ||
                !extra.modulesConfig.downloadOrder.isNullOrBlank() ||
                !extra.modulesConfig.webModulesJson.isNullOrBlank() ||
                !extra.modulesConfig.monochromeServersJson.isNullOrBlank()
            )
        val cleanOffsets = extra.lyricsOffsets.entries
            .filter { it.key.isNotBlank() && it.value != 0 }
        if (extra.albums.isEmpty() &&
            extra.artists.isEmpty() &&
            !hasAudio &&
            extra.speedDial.isEmpty() &&
            extra.recognitionHistory.isEmpty() &&
            cleanOffsets.isEmpty() &&
            !hasModules
        ) {
            return ""
        }

        val cappedSpeedDial = extra.speedDial.take(MaxSpeedDialItems)
        val cappedRecs = extra.recognitionHistory.take(MaxRecognitionItems)
        val cappedOffsets = cleanOffsets.take(MaxLyricsOffsets)
        val modulesFull = extra.modulesConfig?.let { cfg ->
            cfg.copy(
                webModulesJson = cfg.webModulesJson
                    ?.takeIf { it.isNotBlank() && it.length <= MaxWebModulesChars },
            )
        }
        val modulesNoWeb = modulesFull?.copy(webModulesJson = null)

        fun tryEncode(
            albumArtistCap: Int,
            recCap: Int,
            offsetCap: Int,
            speedDialList: List<CloudSpeedDialRecord>,
            modules: CloudModulesConfig?,
            audio: CloudAudioConfig?,
        ): String? {
            val albumsSlice = if (albumArtistCap == 0) emptyList() else extra.albums.take(albumArtistCap)
            val artistsSlice = if (albumArtistCap == 0) emptyList() else extra.artists.take(albumArtistCap)
            val recSlice = if (recCap == 0) emptyList() else cappedRecs.take(recCap)
            val offsetSlice = if (offsetCap == 0) emptyList() else cappedOffsets.take(offsetCap)
            val raw = buildJsonString(
                albums = albumsSlice,
                artists = artistsSlice,
                audio = audio,
                albumsTruncated = albumsSlice.size < extra.albums.size,
                artistsTruncated = artistsSlice.size < extra.artists.size,
                speedDial = speedDialList,
                recognitionHistory = recSlice,
                lyricsOffsets = offsetSlice,
                modulesConfig = modules,
                speedDialTruncated = speedDialList.size < extra.speedDial.size,
                recognitionHistoryTruncated = recSlice.size < extra.recognitionHistory.size,
                lyricsOffsetsTruncated = offsetSlice.size < cleanOffsets.size,
            )
            val gz = gzipBytes(raw)
            if (gz.size >= MaxGzipBytes) return null
            val b64 = Base64.encodeToString(gz, Base64.NO_WRAP)
            return b64.takeIf { it.length <= MaxEncodedChars }
        }

        // 1. Giữ nguyên album/artist/speedDial/modules, cắt dần recognitionHistory & lyricsOffsets trước
        for ((recCap, offsetCap) in ExtraStepCaps) {
            tryEncode(200, recCap, offsetCap, cappedSpeedDial, modulesFull, extra.audio)?.let { return it }
        }
        // 2. Giảm dần album/artist nếu vẫn vượt ngưỡng nén
        for (cap in Caps.drop(1)) {
            for ((recCap, offsetCap) in listOf(10 to 60, 0 to 0)) {
                tryEncode(cap, recCap, offsetCap, cappedSpeedDial, modulesFull, extra.audio)?.let { return it }
            }
        }
        // 3. Nếu webModulesJson quá lớn khiến vượt 16 KB Base64, bỏ webModulesJson nhưng giữ switch/picks/order/monochrome
        if (modulesFull?.webModulesJson != null) {
            for ((recCap, offsetCap) in ExtraStepCaps) {
                tryEncode(200, recCap, offsetCap, cappedSpeedDial, modulesNoWeb, extra.audio)?.let { return it }
            }
            for (cap in Caps.drop(1)) {
                tryEncode(cap, 0, 0, cappedSpeedDial, modulesNoWeb, extra.audio)?.let { return it }
            }
        }
        // 4. Fallback cuối: giữ cấu hình nhẹ (bỏ fields trước, rồi mới bỏ toàn bộ modules nếu vẫn vượt) + audio
        tryEncode(0, 0, 0, emptyList(), modulesNoWeb, extra.audio)?.let { return it }
        tryEncode(0, 0, 0, emptyList(), modulesNoWeb?.copy(fields = emptyMap()), extra.audio)?.let { return it }
        return tryEncode(0, 0, 0, emptyList(), null, extra.audio).orEmpty()
    }

    fun decode(libraryExtraGz: String?): CloudLibraryExtra? {
        if (libraryExtraGz.isNullOrBlank()) return null
        return runCatching {
            val jsonStr = gunzipFromBase64(libraryExtraGz)
            val root = JSONObject(jsonStr)
            val albArr = root.optJSONArray("alb") ?: JSONArray()
            val albums = buildList(albArr.length()) {
                for (i in 0 until albArr.length()) {
                    val row = albArr.optJSONArray(i) ?: continue
                    val id = row.optString(0).trim()
                    val title = row.optString(2).trim()
                    if (id.isBlank() || title.isBlank()) continue
                    add(
                        CloudAlbumRecord(
                            id = id,
                            playlistId = row.optString(1).takeIf { it.isNotBlank() && it != "null" },
                            title = title,
                            artists = row.optString(3),
                            year = row.optInt(4, 0).takeIf { it > 0 },
                            songCount = row.optInt(5, 0).coerceAtLeast(0),
                            duration = row.optInt(6, 0).coerceAtLeast(0),
                            thumbnailUrl = row.optString(7).takeIf { it.isNotBlank() && it != "null" },
                            bookmarkedEpochSec = row.optLong(8, 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }

            val artArr = root.optJSONArray("art") ?: JSONArray()
            val artists = buildList(artArr.length()) {
                for (i in 0 until artArr.length()) {
                    val row = artArr.optJSONArray(i) ?: continue
                    val id = row.optString(0).trim()
                    val name = row.optString(1).trim()
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        CloudArtistRecord(
                            id = id,
                            name = name,
                            channelId = row.optString(2).takeIf { it.isNotBlank() && it != "null" },
                            thumbnailUrl = row.optString(3).takeIf { it.isNotBlank() && it != "null" },
                            bookmarkedEpochSec = row.optLong(4, 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }

            val eqJson = root.optString("eq").takeIf { it.isNotBlank() && it != "null" }
            val norm = if (root.has("an")) root.optBoolean("an") else null
            val quality = root.optString("aq").takeIf { it.isNotBlank() && it != "null" }
            val revJson = root.optString("rev").takeIf { it.isNotBlank() && it != "null" }
            val loudJson = root.optString("ld").takeIf { it.isNotBlank() && it != "null" }
            val spatJson = root.optString("sp").takeIf { it.isNotBlank() && it != "null" }
            val normJson = root.optString("nm").takeIf { it.isNotBlank() && it != "null" }
            val volJson = root.optString("vl").takeIf { it.isNotBlank() && it != "null" }
            val pbJson = root.optString("pb").takeIf { it.isNotBlank() && it != "null" }
            val skipSil = if (root.has("ss")) root.optBoolean("ss") else null
            val audio = if (eqJson != null || norm != null || quality != null ||
                revJson != null || loudJson != null || spatJson != null ||
                normJson != null || volJson != null || pbJson != null || skipSil != null
            ) {
                CloudAudioConfig(
                    eqSettingsJson = eqJson,
                    audioNormalization = norm,
                    audioQuality = quality,
                    reverbJson = revJson,
                    loudnessJson = loudJson,
                    spatialJson = spatJson,
                    normalizeJson = normJson,
                    volumeJson = volJson,
                    playbackJson = pbJson,
                    skipSilence = skipSil,
                )
            } else null

            val sdArr = root.optJSONArray("sd") ?: JSONArray()
            val speedDial = buildList(minOf(sdArr.length(), MaxSpeedDialItems)) {
                for (i in 0 until minOf(sdArr.length(), MaxSpeedDialItems)) {
                    val row = sdArr.optJSONArray(i) ?: continue
                    val id = row.optString(0).trim()
                    val title = row.optString(2).trim()
                    val type = row.optString(5).trim()
                    if (id.isBlank() || title.isBlank() || type.isBlank()) continue
                    add(
                        CloudSpeedDialRecord(
                            id = id,
                            secondaryId = row.optString(1).takeIf { it.isNotBlank() && it != "null" },
                            title = title,
                            subtitle = row.optString(3).takeIf { it.isNotBlank() && it != "null" },
                            thumbnailUrl = row.optString(4).takeIf { it.isNotBlank() && it != "null" },
                            type = type,
                            explicit = row.optBoolean(6, false),
                            createDate = row.optLong(7, 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }

            val recArr = root.optJSONArray("rec") ?: JSONArray()
            val recognitionHistory = buildList(minOf(recArr.length(), MaxRecognitionItems)) {
                for (i in 0 until minOf(recArr.length(), MaxRecognitionItems)) {
                    val row = recArr.optJSONArray(i) ?: continue
                    val trackId = row.optString(0).trim()
                    val title = row.optString(1).trim()
                    val artist = row.optString(2).trim()
                    if (trackId.isBlank() || title.isBlank()) continue
                    add(
                        CloudRecognitionRecord(
                            trackId = trackId,
                            title = title,
                            artist = artist,
                            album = row.optString(3).takeIf { it.isNotBlank() && it != "null" },
                            coverArtUrl = row.optString(4).takeIf { it.isNotBlank() && it != "null" },
                            youtubeVideoId = row.optString(5).takeIf { it.isNotBlank() && it != "null" },
                            recognizedAtEpochMs = row.optLong(6, 0L).coerceAtLeast(0L),
                            liked = row.optBoolean(7, false),
                            genre = row.optString(8).takeIf { it.isNotBlank() && it != "null" },
                            isrc = row.optString(9).takeIf { it.isNotBlank() && it != "null" },
                            shazamUrl = row.optString(10).takeIf { it.isNotBlank() && it != "null" },
                            coverArtHqUrl = row.optString(11).takeIf { it.isNotBlank() && it != "null" },
                            releaseDate = row.optString(12).takeIf { it.isNotBlank() && it != "null" },
                            label = row.optString(13).takeIf { it.isNotBlank() && it != "null" },
                            appleMusicUrl = row.optString(14).takeIf { it.isNotBlank() && it != "null" },
                            spotifyUrl = row.optString(15).takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }
            }

            val loObj = root.optJSONObject("lo")
            val lyricsOffsets = if (loObj != null) {
                buildMap {
                    val keys = loObj.keys()
                    while (keys.hasNext() && size < MaxLyricsOffsets) {
                        val songId = keys.next().trim()
                        val offset = loObj.optInt(songId, 0)
                        if (songId.isNotBlank() && offset != 0) {
                            put(songId, offset)
                        }
                    }
                }
            } else emptyMap()

            val modObj = root.optJSONObject("mod")
            val modulesConfig = if (modObj != null) {
                val enObj = modObj.optJSONObject("en")
                val enabled = buildMap {
                    if (enObj != null) {
                        val keys = enObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next().trim()
                            if (k.isNotBlank()) put(k, enObj.optBoolean(k, false))
                        }
                    }
                }
                val pkObj = modObj.optJSONObject("pk")
                val picks = buildMap {
                    if (pkObj != null) {
                        val keys = pkObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next().trim()
                            val v = pkObj.optString(k).takeIf { it.isNotBlank() && it != "null" }
                            if (k.isNotBlank() && v != null) put(k, v)
                        }
                    }
                }
                val fldObj = modObj.optJSONObject("fld")
                val fields = buildMap {
                    if (fldObj != null) {
                        val keys = fldObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next().trim()
                            val v = fldObj.optString(k).takeIf { it.isNotBlank() && it != "null" }
                            if (k.isNotBlank() && v != null) put(k, v)
                        }
                    }
                }
                val order = modObj.optString("ord").takeIf { it.isNotBlank() && it != "null" }
                val provOrder = modObj.optString("pord").takeIf { it.isNotBlank() && it != "null" }
                val dlOrder = modObj.optString("dord").takeIf { it.isNotBlank() && it != "null" }
                val web = modObj.optString("web")
                    .takeIf { it.isNotBlank() && it != "null" && it.length <= MaxWebModulesChars }
                val mono = modObj.optString("mono").takeIf { it.isNotBlank() && it != "null" }
                if (enabled.isNotEmpty() || picks.isNotEmpty() || fields.isNotEmpty() ||
                    order != null || provOrder != null || dlOrder != null || web != null || mono != null
                ) {
                    CloudModulesConfig(
                        enabled = enabled,
                        picks = picks,
                        fields = fields,
                        moduleOrder = order,
                        providerOrder = provOrder,
                        downloadOrder = dlOrder,
                        webModulesJson = web,
                        monochromeServersJson = mono,
                    )
                } else null
            } else null

            CloudLibraryExtra(
                albums = albums,
                artists = artists,
                audio = audio,
                albumsTruncated = root.optBoolean("at", false),
                artistsTruncated = root.optBoolean("rt", false),
                speedDial = speedDial,
                recognitionHistory = recognitionHistory,
                lyricsOffsets = lyricsOffsets,
                modulesConfig = modulesConfig,
                speedDialTruncated = root.optBoolean("sdt", false),
                recognitionHistoryTruncated = root.optBoolean("rect", false),
                lyricsOffsetsTruncated = root.optBoolean("lot", false),
            )
        }.getOrNull()
    }

    private fun buildJsonString(
        albums: List<CloudAlbumRecord>,
        artists: List<CloudArtistRecord>,
        audio: CloudAudioConfig?,
        albumsTruncated: Boolean,
        artistsTruncated: Boolean,
        speedDial: List<CloudSpeedDialRecord>,
        recognitionHistory: List<CloudRecognitionRecord>,
        lyricsOffsets: List<Map.Entry<String, Int>>,
        modulesConfig: CloudModulesConfig?,
        speedDialTruncated: Boolean,
        recognitionHistoryTruncated: Boolean,
        lyricsOffsetsTruncated: Boolean,
    ): String {
        val albArr = JSONArray()
        for (a in albums) {
            albArr.put(
                JSONArray().apply {
                    put(a.id.take(64))
                    put(a.playlistId.orEmpty().take(64))
                    put(a.title.take(120))
                    put(a.artists.take(120))
                    put(a.year ?: 0)
                    put(a.songCount.coerceIn(0, 10_000))
                    put(a.duration.coerceIn(0, 864_000))
                    put(a.thumbnailUrl.orEmpty().take(320))
                    put(a.bookmarkedEpochSec.coerceAtLeast(0L))
                },
            )
        }
        val artArr = JSONArray()
        for (ar in artists) {
            artArr.put(
                JSONArray().apply {
                    put(ar.id.take(64))
                    put(ar.name.take(120))
                    put(ar.channelId.orEmpty().take(64))
                    put(ar.thumbnailUrl.orEmpty().take(320))
                    put(ar.bookmarkedEpochSec.coerceAtLeast(0L))
                },
            )
        }
        val root = JSONObject().apply {
            put("v", 2)
            put("alb", albArr)
            put("art", artArr)
            if (albumsTruncated) put("at", true)
            if (artistsTruncated) put("rt", true)
            if (audio != null) {
                if (!audio.eqSettingsJson.isNullOrBlank()) put("eq", audio.eqSettingsJson)
                if (audio.audioNormalization != null) put("an", audio.audioNormalization)
                if (!audio.audioQuality.isNullOrBlank()) put("aq", audio.audioQuality)
                if (!audio.reverbJson.isNullOrBlank()) put("rev", audio.reverbJson)
                if (!audio.loudnessJson.isNullOrBlank()) put("ld", audio.loudnessJson)
                if (!audio.spatialJson.isNullOrBlank()) put("sp", audio.spatialJson)
                if (!audio.normalizeJson.isNullOrBlank()) put("nm", audio.normalizeJson)
                if (!audio.volumeJson.isNullOrBlank()) put("vl", audio.volumeJson)
                if (!audio.playbackJson.isNullOrBlank()) put("pb", audio.playbackJson)
                if (audio.skipSilence != null) put("ss", audio.skipSilence)
            }
            if (speedDial.isNotEmpty()) {
                val sdArr = JSONArray()
                for (sd in speedDial) {
                    sdArr.put(
                        JSONArray().apply {
                            put(sd.id.take(80))
                            put(sd.secondaryId.orEmpty().take(80))
                            put(sd.title.take(120))
                            put(sd.subtitle.orEmpty().take(120))
                            put(sd.thumbnailUrl.orEmpty().take(320))
                            put(sd.type.take(16))
                            put(sd.explicit)
                            put(sd.createDate.coerceAtLeast(0L))
                        },
                    )
                }
                put("sd", sdArr)
            }
            if (speedDialTruncated) put("sdt", true)
            if (recognitionHistory.isNotEmpty()) {
                val recArr = JSONArray()
                for (r in recognitionHistory) {
                    recArr.put(
                        JSONArray().apply {
                            put(r.trackId.take(64))
                            put(r.title.take(120))
                            put(r.artist.take(120))
                            put(r.album.orEmpty().take(120))
                            put(r.coverArtUrl.orEmpty().take(320))
                            put(r.youtubeVideoId.orEmpty().take(32))
                            put(r.recognizedAtEpochMs.coerceAtLeast(0L))
                            put(r.liked)
                            put(r.genre.orEmpty().take(60))
                            put(r.isrc.orEmpty().take(32))
                            put(r.shazamUrl.orEmpty().take(240))
                            put(r.coverArtHqUrl.orEmpty().take(320))
                            put(r.releaseDate.orEmpty().take(32))
                            put(r.label.orEmpty().take(80))
                            put(r.appleMusicUrl.orEmpty().take(240))
                            put(r.spotifyUrl.orEmpty().take(240))
                        },
                    )
                }
                put("rec", recArr)
            }
            if (recognitionHistoryTruncated) put("rect", true)
            if (lyricsOffsets.isNotEmpty()) {
                val loObj = JSONObject()
                for ((songId, offset) in lyricsOffsets) {
                    loObj.put(songId.take(64), offset)
                }
                put("lo", loObj)
            }
            if (lyricsOffsetsTruncated) put("lot", true)
            if (modulesConfig != null) {
                val modObj = JSONObject()
                if (modulesConfig.enabled.isNotEmpty()) {
                    val enObj = JSONObject()
                    for ((k, v) in modulesConfig.enabled) enObj.put(k, v)
                    modObj.put("en", enObj)
                }
                if (modulesConfig.picks.isNotEmpty()) {
                    val pkObj = JSONObject()
                    for ((k, v) in modulesConfig.picks) pkObj.put(k, v)
                    modObj.put("pk", pkObj)
                }
                if (modulesConfig.fields.isNotEmpty()) {
                    val fldObj = JSONObject()
                    for ((k, v) in modulesConfig.fields) fldObj.put(k, v)
                    modObj.put("fld", fldObj)
                }
                if (!modulesConfig.moduleOrder.isNullOrBlank()) {
                    modObj.put("ord", modulesConfig.moduleOrder)
                }
                if (!modulesConfig.providerOrder.isNullOrBlank()) {
                    modObj.put("pord", modulesConfig.providerOrder)
                }
                if (!modulesConfig.downloadOrder.isNullOrBlank()) {
                    modObj.put("dord", modulesConfig.downloadOrder)
                }
                if (!modulesConfig.webModulesJson.isNullOrBlank() &&
                    modulesConfig.webModulesJson.length <= MaxWebModulesChars
                ) {
                    modObj.put("web", modulesConfig.webModulesJson)
                }
                if (!modulesConfig.monochromeServersJson.isNullOrBlank()) {
                    modObj.put("mono", modulesConfig.monochromeServersJson)
                }
                if (modObj.length() > 0) put("mod", modObj)
            }
        }.toString()
        return root
    }
}
