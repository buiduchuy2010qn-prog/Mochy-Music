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

/**
 * Đóng gói Playlist cá nhân siêu nhẹ bằng JSON + GZIP + Base64 để lưu trực tiếp
 * vào `user_metadata.playlists_gz` của Mochy Cloud mà không cần thêm bảng SQL.
 */
object MochyPlaylistCodec {

    private const val MaxPlaylists = 25
    private const val MaxEncodedChars = 16_384
    private const val MaxDecodedBytes = 512 * 1024
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
        return compressToBase64(subset, 10).take(MaxEncodedChars)
    }

    fun decode(playlistsGz: String?): List<CloudPlaylistRecord> {
        if (playlistsGz.isNullOrBlank()) return emptyList()
        return runCatching {
            val compressed = Base64.decode(playlistsGz.trim(), Base64.NO_WRAP)
            val jsonStr = GZIPInputStream(ByteArrayInputStream(compressed)).use { gz ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = gz.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MaxDecodedBytes) {
                        error("Dữ liệu playlist vượt ngưỡng an toàn")
                    }
                    out.write(buf, 0, read)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
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
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            gz.write(root.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
