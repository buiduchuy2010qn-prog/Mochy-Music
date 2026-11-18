package com.mochyy.mochymusic.cloud

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.datastore.preferences.core.edit
import com.mochyy.mochymusic.constants.AudioNormalizationKey
import com.mochyy.mochymusic.constants.AudioQualityKey
import com.mochyy.mochymusic.constants.SkipSilenceKey
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.daos.SpeedDialDao
import com.mochyy.mochymusic.db.entities.AlbumArtistMap
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.Event
import com.mochyy.mochymusic.db.entities.PlaylistEntity
import com.mochyy.mochymusic.db.entities.PlaylistSongMap
import com.mochyy.mochymusic.db.entities.RecognitionHistory
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.db.entities.SpeedDialItem
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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

sealed interface SinglePlaylistImportResult {
    data class Success(
        val playlistId: String,
        val name: String,
        val trackCount: Int,
    ) : SinglePlaylistImportResult

    data class Failure(
        val message: String,
    ) : SinglePlaylistImportResult
}

/**
 * Xuất và khôi phục toàn bộ thư viện nhạc cá nhân ra tệp `.mochy.gz` (JSON + GZIP)
 * không giới hạn số lượng playlist, bài yêu thích, album, nghệ sĩ, lịch sử nghe & EQ,
 * đồng thời hỗ trợ chia sẻ/nhập playlist lẻ `.mochypl` và mã chia sẻ `mochypl1:`.
 */
object MochyOfflineBackupCodec {

    const val PLAYLIST_CODE_PREFIX = "mochypl1:"

    private const val BackupFormat = "mochy-cloud-backup"
    private const val BackupVersion = 1
    private const val MaxUncompressedBytes = 32 * 1024 * 1024
    private const val SinglePlaylistFormat = "mochy-single-playlist"
    private const val SinglePlaylistVersion = 1
    private const val MaxSinglePlaylistBytes = 8 * 1024 * 1024
    private const val MaxPlaylistCodeBytes = 2 * 1024 * 1024
    private const val DefaultMaxPlaylistCodeTracks = 250
    private val PlaylistCodeRegex = Regex("mochypl1:[A-Za-z0-9_-]{16,}")
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

            // Speed Dial (tối đa 9 mục ghim)
            val speedDialItems = db.speedDialDao.getAllOnce(SpeedDialDao.MaxPinned)
            val speedDialArr = JSONArray()
            for (sd in speedDialItems) {
                speedDialArr.put(
                    JSONObject().apply {
                        put("id", sd.id)
                        sd.secondaryId?.let { put("secondaryId", it) }
                        put("title", sd.title)
                        sd.subtitle?.let { put("subtitle", it) }
                        sd.thumbnailUrl?.let { put("thumbnailUrl", it) }
                        put("type", sd.type)
                        put("explicit", sd.explicit)
                        put("createDate", sd.createDate)
                    },
                )
            }

            // Lịch sử nhận diện nhạc Shazam
            val recognitionItems = db.recognitionHistory().first().take(200)
            val recArr = JSONArray()
            for (rec in recognitionItems) {
                recArr.put(
                    JSONObject().apply {
                        put("trackId", rec.trackId)
                        put("title", rec.title)
                        put("artist", rec.artist)
                        rec.album?.let { put("album", it) }
                        rec.coverArtUrl?.let { put("coverArtUrl", it) }
                        rec.coverArtHqUrl?.let { put("coverArtHqUrl", it) }
                        rec.genre?.let { put("genre", it) }
                        rec.releaseDate?.let { put("releaseDate", it) }
                        rec.label?.let { put("label", it) }
                        rec.shazamUrl?.let { put("shazamUrl", it) }
                        rec.appleMusicUrl?.let { put("appleMusicUrl", it) }
                        rec.spotifyUrl?.let { put("spotifyUrl", it) }
                        rec.isrc?.let { put("isrc", it) }
                        rec.youtubeVideoId?.let { put("youtubeVideoId", it) }
                        put("recognizedAtMs", localToEpochMs(rec.recognizedAt))
                        put("liked", rec.liked)
                    },
                )
            }

            // Độ lệch lời bài hát (!= 0)
            val nonZeroOffsetSongs = db.songsWithNonZeroLyricsOffset(1000)
            val lyricsOffsetsObj = JSONObject()
            for (songEntity in nonZeroOffsetSongs) {
                if (songEntity.id.isBlank() || songEntity.lyricsOffset == 0) continue
                lyricsOffsetsObj.put(songEntity.id, songEntity.lyricsOffset)
                if (!songCatalog.containsKey(songEntity.id)) {
                    db.getSongByIdBlocking(songEntity.id)?.let(::registerSong)
                }
            }

            // Cấu hình Module ("source_modules" + "module_monochrome")
            val modulesCfg = MochyCloudSyncManager.readLocalModulesConfig(app)
            val sourcePrefs = app.getSharedPreferences("source_modules", Context.MODE_PRIVATE)
            val fullWebModules = sourcePrefs.getString("web_modules", null)
                ?.takeIf { it.isNotBlank() && it != "[]" && it.length <= 65_536 }
                ?: modulesCfg.webModulesJson
            val modulesObj = JSONObject().apply {
                if (modulesCfg.enabled.isNotEmpty()) {
                    put("enabled", JSONObject().apply {
                        for ((k, v) in modulesCfg.enabled) put(k, v)
                    })
                }
                if (modulesCfg.picks.isNotEmpty()) {
                    put("picks", JSONObject().apply {
                        for ((k, v) in modulesCfg.picks) put(k, v)
                    })
                }
                if (modulesCfg.fields.isNotEmpty()) {
                    put("fields", JSONObject().apply {
                        for ((k, v) in modulesCfg.fields) put(k, v)
                    })
                }
                modulesCfg.moduleOrder?.let { put("moduleOrder", it) }
                fullWebModules?.let { put("webModulesJson", it) }
                modulesCfg.monochromeServersJson?.let { put("monochromeServersJson", it) }
                sourcePrefs.getString("provider_order", null)?.takeIf { it.isNotBlank() }?.let {
                    put("providerOrder", it)
                }
                sourcePrefs.getString("download_order", null)?.takeIf { it.isNotBlank() }?.let {
                    put("downloadOrder", it)
                }
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
                if (speedDialArr.length() > 0) put("speedDial", speedDialArr)
                if (recArr.length() > 0) put("recognitionHistory", recArr)
                if (lyricsOffsetsObj.length() > 0) put("lyricsOffsets", lyricsOffsetsObj)
                if (modulesObj.length() > 0) put("modules", modulesObj)
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
            val speedDialArr = root.optJSONArray("speedDial")
            val recognitionArr = root.optJSONArray("recognitionHistory")
            val lyricsOffsetsObj = root.optJSONObject("lyricsOffsets")
            val modulesObj = root.optJSONObject("modules")

            // Preload existing snapshots before entering Room transaction
            val existingEvents = db.events().first().mapTo(HashSet()) {
                it.event.songId to localToEpochMs(it.event.timestamp)
            }
            val existingSpeedDialIds = db.speedDialDao.getAllOnce(SpeedDialDao.MaxPinned).mapTo(HashSet()) { it.id }
            val existingRecTrackIds = db.recognitionHistory().first().mapTo(HashSet()) { it.trackId }
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

                // 5B. Speed Dial (tối đa 9 mục ghim)
                if (speedDialArr != null) {
                    for (i in 0 until speedDialArr.length()) {
                        val sdObj = speedDialArr.optJSONObject(i) ?: continue
                        val sdId = sdObj.optString("id").trim()
                        val title = sdObj.optString("title").trim()
                        val type = sdObj.optString("type").trim()
                        if (sdId.isBlank() || title.isBlank() || type !in MochyCloudSyncManager.ValidSpeedDialTypes) {
                            continue
                        }
                        if (existingSpeedDialIds.contains(sdId)) continue
                        val item = SpeedDialItem(
                            id = sdId,
                            secondaryId = sdObj.optString("secondaryId").takeIf { it.isNotBlank() && it != "null" },
                            title = title,
                            subtitle = sdObj.optString("subtitle").takeIf { it.isNotBlank() && it != "null" },
                            thumbnailUrl = sdObj.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                            type = type,
                            explicit = sdObj.optBoolean("explicit", false),
                            createDate = sdObj.optLong("createDate", 0L).takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                        )
                        if (MochyCloudSyncManager.mergeSpeedDialItem(db, item)) {
                            existingSpeedDialIds.add(sdId)
                        }
                    }
                }

                // 5C. Lịch sử nhận diện nhạc Shazam
                if (recognitionArr != null) {
                    for (i in 0 until recognitionArr.length()) {
                        val rObj = recognitionArr.optJSONObject(i) ?: continue
                        val trackId = rObj.optString("trackId").trim()
                        val title = rObj.optString("title").trim()
                        if (trackId.isBlank() || title.isBlank() || !existingRecTrackIds.add(trackId)) continue
                        val tsMs = rObj.optLong("recognizedAtMs", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
                        db.insert(
                            RecognitionHistory(
                                trackId = trackId,
                                title = title,
                                artist = rObj.optString("artist").trim().ifBlank { "Nghệ sĩ" },
                                album = rObj.optString("album").takeIf { it.isNotBlank() && it != "null" },
                                coverArtUrl = rObj.optString("coverArtUrl").takeIf { it.isNotBlank() && it != "null" },
                                coverArtHqUrl = rObj.optString("coverArtHqUrl").takeIf { it.isNotBlank() && it != "null" },
                                genre = rObj.optString("genre").takeIf { it.isNotBlank() && it != "null" },
                                releaseDate = rObj.optString("releaseDate").takeIf { it.isNotBlank() && it != "null" },
                                label = rObj.optString("label").takeIf { it.isNotBlank() && it != "null" },
                                shazamUrl = rObj.optString("shazamUrl").takeIf { it.isNotBlank() && it != "null" },
                                appleMusicUrl = rObj.optString("appleMusicUrl").takeIf { it.isNotBlank() && it != "null" },
                                spotifyUrl = rObj.optString("spotifyUrl").takeIf { it.isNotBlank() && it != "null" },
                                isrc = rObj.optString("isrc").takeIf { it.isNotBlank() && it != "null" },
                                youtubeVideoId = rObj.optString("youtubeVideoId").takeIf { it.isNotBlank() && it != "null" },
                                recognizedAt = epochMsToLocal(tsMs),
                                liked = rObj.optBoolean("liked", false),
                            ),
                        )
                    }
                }

                // 5D. Độ lệch lời bài hát (!= 0)
                if (lyricsOffsetsObj != null) {
                    val keys = lyricsOffsetsObj.keys()
                    while (keys.hasNext()) {
                        val songId = keys.next().trim()
                        val offset = lyricsOffsetsObj.optInt(songId, 0)
                        if (songId.isBlank() || offset == 0) continue
                        val existing = db.getSongByIdBlocking(songId)
                        if (existing == null) {
                            if (songsObj.has(songId)) {
                                ensureSongFromCatalog(db, songId, songsObj)
                                db.updateSongLyricsOffset(songId, offset)
                            }
                        } else if (existing.song.lyricsOffset == 0) {
                            db.updateSongLyricsOffset(songId, offset)
                        }
                    }
                }
            }

            // 6B. Cấu hình Source/Look Modules
            if (modulesObj != null) {
                val enObj = modulesObj.optJSONObject("enabled")
                val enabledMap = buildMap {
                    if (enObj != null) {
                        val keys = enObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next().trim()
                            if (k.isNotBlank()) put(k, enObj.optBoolean(k, false))
                        }
                    }
                }
                val pkObj = modulesObj.optJSONObject("picks")
                val picksMap = buildMap {
                    if (pkObj != null) {
                        val keys = pkObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next().trim()
                            val v = pkObj.optString(k).takeIf { it.isNotBlank() && it != "null" }
                            if (k.isNotBlank() && v != null) put(k, v)
                        }
                    }
                }
                val fldObj = modulesObj.optJSONObject("fields")
                val fieldsMap = buildMap {
                    if (fldObj != null) {
                        val keys = fldObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next().trim()
                            val v = fldObj.optString(k).takeIf { it.isNotBlank() && it != "null" }
                            if (k.isNotBlank() && v != null) put(k, v)
                        }
                    }
                }
                val cfg = CloudModulesConfig(
                    enabled = enabledMap,
                    picks = picksMap,
                    fields = fieldsMap,
                    moduleOrder = modulesObj.optString("moduleOrder").takeIf { it.isNotBlank() && it != "null" },
                    providerOrder = modulesObj.optString("providerOrder").takeIf { it.isNotBlank() && it != "null" },
                    downloadOrder = modulesObj.optString("downloadOrder").takeIf { it.isNotBlank() && it != "null" },
                    webModulesJson = modulesObj.optString("webModulesJson").takeIf { it.isNotBlank() && it != "null" },
                    monochromeServersJson = modulesObj.optString("monochromeServersJson")
                        .takeIf { it.isNotBlank() && it != "null" },
                )
                MochyCloudSyncManager.applyModulesConfigToPrefs(
                    context = app,
                    remote = cfg,
                    overwriteScalars = true,
                    overwriteArrays = false,
                    maxWebModulesChars = 65_536,
                )
                val provOrder = modulesObj.optString("providerOrder").takeIf { it.isNotBlank() && it != "null" }
                val dlOrder = modulesObj.optString("downloadOrder").takeIf { it.isNotBlank() && it != "null" }
                if (provOrder != null || dlOrder != null) {
                    val prefs = app.getSharedPreferences("source_modules", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    var orderChanged = false
                    if (provOrder != null && prefs.getString("provider_order", null) != provOrder) {
                        editor.putString("provider_order", provOrder)
                        orderChanged = true
                    }
                    if (dlOrder != null && prefs.getString("download_order", null) != dlOrder) {
                        editor.putString("download_order", dlOrder)
                        orderChanged = true
                    }
                    if (orderChanged) {
                        editor.putLong("cloud_sync_stamp", System.currentTimeMillis())
                        editor.apply()
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

    fun extractPlaylistCodeFromText(raw: String): String? =
        PlaylistCodeRegex.find(raw)?.value

    suspend fun encodeSinglePlaylistCode(
        database: MusicDatabase,
        playlistId: String,
        maxTracks: Int = DefaultMaxPlaylistCodeTracks,
    ): String? = withContext(Dispatchers.IO) {
        val pl = database.playlistEntity(playlistId) ?: return@withContext null
        val limit = maxTracks.coerceIn(1, DefaultMaxPlaylistCodeTracks)
        val tracks = database.playlistSongs(playlistId).first()
        val songsArr = JSONArray()
        val seen = HashSet<String>()
        for (ps in tracks) {
            if (songsArr.length() >= limit) break
            val song = ps.song
            val songId = song.id.trim()
            if (songId.isBlank() || !seen.add(songId)) continue
            val artistsStr = song.artists.joinToString(", ") { it.name }.trim()
            val safeThumb = song.thumbnailUrl?.takeIf(::isHttpUrl).orEmpty()
            songsArr.put(
                JSONArray().apply {
                    put(songId.take(64))
                    put(song.title.take(120))
                    put(artistsStr.take(120))
                    put(song.song.duration.coerceIn(-1, 86_400))
                    put(safeThumb.take(320))
                    put(song.song.albumName.orEmpty().take(120))
                    put(if (song.song.isVideo) 1 else 0)
                },
            )
        }
        if (songsArr.length() == 0) return@withContext null

        val root = JSONObject().apply {
            put("v", 1)
            put("n", pl.name.trim().ifBlank { "Playlist được chia sẻ" }.take(120))
            pl.description?.trim()?.takeIf { it.isNotBlank() }?.let { put("d", it.take(300)) }
            pl.thumbnailUrl?.takeIf(::isHttpUrl)?.let { put("th", it.take(320)) }
            put("s", songsArr)
        }
        val gzBytes = gzipBytes(root.toString())
        val encoded = Base64.encodeToString(
            gzBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        PLAYLIST_CODE_PREFIX + encoded
    }

    suspend fun importSinglePlaylistFromCode(
        context: Context,
        database: MusicDatabase,
        rawTextOrCode: String,
    ): SinglePlaylistImportResult = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val code = extractPlaylistCodeFromText(rawTextOrCode)
                ?: return@withContext SinglePlaylistImportResult.Failure("Không tìm thấy mã chia sẻ playlist hợp lệ.")
            val payload = code.removePrefix(PLAYLIST_CODE_PREFIX)
            val compressed = runCatching {
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@withContext SinglePlaylistImportResult.Failure("Mã chia sẻ playlist không hợp lệ.")

            val jsonStr = readGzipJsonFromStream(
                input = ByteArrayInputStream(compressed),
                maxBytes = MaxPlaylistCodeBytes,
                invalidMsg = "Mã chia sẻ playlist bị hỏng hoặc không đúng định dạng.",
            )
            val root = runCatching { JSONObject(jsonStr) }.getOrElse {
                return@withContext SinglePlaylistImportResult.Failure("Nội dung mã playlist không hợp lệ.")
            }

            val imported = if (root.optString("format") == SinglePlaylistFormat && root.has("playlist")) {
                parseAndInsertLegacyPlaylistObject(app, database, root)
            } else {
                val plName = root.optString("n").trim().ifBlank { "Playlist được chia sẻ" }.take(120)
                val description = root.optString("d").takeIf { it.isNotBlank() && it != "null" }
                val thumbUrl = root.optString("th").takeIf(::isHttpUrl)
                val songsArr = root.optJSONArray("s") ?: JSONArray()
                val songsObj = JSONObject()
                val trackIds = ArrayList<String>(songsArr.length())
                for (i in 0 until songsArr.length()) {
                    val row = songsArr.optJSONArray(i) ?: continue
                    val songId = row.optString(0).trim()
                    if (songId.isBlank()) continue
                    trackIds.add(songId)
                    if (!songsObj.has(songId)) {
                        val artistsJson = JSONArray()
                        row.optString(2).split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { aName ->
                            artistsJson.put(JSONObject().put("name", aName))
                        }
                        songsObj.put(
                            songId,
                            JSONObject().apply {
                                put("title", row.optString(1).trim().ifBlank { "Bài hát được chia sẻ" })
                                put("duration", row.optInt(3, -1))
                                row.optString(4).takeIf(::isHttpUrl)?.let { put("thumbnailUrl", it) }
                                row.optString(5).takeIf { it.isNotBlank() && it != "null" }?.let { put("albumName", it) }
                                put("isVideo", row.optInt(6, 0) != 0)
                                put("artists", artistsJson)
                            },
                        )
                    }
                }
                if (trackIds.isEmpty()) {
                    return@withContext SinglePlaylistImportResult.Failure("Mã playlist không có bài hát hợp lệ.")
                }
                insertImportedSinglePlaylist(
                    app = app,
                    db = database,
                    plName = plName,
                    description = description,
                    thumbUrl = thumbUrl,
                    trackIds = trackIds,
                    songsObj = songsObj,
                )
            }

            SinglePlaylistImportResult.Success(
                playlistId = imported.playlistId,
                name = imported.name,
                trackCount = imported.trackCount,
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            SinglePlaylistImportResult.Failure(err.message ?: "Không thể nhập playlist từ mã chia sẻ.")
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
                val db = EntryPointAccessors.fromApplication(app, CloudSyncEntryPoint::class.java).database()
                parseAndInsertLegacyPlaylistObject(app, db, root)
            }
        }

    private suspend fun parseAndInsertLegacyPlaylistObject(
        app: Context,
        db: MusicDatabase,
        root: JSONObject,
    ): ImportedSinglePlaylist {
        if (root.optString("format") != SinglePlaylistFormat ||
            root.optInt("version", 0) !in 1..SinglePlaylistVersion
        ) {
            throw MochyCloudException("Định dạng tệp playlist .mochypl không tương thích.")
        }
        val plObj = root.optJSONObject("playlist")
            ?: throw MochyCloudException("Tệp .mochypl thiếu dữ liệu playlist.")
        val plName = plObj.optString("name").trim().ifBlank { "Playlist được chia sẻ" }.take(120)
        val description = plObj.optString("description").takeIf { it.isNotBlank() && it != "null" }
        val thumbUrl = plObj.optString("thumbnailUrl").takeIf(::isHttpUrl)
        val songsObj = root.optJSONObject("songs") ?: JSONObject()
        val trackIdsArr = plObj.optJSONArray("trackIds") ?: JSONArray()
        val trackIds = buildList(trackIdsArr.length()) {
            for (i in 0 until trackIdsArr.length()) {
                val songId = trackIdsArr.optString(i).trim()
                if (songId.isNotBlank()) add(songId)
            }
        }
        return insertImportedSinglePlaylist(
            app = app,
            db = db,
            plName = plName,
            description = description,
            thumbUrl = thumbUrl,
            trackIds = trackIds,
            songsObj = songsObj,
        )
    }

    private suspend fun insertImportedSinglePlaylist(
        app: Context,
        db: MusicDatabase,
        plName: String,
        description: String?,
        thumbUrl: String?,
        trackIds: List<String>,
        songsObj: JSONObject,
    ): ImportedSinglePlaylist {
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
            for (songId in trackIds) {
                if (songId.isBlank() || !seen.add(songId)) continue
                ensureSongFromCatalog(db, songId, songsObj)
                db.insert(PlaylistSongMap(playlistId = newPlaylistId, songId = songId, position = insertedCount++))
            }
        }

        MochyCloudSyncManager.refreshLocalStats(app)
        return ImportedSinglePlaylist(
            playlistId = newPlaylistId,
            name = plName,
            trackCount = insertedCount,
        )
    }

    private fun readGzipJson(
        context: Context,
        uri: Uri,
        maxBytes: Int,
        invalidMsg: String,
    ): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw MochyCloudException("Không thể đọc tệp đã chọn.")
        return readGzipJsonFromStream(stream, maxBytes, invalidMsg)
    }

    private fun readGzipJsonFromStream(
        input: InputStream,
        maxBytes: Int,
        invalidMsg: String,
    ): String {
        return input.use { raw ->
            runCatching {
                GZIPInputStream(raw).use { gz ->
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

    private fun isHttpUrl(raw: String?): Boolean =
        !raw.isNullOrBlank() &&
            (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true))

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
