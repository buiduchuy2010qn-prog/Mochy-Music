package com.mochyy.mochymusic.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

class SmartMixEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dayMs = 86_400_000L
    private val nowMs = 1_760_000_000_000L

    @Test
    fun daySlotCoversAllHoursAccurately() {
        assertEquals(DaySlot.MORNING, SmartMixEngine.daySlotForHour(5))
        assertEquals(DaySlot.MORNING, SmartMixEngine.daySlotForHour(11))
        assertEquals(DaySlot.AFTERNOON, SmartMixEngine.daySlotForHour(12))
        assertEquals(DaySlot.AFTERNOON, SmartMixEngine.daySlotForHour(17))
        assertEquals(DaySlot.EVENING, SmartMixEngine.daySlotForHour(18))
        assertEquals(DaySlot.EVENING, SmartMixEngine.daySlotForHour(21))
        assertEquals(DaySlot.LATE_NIGHT, SmartMixEngine.daySlotForHour(22))
        assertEquals(DaySlot.LATE_NIGHT, SmartMixEngine.daySlotForHour(0))
        assertEquals(DaySlot.LATE_NIGHT, SmartMixEngine.daySlotForHour(4))
    }

    @Test
    fun buildsAllFiveOnDeviceMixesRespectingTimeBpmForgottenAndHabits() {
        val tracks = buildMap {
            // 4 late-night chill songs
            for (i in 1..4) {
                put("chill_$i", SmartMixTrack("chill_$i", "chill_artist_$i", liked = true, durationSec = 210))
            }
            // 4 recent on-repeat upbeat songs
            for (i in 1..4) {
                put("repeat_$i", SmartMixTrack("repeat_$i", "pop_artist_$i", liked = true, durationSec = 195))
            }
            // 4 forgotten favorites (played heavily 60 days ago, ZERO plays in last 30 days)
            for (i in 1..4) {
                put("forgot_$i", SmartMixTrack("forgot_$i", "old_artist_$i", liked = true, durationSec = 230))
            }
            // 1 heavily skipped song that should be suppressed
            put("skipped_1", SmartMixTrack("skipped_1", "annoying_artist", liked = false, durationSec = 200))
        }

        val events = buildList {
            for (i in 1..4) {
                repeat(3) { k ->
                    add(SmartMixEvent("chill_$i", nowMs - (k + 1) * dayMs, hourOfDay = 23, playTimeMs = 200_000L))
                }
            }
            for (i in 1..4) {
                repeat(6) { k ->
                    add(SmartMixEvent("repeat_$i", nowMs - (k + 1) * dayMs, hourOfDay = 14, playTimeMs = 190_000L))
                }
            }
            for (i in 1..4) {
                repeat(5) { k ->
                    add(SmartMixEvent("forgot_$i", nowMs - (45 + k) * dayMs, hourOfDay = 19, playTimeMs = 220_000L))
                }
            }
            repeat(5) { k ->
                add(SmartMixEvent("skipped_1", nowMs - (k + 1) * dayMs, hourOfDay = 23, playTimeMs = 8_000L))
            }
        }

        val features = buildMap {
            for (i in 1..4) {
                put("chill_$i", SmartMixAudioFeature(bpm = 78f + i * 3f, energy = 0.28f, danceability = 0.35f, bassWeight = 0.3f))
                put("repeat_$i", SmartMixAudioFeature(bpm = 126f + i * 4f, energy = 0.82f, danceability = 0.78f, bassWeight = 0.7f))
            }
        }

        val habits = mapOf("skipped_1" to -0.95, "repeat_1" to 0.9)

        val mixes = SmartMixEngine.buildMixes(
            tracks = tracks,
            events = events,
            audioFeatures = features,
            habitScores = habits,
            nowEpochMs = nowMs,
            currentHour = 23,
        )

        val byKind = mixes.associateBy { it.kind }
        assertTrue(SmartMixKind.TIME_OF_DAY in byKind)
        assertTrue(SmartMixKind.ON_REPEAT in byKind)
        assertTrue(SmartMixKind.FORGOTTEN_FAVORITES in byKind)
        assertTrue(SmartMixKind.CHILL_BPM in byKind)
        assertTrue(SmartMixKind.UPBEAT_BPM in byKind)

        // Late night Time-of-Day mix should surface late-night chill songs near the top and exclude skipped_1
        val lateNight = byKind.getValue(SmartMixKind.TIME_OF_DAY)
        assertEquals(DaySlot.LATE_NIGHT, lateNight.daySlot)
        assertTrue(lateNight.songIds.take(4).all { it.startsWith("chill_") })
        assertFalse("skipped_1" in lateNight.songIds)

        // On Repeat should prioritize repeat_* tracks and exclude skipped_1
        val onRepeat = byKind.getValue(SmartMixKind.ON_REPEAT)
        assertTrue(onRepeat.songIds.take(4).all { it.startsWith("repeat_") })
        assertFalse("skipped_1" in onRepeat.songIds)

        // Forgotten Favorites must find songs with 0 plays in the last 30 days and never include recent repeat_* songs
        val forgotten = byKind.getValue(SmartMixKind.FORGOTTEN_FAVORITES)
        assertEquals(setOf("forgot_1", "forgot_2", "forgot_3", "forgot_4"), forgotten.songIds.toSet())

        // Chill BPM must contain chill_* and NEVER leak 130+ BPM upbeat repeat_* tracks
        val chill = byKind.getValue(SmartMixKind.CHILL_BPM)
        assertTrue(chill.songIds.all { !it.startsWith("repeat_") })
        assertTrue(chill.songIds.containsAll(listOf("chill_1", "chill_2", "chill_3", "chill_4")))
        assertNotNull(chill.avgBpm)
        assertTrue(chill.avgBpm!! < 105)

        // Upbeat BPM must contain repeat_* and NEVER leak low-BPM chill_* tracks
        val upbeat = byKind.getValue(SmartMixKind.UPBEAT_BPM)
        assertTrue(upbeat.songIds.all { !it.startsWith("chill_") })
        assertTrue(upbeat.songIds.containsAll(listOf("repeat_1", "repeat_2", "repeat_3", "repeat_4")))
        assertNotNull(upbeat.avgBpm)
        assertTrue(upbeat.avgBpm!! >= 120)
    }

    @Test
    fun spaceArtistsAvoidsConsecutiveSameArtist() {
        val tracks = mapOf(
            "a1" to SmartMixTrack("a1", "son tung"),
            "a2" to SmartMixTrack("a2", "son tung"),
            "a3" to SmartMixTrack("a3", "son tung"),
            "b1" to SmartMixTrack("b1", "den vau"),
            "b2" to SmartMixTrack("b2", "den vau"),
            "c1" to SmartMixTrack("c1", "wren evans"),
        )
        val spaced = SmartMixEngine.spaceArtists(listOf("a1", "a2", "a3", "b1", "b2", "c1"), tracks, 6)
        assertEquals(6, spaced.size)
        for (i in 1 until spaced.size) {
            assertFalse(
                "Back-to-back artist at index $i: $spaced",
                tracks[spaced[i]]?.primaryArtistKey == tracks[spaced[i - 1]]?.primaryArtistKey,
            )
        }
    }

    @Test
    fun crossmixFeatureCacheReaderParsesV5BinaryAndIgnoresCorruptFiles() {
        val cacheRoot = tempFolder.newFolder("cache")
        val v2Dir = File(cacheRoot, "crossmix-analysis-v2").also { it.mkdirs() }

        val validFile = CrossmixFeatureCacheReader.cacheFile(v2Dir, "song_ok")
        DataOutputStream(BufferedOutputStream(validFile.outputStream())).use { out ->
            out.writeInt(5) // CacheVersion
            out.writeLong(180_000L) // analyzedMs
            out.writeBoolean(true) // complete
            out.writeFloat(124.5f) // bpm
            out.writeFloat(0.88f) // bpmConfidence
            out.writeInt(3); out.writeLong(500L); out.writeLong(1000L); out.writeLong(1500L) // beatsMs
            out.writeInt(2); out.writeInt(0); out.writeInt(2) // barBeats
            out.writeInt(1); out.writeInt(0) // sectionBars
            out.writeUTF("C minor") // key
            out.writeFloat(0.7f) // keyConfidence
            out.writeLong(100L) // leadingSilenceEndMs
            out.writeLong(175_000L) // trailingSilenceStartMs
            out.writeInt(2); out.writeFloat(0.4f); out.writeFloat(0.8f) // energy
            out.writeInt(2); out.writeFloat(0.5f); out.writeFloat(0.6f) // bass
            out.writeInt(2); out.writeFloat(0.3f); out.writeFloat(0.7f) // brightness
            out.writeFloat(0.77f) // vibe.energy
            out.writeFloat(0.81f) // vibe.danceability
            out.writeFloat(0.55f) // vibe.brightness
            out.writeFloat(0.64f) // vibe.bassWeight
            out.writeBoolean(true) // vibe.minor
            out.writeInt(0) // vocal
        }

        val corruptFile = CrossmixFeatureCacheReader.cacheFile(v2Dir, "song_bad")
        corruptFile.writeBytes(byteArrayOf(0, 0, 0, 4, 1, 2)) // old version + truncated

        val map = CrossmixFeatureCacheReader.readForSongIds(cacheRoot, listOf("song_ok", "song_bad", "song_missing"))
        assertEquals(1, map.size)
        val feat = map["song_ok"]
        assertNotNull(feat)
        assertEquals(124.5f, feat!!.bpm!!, 0.01f)
        assertEquals(0.77f, feat.energy, 0.001f)
        assertEquals(0.81f, feat.danceability, 0.001f)
        assertEquals(0.64f, feat.bassWeight, 0.001f)
        assertNull(map["song_bad"])
        assertNull(map["song_missing"])
    }

    @Test
    fun emptyInputsReturnEmptyMixes() {
        assertTrue(SmartMixEngine.buildMixes(emptyMap(), emptyList(), emptyMap(), emptyMap(), nowMs, 10).isEmpty())
    }

    @Test
    fun chillAndUpbeatAreMutuallyExclusiveAndNonFiniteFeaturesNeverCrash() {
        val tracks = (1..6).associate { i ->
            "t$i" to SmartMixTrack("t$i", "artist_$i", liked = true, durationSec = 200)
        }
        val events = listOf(
            // Borderline 50/50 unanalyzed tracks (1 morning + 1 late night)
            SmartMixEvent("t5", nowMs - dayMs, hourOfDay = 9, playTimeMs = 180_000L),
            SmartMixEvent("t5", nowMs - 2 * dayMs, hourOfDay = 23, playTimeMs = 180_000L),
            SmartMixEvent("t6", nowMs - dayMs, hourOfDay = 10, playTimeMs = 180_000L),
            SmartMixEvent("t6", nowMs - 2 * dayMs, hourOfDay = 23, playTimeMs = 180_000L),
        ) + (1..4).map { i -> SmartMixEvent("t$i", nowMs - dayMs, hourOfDay = 15, playTimeMs = 180_000L) }

        val features = mapOf(
            // Borderline BPM/energy track that previously leaked into BOTH Chill and Upbeat
            "t1" to SmartMixAudioFeature(bpm = 104f, energy = 0.62f, danceability = 0.5f, bassWeight = 0.5f),
            // Non-finite corrupted values that previously produced NaN sort keys
            "t2" to SmartMixAudioFeature(bpm = Float.POSITIVE_INFINITY, energy = Float.NaN, danceability = Float.NaN, bassWeight = Float.NaN),
            "t3" to SmartMixAudioFeature(bpm = 82f, energy = 0.25f, danceability = 0.3f, bassWeight = 0.3f),
            "t4" to SmartMixAudioFeature(bpm = 132f, energy = 0.85f, danceability = 0.8f, bassWeight = 0.7f),
        )

        val mixes = SmartMixEngine.buildMixes(
            tracks = tracks,
            events = events,
            audioFeatures = features,
            nowEpochMs = nowMs,
            currentHour = 15,
            maxTracksPerMix = 1, // previously risked negative take() count
        )
        val chillIds = mixes.firstOrNull { it.kind == SmartMixKind.CHILL_BPM }?.songIds.orEmpty().toSet()
        val upbeatIds = mixes.firstOrNull { it.kind == SmartMixKind.UPBEAT_BPM }?.songIds.orEmpty().toSet()
        assertTrue("Chill and Upbeat must never overlap: chill=$chillIds upbeat=$upbeatIds", chillIds.intersect(upbeatIds).isEmpty())
    }
}
