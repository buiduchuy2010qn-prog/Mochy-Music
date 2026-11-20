// RecapPlaylistSaver.kt
//
// Saves any Re'cap period's Top 25 songs as a local Mochy Library playlist and optionally encodes
// a portable mochypl1: share code for sharing with friends or across devices.

package com.mochyy.mochymusic.ui.screens.recap.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.cloud.MochyOfflineBackupCodec
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.PlaylistEntity
import com.mochyy.mochymusic.db.entities.PlaylistSongMap
import com.mochyy.mochymusic.ui.screens.recap.RecapStoryData
import com.mochyy.mochymusic.ui.screens.recap.StatsRange
import com.mochyy.mochymusic.ui.screens.recap.formatHoursMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

internal data class SavedRecapPlaylist(
    val playlistId: String,
    val name: String,
    val trackCount: Int,
    val shareCode: String?,
)

internal fun recapPlaylistIdForRange(range: StatsRange): String =
    if (range.kind == StatsRange.Kind.Month) {
        "LP_RECAP_${range.year}_${"%02d".format(range.month)}"
    } else {
        "LP_RECAP_${range.year}_ALL"
    }

internal suspend fun saveRecapPlaylist(
    context: Context,
    database: MusicDatabase,
    data: RecapStoryData,
    includeShareCode: Boolean = false,
): SavedRecapPlaylist {
    val tracks = data.stats.topSongs.take(25)
    check(tracks.isNotEmpty()) { "No tracks available to save Re'cap playlist" }

    val range = data.stats.range
    val playlistId = recapPlaylistIdForRange(range)
    val playlistName = if (range.kind == StatsRange.Kind.Month) {
        context.getString(R.string.rc_playlist_title_month, data.monthName, range.year)
    } else {
        context.getString(R.string.rc_playlist_title_year, range.year)
    }
    val description = context.getString(
        R.string.rc_playlist_desc,
        formatHoursMinutes(data.stats.totalMs),
        data.stats.plays,
    )
    val coverUrl = data.heroSong?.thumbnailUrl ?: data.heroArtist?.thumbnailUrl
    val now = LocalDateTime.now()

    var insertedCount = 0
    database.withTransaction {
        val validSongIds = getSongsByIds(tracks.map { it.id }).mapTo(HashSet()) { it.id }
        val existing = playlistEntity(playlistId)
        if (existing == null) {
            insert(
                PlaylistEntity(
                    id = playlistId,
                    name = playlistName,
                    bookmarkedAt = now,
                    createdAt = now,
                    lastUpdateTime = now,
                    isEditable = true,
                    thumbnailUrl = coverUrl,
                    description = description,
                ),
            )
        } else {
            update(
                existing.copy(
                    name = playlistName,
                    bookmarkedAt = existing.bookmarkedAt ?: now,
                    lastUpdateTime = now,
                    isEditable = true,
                    thumbnailUrl = coverUrl ?: existing.thumbnailUrl,
                    description = description,
                ),
            )
            clearPlaylistRaw(playlistId)
        }

        val seen = HashSet<String>()
        tracks.forEach { song ->
            if (song.id in validSongIds && seen.add(song.id)) {
                insert(
                    PlaylistSongMap(
                        playlistId = playlistId,
                        songId = song.id,
                        position = insertedCount,
                    ),
                )
                insertedCount++
            }
        }
    }

    check(insertedCount > 0) { "No matching tracks found in database for Re'cap playlist" }

    val shareCode = if (includeShareCode) {
        withContext(Dispatchers.IO) {
            runCatching {
                MochyOfflineBackupCodec.encodeSinglePlaylistCode(
                    database = database,
                    playlistId = playlistId,
                    maxTracks = 50,
                )
            }.getOrNull()
        }
    } else {
        null
    }

    return SavedRecapPlaylist(
        playlistId = playlistId,
        name = playlistName,
        trackCount = insertedCount,
        shareCode = shareCode,
    )
}

internal fun copyRecapPlaylistCodeToClipboard(
    context: Context,
    label: String,
    code: String,
): Boolean {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    cm.setPrimaryClip(ClipData.newPlainText(label, code))
    return true
}
