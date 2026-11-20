package com.mochyy.mochymusic.recap

import com.mochyy.mochymusic.ui.screens.recap.ListeningStats
import com.mochyy.mochymusic.ui.screens.recap.RecapArtist
import com.mochyy.mochymusic.ui.screens.recap.RecapPersonaId
import com.mochyy.mochymusic.ui.screens.recap.RecapSong
import com.mochyy.mochymusic.ui.screens.recap.RecapStreakStats
import com.mochyy.mochymusic.ui.screens.recap.StatsRange
import com.mochyy.mochymusic.ui.screens.recap.buildRecapInsights
import com.mochyy.mochymusic.ui.screens.recap.classifyPersona
import com.mochyy.mochymusic.ui.screens.recap.computePeriodDelta
import com.mochyy.mochymusic.ui.screens.recap.computeStreakFromDailyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecapInsightsTest {

    @Test
    fun streakComputesEmptySingleNonConsecutiveTieAndPeakDayCorrectly() {
        val empty = computeStreakFromDailyMap(emptyMap())
        assertEquals(0, empty.longestStreakDays)
        assertNull(empty.peakDate)
        assertEquals(0, empty.activeDaysCount)

        val d = LocalDate.of(2026, 11, 5)
        val single = computeStreakFromDailyMap(mapOf(d to 3_600_000L, d.plusDays(1) to 0L))
        assertEquals(1, single.longestStreakDays)
        assertEquals(d, single.streakStart)
        assertEquals(d, single.streakEnd)
        assertEquals(d, single.peakDate)
        assertEquals(1, single.activeDaysCount)

        // Two equal-length 3-day runs: prefers the later run on ties; peak day prefers highest (or later on tie)
        val data = mapOf(
            LocalDate.of(2026, 11, 1) to 1_000L,
            LocalDate.of(2026, 11, 2) to 2_000L,
            LocalDate.of(2026, 11, 3) to 5_000L,
            LocalDate.of(2026, 11, 10) to 1_500L,
            LocalDate.of(2026, 11, 11) to 9_000L,
            LocalDate.of(2026, 11, 12) to 3_000L,
        )
        val tied = computeStreakFromDailyMap(data)
        assertEquals(3, tied.longestStreakDays)
        assertEquals(LocalDate.of(2026, 11, 10), tied.streakStart)
        assertEquals(LocalDate.of(2026, 11, 12), tied.streakEnd)
        assertEquals(LocalDate.of(2026, 11, 11), tied.peakDate)
        assertEquals(9_000L, tied.peakDayMs)
        assertEquals(6, tied.activeDaysCount)
    }

    @Test
    fun personaClassifierDistinguishesAllEightPersonas() {
        val night = fakeStats(hourDistribution = mapOf(23 to 4_000_000L, 1 to 2_000_000L, 14 to 1_000_000L))
        assertEquals(RecapPersonaId.NIGHT_OWL, classifyPersona(night).id)

        val dawn = fakeStats(hourDistribution = mapOf(6 to 4_000_000L, 7 to 2_000_000L, 15 to 1_000_000L))
        assertEquals(RecapPersonaId.DAWN_CHASER, classifyPersona(dawn).id)

        val oneSong = fakeStats(
            totalMs = 10_000_000L,
            plays = 40,
            topSongs = listOf(fakeSong("s1", plays = 22, ms = 5_000_000L)),
            hourDistribution = mapOf(14 to 10_000_000L),
        )
        assertEquals(RecapPersonaId.ONE_SONG_DEVOTEE, classifyPersona(oneSong).id)

        val loyalist = fakeStats(
            totalMs = 10_000_000L,
            plays = 30,
            topSongs = listOf(fakeSong("s1", plays = 3, ms = 1_000_000L)),
            topArtists = listOf(fakeArtist("a1", "Artist One", plays = 12, ms = 4_500_000L)),
            hourDistribution = mapOf(14 to 10_000_000L),
        )
        assertEquals(RecapPersonaId.ARTIST_LOYALIST, classifyPersona(loyalist).id)

        val explorer = fakeStats(
            totalMs = 10_000_000L,
            plays = 60,
            songCount = 48,
            artistCount = 30,
            topSongs = listOf(fakeSong("s1", plays = 2, ms = 400_000L)),
            topArtists = listOf(fakeArtist("a1", "Variety", plays = 4, ms = 800_000L)),
            hourDistribution = mapOf(14 to 10_000_000L),
        )
        assertEquals(RecapPersonaId.SONIC_EXPLORER, classifyPersona(explorer).id)

        val streakMachine = fakeStats(
            totalMs = 10_000_000L,
            plays = 25,
            songCount = 15,
            artistCount = 8,
            streakDays = 14,
            hourDistribution = mapOf(14 to 10_000_000L),
        )
        assertEquals(RecapPersonaId.STREAK_MACHINE, classifyPersona(streakMachine).id)

        val goldenHour = fakeStats(
            totalMs = 10_000_000L,
            plays = 20,
            songCount = 15,
            artistCount = 8,
            hourDistribution = mapOf(18 to 5_000_000L, 19 to 3_000_000L, 14 to 2_000_000L),
        )
        assertEquals(RecapPersonaId.GOLDEN_HOUR, classifyPersona(goldenHour).id)

        val curator = fakeStats(
            totalMs = 10_000_000L,
            plays = 20,
            songCount = 15,
            artistCount = 8,
            hourDistribution = mapOf(12 to 3_500_000L, 15 to 3_500_000L, 20 to 3_000_000L),
        )
        assertEquals(RecapPersonaId.VIBE_CURATOR, classifyPersona(curator).id)
    }

    @Test
    fun periodDeltaHandlesEmptyPreviousPositiveNegativeAndNewCrown() {
        val current = fakeStats(
            totalMs = 12_000_000L,
            plays = 50,
            topSongs = listOf(fakeSong("s_new", plays = 10, ms = 2_000_000L)),
            topArtists = listOf(fakeArtist("a_new", "Low G", plays = 15, ms = 4_000_000L)),
        )
        val noPrev = computePeriodDelta(current, null)
        assertFalse(noPrev.hasPrevious)

        val emptyPrev = fakeStats(totalMs = 0L, plays = 0)
        assertFalse(computePeriodDelta(current, emptyPrev).hasPrevious)

        val prevLower = fakeStats(
            range = StatsRange(StatsRange.Kind.Month, 2026, 10),
            totalMs = 10_000_000L,
            plays = 35,
            topSongs = listOf(fakeSong("s_old", plays = 8, ms = 1_500_000L)),
            topArtists = listOf(fakeArtist("a_old", "Vu.", plays = 10, ms = 3_000_000L)),
        )
        val upDelta = computePeriodDelta(current, prevLower)
        assertTrue(upDelta.hasPrevious)
        assertEquals(20, upDelta.timeDeltaPercent)
        assertEquals(15, upDelta.playsDelta)
        assertTrue(upDelta.isNewTopArtist)
        assertTrue(upDelta.isNewTopSong)

        val prevHigherSameArtist = fakeStats(
            range = StatsRange(StatsRange.Kind.Month, 2026, 10),
            totalMs = 24_000_000L,
            plays = 80,
            topSongs = listOf(fakeSong("s_new", plays = 12, ms = 2_500_000L)),
            topArtists = listOf(fakeArtist("a_new", "low g", plays = 20, ms = 6_000_000L)),
        )
        val downDelta = buildRecapInsights(current, prevHigherSameArtist).delta
        assertTrue(downDelta.hasPrevious)
        assertEquals(-50, downDelta.timeDeltaPercent)
        assertFalse(downDelta.isNewTopArtist)
        assertFalse(downDelta.isNewTopSong)
    }

    private fun fakeSong(id: String, plays: Int, ms: Long) = RecapSong(
        id = id,
        title = "Song $id",
        artistName = "Artist",
        artistThumbnailUrl = null,
        thumbnailUrl = null,
        playTimeMs = ms,
        plays = plays,
    )

    private fun fakeArtist(id: String, name: String, plays: Int, ms: Long) = RecapArtist(
        id = id,
        name = name,
        thumbnailUrl = null,
        playTimeMs = ms,
        plays = plays,
        topSong = null,
    )

    private fun fakeStats(
        range: StatsRange = StatsRange(StatsRange.Kind.Month, 2026, 11),
        totalMs: Long? = null,
        plays: Int = 20,
        songCount: Int = 12,
        artistCount: Int = 6,
        streakDays: Int = 3,
        topSongs: List<RecapSong> = listOf(fakeSong("s1", plays = 3, ms = 600_000L)),
        topArtists: List<RecapArtist> = listOf(fakeArtist("a1", "Artist A", plays = 5, ms = 1_000_000L)),
        hourDistribution: Map<Int, Long> = mapOf(14 to 5_000_000L),
    ): ListeningStats {
        val hours = MutableList(24) { h -> hourDistribution[h] ?: 0L }
        val resolvedTotal = totalMs ?: hours.sum()
        return ListeningStats(
            range = range,
            totalMs = resolvedTotal,
            plays = plays,
            songCount = songCount,
            artistCount = artistCount,
            albumCount = 4,
            topSongs = topSongs,
            topArtists = topArtists,
            weekdayMs = List(7) { resolvedTotal / 7L },
            hourMs = hours,
            bucketMs = List(range.bucketCount) { 0L },
            streak = RecapStreakStats(
                longestStreakDays = streakDays,
                streakStart = LocalDate.of(2026, 11, 1),
                streakEnd = LocalDate.of(2026, 11, streakDays.coerceAtLeast(1)),
                peakDate = LocalDate.of(2026, 11, 2),
                peakDayMs = resolvedTotal / 3L,
                activeDaysCount = streakDays,
            ),
        )
    }
}
