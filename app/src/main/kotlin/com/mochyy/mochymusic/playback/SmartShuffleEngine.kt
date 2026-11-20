package com.mochyy.mochymusic.playback

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

enum class TrackInteraction {
    /** User skipped the track within the first 15 seconds. */
    EARLY_SKIP,

    /** User actively replayed the track (repeat, seek back to start after >= 50%, or re-selected). */
    REPLAY,

    /** User listened through most of the track (>= 75% or >= 60s when duration unknown). */
    COMPLETED,

    /** Ignored noise (e.g. instant scrub < 500ms, ultra-short interlude, or mid-song leave). */
    NEUTRAL,
}

@Serializable
data class TrackHabit(
    val songId: String,
    val artistKey: String = "",
    val earlySkipCount: Int = 0,
    val completeCount: Int = 0,
    val replayCount: Int = 0,
    val lastPlayedAtMs: Long = 0L,
    val lastSkippedAtMs: Long = 0L,
)

data class ShuffleCandidate(
    val index: Int,
    val songId: String,
    val artistKey: String,
    val playCount: Int = 0,
    val lastPlayedAtMs: Long = 0L,
    val habitScore: Double = 0.0,
)

data class AutoplayCandidate(
    val index: Int,
    val songId: String,
    val artistKey: String,
)

/**
 * Pure JVM-testable engine for:
 * 1. Skip (< 15s) and Replay habit learning.
 * 2. Smart Shuffle: maximizes distance between songs by the same artist, interleaves
 *    familiar favorites with rediscovery / long-unheard tracks, and respects skip/replay habits.
 * 3. Radio / Autoplay reranking so early-skipped tracks/artists drop in priority while
 *    replayed tracks/artists rise.
 */
object SmartShuffleEngine {

    const val EarlySkipThresholdMs = 15_000L
    const val MinSkipListenMs = 500L
    const val MinTrackDurationForSkipMs = 30_000L
    const val RediscoveryThresholdMs = 14L * 86_400_000L
    private const val DecayHalfLifeDays = 14.0
    private const val CandidateWindow = 48

    fun normalizeArtistKey(rawArtist: String?): String = rawArtist
        .orEmpty()
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

    /**
     * Classifies what happened when a track stopped playing or repeated.
     */
    fun classifyTrackLeave(
        listenedMs: Long,
        durationMs: Long,
        isManualSkip: Boolean,
        isRepeatReplay: Boolean,
    ): TrackInteraction {
        if (isRepeatReplay && listenedMs >= 5_000L) return TrackInteraction.REPLAY
        if (listenedMs < MinSkipListenMs) return TrackInteraction.NEUTRAL
        if (durationMs in 1 until MinTrackDurationForSkipMs) return TrackInteraction.NEUTRAL

        if (isManualSkip && listenedMs < EarlySkipThresholdMs) {
            return TrackInteraction.EARLY_SKIP
        }

        val completed = when {
            durationMs > 0L -> listenedMs >= (durationMs * 0.75).toLong().coerceAtLeast(30_000L)
            else -> listenedMs >= 60_000L
        }
        return if (completed) TrackInteraction.COMPLETED else TrackInteraction.NEUTRAL
    }

    fun updateHabit(
        existing: TrackHabit?,
        songId: String,
        artistKey: String,
        interaction: TrackInteraction,
        nowMs: Long,
    ): TrackHabit {
        val normArtist = normalizeArtistKey(artistKey).ifBlank { existing?.artistKey.orEmpty() }
        val base = existing ?: TrackHabit(songId = songId, artistKey = normArtist)
        return when (interaction) {
            TrackInteraction.EARLY_SKIP -> base.copy(
                artistKey = normArtist,
                earlySkipCount = (base.earlySkipCount + 1).coerceAtMost(50),
                lastSkippedAtMs = nowMs,
            )
            TrackInteraction.REPLAY -> base.copy(
                artistKey = normArtist,
                replayCount = (base.replayCount + 1).coerceAtMost(100),
                completeCount = (base.completeCount + 1).coerceAtMost(200),
                lastPlayedAtMs = nowMs,
            )
            TrackInteraction.COMPLETED -> base.copy(
                artistKey = normArtist,
                completeCount = (base.completeCount + 1).coerceAtMost(200),
                lastPlayedAtMs = nowMs,
            )
            TrackInteraction.NEUTRAL -> base.copy(artistKey = normArtist)
        }
    }

    /**
     * Returns learned track affinity in [-1.2, +1.5].
     * - Negative: frequently/recently skipped within the first 15 seconds.
     * - Positive: completed and especially replayed tracks.
     */
    fun trackAffinityScore(habit: TrackHabit?, nowMs: Long): Double {
        if (habit == null) return 0.0
        val skipAgeDays = if (habit.lastSkippedAtMs > 0L) {
            ((nowMs - habit.lastSkippedAtMs).coerceAtLeast(0L)) / 86_400_000.0
        } else {
            999.0
        }
        val playAgeDays = if (habit.lastPlayedAtMs > 0L) {
            ((nowMs - habit.lastPlayedAtMs).coerceAtLeast(0L)) / 86_400_000.0
        } else {
            999.0
        }

        val skipDecay = exp(-skipAgeDays * ln(2.0) / DecayHalfLifeDays)
        val playDecay = exp(-playAgeDays * ln(2.0) / (DecayHalfLifeDays * 2.5))

        val skipPenalty = (habit.earlySkipCount * 0.42).coerceAtMost(1.25) * skipDecay
        val completeBonus = (habit.completeCount * 0.14).coerceAtMost(0.65) * playDecay
        val replayBonus = (habit.replayCount * 0.42).coerceAtMost(1.15) * playDecay

        return (completeBonus + replayBonus - skipPenalty).coerceIn(-1.2, 1.5)
    }

    /**
     * Computes artist-level affinity adjustment across all learned habits for that artist.
     */
    fun artistAffinityScore(habits: Collection<TrackHabit>, artistKey: String, nowMs: Long): Double {
        val norm = normalizeArtistKey(artistKey)
        if (norm.isEmpty()) return 0.0
        var weightedSum = 0.0
        var count = 0
        for (h in habits) {
            if (h.artistKey == norm) {
                weightedSum += trackAffinityScore(h, nowMs)
                count++
            }
        }
        if (count == 0) return 0.0
        return (weightedSum / count).coerceIn(-0.8, 0.8)
    }

    /**
     * Builds a smart shuffled index order over [candidates].
     * - When [pinCurrent] is true, [currentIndex] is pinned at position 0.
     * - When [pinCurrent] is false (e.g. shuffling appended segments), no candidate is pinned and
     *   [leadingArtistKey] seeds the artist spacing window across segment boundaries.
     * - Maximizes separation between tracks by the same artist (never consecutive if avoidable).
     * - Interleaves familiar tracks with long-unheard / fresh tracks.
     * - Applies weighted randomness influenced by skip/replay habit scores so heavily skipped
     *   tracks drift toward the end while replayed tracks feel present without becoming static.
     */
    fun computeSmartShuffleOrder(
        candidates: List<ShuffleCandidate>,
        currentIndex: Int,
        nowMs: Long,
        random: Random = Random.Default,
        pinCurrent: Boolean = true,
        leadingArtistKey: String = "",
    ): IntArray {
        if (candidates.isEmpty()) return IntArray(0)
        if (candidates.size == 1) return intArrayOf(candidates[0].index)

        val currentCandidate = if (pinCurrent) {
            candidates.firstOrNull { it.index == currentIndex } ?: candidates.first()
        } else {
            null
        }
        val remaining = if (currentCandidate != null) {
            candidates.filter { it.index != currentCandidate.index }
        } else {
            candidates
        }
        if (remaining.isEmpty()) return intArrayOf(currentCandidate!!.index)

        val familiar = ArrayList<ShuffleCandidate>()
        val fresh = ArrayList<ShuffleCandidate>()

        for (c in remaining) {
            val isLongUnheard = c.lastPlayedAtMs <= 0L || (nowMs - c.lastPlayedAtMs) >= RediscoveryThresholdMs
            val isFamiliar = (c.playCount >= 3 && !isLongUnheard) || c.habitScore >= 0.35
            if (isFamiliar) familiar += c else fresh += c
        }

        // Weighted randomized pre-ordering (Efraimidis-Spirakis) within each pool so every shuffle
        // produces a distinct order while respecting habit weights and favoring genuine rediscoveries.
        fun weightedOrder(pool: List<ShuffleCandidate>, favorRediscovery: Boolean): MutableList<ShuffleCandidate> = pool
            .map { item ->
                val rediscoveryBias = if (favorRediscovery) {
                    val isDormant = item.lastPlayedAtMs <= 0L || (nowMs - item.lastPlayedAtMs) >= RediscoveryThresholdMs
                    if (isDormant) 0.35 else -0.15
                } else {
                    0.0
                }
                val weight = exp((item.habitScore + rediscoveryBias).coerceIn(-1.2, 1.8)).coerceIn(0.18, 5.5)
                val u = random.nextDouble(1e-6, 1.0)
                item to (ln(u) / weight)
            }
            .sortedByDescending { it.second }
            .mapTo(ArrayList(pool.size)) { it.first }

        val familiarQueue = weightedOrder(familiar, favorRediscovery = false)
        val freshQueue = weightedOrder(fresh, favorRediscovery = true)

        val result = IntArray(candidates.size)
        var pos = 0
        val recentArtists = ArrayDeque<String>(6)
        if (currentCandidate != null) {
            result[pos++] = currentCandidate.index
            if (currentCandidate.artistKey.isNotBlank()) recentArtists.addLast(currentCandidate.artistKey)
        } else {
            val lead = normalizeArtistKey(leadingArtistKey)
            if (lead.isNotBlank()) recentArtists.addLast(lead)
        }

        fun artistDistancePenalty(artistKey: String): Double {
            if (artistKey.isBlank() || recentArtists.isEmpty()) return 0.0
            var penalty = 0.0
            val size = recentArtists.size
            for (i in 0 until size) {
                if (recentArtists[i] == artistKey) {
                    val dist = size - i // 1 = immediate previous, 2 = two tracks ago...
                    penalty += when (dist) {
                        1 -> 1000.0
                        2 -> 45.0
                        3 -> 14.0
                        4 -> 5.0
                        else -> 2.0
                    }
                }
            }
            return penalty
        }

        fun pickBestFrom(primary: MutableList<ShuffleCandidate>, secondary: MutableList<ShuffleCandidate>): ShuffleCandidate {
            val primaryLimit = min(primary.size, CandidateWindow)
            var bestFromPrimary: Int = -1
            var bestPrimaryScore = Double.NEGATIVE_INFINITY
            for (i in 0 until primaryLimit) {
                val cand = primary[i]
                // Prefer earlier weighted-rank items slightly while strictly prioritizing artist spacing
                val rankBonus = (primaryLimit - i) * 0.08
                val score = cand.habitScore * 1.2 + rankBonus - artistDistancePenalty(cand.artistKey)
                if (score > bestPrimaryScore) {
                    bestPrimaryScore = score
                    bestFromPrimary = i
                }
            }

            // If primary's best pick causes an immediate artist repeat, check secondary pool for a clean alternative
            if (bestFromPrimary >= 0 && bestPrimaryScore > -500.0) {
                return primary.removeAt(bestFromPrimary)
            }

            val secondaryLimit = min(secondary.size, CandidateWindow)
            var bestFromSecondary = -1
            var bestSecondaryScore = Double.NEGATIVE_INFINITY
            for (i in 0 until secondaryLimit) {
                val cand = secondary[i]
                val rankBonus = (secondaryLimit - i) * 0.08
                val score = cand.habitScore * 1.2 + rankBonus - artistDistancePenalty(cand.artistKey)
                if (score > bestSecondaryScore) {
                    bestSecondaryScore = score
                    bestFromSecondary = i
                }
            }

            return if (bestFromSecondary >= 0 && (bestFromPrimary < 0 || bestSecondaryScore > bestPrimaryScore)) {
                secondary.removeAt(bestFromSecondary)
            } else if (bestFromPrimary >= 0) {
                primary.removeAt(bestFromPrimary)
            } else {
                secondary.removeAt(0)
            }
        }

        // Interleave pattern: familiar -> fresh -> familiar -> fresh (adapts seamlessly when one pool empties)
        var step = 0
        while (familiarQueue.isNotEmpty() || freshQueue.isNotEmpty()) {
            val wantFresh = (step % 2 == 1)
            val chosen = when {
                familiarQueue.isEmpty() -> pickBestFrom(freshQueue, familiarQueue)
                freshQueue.isEmpty() -> pickBestFrom(familiarQueue, freshQueue)
                wantFresh -> pickBestFrom(freshQueue, familiarQueue)
                else -> pickBestFrom(familiarQueue, freshQueue)
            }
            result[pos++] = chosen.index
            if (chosen.artistKey.isNotBlank()) {
                recentArtists.addLast(chosen.artistKey)
                while (recentArtists.size > 5) recentArtists.removeFirst()
            }
            step++
        }

        return result
    }

    /**
     * Reranks Radio / Autoplay candidates according to learned skip/replay habits and artist spacing:
     * - Early-skipped songs (< 15s) and heavily skipped artists drop down in priority.
     * - Replayed / completed songs and loved artists float higher.
     * - Consecutive identical artists are separated whenever possible.
     */
    fun rankAutoplayCandidates(
        candidates: List<AutoplayCandidate>,
        habits: Map<String, TrackHabit>,
        nowMs: Long,
        recentArtistKey: String = "",
    ): List<Int> {
        if (candidates.size <= 1) return candidates.map { it.index }

        // Precompute per-artist affinity averages once in O(H) instead of O(N * H)
        val artistSums = HashMap<String, Double>()
        val artistCounts = HashMap<String, Int>()
        for (h in habits.values) {
            val key = normalizeArtistKey(h.artistKey)
            if (key.isEmpty()) continue
            artistSums[key] = (artistSums[key] ?: 0.0) + trackAffinityScore(h, nowMs)
            artistCounts[key] = (artistCounts[key] ?: 0) + 1
        }
        val artistAvg = HashMap<String, Double>(artistSums.size)
        for ((key, sum) in artistSums) {
            val cnt = artistCounts[key] ?: 1
            artistAvg[key] = (sum / cnt).coerceIn(-0.8, 0.8)
        }

        // Base score blends original radio relevance (slight decay by position) + learned track & artist habit
        val scored = candidates.mapIndexed { rank, cand ->
            val relevance = (candidates.size - rank).toDouble() / candidates.size.coerceAtLeast(1) * 0.45
            val trackScore = trackAffinityScore(habits[cand.songId], nowMs)
            val normArtist = normalizeArtistKey(cand.artistKey)
            val artistScore = (artistAvg[normArtist] ?: 0.0) * 0.5
            cand to (relevance + trackScore + artistScore)
        }.toMutableList()

        val ordered = ArrayList<Int>(candidates.size)
        val recentArtists = ArrayDeque<String>(4)
        val normInitial = normalizeArtistKey(recentArtistKey)
        if (normInitial.isNotBlank()) recentArtists.addLast(normInitial)

        while (scored.isNotEmpty()) {
            var bestIdx = 0
            var bestVal = Double.NEGATIVE_INFINITY
            for (i in scored.indices) {
                val (cand, baseScore) = scored[i]
                val penalty = if (cand.artistKey.isNotBlank() && recentArtists.isNotEmpty()) {
                    when (recentArtists.indexOfLast { it == cand.artistKey }) {
                        recentArtists.lastIndex -> 0.9
                        recentArtists.lastIndex - 1 -> 0.3
                        else -> 0.0
                    }
                } else {
                    0.0
                }
                val total = baseScore - penalty
                if (total > bestVal) {
                    bestVal = total
                    bestIdx = i
                }
            }
            val chosen = scored.removeAt(bestIdx).first
            ordered += chosen.index
            if (chosen.artistKey.isNotBlank()) {
                recentArtists.addLast(chosen.artistKey)
                while (recentArtists.size > 3) recentArtists.removeFirst()
            }
        }
        return ordered
    }
}
