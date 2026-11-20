package com.mochyy.mochymusic.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
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

private const val MaxGzipDecodedBytes = 512 * 1024

private val EmptyJsonArray = JsonArray(emptyList())

internal val CloudCodecJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val CloudThumbPrefixPairs = arrayOf(
    "https://i.ytimg.com/vi/" to "~yv/",
    "https://lh3.googleusercontent.com/" to "~lh/",
    "https://yt3.googleusercontent.com/" to "~yt/",
    "https://i1.sndcdn.com/" to "~sc/",
    "https://photo-resize-zmp3.zmdcdn.me/" to "~zm/",
)

internal fun compactCloudThumbUrl(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "null") return ""
    for ((fullPrefix, shortToken) in CloudThumbPrefixPairs) {
        if (trimmed.startsWith(fullPrefix, ignoreCase = true)) {
            return shortToken + trimmed.substring(fullPrefix.length)
        }
    }
    return trimmed
}

internal fun expandCloudThumbUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "null") return null
    if (trimmed.startsWith("~")) {
        for ((fullPrefix, shortToken) in CloudThumbPrefixPairs) {
            if (trimmed.startsWith(shortToken)) {
                return fullPrefix + trimmed.substring(shortToken.length)
            }
        }
    }
    return trimmed
}

internal fun isCloudSafeTrackId(id: String): Boolean {
    val clean = id.trim()
    return clean.isNotEmpty() &&
        clean.length <= 64 &&
        !clean.startsWith("LA") &&
        !clean.startsWith("LOCAL_", ignoreCase = true) &&
        !clean.contains("://") &&
        !clean.contains("/")
}

internal fun gzipBytes(rawUtf8: String): ByteArray {
    val baos = ByteArrayOutputStream()
    object : GZIPOutputStream(baos) {
        init {
            def.setLevel(Deflater.BEST_COMPRESSION)
        }
    }.use { gz ->
        gz.write(rawUtf8.toByteArray(Charsets.UTF_8))
    }
    return baos.toByteArray()
}

internal fun encodeBase64NoWrap(bytes: ByteArray): String =
    Base64.getEncoder().withoutPadding().encodeToString(bytes)

internal fun decodeBase64Flexible(encoded: String): ByteArray {
    val cleaned = buildString(encoded.length) {
        for (ch in encoded) {
            when {
                ch.isWhitespace() -> Unit
                ch == '-' -> append('+')
                ch == '_' -> append('/')
                else -> append(ch)
            }
        }
    }
    val padded = when (cleaned.length % 4) {
        2 -> "$cleaned=="
        3 -> "$cleaned="
        else -> cleaned
    }
    return Base64.getDecoder().decode(padded)
}

internal fun gzipToBase64(rawUtf8: String): String =
    encodeBase64NoWrap(gzipBytes(rawUtf8))

internal fun gunzipFromBase64(encoded: String): String {
    val compressed = decodeBase64Flexible(encoded)
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

internal fun JsonObject.hasJsonValue(key: String): Boolean {
    val el = this[key] ?: return false
    return el !is JsonNull
}

internal fun JsonObject.optJsonString(key: String, defaultValue: String = ""): String {
    val el = this[key] ?: return defaultValue
    if (el is JsonNull) return defaultValue
    val prim = el as? JsonPrimitive ?: return el.toString()
    return prim.content
}

internal fun JsonObject.optJsonInt(key: String, defaultValue: Int = 0): Int {
    val el = this[key] as? JsonPrimitive ?: return defaultValue
    if (el is JsonNull) return defaultValue
    return el.intOrNull ?: el.content.trim().toDoubleOrNull()?.toInt() ?: defaultValue
}

internal fun JsonObject.optJsonLong(key: String, defaultValue: Long = 0L): Long {
    val el = this[key] as? JsonPrimitive ?: return defaultValue
    if (el is JsonNull) return defaultValue
    return el.longOrNull ?: el.content.trim().toDoubleOrNull()?.toLong() ?: defaultValue
}

internal fun JsonObject.optJsonFloat(key: String, defaultValue: Float = 0f): Float {
    val el = this[key] as? JsonPrimitive ?: return defaultValue
    if (el is JsonNull) return defaultValue
    return el.floatOrNull ?: el.content.trim().toFloatOrNull() ?: defaultValue
}

internal fun JsonObject.optJsonBoolean(key: String, defaultValue: Boolean = false): Boolean {
    val el = this[key] as? JsonPrimitive ?: return defaultValue
    if (el is JsonNull) return defaultValue
    return el.booleanOrNull ?: when (el.content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> defaultValue
    }
}

internal fun JsonObject.optJsonArray(key: String): JsonArray? =
    this[key] as? JsonArray

internal fun JsonObject.optJsonObject(key: String): JsonObject? =
    this[key] as? JsonObject

internal fun JsonArray.optJsonString(index: Int, defaultValue: String = ""): String {
    val el = getOrNull(index) ?: return defaultValue
    if (el is JsonNull) return defaultValue
    val prim = el as? JsonPrimitive ?: return el.toString()
    return prim.content
}

internal fun JsonArray.optJsonInt(index: Int, defaultValue: Int = 0): Int {
    val el = getOrNull(index) as? JsonPrimitive ?: return defaultValue
    if (el is JsonNull) return defaultValue
    return el.intOrNull ?: el.content.trim().toDoubleOrNull()?.toInt() ?: defaultValue
}

internal fun JsonArray.optJsonLong(index: Int, defaultValue: Long = 0L): Long {
    val el = getOrNull(index) as? JsonPrimitive ?: return defaultValue
    if (el is JsonNull) return defaultValue
    return el.longOrNull ?: el.content.trim().toDoubleOrNull()?.toLong() ?: defaultValue
}

internal fun JsonArray.optJsonBoolean(index: Int, defaultValue: Boolean = false): Boolean {
    val el = getOrNull(index) as? JsonPrimitive ?: return defaultValue
    if (el is JsonNull) return defaultValue
    return el.booleanOrNull ?: when (el.content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> defaultValue
    }
}

internal fun JsonArray.optJsonArray(index: Int): JsonArray? =
    getOrNull(index) as? JsonArray

internal fun JsonArray.optJsonObject(index: Int): JsonObject? =
    getOrNull(index) as? JsonObject

/**
 * Đóng gói Playlist cá nhân siêu nhẹ bằng JSON + GZIP + Base64 (<= 5.600 ký tự) để lưu trực tiếp
 * vào `user_metadata.playlists_gz` của Mochy Cloud mà không làm tràn header JWT 16 KB.
 */
object MochyPlaylistCodec {

    private const val MaxPlaylists = 25
    internal const val MaxEncodedChars = 5_600
    private val TrackLimits = intArrayOf(200, 150, 110, 80, 55, 35, 20, 12)

    fun encode(playlists: List<CloudPlaylistRecord>): String {
        val cappedPlaylists = playlists.take(MaxPlaylists)
        if (cappedPlaylists.isEmpty()) return ""
        for (limit in TrackLimits) {
            val candidate = compressToBase64(cappedPlaylists, limit)
            if (candidate.length <= MaxEncodedChars) {
                return candidate
            }
        }
        val eightTrackAll = compressToBase64(cappedPlaylists, 8)
        if (eightTrackAll.length <= MaxEncodedChars) {
            return eightTrackAll
        }
        // Giảm dần số playlist nếu người dùng có nhiều danh sách cực dài
        var subset = cappedPlaylists
        while (subset.size > 1) {
            subset = subset.dropLast(1)
            val candidate = compressToBase64(subset, 12)
            if (candidate.length <= MaxEncodedChars) {
                return candidate
            }
        }
        val eightTrack = compressToBase64(subset, 8)
        if (eightTrack.length <= MaxEncodedChars) return eightTrack
        return compressToBase64(subset, 5).takeIf { it.length <= MaxEncodedChars }.orEmpty()
    }

    fun decode(playlistsGz: String?): List<CloudPlaylistRecord> {
        if (playlistsGz.isNullOrBlank()) return emptyList()
        return runCatching {
            val jsonStr = gunzipFromBase64(playlistsGz)
            val root = CloudCodecJson.parseToJsonElement(jsonStr).jsonObject
            val arr = root.optJsonArray("pls") ?: return@runCatching emptyList()
            buildList(minOf(arr.size, MaxPlaylists)) {
                for (i in 0 until minOf(arr.size, MaxPlaylists)) {
                    val o = arr.optJsonObject(i) ?: continue
                    val id = o.optJsonString("id").trim()
                    val name = o.optJsonString("n").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    val updatedSec = o.optJsonLong("u", 0L).coerceAtLeast(0L)
                    val songsArr = o.optJsonArray("s") ?: EmptyJsonArray
                    val tracks = buildList(minOf(songsArr.size, 200)) {
                        for (j in 0 until minOf(songsArr.size, 200)) {
                            val row = songsArr.optJsonArray(j) ?: continue
                            val songId = row.optJsonString(0).trim()
                            if (songId.isBlank()) continue
                            add(
                                CloudPlaylistTrack(
                                    songId = songId,
                                    title = row.optJsonString(1),
                                    artists = row.optJsonString(2),
                                    albumName = row.optJsonString(3).takeIf { it.isNotBlank() && it != "null" },
                                    duration = row.optJsonInt(4, -1),
                                    thumbnailUrl = expandCloudThumbUrl(row.optJsonString(5)),
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
        val plsArr = buildJsonArray {
            for (pl in playlists) {
                val songsArr = buildJsonArray {
                    for (t in pl.tracks.take(maxTracksPerPlaylist)) {
                        add(
                            buildJsonArray {
                                add(JsonPrimitive(t.songId.take(64)))
                                add(JsonPrimitive(t.title.take(120)))
                                add(JsonPrimitive(t.artists.take(120)))
                                add(JsonPrimitive(t.albumName.orEmpty().take(120)))
                                add(JsonPrimitive(t.duration.coerceIn(-1, 86400)))
                                add(JsonPrimitive(compactCloudThumbUrl(t.thumbnailUrl).take(320)))
                            },
                        )
                    }
                }
                add(
                    buildJsonObject {
                        put("id", pl.id.take(48))
                        put("n", pl.name.take(120))
                        put("u", pl.updatedEpochSec)
                        put("s", songsArr)
                    },
                )
            }
        }
        val root = buildJsonObject {
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
    val eightDJson: String? = null,
    val playerCoverZoom: Float? = null,
    val canvasBestQuality: Boolean? = null,
    val deviceProfilesJson: String? = null,
    val deviceProfilesEnabled: Boolean? = null,
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
 * (Base64 <= 3.800 ký tự để giữ toàn bộ JWT dưới ngưỡng 16 KB, không cần bảng Supabase mới).
 */
object MochyLibraryExtraCodec {

    internal const val MaxEncodedChars = 3_800
    private const val MaxGzipBytes = 24_576
    private const val MaxSpeedDialItems = 9
    private const val MaxRecognitionItems = 35
    private const val MaxLyricsOffsets = 300
    private const val MaxWebModulesChars = 2_048
    private const val MaxDecodeWebModulesChars = 16_384
    private const val MaxDeviceProfilesChars = 8_192
    private const val MaxDecodeDeviceProfilesChars = 32_768
    private val Caps = intArrayOf(200, 150, 115, 90, 70, 50, 35, 20, 10, 0)
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
                extra.audio.skipSilence != null ||
                !extra.audio.eightDJson.isNullOrBlank() ||
                extra.audio.playerCoverZoom != null ||
                extra.audio.canvasBestQuality != null ||
                !extra.audio.deviceProfilesJson.isNullOrBlank() ||
                extra.audio.deviceProfilesEnabled != null
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
            val b64 = encodeBase64NoWrap(gz)
            return b64.takeIf { it.length <= MaxEncodedChars }
        }

        // 1. Giữ nguyên album/artist/speedDial/modules, cắt dần recognitionHistory & lyricsOffsets trước
        val audioFitted = extra.audio?.let { a ->
            val fittedDp = com.mochyy.mochymusic.eq.DeviceAudioProfileEngine.trimEncodedCompactJson(
                a.deviceProfilesJson,
                maxChars = MaxDeviceProfilesChars,
            )
            a.copy(deviceProfilesJson = fittedDp)
        }
        for ((recCap, offsetCap) in ExtraStepCaps) {
            tryEncode(200, recCap, offsetCap, cappedSpeedDial, modulesFull, audioFitted)?.let { return it }
        }
        // 2. Giảm dần album/artist nếu vẫn vượt ngưỡng nén
        for (cap in Caps.drop(1)) {
            for ((recCap, offsetCap) in listOf(10 to 60, 0 to 0)) {
                tryEncode(cap, recCap, offsetCap, cappedSpeedDial, modulesFull, audioFitted)?.let { return it }
            }
        }
        // 3. Nếu webModulesJson làm vượt hạn mức Base64, bỏ webModulesJson nhưng giữ switch/picks/order/monochrome
        if (modulesFull?.webModulesJson != null) {
            for ((recCap, offsetCap) in ExtraStepCaps) {
                tryEncode(200, recCap, offsetCap, cappedSpeedDial, modulesNoWeb, audioFitted)?.let { return it }
            }
            for (cap in Caps.drop(1)) {
                tryEncode(cap, 0, 0, cappedSpeedDial, modulesNoWeb, audioFitted)?.let { return it }
            }
        }
        // 4. Fallback cuối: giữ cấu hình nhẹ + thử thu gọn dần số hồ sơ thiết bị (8 -> 4 -> 2 -> 1 -> bỏ) trước khi bỏ hết
        val audioCandidates = buildList<CloudAudioConfig?> {
            if (audioFitted != null) {
                add(audioFitted)
                for (cap in listOf(4, 2, 1)) {
                    val trimmed = com.mochyy.mochymusic.eq.DeviceAudioProfileEngine.trimEncodedCompactJson(
                        extra.audio?.deviceProfilesJson,
                        maxChars = MaxDeviceProfilesChars,
                        maxProfiles = cap,
                    )
                    val candidate = audioFitted.copy(deviceProfilesJson = trimmed)
                    if (candidate !in this) add(candidate)
                }
                val withoutDp = audioFitted.copy(deviceProfilesJson = null)
                if (withoutDp !in this) add(withoutDp)
            } else {
                add(null)
            }
        }
        for (audioCandidate in audioCandidates) {
            tryEncode(0, 0, 0, emptyList(), modulesNoWeb, audioCandidate)?.let { return it }
            tryEncode(0, 0, 0, emptyList(), modulesNoWeb?.copy(fields = emptyMap()), audioCandidate)?.let { return it }
            tryEncode(0, 0, 0, emptyList(), null, audioCandidate)?.let { return it }
        }
        return ""
    }

    fun decode(libraryExtraGz: String?): CloudLibraryExtra? {
        if (libraryExtraGz.isNullOrBlank()) return null
        return runCatching {
            val jsonStr = gunzipFromBase64(libraryExtraGz)
            val root = CloudCodecJson.parseToJsonElement(jsonStr).jsonObject
            val albArr = root.optJsonArray("alb") ?: EmptyJsonArray
            val albums = buildList(albArr.size) {
                for (i in 0 until albArr.size) {
                    val row = albArr.optJsonArray(i) ?: continue
                    val id = row.optJsonString(0).trim()
                    val title = row.optJsonString(2).trim()
                    if (id.isBlank() || title.isBlank()) continue
                    add(
                        CloudAlbumRecord(
                            id = id,
                            playlistId = row.optJsonString(1).takeIf { it.isNotBlank() && it != "null" },
                            title = title,
                            artists = row.optJsonString(3),
                            year = row.optJsonInt(4, 0).takeIf { it > 0 },
                            songCount = row.optJsonInt(5, 0).coerceAtLeast(0),
                            duration = row.optJsonInt(6, 0).coerceAtLeast(0),
                            thumbnailUrl = expandCloudThumbUrl(row.optJsonString(7)),
                            bookmarkedEpochSec = row.optJsonLong(8, 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }

            val artArr = root.optJsonArray("art") ?: EmptyJsonArray
            val artists = buildList(artArr.size) {
                for (i in 0 until artArr.size) {
                    val row = artArr.optJsonArray(i) ?: continue
                    val id = row.optJsonString(0).trim()
                    val name = row.optJsonString(1).trim()
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        CloudArtistRecord(
                            id = id,
                            name = name,
                            channelId = row.optJsonString(2).takeIf { it.isNotBlank() && it != "null" },
                            thumbnailUrl = expandCloudThumbUrl(row.optJsonString(3)),
                            bookmarkedEpochSec = row.optJsonLong(4, 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }

            val eqJson = root.optJsonString("eq").takeIf { it.isNotBlank() && it != "null" }
            val norm = if (root.hasJsonValue("an")) root.optJsonBoolean("an") else null
            val quality = root.optJsonString("aq").takeIf { it.isNotBlank() && it != "null" }
            val revJson = root.optJsonString("rev").takeIf { it.isNotBlank() && it != "null" }
            val loudJson = root.optJsonString("ld").takeIf { it.isNotBlank() && it != "null" }
            val spatJson = root.optJsonString("sp").takeIf { it.isNotBlank() && it != "null" }
            val normJson = root.optJsonString("nm").takeIf { it.isNotBlank() && it != "null" }
            val volJson = root.optJsonString("vl").takeIf { it.isNotBlank() && it != "null" }
            val pbJson = root.optJsonString("pb").takeIf { it.isNotBlank() && it != "null" }
            val skipSil = if (root.hasJsonValue("ss")) root.optJsonBoolean("ss") else null
            val eightDJson = root.optJsonString("ed").takeIf { it.isNotBlank() && it != "null" }
            val coverZoom = if (root.hasJsonValue("cz")) {
                root.optJsonFloat("cz", Float.NaN).takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            } else null
            val canvasBest = if (root.hasJsonValue("cb")) root.optJsonBoolean("cb") else null
            val deviceProfilesJson = root.optJsonString("dp")
                .takeIf { it.isNotBlank() && it != "null" && it.length <= MaxDecodeDeviceProfilesChars }
            val deviceProfilesEnabled = if (root.hasJsonValue("dpe")) root.optJsonBoolean("dpe") else null
            val audio = if (eqJson != null || norm != null || quality != null ||
                revJson != null || loudJson != null || spatJson != null ||
                normJson != null || volJson != null || pbJson != null || skipSil != null ||
                eightDJson != null || coverZoom != null || canvasBest != null ||
                deviceProfilesJson != null || deviceProfilesEnabled != null
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
                    eightDJson = eightDJson,
                    playerCoverZoom = coverZoom,
                    canvasBestQuality = canvasBest,
                    deviceProfilesJson = deviceProfilesJson,
                    deviceProfilesEnabled = deviceProfilesEnabled,
                )
            } else null

            val sdArr = root.optJsonArray("sd") ?: EmptyJsonArray
            val speedDial = buildList(minOf(sdArr.size, MaxSpeedDialItems)) {
                for (i in 0 until minOf(sdArr.size, MaxSpeedDialItems)) {
                    val row = sdArr.optJsonArray(i) ?: continue
                    val id = row.optJsonString(0).trim()
                    val title = row.optJsonString(2).trim()
                    val type = row.optJsonString(5).trim()
                    if (id.isBlank() || title.isBlank() || type.isBlank()) continue
                    add(
                        CloudSpeedDialRecord(
                            id = id,
                            secondaryId = row.optJsonString(1).takeIf { it.isNotBlank() && it != "null" },
                            title = title,
                            subtitle = row.optJsonString(3).takeIf { it.isNotBlank() && it != "null" },
                            thumbnailUrl = expandCloudThumbUrl(row.optJsonString(4)),
                            type = type,
                            explicit = row.optJsonBoolean(6, false),
                            createDate = row.optJsonLong(7, 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }

            val recArr = root.optJsonArray("rec") ?: EmptyJsonArray
            val recognitionHistory = buildList(minOf(recArr.size, MaxRecognitionItems)) {
                for (i in 0 until minOf(recArr.size, MaxRecognitionItems)) {
                    val row = recArr.optJsonArray(i) ?: continue
                    val trackId = row.optJsonString(0).trim()
                    val title = row.optJsonString(1).trim()
                    val artist = row.optJsonString(2).trim()
                    if (trackId.isBlank() || title.isBlank()) continue
                    add(
                        CloudRecognitionRecord(
                            trackId = trackId,
                            title = title,
                            artist = artist,
                            album = row.optJsonString(3).takeIf { it.isNotBlank() && it != "null" },
                            coverArtUrl = expandCloudThumbUrl(row.optJsonString(4)),
                            youtubeVideoId = row.optJsonString(5).takeIf { it.isNotBlank() && it != "null" },
                            recognizedAtEpochMs = row.optJsonLong(6, 0L).coerceAtLeast(0L),
                            liked = row.optJsonBoolean(7, false),
                            genre = row.optJsonString(8).takeIf { it.isNotBlank() && it != "null" },
                            isrc = row.optJsonString(9).takeIf { it.isNotBlank() && it != "null" },
                            shazamUrl = row.optJsonString(10).takeIf { it.isNotBlank() && it != "null" },
                            coverArtHqUrl = expandCloudThumbUrl(row.optJsonString(11)),
                            releaseDate = row.optJsonString(12).takeIf { it.isNotBlank() && it != "null" },
                            label = row.optJsonString(13).takeIf { it.isNotBlank() && it != "null" },
                            appleMusicUrl = row.optJsonString(14).takeIf { it.isNotBlank() && it != "null" },
                            spotifyUrl = row.optJsonString(15).takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }
            }

            val loObj = root.optJsonObject("lo")
            val lyricsOffsets = if (loObj != null) {
                buildMap {
                    for ((rawKey, _) in loObj) {
                        if (size >= MaxLyricsOffsets) break
                        val songId = rawKey.trim()
                        val offset = loObj.optJsonInt(rawKey, 0)
                        if (songId.isNotBlank() && offset != 0) {
                            put(songId, offset)
                        }
                    }
                }
            } else emptyMap()

            val modObj = root.optJsonObject("mod")
            val modulesConfig = if (modObj != null) {
                val enObj = modObj.optJsonObject("en")
                val enabled = buildMap {
                    if (enObj != null) {
                        for ((rawKey, _) in enObj) {
                            val k = rawKey.trim()
                            if (k.isNotBlank()) put(k, enObj.optJsonBoolean(rawKey, false))
                        }
                    }
                }
                val pkObj = modObj.optJsonObject("pk")
                val picks = buildMap {
                    if (pkObj != null) {
                        for ((rawKey, _) in pkObj) {
                            val k = rawKey.trim()
                            val v = pkObj.optJsonString(rawKey).takeIf { it.isNotBlank() && it != "null" }
                            if (k.isNotBlank() && v != null) put(k, v)
                        }
                    }
                }
                val fldObj = modObj.optJsonObject("fld")
                val fields = buildMap {
                    if (fldObj != null) {
                        for ((rawKey, _) in fldObj) {
                            val k = rawKey.trim()
                            val v = fldObj.optJsonString(rawKey).takeIf { it.isNotBlank() && it != "null" }
                            if (k.isNotBlank() && v != null) put(k, v)
                        }
                    }
                }
                val order = modObj.optJsonString("ord").takeIf { it.isNotBlank() && it != "null" }
                val provOrder = modObj.optJsonString("pord").takeIf { it.isNotBlank() && it != "null" }
                val dlOrder = modObj.optJsonString("dord").takeIf { it.isNotBlank() && it != "null" }
                val web = modObj.optJsonString("web")
                    .takeIf { it.isNotBlank() && it != "null" && it.length <= MaxDecodeWebModulesChars }
                val mono = modObj.optJsonString("mono").takeIf { it.isNotBlank() && it != "null" }
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
                albumsTruncated = root.optJsonBoolean("at", false),
                artistsTruncated = root.optJsonBoolean("rt", false),
                speedDial = speedDial,
                recognitionHistory = recognitionHistory,
                lyricsOffsets = lyricsOffsets,
                modulesConfig = modulesConfig,
                speedDialTruncated = root.optJsonBoolean("sdt", false),
                recognitionHistoryTruncated = root.optJsonBoolean("rect", false),
                lyricsOffsetsTruncated = root.optJsonBoolean("lot", false),
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
        val albArr = buildJsonArray {
            for (a in albums) {
                add(
                    buildJsonArray {
                        add(JsonPrimitive(a.id.take(64)))
                        add(JsonPrimitive(a.playlistId.orEmpty().take(64)))
                        add(JsonPrimitive(a.title.take(120)))
                        add(JsonPrimitive(a.artists.take(120)))
                        add(JsonPrimitive(a.year ?: 0))
                        add(JsonPrimitive(a.songCount.coerceIn(0, 10_000)))
                        add(JsonPrimitive(a.duration.coerceIn(0, 864_000)))
                        add(JsonPrimitive(compactCloudThumbUrl(a.thumbnailUrl).take(320)))
                        add(JsonPrimitive(a.bookmarkedEpochSec.coerceAtLeast(0L)))
                    },
                )
            }
        }
        val artArr = buildJsonArray {
            for (ar in artists) {
                add(
                    buildJsonArray {
                        add(JsonPrimitive(ar.id.take(64)))
                        add(JsonPrimitive(ar.name.take(120)))
                        add(JsonPrimitive(ar.channelId.orEmpty().take(64)))
                        add(JsonPrimitive(compactCloudThumbUrl(ar.thumbnailUrl).take(320)))
                        add(JsonPrimitive(ar.bookmarkedEpochSec.coerceAtLeast(0L)))
                    },
                )
            }
        }
        return buildJsonObject {
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
                if (!audio.eightDJson.isNullOrBlank()) put("ed", audio.eightDJson)
                audio.playerCoverZoom?.takeIf { it.isFinite() }?.let { put("cz", it.coerceIn(0f, 1f)) }
                if (audio.canvasBestQuality != null) put("cb", audio.canvasBestQuality)
                if (!audio.deviceProfilesJson.isNullOrBlank() &&
                    audio.deviceProfilesJson.length <= MaxDeviceProfilesChars
                ) {
                    put("dp", audio.deviceProfilesJson)
                }
                if (audio.deviceProfilesEnabled != null) put("dpe", audio.deviceProfilesEnabled)
            }
            if (speedDial.isNotEmpty()) {
                put(
                    "sd",
                    buildJsonArray {
                        for (sd in speedDial) {
                            add(
                                buildJsonArray {
                                    add(JsonPrimitive(sd.id.take(80)))
                                    add(JsonPrimitive(sd.secondaryId.orEmpty().take(80)))
                                    add(JsonPrimitive(sd.title.take(120)))
                                    add(JsonPrimitive(sd.subtitle.orEmpty().take(120)))
                                    add(JsonPrimitive(compactCloudThumbUrl(sd.thumbnailUrl).take(320)))
                                    add(JsonPrimitive(sd.type.take(16)))
                                    add(JsonPrimitive(sd.explicit))
                                    add(JsonPrimitive(sd.createDate.coerceAtLeast(0L)))
                                },
                            )
                        }
                    },
                )
            }
            if (speedDialTruncated) put("sdt", true)
            if (recognitionHistory.isNotEmpty()) {
                put(
                    "rec",
                    buildJsonArray {
                        for (r in recognitionHistory) {
                            add(
                                buildJsonArray {
                                    add(JsonPrimitive(r.trackId.take(64)))
                                    add(JsonPrimitive(r.title.take(120)))
                                    add(JsonPrimitive(r.artist.take(120)))
                                    add(JsonPrimitive(r.album.orEmpty().take(120)))
                                    add(JsonPrimitive(compactCloudThumbUrl(r.coverArtUrl).take(320)))
                                    add(JsonPrimitive(r.youtubeVideoId.orEmpty().take(32)))
                                    add(JsonPrimitive(r.recognizedAtEpochMs.coerceAtLeast(0L)))
                                    add(JsonPrimitive(r.liked))
                                    add(JsonPrimitive(r.genre.orEmpty().take(60)))
                                    add(JsonPrimitive(r.isrc.orEmpty().take(32)))
                                    add(JsonPrimitive(r.shazamUrl.orEmpty().take(240)))
                                    add(JsonPrimitive(compactCloudThumbUrl(r.coverArtHqUrl).take(320)))
                                    add(JsonPrimitive(r.releaseDate.orEmpty().take(32)))
                                    add(JsonPrimitive(r.label.orEmpty().take(80)))
                                    add(JsonPrimitive(r.appleMusicUrl.orEmpty().take(240)))
                                    add(JsonPrimitive(r.spotifyUrl.orEmpty().take(240)))
                                },
                            )
                        }
                    },
                )
            }
            if (recognitionHistoryTruncated) put("rect", true)
            if (lyricsOffsets.isNotEmpty()) {
                put(
                    "lo",
                    buildJsonObject {
                        for ((songId, offset) in lyricsOffsets) {
                            put(songId.take(64), offset)
                        }
                    },
                )
            }
            if (lyricsOffsetsTruncated) put("lot", true)
            if (modulesConfig != null) {
                val modObj = buildJsonObject {
                    if (modulesConfig.enabled.isNotEmpty()) {
                        put(
                            "en",
                            buildJsonObject {
                                for ((k, v) in modulesConfig.enabled) put(k, v)
                            },
                        )
                    }
                    if (modulesConfig.picks.isNotEmpty()) {
                        put(
                            "pk",
                            buildJsonObject {
                                for ((k, v) in modulesConfig.picks) put(k, v)
                            },
                        )
                    }
                    if (modulesConfig.fields.isNotEmpty()) {
                        put(
                            "fld",
                            buildJsonObject {
                                for ((k, v) in modulesConfig.fields) put(k, v)
                            },
                        )
                    }
                    if (!modulesConfig.moduleOrder.isNullOrBlank()) {
                        put("ord", modulesConfig.moduleOrder)
                    }
                    if (!modulesConfig.providerOrder.isNullOrBlank()) {
                        put("pord", modulesConfig.providerOrder)
                    }
                    if (!modulesConfig.downloadOrder.isNullOrBlank()) {
                        put("dord", modulesConfig.downloadOrder)
                    }
                    if (!modulesConfig.webModulesJson.isNullOrBlank() &&
                        modulesConfig.webModulesJson.length <= MaxWebModulesChars
                    ) {
                        put("web", modulesConfig.webModulesJson)
                    }
                    if (!modulesConfig.monochromeServersJson.isNullOrBlank()) {
                        put("mono", modulesConfig.monochromeServersJson)
                    }
                }
                if (modObj.isNotEmpty()) put("mod", modObj)
            }
        }.toString()
    }
}
