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

internal fun gzipToBase64(rawUtf8: String): String {
    val baos = ByteArrayOutputStream()
    GZIPOutputStream(baos).use { gz ->
        gz.write(rawUtf8.toByteArray(Charsets.UTF_8))
    }
    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
}

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
)

data class CloudLibraryExtra(
    val albums: List<CloudAlbumRecord> = emptyList(),
    val artists: List<CloudArtistRecord> = emptyList(),
    val audio: CloudAudioConfig? = null,
    val albumsTruncated: Boolean = false,
    val artistsTruncated: Boolean = false,
)

/**
 * Đóng gói Album đã lưu, Nghệ sĩ theo dõi & cài đặt Equalizer bằng JSON + GZIP + Base64
 * vào `user_metadata.library_extra_gz` (luôn <= 16 KB, không cần bảng Supabase mới).
 */
object MochyLibraryExtraCodec {

    private const val MaxEncodedChars = MaxGzipEncodedChars
    private val Caps = intArrayOf(200, 140, 90, 50, 25, 10, 0)

    fun encode(extra: CloudLibraryExtra): String {
        val hasAudio = extra.audio != null && (
            !extra.audio.eqSettingsJson.isNullOrBlank() ||
                extra.audio.audioNormalization != null ||
                !extra.audio.audioQuality.isNullOrBlank()
            )
        if (extra.albums.isEmpty() && extra.artists.isEmpty() && !hasAudio) return ""

        for (cap in Caps) {
            val albumsSlice = if (cap == 0) emptyList() else extra.albums.take(cap)
            val artistsSlice = if (cap == 0) emptyList() else extra.artists.take(cap)
            val candidate = compressToBase64(
                albums = albumsSlice,
                artists = artistsSlice,
                audio = extra.audio,
                albumsTruncated = albumsSlice.size < extra.albums.size,
                artistsTruncated = artistsSlice.size < extra.artists.size,
            )
            if (candidate.length <= MaxEncodedChars) return candidate
        }
        // Trường hợp cực hiếm nếu cả EQ JSON quá dài: chỉ giữ EQ gọn hoặc rỗng
        val audioOnly = compressToBase64(
            albums = emptyList(),
            artists = emptyList(),
            audio = extra.audio,
            albumsTruncated = extra.albums.isNotEmpty(),
            artistsTruncated = extra.artists.isNotEmpty(),
        )
        return audioOnly.takeIf { it.length <= MaxEncodedChars }.orEmpty()
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
            val audio = if (eqJson != null || norm != null || quality != null) {
                CloudAudioConfig(
                    eqSettingsJson = eqJson,
                    audioNormalization = norm,
                    audioQuality = quality,
                )
            } else null

            CloudLibraryExtra(
                albums = albums,
                artists = artists,
                audio = audio,
                albumsTruncated = root.optBoolean("at", false),
                artistsTruncated = root.optBoolean("rt", false),
            )
        }.getOrNull()
    }

    private fun compressToBase64(
        albums: List<CloudAlbumRecord>,
        artists: List<CloudArtistRecord>,
        audio: CloudAudioConfig?,
        albumsTruncated: Boolean,
        artistsTruncated: Boolean,
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
            put("v", 1)
            put("alb", albArr)
            put("art", artArr)
            if (albumsTruncated) put("at", true)
            if (artistsTruncated) put("rt", true)
            if (audio != null) {
                if (!audio.eqSettingsJson.isNullOrBlank()) put("eq", audio.eqSettingsJson)
                if (audio.audioNormalization != null) put("an", audio.audioNormalization)
                if (!audio.audioQuality.isNullOrBlank()) put("aq", audio.audioQuality)
            }
        }.toString()
        return gzipToBase64(root)
    }
}
