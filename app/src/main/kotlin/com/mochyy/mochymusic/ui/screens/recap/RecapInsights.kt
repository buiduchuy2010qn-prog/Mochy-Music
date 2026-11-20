// RecapInsights.kt
//
// Pure-JVM storytelling insights for Mochy Re'cap: listening persona classifier ("Hệ người nghe"),
// consecutive-day Streak & Peak Day stats, and Period-over-Period comparison ("vs. kỳ trước").
// Kept free of Android/Compose dependencies so unit tests run directly on JVM.

package com.mochyy.mochymusic.ui.screens.recap

import java.time.LocalDate
import kotlin.math.roundToInt

enum class RecapPersonaId(
    val clubCode: String,
    val giantWord: String,
) {
    NIGHT_OWL("CLUB // 02:00 AM", "NIGHT OWL"),
    DAWN_CHASER("CLUB // SUNRISE", "SUNRISE"),
    ONE_SONG_DEVOTEE("CLUB // ON REPEAT", "ON REPEAT"),
    ARTIST_LOYALIST("CLUB // LOYAL #1", "LOYALIST"),
    SONIC_EXPLORER("CLUB // EXPLORER", "EXPLORER"),
    STREAK_MACHINE("CLUB // NON-STOP", "NON-STOP"),
    GOLDEN_HOUR("CLUB // DUSK VIBE", "DUSK VIBE"),
    VIBE_CURATOR("CLUB // CURATOR", "CURATOR"),
}

data class RecapPersonaResult(
    val id: RecapPersonaId,
    val peakHour: Int,
    val nightSharePercent: Int,
    val topArtistSharePercent: Int,
    val topSongPlays: Int,
)

data class RecapStreakStats(
    val longestStreakDays: Int,
    val streakStart: LocalDate?,
    val streakEnd: LocalDate?,
    val peakDate: LocalDate?,
    val peakDayMs: Long,
    val activeDaysCount: Int,
)

data class RecapPeriodDelta(
    val hasPrevious: Boolean,
    val previousLabel: String = "",
    val timeDeltaPercent: Int = 0,
    val playsDelta: Int = 0,
    val isNewTopArtist: Boolean = false,
    val isNewTopSong: Boolean = false,
)

data class RecapInsights(
    val persona: RecapPersonaResult,
    val streak: RecapStreakStats,
    val delta: RecapPeriodDelta,
)

/**
 * Computes consecutive-day streak and highest-listening single day from per-date playtime.
 * On ties, prefers the later streak / peak date.
 */
fun computeStreakFromDailyMap(dailyMs: Map<LocalDate, Long>): RecapStreakStats {
    val active = dailyMs.entries
        .filter { it.value > 0L }
        .sortedBy { it.key }
    if (active.isEmpty()) {
        return RecapStreakStats(
            longestStreakDays = 0,
            streakStart = null,
            streakEnd = null,
            peakDate = null,
            peakDayMs = 0L,
            activeDaysCount = 0,
        )
    }

    var bestStart = active.first().key
    var bestEnd = active.first().key
    var bestLen = 1

    var runStart = active.first().key
    var runEnd = active.first().key
    var runLen = 1

    var peakDate = active.first().key
    var peakMs = active.first().value

    for (i in 1 until active.size) {
        val entry = active[i]
        val date = entry.key
        val ms = entry.value
        if (ms >= peakMs) {
            peakMs = ms
            peakDate = date
        }
        if (runEnd.plusDays(1) == date) {
            runEnd = date
            runLen++
        } else {
            runStart = date
            runEnd = date
            runLen = 1
        }
        if (runLen >= bestLen) {
            bestLen = runLen
            bestStart = runStart
            bestEnd = runEnd
        }
    }

    return RecapStreakStats(
        longestStreakDays = bestLen,
        streakStart = bestStart,
        streakEnd = bestEnd,
        peakDate = peakDate,
        peakDayMs = peakMs,
        activeDaysCount = active.size,
    )
}

fun classifyPersona(
    stats: ListeningStats,
    streak: RecapStreakStats = stats.streak,
): RecapPersonaResult {
    val totalMs = stats.totalMs.coerceAtLeast(1L)
    val nightMs = longArrayOf(22, 23, 0, 1, 2, 3, 4).sumOf { h ->
        stats.hourMs.getOrElse(h.toInt()) { 0L }
    }
    val morningMs = (5..9).sumOf { h -> stats.hourMs.getOrElse(h) { 0L } }
    val eveningMs = (17..21).sumOf { h -> stats.hourMs.getOrElse(h) { 0L } }

    val nightSharePercent = ((nightMs * 100.0) / totalMs).roundToInt().coerceIn(0, 100)
    val morningSharePercent = ((morningMs * 100.0) / totalMs).roundToInt().coerceIn(0, 100)
    val eveningSharePercent = ((eveningMs * 100.0) / totalMs).roundToInt().coerceIn(0, 100)

    val peakHour = stats.hourMs.indices.maxByOrNull { stats.hourMs[it] } ?: 20
    val topSongPlays = stats.topSongs.firstOrNull()?.plays ?: 0
    val top3Plays = stats.topSongs.take(3).sumOf { it.plays }
    val totalPlays = stats.plays.coerceAtLeast(1)
    val topArtist = stats.topArtists.firstOrNull()
    val topArtistMs = topArtist?.playTimeMs ?: 0L
    val topArtistSharePercent = ((topArtistMs * 100.0) / totalMs).roundToInt().coerceIn(0, 100)

    val id = when {
        stats.totalMs > 0L && nightSharePercent >= 36 -> RecapPersonaId.NIGHT_OWL
        stats.totalMs > 0L && morningSharePercent >= 36 -> RecapPersonaId.DAWN_CHASER
        (topSongPlays >= 15 && (topSongPlays * 100 / totalPlays) >= 18) ||
            (stats.plays >= 12 && (top3Plays * 100 / totalPlays) >= 45) -> RecapPersonaId.ONE_SONG_DEVOTEE
        topArtistSharePercent >= 35 && (topArtist?.plays ?: 0) >= 8 -> RecapPersonaId.ARTIST_LOYALIST
        (stats.songCount >= 40 && stats.plays.toFloat() / stats.songCount <= 2.2f) ||
            stats.artistCount >= 25 -> RecapPersonaId.SONIC_EXPLORER
        streak.longestStreakDays >= 10 || stats.totalMs >= 40L * 3_600_000L -> RecapPersonaId.STREAK_MACHINE
        stats.totalMs > 0L && eveningSharePercent >= 38 -> RecapPersonaId.GOLDEN_HOUR
        else -> RecapPersonaId.VIBE_CURATOR
    }

    return RecapPersonaResult(
        id = id,
        peakHour = peakHour,
        nightSharePercent = nightSharePercent,
        topArtistSharePercent = topArtistSharePercent,
        topSongPlays = topSongPlays,
    )
}

fun computePeriodDelta(
    current: ListeningStats,
    previous: ListeningStats?,
): RecapPeriodDelta {
    if (previous == null || previous.isEmpty || previous.totalMs <= 0L) {
        return RecapPeriodDelta(hasPrevious = false)
    }
    val deltaPercent = (((current.totalMs - previous.totalMs) * 100.0) / previous.totalMs)
        .roundToInt()
        .coerceIn(-99, 999)
    val curArtist = current.topArtists.firstOrNull()?.name?.trim()?.lowercase()
    val prevArtist = previous.topArtists.firstOrNull()?.name?.trim()?.lowercase()
    val isNewTopArtist = curArtist != null && prevArtist != null && curArtist != prevArtist

    val curSong = current.topSongs.firstOrNull()?.id
    val prevSong = previous.topSongs.firstOrNull()?.id
    val isNewTopSong = curSong != null && prevSong != null && curSong != prevSong

    return RecapPeriodDelta(
        hasPrevious = true,
        previousLabel = previous.range.label,
        timeDeltaPercent = deltaPercent,
        playsDelta = current.plays - previous.plays,
        isNewTopArtist = isNewTopArtist,
        isNewTopSong = isNewTopSong,
    )
}

fun buildRecapInsights(
    current: ListeningStats,
    previous: ListeningStats? = null,
): RecapInsights = RecapInsights(
    persona = classifyPersona(current, current.streak),
    streak = current.streak,
    delta = computePeriodDelta(current, previous),
)
