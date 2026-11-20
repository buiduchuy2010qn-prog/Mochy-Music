package com.mochyy.mochymusic.playback

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent on-device store for skip (< 15s), completion, and replay habits.
 * Shared across Smart Shuffle, Radio/Autoplay reranking, and Smart Mixes.
 */
@Singleton
class PlaybackHabitStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val habits = ConcurrentHashMap<String, TrackHabit>()
    private var saveJob: Job? = null

    private val _learnedCount = MutableStateFlow(0)
    val learnedCount: StateFlow<Int> = _learnedCount.asStateFlow()

    init {
        load()
    }

    fun get(songId: String): TrackHabit? = habits[songId]

    fun snapshot(): Map<String, TrackHabit> = HashMap(habits)

    fun affinityScore(songId: String, nowMs: Long = System.currentTimeMillis()): Double =
        SmartShuffleEngine.trackAffinityScore(habits[songId], nowMs)

    fun affinityMap(nowMs: Long = System.currentTimeMillis()): Map<String, Double> =
        habits.entries.associate { (id, habit) -> id to SmartShuffleEngine.trackAffinityScore(habit, nowMs) }

    fun recordLeave(
        songId: String,
        artistName: String,
        listenedMs: Long,
        durationMs: Long,
        isManualSkip: Boolean,
        isRepeatReplay: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackInteraction {
        if (songId.isBlank()) return TrackInteraction.NEUTRAL
        val interaction = SmartShuffleEngine.classifyTrackLeave(
            listenedMs = listenedMs,
            durationMs = durationMs,
            isManualSkip = isManualSkip,
            isRepeatReplay = isRepeatReplay,
        )
        if (interaction == TrackInteraction.NEUTRAL) return interaction
        recordInteraction(songId, artistName, interaction, nowMs)
        return interaction
    }

    fun recordInteraction(
        songId: String,
        artistName: String,
        interaction: TrackInteraction,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (songId.isBlank() || interaction == TrackInteraction.NEUTRAL) return
        val updated = SmartShuffleEngine.updateHabit(
            existing = habits[songId],
            songId = songId,
            artistKey = artistName,
            interaction = interaction,
            nowMs = nowMs,
        )
        habits[songId] = updated
        _learnedCount.value = habits.size
        scheduleSave()
    }

    fun clear() {
        habits.clear()
        _learnedCount.value = 0
        saveJob?.cancel()
        prefs.edit { remove(KeyHabits) }
    }

    private fun load() {
        val raw = prefs.getString(KeyHabits, null) ?: return
        val list = runCatching {
            json.decodeFromString(ListSerializer(TrackHabit.serializer()), raw)
        }.getOrDefault(emptyList())
        for (h in list) {
            if (h.songId.isNotBlank()) habits[h.songId] = h
        }
        _learnedCount.value = habits.size
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            persistNow()
        }
    }

    @Synchronized
    private fun persistNow() {
        val sorted = habits.values
            .sortedByDescending { maxOf(it.lastPlayedAtMs, it.lastSkippedAtMs) }
            .take(MaxTracks)
        if (habits.size > MaxTracks) {
            val keep = sorted.mapTo(HashSet(sorted.size)) { it.songId }
            habits.keys.retainAll(keep)
            _learnedCount.value = habits.size
        }
        val encoded = runCatching {
            json.encodeToString(ListSerializer(TrackHabit.serializer()), sorted)
        }.getOrNull() ?: return
        prefs.edit { putString(KeyHabits, encoded) }
    }

    private companion object {
        const val PrefsName = "playback_habits"
        const val KeyHabits = "track_habits_v1"
        const val MaxTracks = 1_000
    }
}
