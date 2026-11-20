package com.mochyy.mochymusic.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartShuffleEngineTest {

    private val nowMs = 1_760_000_000_000L

    @Test
    fun classifiesEarlySkipWithin15sReplayCompletedAndNeutralAccurately() {
        // Skipped at 8 seconds on a 3-minute song -> EARLY_SKIP
        assertEquals(
            TrackInteraction.EARLY_SKIP,
            SmartShuffleEngine.classifyTrackLeave(8_000L, 180_000L, isManualSkip = true, isRepeatReplay = false),
        )
        // Boundary: 14,999ms is EARLY_SKIP, 15,000ms is NEUTRAL
        assertEquals(
            TrackInteraction.EARLY_SKIP,
            SmartShuffleEngine.classifyTrackLeave(14_999L, 180_000L, isManualSkip = true, isRepeatReplay = false),
        )
        assertEquals(
            TrackInteraction.NEUTRAL,
            SmartShuffleEngine.classifyTrackLeave(15_000L, 180_000L, isManualSkip = true, isRepeatReplay = false),
        )
        // Instant scrub (< 500ms) or tiny intro (< 30s) -> NEUTRAL
        assertEquals(
            TrackInteraction.NEUTRAL,
            SmartShuffleEngine.classifyTrackLeave(200L, 180_000L, isManualSkip = true, isRepeatReplay = false),
        )
        assertEquals(
            TrackInteraction.NEUTRAL,
            SmartShuffleEngine.classifyTrackLeave(5_000L, 20_000L, isManualSkip = true, isRepeatReplay = false),
        )
        // Listened >= 75% -> COMPLETED
        assertEquals(
            TrackInteraction.COMPLETED,
            SmartShuffleEngine.classifyTrackLeave(150_000L, 180_000L, isManualSkip = false, isRepeatReplay = false),
        )
        // Repeated track -> REPLAY
        assertEquals(
            TrackInteraction.REPLAY,
            SmartShuffleEngine.classifyTrackLeave(60_000L, 180_000L, isManualSkip = false, isRepeatReplay = true),
        )
    }

    @Test
    fun affinityScoresPenalizeEarlySkipsBoostReplaysAndDecayOverTime() {
        val skipped = SmartShuffleEngine.updateHabit(null, "s1", "Artist A", TrackInteraction.EARLY_SKIP, nowMs)
        val skippedTwice = SmartShuffleEngine.updateHabit(skipped, "s1", "Artist A", TrackInteraction.EARLY_SKIP, nowMs)
        val replayed = SmartShuffleEngine.updateHabit(null, "s2", "Artist B", TrackInteraction.REPLAY, nowMs)

        val scoreSkip1 = SmartShuffleEngine.trackAffinityScore(skipped, nowMs)
        val scoreSkip2 = SmartShuffleEngine.trackAffinityScore(skippedTwice, nowMs)
        val scoreReplay = SmartShuffleEngine.trackAffinityScore(replayed, nowMs)

        assertTrue("Early skip must be negative", scoreSkip1 < 0.0)
        assertTrue("Repeated skip must penalize further", scoreSkip2 < scoreSkip1)
        assertTrue("Replay must be strongly positive", scoreReplay > 0.4)

        // After 28 days, skip penalty should decay substantially
        val laterScore = SmartShuffleEngine.trackAffinityScore(skippedTwice, nowMs + 28L * 86_400_000L)
        assertTrue("Skip penalty decays over time", laterScore > scoreSkip2)
    }

    @Test
    fun smartShuffleSpacesSameArtistPinsCurrentAndInterleavesFamiliarWithFresh() {
        val artists = listOf("son tung", "den vau", "wren evans", "tlinh", "low g")
        val candidates = (0 until 20).map { i ->
            val artist = artists[i % artists.size]
            val isFamiliar = i % 2 == 0
            ShuffleCandidate(
                index = i,
                songId = "song_$i",
                artistKey = artist,
                playCount = if (isFamiliar) 8 else 0,
                lastPlayedAtMs = if (isFamiliar) nowMs - 86_400_000L else 0L,
                habitScore = if (i == 19) -1.0 else if (i == 2) 1.0 else 0.0,
            )
        }

        val order = SmartShuffleEngine.computeSmartShuffleOrder(
            candidates = candidates,
            currentIndex = 4,
            nowMs = nowMs,
            random = Random(42),
        )

        // Exact permutation with currentIndex pinned at index 0
        assertEquals(20, order.size)
        assertEquals(4, order[0])
        assertEquals((0 until 20).toSet(), order.toSet())

        // Zero back-to-back songs by the same artist!
        for (p in 1 until order.size) {
            val prevArtist = candidates[order[p - 1]].artistKey
            val curArtist = candidates[order[p]].artistKey
            assertFalse("Back-to-back artist '$curArtist' at pos $p", prevArtist == curArtist)
        }

        // First half contains BOTH familiar and fresh tracks (interleaved)
        val firstTen = order.drop(1).take(10)
        val familiarCount = firstTen.count { it % 2 == 0 }
        val freshCount = firstTen.count { it % 2 == 1 }
        assertTrue("Expected balanced familiar/fresh mix, got familiar=$familiarCount fresh=$freshCount", familiarCount >= 3 && freshCount >= 3)
    }

    @Test
    fun smartShuffleHandlesSingleArtistAlbumAndEmptyQueuesSafely() {
        assertEquals(0, SmartShuffleEngine.computeSmartShuffleOrder(emptyList(), 0, nowMs).size)

        val single = listOf(ShuffleCandidate(0, "only", "same"))
        assertEquals(listOf(0), SmartShuffleEngine.computeSmartShuffleOrder(single, 0, nowMs).toList())

        val sameArtist = (0 until 6).map { ShuffleCandidate(it, "s$it", "same artist") }
        val order = SmartShuffleEngine.computeSmartShuffleOrder(sameArtist, 2, nowMs, Random(7))
        assertEquals(6, order.size)
        assertEquals(2, order[0])
        assertEquals((0 until 6).toSet(), order.toSet())
    }

    @Test
    fun autoplayRerankingDemotesEarlySkippedSongsAndBoostsReplayedSongs() {
        val habits = mapOf(
            "bad_song" to TrackHabit("bad_song", "artist_bad", earlySkipCount = 3, lastSkippedAtMs = nowMs),
            "loved_song" to TrackHabit("loved_song", "artist_good", replayCount = 3, completeCount = 5, lastPlayedAtMs = nowMs),
        )
        val candidates = listOf(
            AutoplayCandidate(0, "bad_song", "artist_bad"),
            AutoplayCandidate(1, "neutral_1", "artist_n1"),
            AutoplayCandidate(2, "neutral_2", "artist_n2"),
            AutoplayCandidate(3, "loved_song", "artist_good"),
        )

        val ranked = SmartShuffleEngine.rankAutoplayCandidates(candidates, habits, nowMs)
        assertEquals(4, ranked.size)
        assertEquals("Replayed song should rise to #1", 3, ranked.first())
        assertEquals("Frequently skipped song should drop to last", 0, ranked.last())
    }

    @Test
    fun unpinnedSegmentShuffleAvoidsLeadingArtistCollisionAndShufflesAllItems() {
        val candidates = listOf(
            ShuffleCandidate(10, "s10", "son tung"),
            ShuffleCandidate(11, "s11", "son tung"),
            ShuffleCandidate(12, "s12", "den vau"),
            ShuffleCandidate(13, "s13", "wren evans"),
            ShuffleCandidate(14, "s14", "tlinh"),
        )
        val order = SmartShuffleEngine.computeSmartShuffleOrder(
            candidates = candidates,
            currentIndex = -1,
            nowMs = nowMs,
            random = Random(123),
            pinCurrent = false,
            leadingArtistKey = "son tung",
        )
        assertEquals(5, order.size)
        assertEquals(setOf(10, 11, 12, 13, 14), order.toSet())
        val firstArtist = candidates.first { it.index == order.first() }.artistKey
        assertFalse("Unpinned segment must not collide with leadingArtistKey", firstArtist == "son tung")
    }
}
