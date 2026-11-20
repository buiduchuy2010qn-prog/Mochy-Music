package com.mochyy.mochymusic.smart

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.roundToInt

enum class DaySlot {
    MORNING,    // 05:00 - 11:59
    AFTERNOON,  // 12:00 - 17:59
    EVENING,    // 18:00 - 21:59
    LATE_NIGHT, // 22:00 - 04:59
}

enum class SmartMixKind(val sectionId: String) {
    TIME_OF_DAY("smart_mix_time_of_day"),
    ON_REPEAT("smart_mix_on_repeat"),
    FORGOTTEN_FAVORITES("smart_mix_forgotten_favorites"),
    CHILL_BPM("smart_mix_chill_bpm"),
    UPBEAT_BPM("smart_mix_upbeat_bpm");

    companion object {
        fun fromSectionId(id: String): SmartMixKind? = entries.firstOrNull { it.sectionId == id }
    }
}

data class SmartMixEvent(
    val songId: String,
    val epochMs: Long,
    val hourOfDay: Int,
    val playTimeMs: Long,
)

data class SmartMixAudioFeature(
    val bpm: Float?,
    val energy: Float,
    val danceability: Float,
    val bassWeight: Float,
)

data class SmartMixTrack(
    val songId: String,
    val primaryArtistKey: String,
    val liked: Boolean = false,
    val durationSec: Int = 0,
)

data class GeneratedSmartMix(
    val kind: SmartMixKind,
    val daySlot: DaySlot,
    val songIds: List<String>,
    val bpmMatchedCount: Int = 0,
    val avgBpm: Int? = null,
)

/**
 * 100% on-device Smart Mix generator powered by local Room listening history (`Event` timestamps +
 * play times), Crossmix BPM/energy analysis cache, and skip/replay habit affinity scores.
 */
object SmartMixEngine {

    private const val DayMs = 86_400_000L
    private const val OnRepeatWindowDays = 30L
    private const val ForgottenMinSilenceDays = 21L
    private const val ForgottenFallbackSilenceDays = 10L
    private const val MinTracksPerMix = 3

    fun daySlotForHour(hourOfDay: Int): DaySlot {
        val h = Math.floorMod(hourOfDay, 24)
        return when (h) {
            in 5..11 -> DaySlot.MORNING
            in 12..17 -> DaySlot.AFTERNOON
            in 18..21 -> DaySlot.EVENING
            else -> DaySlot.LATE_NIGHT
        }
    }

    private fun isAdjacentSlot(a: DaySlot, b: DaySlot): Boolean {
        val diff = Math.floorMod(a.ordinal - b.ordinal, DaySlot.entries.size)
        return diff == 1 || diff == DaySlot.entries.size - 1
    }

    fun buildMixes(
        tracks: Map<String, SmartMixTrack>,
        events: List<SmartMixEvent>,
        audioFeatures: Map<String, SmartMixAudioFeature>,
        habitScores: Map<String, Double> = emptyMap(),
        nowEpochMs: Long,
        currentHour: Int,
        maxTracksPerMix: Int = 25,
    ): List<GeneratedSmartMix> {
        if (tracks.isEmpty()) return emptyList()
        val slot = daySlotForHour(currentHour)

        // Aggregate per-song event metrics once in O(E)
        val eventsBySong = HashMap<String, MutableList<SmartMixEvent>>()
        for (ev in events) {
            if (ev.songId in tracks) {
                eventsBySong.getOrPut(ev.songId) { ArrayList() }.add(ev)
            }
        }

        val mixes = ArrayList<GeneratedSmartMix>(5)

        buildTimeOfDayMix(tracks, eventsBySong, audioFeatures, habitScores, nowEpochMs, slot, maxTracksPerMix)
            ?.let { mixes += it }

        buildOnRepeatMix(tracks, eventsBySong, habitScores, nowEpochMs, slot, maxTracksPerMix)
            ?.let { mixes += it }

        buildForgottenFavoritesMix(tracks, eventsBySong, habitScores, nowEpochMs, slot, maxTracksPerMix)
            ?.let { mixes += it }

        buildBpmMoodMix(
            kind = SmartMixKind.CHILL_BPM,
            chill = true,
            tracks = tracks,
            eventsBySong = eventsBySong,
            audioFeatures = audioFeatures,
            habitScores = habitScores,
            nowEpochMs = nowEpochMs,
            slot = slot,
            maxTracks = maxTracksPerMix,
        )?.let { mixes += it }

        buildBpmMoodMix(
            kind = SmartMixKind.UPBEAT_BPM,
            chill = false,
            tracks = tracks,
            eventsBySong = eventsBySong,
            audioFeatures = audioFeatures,
            habitScores = habitScores,
            nowEpochMs = nowEpochMs,
            slot = slot,
            maxTracks = maxTracksPerMix,
        )?.let { mixes += it }

        return mixes
    }

    private fun buildTimeOfDayMix(
        tracks: Map<String, SmartMixTrack>,
        eventsBySong: Map<String, List<SmartMixEvent>>,
        audioFeatures: Map<String, SmartMixAudioFeature>,
        habitScores: Map<String, Double>,
        nowEpochMs: Long,
        slot: DaySlot,
        maxTracks: Int,
    ): GeneratedSmartMix? {
        val scored = ArrayList<Pair<String, Double>>()
        for ((songId, track) in tracks) {
            val songEvents = eventsBySong[songId].orEmpty()
            if (songEvents.isEmpty() && !track.liked) continue

            var slotWeight = 0.0
            var matchingSlotHits = 0
            for (ev in songEvents) {
                val ageDays = ((nowEpochMs - ev.epochMs).coerceAtLeast(0L)).toDouble() / DayMs
                val recency = exp(-ageDays / 35.0)
                val durationFactor = (ev.playTimeMs.coerceAtLeast(15_000L) / 120_000.0).coerceIn(0.25, 2.0)
                val evSlot = daySlotForHour(ev.hourOfDay)
                val timeMatch = when {
                    evSlot == slot -> {
                        matchingSlotHits++
                        1.0
                    }
                    isAdjacentSlot(evSlot, slot) -> 0.30
                    else -> 0.05
                }
                slotWeight += timeMatch * recency * durationFactor
            }

            // Modulate with Crossmix energy appropriate to the hour of day
            val feat = audioFeatures[songId]
            val moodBonus = if (feat != null) {
                val energy = if (feat.energy.isFinite()) feat.energy.coerceIn(0f, 1f) else 0.5f
                val dance = if (feat.danceability.isFinite()) feat.danceability.coerceIn(0f, 1f) else 0.5f
                val bpm = feat.bpm?.takeIf { it.isFinite() && it in 40f..240f }
                when (slot) {
                    DaySlot.LATE_NIGHT -> if (energy <= 0.52f || (bpm != null && bpm <= 108f)) 0.65 else -0.25
                    DaySlot.MORNING -> if (energy in 0.38f..0.78f) 0.40 else 0.0
                    DaySlot.AFTERNOON -> if (energy >= 0.50f || dance >= 0.55f) 0.45 else 0.0
                    DaySlot.EVENING -> if (energy in 0.30f..0.68f) 0.35 else 0.0
                }
            } else {
                0.0
            }

            val habit = habitScores[songId] ?: 0.0
            if (habit <= -0.65) continue // heavily skipped track stays out

            val totalScore = slotWeight + (if (matchingSlotHits > 0) 0.8 else 0.0) +
                moodBonus + (if (track.liked) 0.35 else 0.0) + habit * 0.55
            if (totalScore > 0.25) {
                scored += songId to totalScore
            }
        }

        val ordered = spaceArtists(scored.sortedByDescending { it.second }.map { it.first }, tracks, maxTracks)
        if (ordered.size < minThreshold(tracks.size)) return null
        return GeneratedSmartMix(
            kind = SmartMixKind.TIME_OF_DAY,
            daySlot = slot,
            songIds = ordered,
            bpmMatchedCount = ordered.count { audioFeatures[it]?.bpm != null },
            avgBpm = averageBpmOf(ordered, audioFeatures),
        )
    }

    private fun buildOnRepeatMix(
        tracks: Map<String, SmartMixTrack>,
        eventsBySong: Map<String, List<SmartMixEvent>>,
        habitScores: Map<String, Double>,
        nowEpochMs: Long,
        slot: DaySlot,
        maxTracks: Int,
    ): GeneratedSmartMix? {
        val recentSince = nowEpochMs - OnRepeatWindowDays * DayMs
        val scored = ArrayList<Pair<String, Double>>()

        for ((songId, track) in tracks) {
            val recent = eventsBySong[songId].orEmpty().filter { it.epochMs >= recentSince }
            if (recent.isEmpty()) continue
            val habit = habitScores[songId] ?: 0.0
            if (habit <= -0.55) continue

            val playCount = recent.size
            val totalPlaySec = recent.sumOf { it.playTimeMs.coerceAtLeast(0L) } / 1000.0
            val recencyBoost = recent.sumOf { ev ->
                val ageDays = ((nowEpochMs - ev.epochMs).coerceAtLeast(0L)).toDouble() / DayMs
                exp(-ageDays / 10.0)
            }
            val score = (playCount * 1.15) +
                (totalPlaySec / 180.0).coerceAtMost(8.0) +
                (recencyBoost * 0.9) +
                (if (track.liked) 0.6 else 0.0) +
                (habit * 1.1)

            // Favor songs actually replayed (playCount >= 2 or strong listening time/replay habit)
            if (playCount >= 2 || totalPlaySec >= 180.0 || habit >= 0.4) {
                scored += songId to score
            }
        }

        // If user is new and only played songs once recently, still populate On Repeat from recent plays
        if (scored.size < MinTracksPerMix) {
            for ((songId, _) in tracks) {
                if (scored.any { it.first == songId }) continue
                val recent = eventsBySong[songId].orEmpty().filter { it.epochMs >= recentSince }
                if (recent.isNotEmpty() && (habitScores[songId] ?: 0.0) > -0.5) {
                    val totalPlaySec = recent.sumOf { it.playTimeMs.coerceAtLeast(0L) } / 1000.0
                    scored += songId to (0.5 + totalPlaySec / 240.0)
                }
            }
        }

        val ordered = spaceArtists(scored.sortedByDescending { it.second }.map { it.first }, tracks, maxTracks)
        if (ordered.size < minThreshold(tracks.size)) return null
        return GeneratedSmartMix(
            kind = SmartMixKind.ON_REPEAT,
            daySlot = slot,
            songIds = ordered,
        )
    }

    private fun buildForgottenFavoritesMix(
        tracks: Map<String, SmartMixTrack>,
        eventsBySong: Map<String, List<SmartMixEvent>>,
        habitScores: Map<String, Double>,
        nowEpochMs: Long,
        slot: DaySlot,
        maxTracks: Int,
    ): GeneratedSmartMix? {
        fun collect(silenceDays: Long): List<Pair<String, Double>> {
            val silenceCutoff = nowEpochMs - silenceDays * DayMs
            val list = ArrayList<Pair<String, Double>>()
            for ((songId, track) in tracks) {
                val songEvents = eventsBySong[songId].orEmpty()
                if (songEvents.isEmpty()) continue
                val habit = habitScores[songId] ?: 0.0
                if (habit <= -0.55) continue

                val lastPlayedMs = songEvents.maxOf { it.epochMs }
                if (lastPlayedMs > silenceCutoff) continue // still being played recently

                val historicalCount = songEvents.size
                val historicalPlaySec = songEvents.sumOf { it.playTimeMs.coerceAtLeast(0L) } / 1000.0
                val wasFavorite = track.liked || historicalCount >= 2 || historicalPlaySec >= 120.0
                if (!wasFavorite) continue

                val dormantDays = ((nowEpochMs - lastPlayedMs).coerceAtLeast(0L)).toDouble() / DayMs
                // Sweet spot: beloved in the past, dormant 21-365 days
                val nostalgiaBonus = (dormantDays / 30.0).coerceIn(0.5, 4.0)
                val engagement = (historicalCount * 0.9).coerceAtMost(10.0) +
                    (historicalPlaySec / 180.0).coerceAtMost(8.0) +
                    (if (track.liked) 2.2 else 0.0)
                list += songId to (engagement + nostalgiaBonus + habit * 0.6)
            }
            return list
        }

        var scored = collect(ForgottenMinSilenceDays)
        if (scored.size < MinTracksPerMix) {
            scored = collect(ForgottenFallbackSilenceDays)
        }
        val ordered = spaceArtists(scored.sortedByDescending { it.second }.map { it.first }, tracks, maxTracks)
        if (ordered.size < MinTracksPerMix) return null
        return GeneratedSmartMix(
            kind = SmartMixKind.FORGOTTEN_FAVORITES,
            daySlot = slot,
            songIds = ordered,
        )
    }

    private fun buildBpmMoodMix(
        kind: SmartMixKind,
        chill: Boolean,
        tracks: Map<String, SmartMixTrack>,
        eventsBySong: Map<String, List<SmartMixEvent>>,
        audioFeatures: Map<String, SmartMixAudioFeature>,
        habitScores: Map<String, Double>,
        nowEpochMs: Long,
        slot: DaySlot,
        maxTracks: Int,
    ): GeneratedSmartMix? {
        val strictAnalyzed = ArrayList<Pair<String, Double>>()
        val fallbackUnanalyzed = ArrayList<Pair<String, Double>>()

        for ((songId, track) in tracks) {
            val habit = habitScores[songId] ?: 0.0
            if (habit <= -0.60) continue
            val feat = audioFeatures[songId]
            val songEvents = eventsBySong[songId].orEmpty()
            val engagement = (songEvents.size * 0.25).coerceAtMost(2.0) +
                (if (track.liked) 0.5 else 0.0) +
                habit * 0.5

            if (feat != null) {
                val bpm = feat.bpm?.takeIf { it.isFinite() && it in 40f..240f }
                val energy = if (feat.energy.isFinite()) feat.energy.coerceIn(0f, 1f) else 0.5f
                val dance = if (feat.danceability.isFinite()) feat.danceability.coerceIn(0f, 1f) else 0.5f
                if (chill) {
                    // Mutually exclusive with UPBEAT_BPM
                    if (energy >= 0.58f || (bpm != null && bpm >= 112f)) continue
                    val matchesChill = (bpm != null && bpm <= 104f && energy <= 0.56f) ||
                        (bpm == null && energy <= 0.45f)
                    if (matchesChill) {
                        val bpmScore = if (bpm != null) ((112f - bpm).coerceIn(0f, 52f) / 20.0) else 0.6
                        val energyScore = ((0.60f - energy).coerceIn(0f, 0.60f) * 3.2)
                        strictAnalyzed += songId to (3.0 + bpmScore + energyScore + engagement)
                    }
                } else {
                    // Mutually exclusive with CHILL_BPM
                    if (energy <= 0.44f || (bpm != null && bpm <= 104f)) continue
                    val matchesUpbeat = (bpm != null && bpm >= 114f && energy >= 0.48f) ||
                        (bpm == null && (energy >= 0.62f || dance >= 0.68f))
                    if (matchesUpbeat) {
                        val bpmScore = if (bpm != null) ((bpm - 108f).coerceIn(0f, 65f) / 22.0) else 0.6
                        val energyScore = (energy * 2.2) + (dance * 1.4)
                        strictAnalyzed += songId to (3.0 + bpmScore + energyScore + engagement)
                    }
                }
            } else if (songEvents.isNotEmpty()) {
                // Soft fallback using listening-hour tendencies only when few tracks have Crossmix cache yet;
                // strict > 0.5 guarantees a 50/50 track cannot appear in both Chill and Upbeat mixes.
                val matchingHourRatio = songEvents.count { ev ->
                    val s = daySlotForHour(ev.hourOfDay)
                    if (chill) (s == DaySlot.LATE_NIGHT || s == DaySlot.EVENING)
                    else (s == DaySlot.MORNING || s == DaySlot.AFTERNOON)
                }.toDouble() / songEvents.size
                if (matchingHourRatio > 0.5) {
                    val recency = songEvents.maxOf { ev ->
                        val ageDays = ((nowEpochMs - ev.epochMs).coerceAtLeast(0L)).toDouble() / DayMs
                        exp(-ageDays / 45.0)
                    }
                    fallbackUnanalyzed += songId to (matchingHourRatio + recency + engagement * 0.5)
                }
            }
        }

        val combined = if (strictAnalyzed.size >= MinTracksPerMix) {
            strictAnalyzed
        } else if (strictAnalyzed.isNotEmpty() || fallbackUnanalyzed.size >= MinTracksPerMix) {
            val remainingSlots = (maxTracks - strictAnalyzed.size).coerceAtLeast(0)
            strictAnalyzed + fallbackUnanalyzed.sortedByDescending { it.second }.take(remainingSlots)
        } else {
            emptyList()
        }

        val ordered = spaceArtists(combined.sortedByDescending { it.second }.map { it.first }, tracks, maxTracks)
        if (ordered.size < MinTracksPerMix) return null
        val bpmMatched = ordered.count { audioFeatures[it]?.bpm != null }
        return GeneratedSmartMix(
            kind = kind,
            daySlot = slot,
            songIds = ordered,
            bpmMatchedCount = bpmMatched,
            avgBpm = averageBpmOf(ordered, audioFeatures),
        )
    }

    /**
     * Greedily reorders top-scored song IDs to avoid placing the same artist back-to-back
     * while preserving high-ranked picks near the top of the mix.
     */
    internal fun spaceArtists(
        rankedSongIds: List<String>,
        tracks: Map<String, SmartMixTrack>,
        maxCount: Int,
    ): List<String> {
        if (rankedSongIds.size <= 1) return rankedSongIds.take(maxCount)
        val pool = rankedSongIds.distinct().take(maxCount * 2).toMutableList()
        val result = ArrayList<String>(minOf(pool.size, maxCount))
        val recentArtists = ArrayDeque<String>(3)

        while (pool.isNotEmpty() && result.size < maxCount) {
            var chosenIdx = 0
            if (recentArtists.isNotEmpty()) {
                val immediateArtist = recentArtists.last()
                val altIdx = pool.indexOfFirst { id ->
                    val artist = tracks[id]?.primaryArtistKey.orEmpty()
                    artist.isBlank() || artist !in recentArtists
                }
                if (altIdx in 0..8) {
                    chosenIdx = altIdx
                } else {
                    val nonImmediate = pool.indexOfFirst { id ->
                        val artist = tracks[id]?.primaryArtistKey.orEmpty()
                        artist.isBlank() || artist != immediateArtist
                    }
                    if (nonImmediate in 0..12) chosenIdx = nonImmediate
                }
            }
            val pickedId = pool.removeAt(chosenIdx)
            result += pickedId
            val artist = tracks[pickedId]?.primaryArtistKey.orEmpty()
            if (artist.isNotBlank()) {
                recentArtists.addLast(artist)
                while (recentArtists.size > 2) recentArtists.removeFirst()
            }
        }
        return result
    }

    private fun averageBpmOf(
        songIds: List<String>,
        audioFeatures: Map<String, SmartMixAudioFeature>,
    ): Int? {
        val bpms = songIds.mapNotNull { audioFeatures[it]?.bpm?.takeIf { b -> b.isFinite() && b in 45f..220f } }
        if (bpms.isEmpty()) return null
        return (bpms.sum() / bpms.size).roundToInt()
    }

    private fun minThreshold(totalTracks: Int): Int =
        if (totalTracks < MinTracksPerMix) 1 else MinTracksPerMix
}

/**
 * Lightweight binary reader for `CrossmixAnalysisRepository`'s disk cache (`crossmix-analysis-v2`)
 * that extracts only `bpm` and `Vibe` (`energy`, `danceability`, `bassWeight`) without allocating
 * per-beat or per-second curves and without invoking ExoPlayer.
 */
object CrossmixFeatureCacheReader {
    private const val CacheDirName = "crossmix-analysis-v2"
    private const val CacheVersion = 5
    private const val MaxCachedArray = 200_000

    fun readForSongIds(cacheRootDir: File, songIds: Collection<String>): Map<String, SmartMixAudioFeature> {
        val dir = File(cacheRootDir, CacheDirName)
        if (!dir.isDirectory) return emptyMap()
        val out = HashMap<String, SmartMixAudioFeature>()
        for (id in songIds) {
            if (id.isBlank() || out.containsKey(id)) continue
            val file = cacheFile(dir, id)
            if (!file.isFile) continue
            readFeatureFromFile(file)?.let { out[id] = it }
        }
        return out
    }

    internal fun cacheFile(dir: File, mediaId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    internal fun readFeatureFromFile(file: File): SmartMixAudioFeature? = runCatching {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != CacheVersion) return null
            input.readLong() // analyzedMs
            input.readBoolean() // complete
            val bpm = input.readFloat().takeIf { it.isFinite() && it in 40f..240f }
            input.readFloat() // bpmConfidence
            if (!skipTypedArray(input, 8L)) return null // beatsMs
            if (!skipTypedArray(input, 4L)) return null // barBeats
            if (!skipTypedArray(input, 4L)) return null // sectionBars
            input.readUTF() // key
            input.readFloat() // keyConfidence
            input.readLong() // leadingSilenceEndMs
            input.readLong() // trailingSilenceStartMs
            if (!skipTypedArray(input, 4L)) return null // energy curve
            if (!skipTypedArray(input, 4L)) return null // bass curve
            if (!skipTypedArray(input, 4L)) return null // brightness curve
            val rawEnergy = input.readFloat()
            val rawDance = input.readFloat()
            input.readFloat() // vibe.brightness
            val rawBass = input.readFloat()
            input.readBoolean() // vibe.minor
            SmartMixAudioFeature(
                bpm = bpm,
                energy = if (rawEnergy.isFinite()) rawEnergy.coerceIn(0f, 1f) else 0.5f,
                danceability = if (rawDance.isFinite()) rawDance.coerceIn(0f, 1f) else 0.5f,
                bassWeight = if (rawBass.isFinite()) rawBass.coerceIn(0f, 1f) else 0.5f,
            )
        }
    }.getOrNull()

    private fun skipTypedArray(input: DataInputStream, elementBytes: Long): Boolean {
        val count = input.readInt()
        if (count < 0 || count > MaxCachedArray) return false
        var remaining = count.toLong() * elementBytes
        while (remaining > 0L) {
            val skipped = input.skipBytes(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (skipped <= 0) return false
            remaining -= skipped.toLong()
        }
        return true
    }
}
