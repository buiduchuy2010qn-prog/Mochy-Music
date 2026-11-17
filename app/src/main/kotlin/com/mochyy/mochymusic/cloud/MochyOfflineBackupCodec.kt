package com.mochyy.mochymusic.cloud

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.constants.AudioNormalizationKey
import com.mochyy.mochymusic.constants.AudioQualityKey
import com.mochyy.mochymusic.constants.SkipSilenceKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.AlbumArtistMap
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Event
import com.mochyy.mochymusic.db.entities.PlaylistEntity
import com.mochyy.mochymusic.db.entities.PlaylistSongMap
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.di.AudioLabEntryPoint
import com.mochyy.mochymusic.di.CloudSyncEntryPoint
import com.mochyy.mochymusic.eq.EqSettings
import com.mochyy.mochymusic.eq.LoudnessSettings
import com.mochyy.mochymusic.eq.NormalizeSettings
import com.mochyy.mochymusic.eq.PlaybackSettings
import com.mochyy.mochymusic.eq.ReverbSettings
import com.mochyy.mochymusic.eq.SpatialSettings
import com.mochyy.mochymusic.eq.VolumeSettings
import com.mochyy.mochymusic.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class MochyBackupSummary(
    val playlistsCount: Int,
    val favoritesCount: Int,
    val albumsCount: Int,
    val artistsCount: Int,
    val historyCount: Int,
    val eqRestored: Boolean,
)

data class ImportedSinglePlaylist(
    val playlistId: String,
    val name: String,
    val trackCount: Int,
)

/**
 * Xuất và khôi phục toàn bộ thư viện nhạc cá nhân ra tệp `.mochy.gz` (JSON + GZIP)
 * không giới hạn số lượng playlist, bài yêu thích, album, nghệ sĩ, lịch sử nghe & EQ,
 * đồng thời hỗ trợ chia sẻ/nhập playlist lẻ `.mochypl`.
 */
object MochyOfflineBackupCodec {

    private const val BackupFormat = "mochy-cloud-backup"
    private const val BackupVersion = 1
    private const val MaxUncompressedBytes = 32 * 1024 * 1024
    private const val SinglePlaylistFormat = "mochy-single-playlist"
    private const val SinglePlaylistVersion = 1
    private const val MaxSinglePlaylistBytes = 8 * 1024 * 1024
    private val CombiningMarksRegex = Regex("\\p{M}+")

    private val audioJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "mochy-backup-$stamp.mochy.gz"
    }

    suspend fun exportToUri(context: Context, uri: Uri): Result<MochyBackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val db = EntryPointAccessors.fromApplication(app, CloudSyncEntryPoint::class.java).database()
            val audioLab = EntryPointAccessors.fromApplication(app, AudioLabEntryPoint::class.java).audioLabStore()
            val prefs = app.dataStore.data.first()

            val likedSongs = db.likedSongsByCreateDateAsc().first()
            val allPlaylists = db.playlistsByCreateDateAsc().first().filter {
                it.id != PlaylistEntity.LIKED_PLAYLIST_ID &&
                    it.id != PlaylistEntity.DOWNLOADED_PLAYLIST_ID
            }
            val savedAlbums = db.albumsLikedByCreateDateAsc().first()
            val followedArtists = db.artistsBookmarkedByCreateDateAsc().first()
            val events = db.events().first()

            val songCatalog = LinkedHashMap<String, JSONObject>()
            fun registerSong(song: Song) {
                if (song.id.isBlank() || songCatalog.containsKey(song.id)) return
                songCatalog[song.id] = songToJson(song)
            }

            val favoritesArr = JSONArray()
            for (song in likedSongs) {
                if (song.id.isBlank()) continue
                registerSong(song)
                favoritesArr.put(
                    JSONObject().apply {
                        put("id", song.id)
                        song.song.likedDate?.let { put("likedMs", localToEpochMs(it)) }
                        song.song.inLibrary?.let { put("inLibraryMs", localToEpochMs(it)) }
                    },
                )
            }

            val playlistsArr = JSONArray()
            for (pl in allPlaylists) {
                val tracks = db.playlistSongs(pl.id).first()
                val trackIds = JSONArray()
                for (ps in tracks) {
                    if (ps.song.id.isBlank()) continue
                    registerSong(ps.song)
                    trackIds.put(ps.song.id)
                }
                playlistsArr.put(
                    JSONObject().apply {
                        put("id", pl.playlist.id)
                        put("name", pl.playlist.name)
                        pl.playlist.browseId?.let { put("browseId", it) }
                        put("isEditable", pl.playlist.isEditable)
                        pl.playlist.thumbnailUrl?.let { put("thumbnailUrl", it) }
                        pl.playlist.description?.let { put("description", it) }
                        pl.playlist.createdAt?.let { put("createdMs", localToEpochMs(it)) }
                        pl.playlist.lastUpdateTime?.let { put("updatedMs", localToEpochMs(it)) }
                        pl.playlist.bookmarkedAt?.let { put("bookmarkedMs", localToEpochMs(it)) }
                        put("trackIds", trackIds)
                    },
                )
            }

            val albumsArr = JSONArray()
            for (album in savedAlbums) {
                val artistsArr = JSONArray()
                for (a in album.artists) {
                    artistsArr.put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("name", a.name)
                        },
                    )
                }
                albumsArr.put(
                    JSONObject().apply {
                        put("id", album.album.id)
                        album.album.playlistId?.let { put("playlistId", it) }
                        put("title", album.album.title)
                        album.album.year?.let { put("year", it) }
                        album.album.thumbnailUrl?.let { put("thumbnailUrl", it) }
                        put("songCount", album.album.songCount)
                        put("duration", album.album.duration)
                        put("explicit", album.album.explicit)
                        album.album.description?.let { put("description", it) }
                        put("isLocal", album.album.isLocal)
                        album.album.bookmarkedAt?.let { put("bookmarkedMs", localToEpochMs(it)) }
                        put("artists", artistsArr)
                    },
                )
            }

            val artistsArr = JSONArray()
            for (artist in followedArtists) {
                artistsArr.put(
                    JSONObject().apply {
                        put("id", artist.artist.id)
                        put("name", artist.artist.name)
                        artist.artist.thumbnailUrl?.let { put("thumbnailUrl", it) }
                        artist.artist.channelId?.let { put("channelId", it) }
                        put("isLocal", artist.artist.isLocal)
                        artist.artist.bookmarkedAt?.let { put("bookmarkedMs", localToEpochMs(it)) }
                    },
                )
            }

            val historyArr = JSONArray()
            for (ev in events) {
                if (ev.song.id.isBlank()) continue
                registerSong(ev.song)
                historyArr.put(
                    JSONArray().apply {
                        put(ev.event.songId)
                        put(localToEpochMs(ev.event.timestamp))
                        put(ev.event.playTime.coerceAtLeast(0L))
                    },
                )
            }

            val songsObj = JSONObject()
            for ((songId, obj) in songCatalog) {
                songsObj.put(songId, obj)
            }

            val eqObj = JSONObject().apply {
                put("eq", audioJson.encodeToString(EqSettings.serializer(), audioLab.eq.value))
                put("reverb", audioJson.encodeToString(ReverbSettings.serializer(), audioLab.reverb.value))
                put("loudness", audioJson.encodeToString(LoudnessSettings.serializer(), audioLab.loudness.value))
                put("spatial", audioJson.encodeToString(SpatialSettings.serializer(), audioLab.spatial.value))
                put("normalize", audioJson.encodeToString(NormalizeSettings.serializer(), audioLab.normalize.value))
                put("volume", audioJson.encodeToString(VolumeSettings.serializer(), audioLab.volume.value))
                put("playback", audioJson.encodeToString(PlaybackSettings.serializer(), audioLab.playback.value))
                prefs[AudioNormalizationKey]?.let { put("audioNormalization", it) }
                prefs[AudioQualityKey]?.let { put("audioQuality", it) }
                prefs[SkipSilenceKey]?.let { put("skipSilence", it) }
            }

            val root = JSONObject().apply {
                put("format", BackupFormat)
                put("version", BackupVersion)
                put("exportedAtMs", System.currentTimeMillis())
                put("songs", songsObj)
                put("favorites", favoritesArr)
                put("playlists", playlistsArr)
                put("albums", albumsArr)
                put("artists", artistsArr)
                put("history", historyArr)
                put("audio", eqObj)
            }

            val stream = app.contentResolver.openOutputStream(uri)
                ?: throw MochyCloudException("Không thể ghi vào tệp đã chọn.")
            stream.use { rawOut ->
                GZIPOutputStream(BufferedOutputStream(rawOut)).use { gz ->
                    gz.write(root.toString().toByteArray(Charsets.UTF_8))
                }
            }

            MochyBackupSummary(
                playlistsCount = allPlaylists.size,
                favoritesCount = likedSongs.size,
                albumsCount = savedAlbums.size,
                artistsCount = followedArtists.size,
                historyCount = events.size,
                eqRestored = true,
            )
        }
    }

    suspend fun importFromUri(context: Context, uri: Uri): Result<MochyBackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val jsonStr = readGzipJson(
                context = app,
                uri = uri,
                maxBytes = MaxUncompressedBytes,
                invalidMsg = "Tệp không phải bản sao lưu .mochy.gz hợp lệ.",
            )

            val root = runCatching { JSONObject(jsonStr) }.getOrElse {
                throw MochyCloudException("Nội dung sao lưu bị hỏng hoặc không đúng định dạng.")
            }
            if (root.optString("format") != BackupFormat || root.optInt("version", 0) > BackupVersion) {
                throw MochyCloudException("Định dạng tệp sao lưu không tương thích.")
            }

            val db = EntryPointAccessors.fromApplication(app, CloudSyncEntryPoint::class.java).database()
            val audioLab = EntryPointAccessors.fromApplication(app, AudioLabEntryPoint::class.java).audioLabStore()

            val songsObj = root.optJSONObject("songs") ?: JSONObject()
            val favoritesArr = root.optJSONArray("favorites") ?: JSONArray()
            val playlistsArr = root.optJSONArray("playlists") ?: JSONArray()
            val albumsArr = root.optJSONArray("albums") ?: JSONArray()
            val artistsArr = root.optJSONArray("artists") ?: JSONArray()
            val historyArr = root.optJSONArray("history") ?: JSONArray()
            val audioObj = root.optJSONObject("audio")

            // Preload existing snapshots before entering Room transaction
            val existingEvents = db.events().first().mapTo(HashSet()) {
                it.event.songId to localToEpochMs(it.event.timestamp)
            }
            val existingPlaylistTracks = HashMap<String, List<String>>()
            for (i in 0 until playlistsArr.length()) {
                val plObj = playlistsArr.optJSONObject(i) ?: continue
                val plId = plObj.optString("id").trim()
                if (plId.isNotBlank() && db.playlistEntity(plId) != null) {
                    existingPlaylistTracks[plId] = db.playlistSongs(plId).first().map { it.song.id }
                }
            }
            val existingAlbumsMap = HashMap<String, AlbumEntity>()
            for (i in 0 until albumsArr.length()) {
                val albObj = albumsArr.optJSONObject(i) ?: continue
                val albId = albObj.optString("id").trim()
                if (albId.isNotBlank()) {
                    db.album(albId).first()?.album?.let { existingAlbumsMap[albId] = it }
                }
            }

            var restoredFavorites = 0
            var restoredPlaylists = 0
            var restoredAlbums = 0
            var restoredArtists = 0
            var restoredHistory = 0

            db.withTransaction {
                // 1. Yêu thích
                for (i in 0 until favoritesArr.length()) {
                    val item = favoritesArr.optJSONObject(i) ?: continue
                    val songId = item.optString("id").trim()
                    if (songId.isBlank()) continue
                    val likedTime = item.optLong("likedMs", 0L).takeIf { it > 0L }?.let(::epochMsToLocal)
                        ?: LocalDateTime.now()
                    val inLibTime = item.optLong("inLibraryMs", 0L).takeIf { it > 0L }?.let(::epochMsToLocal)
                    ensureSongFromCatalog(
                        db = db,
                        songId = songId,
                        songsObj = songsObj,
                        markLiked = true,
                        likedDate = likedTime,
                        inLibrary = inLibTime,
                    )
                    restoredFavorites++
                }

                // 2. Playlist (không giới hạn số lượng)
                for (i in 0 until playlistsArr.length()) {
                    val plObj = playlistsArr.optJSONObject(i) ?: continue
                    val plId = plObj.optString("id").trim()
                    val plName = plObj.optString("name").trim()
                    if (plId.isBlank() || plName.isBlank()) continue
                    if (plId == PlaylistEntity.LIKED_PLAYLIST_ID || plId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID) continue

                    val existingPl = db.playlistEntity(plId)
                    val trackIdsArr = plObj.optJSONArray("trackIds") ?: JSONArray()
                    if (existingPl == null) {
                        val created = plObj.optLong("createdMs", 0L).takeIf { it > 0L }?.let(::epochMsToLocal)
                            ?: LocalDateTime.now()
                        val updated = plObj.optLong("updatedMs", 0L).takeIf { it > 0L }?.let(::epochMsToLocal)
                            ?: created
                        val bookmarked = plObj.optLong("bookmarkedMs", 0L).takeIf { it > 0L }?.let(::epochMsToLocal)
                            ?: LocalDateTime.now()
                        db.insert(
                            PlaylistEntity(
                                id = plId,
                                name = plName,
                                browseId = plObj.optString("browseId").takeIf { it.isNotBlank() && it != "null" },
                                createdAt = created,
                                lastUpdateTime = updated,
                                isEditable = plObj.optBoolean("isEditable", true),
                                bookmarkedAt = bookmarked,
                                thumbnailUrl = plObj.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                                description = plObj.optString("description").takeIf { it.isNotBlank() && it != "null" },
                            ),
                        )
                        for (pos in 0 until trackIdsArr.length()) {
                            val songId = trackIdsArr.optString(pos).trim()
                            if (songId.isBlank()) continue
                            ensureSongFromCatalog(db, songId, songsObj)
                            db.insert(PlaylistSongMap(playlistId = plId, songId = songId, position = pos))
                        }
                    } else {
                        val currentTrackIds = existingPlaylistTracks[plId].orEmpty().toHashSet()
                        var nextPos = existingPlaylistTracks[plId]?.size ?: 0
                        for (pos in 0 until trackIdsArr.length()) {
                            val songId = trackIdsArr.optString(pos).trim()
                            if (songId.isBlank()) continue
                            ensureSongFromCatalog(db, songId, songsObj)
                            if (songId !in currentTrackIds) {
                                db.insert(PlaylistSongMap(playlistId = plId, songId = songId, position = nextPos++))
                                currentTrackIds.add(songId)
                            }
                        }
                    }
                    restoredPlaylists++
                }

                // 3. Album đã lưu
                for (i in 0 until albumsArr.length()) {
                    val albObj = albumsArr.optJSONObject(i) ?: continue
                    val albId = albObj.optString("id").trim()
                    val title = albObj.optString("title").trim()
                    if (albId.isBlank() || title.isBlank()) continue
                    val bookmarked = albObj.optLong("bookmarkedMs", 0L).takeIf { it > 0L }?.let(::epochMsToLocal)
                        ?: LocalDateTime.now()
                    val existing = existingAlbumsMap[albId]
                    if (existing == null) {
                        db.insert(
                            AlbumEntity(
                                id = albId,
                                playlistId = albObj.optString("playlistId").takeIf { it.isNotBlank() && it != "null" },
                                title = title,
                                year = albObj.optInt("year", 0).takeIf { it > 0 },
                                thumbnailUrl = albObj.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                                songCount = albObj.optInt("songCount", 0).coerceAtLeast(0),
                                duration = albObj.optInt("duration", 0).coerceAtLeast(0),
                                explicit = albObj.optBoolean("explicit", false),
                                bookmarkedAt = bookmarked,
                                description = albObj.optString("description").takeIf { it.isNotBlank() && it != "null" },
                                isLocal = albObj.optBoolean("isLocal", false),
                            ),
                        )
                        val albArtists = albObj.optJSONArray("artists") ?: JSONArray()
                        for (idx in 0 until albArtists.length()) {
                            val aObj = albArtists.optJSONObject(idx) ?: continue
                            val aName = aObj.optString("name").trim()
                            if (aName.isBlank()) continue
                            val rawId = aObj.optString("id").trim()
                            val aId = rawId.takeIf { it.isNotBlank() }
                                ?: db.artistByName(aName)?.id
                                ?: ArtistEntity.generateArtistId()
                            db.insert(ArtistEntity(id = aId, name = aName))
                            db.insert(AlbumArtistMap(albumId = albId, artistId = aId, order = idx))
                        }
                    } else if (existing.bookmarkedAt == null) {
                        db.update(existing.copy(bookmarkedAt = bookmarked))
                    }
                    restoredAlbums++
                }

                // 4. Nghệ sĩ theo dõi
                for (i in 0 until artistsArr.length()) {
                    val artObj = artistsArr.optJSONObject(i) ?: continue
                    val artId = artObj.optString("id").trim()
                    val name = artObj.optString("name").trim()
                    if (artId.isBlank() || name.isBlank()) continue
                    val bookmarked = artObj.optLong("bookmarkedMs", 0L).takeIf { it > 0L }?.let(::epochMsToLocal)
                        ?: LocalDateTime.now()
                    val thumb = artObj.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" }
                    val channelId = artObj.optString("channelId").takeIf { it.isNotBlank() && it != "null" }
                    val existing = db.getArtistById(artId)
                    if (existing == null) {
                        db.insert(
                            ArtistEntity(
                                id = artId,
                                name = name,
                                thumbnailUrl = thumb,
                                channelId = channelId,
                                bookmarkedAt = bookmarked,
                                isLocal = artObj.optBoolean("isLocal", false),
                            ),
                        )
                    } else if (existing.bookmarkedAt == null) {
                        db.update(
                            existing.copy(
                                bookmarkedAt = bookmarked,
                                thumbnailUrl = existing.thumbnailUrl ?: thumb,
                                channelId = existing.channelId ?: channelId,
                            ),
                        )
                    }
                    restoredArtists++
                }

                // 5. Lịch sử nghe
                for (i in 0 until historyArr.length()) {
                    val row = historyArr.optJSONArray(i) ?: continue
                    val songId = row.optString(0).trim()
                    if (songId.isBlank()) continue
                    val tsMs = row.optLong(1, 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
                    val playTime = row.optLong(2, 0L).coerceAtLeast(0L)
                    ensureSongFromCatalog(db, songId, songsObj)
                    val key = songId to tsMs
                    if (existingEvents.add(key)) {
                        db.insert(
                            Event(
                                songId = songId,
                                timestamp = epochMsToLocal(tsMs),
                                playTime = playTime,
                            ),
                        )
                        restoredHistory++
                    }
                }
            }

            // 6. Cài đặt Equalizer & âm thanh
            var eqRestored = false
            if (audioObj != null) {
                audioObj.optString("eq").takeIf { it.isNotBlank() && it != "null" }?.let { eqStr ->
                    runCatching { audioJson.decodeFromString(EqSettings.serializer(), eqStr) }.getOrNull()?.let { restoredEq ->
                        audioLab.updateEq { restoredEq }
                        eqRestored = true
                    }
                }
                audioObj.optString("reverb").takeIf { it.isNotBlank() && it != "null" }?.let { raw ->
                    runCatching { audioJson.decodeFromString(ReverbSettings.serializer(), raw) }.getOrNull()?.let { r ->
                        audioLab.updateReverb { r }
                    }
                }
                audioObj.optString("loudness").takeIf { it.isNotBlank() && it != "null" }?.let { raw ->
                    runCatching { audioJson.decodeFromString(LoudnessSettings.serializer(), raw) }.getOrNull()?.let { l ->
                        audioLab.updateLoudness { l }
                    }
                }
                audioObj.optString("spatial").takeIf { it.isNotBlank() && it != "null" }?.let { raw ->
                    runCatching { audioJson.decodeFromString(SpatialSettings.serializer(), raw) }.getOrNull()?.let { s ->
                        audioLab.updateSpatial { s }
                    }
                }
                audioObj.optString("normalize").takeIf { it.isNotBlank() && it != "null" }?.let { raw ->
                    runCatching { audioJson.decodeFromString(NormalizeSettings.serializer(), raw) }.getOrNull()?.let { n ->
                        audioLab.updateNormalize { n }
                    }
                }
                audioObj.optString("volume").takeIf { it.isNotBlank() && it != "null" }?.let { raw ->
                    runCatching { audioJson.decodeFromString(VolumeSettings.serializer(), raw) }.getOrNull()?.let { v ->
                        audioLab.updateVolume { v }
                    }
                }
                audioObj.optString("playback").takeIf { it.isNotBlank() && it != "null" }?.let { raw ->
                    runCatching { audioJson.decodeFromString(PlaybackSettings.serializer(), raw) }.getOrNull()?.let { p ->
                        audioLab.updatePlayback { p }
                    }
                }
                app.dataStore.edit { prefs ->
                    if (audioObj.has("audioNormalization")) {
                        prefs[AudioNormalizationKey] = audioObj.optBoolean("audioNormalization")
                    }
                    audioObj.optString("audioQuality").takeIf { it.isNotBlank() && it != "null" }?.let { q ->
                        prefs[AudioQualityKey] = q
                    }
                    if (audioObj.has("skipSilence")) {
                        prefs[SkipSilenceKey] = audioObj.optBoolean("skipSilence")
                    }
                }
            }

            MochyCloudSyncManager.refreshLocalStats(app)

            MochyBackupSummary(
                playlistsCount = restoredPlaylists,
                favoritesCount = restoredFavorites,
                albumsCount = restoredAlbums,
                artistsCount = restoredArtists,
                historyCount = restoredHistory,
                eqRestored = eqRestored,
            )
        }
    }

    suspend fun exportSinglePlaylistToFile(context: Context, playlistId: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val app = context.applicationContext
                val db = EntryPointAccessors.fromApplication(app, CloudSyncEntryPoint::class.java).database()
                val pl = db.playlistEntity(playlistId)
                    ?: throw MochyCloudException("Không tìm thấy playlist để chia sẻ.")
                val tracks = db.playlistSongs(playlistId).first()

                val songsObj = JSONObject()
                val trackIds = JSONArray()
                for (ps in tracks) {
                    val song = ps.song
                    if (song.id.isBlank()) continue
                    if (!songsObj.has(song.id)) {
                        songsObj.put(song.id, songToJson(song, sanitizeLocalThumb = true))
                    }
                    trackIds.put(song.id)
                }

                val playlistObj = JSONObject().apply {
                    put("name", pl.name)
                    pl.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                    pl.thumbnailUrl
                        ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                        ?.let { put("thumbnailUrl", it) }
                    pl.createdAt?.let { put("createdMs", localToEpochMs(it)) }
                    pl.lastUpdateTime?.let { put("updatedMs", localToEpochMs(it)) }
                    put("trackIds", trackIds)
                }

                val root = JSONObject().apply {
                    put("format", SinglePlaylistFormat)
                    put("version", SinglePlaylistVersion)
                    put("exportedAtMs", System.currentTimeMillis())
                    put("playlist", playlistObj)
                    put("songs", songsObj)
                }

                val shareDir = File(app.cacheDir, "shared_playlists").apply { mkdirs() }
                val cutoffMs = System.currentTimeMillis() - 7_200_000L
                shareDir.listFiles()?.forEach { old ->
                    if (old.isFile && old.lastModified() < cutoffMs) {
                        old.delete()
                    }
                }
                val outFile = File(shareDir, "${safeShareFileName(pl.name)}.mochypl")
                GZIPOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { gz ->
                    gz.write(root.toString().toByteArray(Charsets.UTF_8))
                }
                outFile
            }
        }

    suspend fun importSinglePlaylistFromUri(context: Context, uri: Uri): Result<ImportedSinglePlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val app = context.applicationContext
                val jsonStr = readGzipJson(
                    context = app,
                    uri = uri,
                    maxBytes = MaxSinglePlaylistBytes,
                    invalidMsg = "Tệp không phải playlist .mochypl hợp lệ.",
                )
                val root = runCatching { JSONObject(jsonStr) }.getOrElse {
                    throw MochyCloudException("Nội dung tệp .mochypl bị hỏng hoặc không đúng định dạng.")
                }
                if (root.optString("format") != SinglePlaylistFormat ||
                    root.optInt("version", 0) !in 1..SinglePlaylistVersion
                ) {
                    throw MochyCloudException("Định dạng tệp playlist .mochypl không tương thích.")
                }
                val plObj = root.optJSONObject("playlist")
                    ?: throw MochyCloudException("Tệp .mochypl thiếu dữ liệu playlist.")
                val plName = plObj.optString("name").trim().ifBlank { "Playlist được chia sẻ" }.take(120)
                val description = plObj.optString("description").takeIf { it.isNotBlank() && it != "null" }
                val thumbUrl = plObj.optString("thumbnailUrl")
                    .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                val songsObj = root.optJSONObject("songs") ?: JSONObject()
                val trackIdsArr = plObj.optJSONArray("trackIds") ?: JSONArray()

                val db = EntryPointAccessors.fromApplication(app, CloudSyncEntryPoint::class.java).database()
                var newPlaylistId = PlaylistEntity.generatePlaylistId()
                while (db.playlistEntity(newPlaylistId) != null) {
                    newPlaylistId = PlaylistEntity.generatePlaylistId()
                }
                val now = LocalDateTime.now()
                var insertedCount = 0

                db.withTransaction {
                    db.insert(
                        PlaylistEntity(
                            id = newPlaylistId,
                            name = plName,
                            browseId = null,
                            createdAt = now,
                            lastUpdateTime = now,
                            isEditable = true,
                            bookmarkedAt = now,
                            thumbnailUrl = thumbUrl,
                            description = description,
                        ),
                    )
                    val seen = HashSet<String>()
                    for (i in 0 until trackIdsArr.length()) {
                        val songId = trackIdsArr.optString(i).trim()
                        if (songId.isBlank() || !seen.add(songId)) continue
                        ensureSongFromCatalog(db, songId, songsObj)
                        db.insert(PlaylistSongMap(playlistId = newPlaylistId, songId = songId, position = insertedCount++))
                    }
                }

                MochyCloudSyncManager.refreshLocalStats(app)
                ImportedSinglePlaylist(
                    playlistId = newPlaylistId,
                    name = plName,
                    trackCount = insertedCount,
                )
            }
        }

    private fun readGzipJson(
        context: Context,
        uri: Uri,
        maxBytes: Int,
        invalidMsg: String,
    ): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw MochyCloudException("Không thể đọc tệp đã chọn.")
        return stream.use { input ->
            runCatching {
                GZIPInputStream(input).use { gz ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = gz.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) {
                            throw MochyCloudException("Tệp vượt kích thước an toàn cho phép.")
                        }
                        out.write(buf, 0, n)
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
            }.getOrElse { err ->
                if (err is MochyCloudException) throw err
                throw MochyCloudException(invalidMsg)
            }
        }
    }

    private fun safeShareFileName(rawName: String): String {
        val ascii = Normalizer.normalize(rawName, Normalizer.Form.NFD)
            .replace(CombiningMarksRegex, "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('.', '_', '-')
            .take(48)
        return ascii.ifBlank { "mochy-playlist" }
    }

    private fun ensureSongFromCatalog(
        db: MusicDatabase,
        songId: String,
        songsObj: JSONObject,
        markLiked: Boolean = false,
        likedDate: LocalDateTime? = null,
        inLibrary: LocalDateTime? = null,
    ) {
        val existing = db.getSongByIdBlocking(songId)
        if (existing != null) {
            if (markLiked && !existing.song.liked) {
                db.update(
                    existing.song.copy(
                        liked = true,
                        likedDate = existing.song.likedDate ?: likedDate ?: LocalDateTime.now(),
                        inLibrary = existing.song.inLibrary ?: inLibrary,
                    ),
                )
            }
            return
        }
        val meta = songsObj.optJSONObject(songId)
        val title = meta?.optString("title")?.takeIf { it.isNotBlank() } ?: "Bài hát đã sao lưu"
        db.insert(
            SongEntity(
                id = songId,
                title = title,
                duration = meta?.optInt("duration", -1) ?: -1,
                thumbnailUrl = meta?.optString("thumbnailUrl")?.takeIf { it.isNotBlank() && it != "null" },
                albumId = meta?.optString("albumId")?.takeIf { it.isNotBlank() && it != "null" },
                albumName = meta?.optString("albumName")?.takeIf { it.isNotBlank() && it != "null" },
                explicit = meta?.optBoolean("explicit", false) ?: false,
                year = meta?.optInt("year", 0)?.takeIf { it > 0 },
                liked = markLiked,
                likedDate = if (markLiked) (likedDate ?: LocalDateTime.now()) else null,
                inLibrary = inLibrary,
                lyricsOffset = meta?.optInt("lyricsOffset", 0) ?: 0,
                romanizeLyrics = meta?.optBoolean("romanizeLyrics", true) ?: true,
                isVideo = meta?.optBoolean("isVideo", false) ?: false,
            ),
        )
        val artistsArr = meta?.optJSONArray("artists") ?: JSONArray()
        for (idx in 0 until artistsArr.length()) {
            val aObj = artistsArr.optJSONObject(idx) ?: continue
            val aName = aObj.optString("name").trim()
            if (aName.isBlank()) continue
            val rawId = aObj.optString("id").trim()
            val aId = rawId.takeIf { it.isNotBlank() }
                ?: db.artistByName(aName)?.id
                ?: ArtistEntity.generateArtistId()
            db.insert(
                ArtistEntity(
                    id = aId,
                    name = aName,
                    thumbnailUrl = aObj.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                ),
            )
            db.insert(SongArtistMap(songId = songId, artistId = aId, position = idx))
        }
    }

    private fun songToJson(song: Song, sanitizeLocalThumb: Boolean = false): JSONObject {
        val artistsArr = JSONArray()
        for (artist in song.artists) {
            artistsArr.put(
                JSONObject().apply {
                    put("id", artist.id)
                    put("name", artist.name)
                    artist.thumbnailUrl
                        ?.takeIf { !sanitizeLocalThumb || it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                        ?.let { put("thumbnailUrl", it) }
                },
            )
        }
        return JSONObject().apply {
            put("title", song.title)
            put("duration", song.song.duration)
            song.thumbnailUrl
                ?.takeIf { !sanitizeLocalThumb || it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                ?.let { put("thumbnailUrl", it) }
            song.song.albumId?.let { put("albumId", it) }
            song.song.albumName?.let { put("albumName", it) }
            put("explicit", song.song.explicit)
            song.song.year?.let { put("year", it) }
            put("lyricsOffset", song.song.lyricsOffset)
            put("romanizeLyrics", song.song.romanizeLyrics)
            put("isVideo", song.song.isVideo)
            put("artists", artistsArr)
        }
    }

    private fun localToEpochMs(dt: LocalDateTime): Long =
        dt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun epochMsToLocal(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
}
