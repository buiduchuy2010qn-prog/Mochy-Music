// SleepTimer.kt

package com.mochyy.mochymusic.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class SleepTimer(
    private val scope: CoroutineScope,
    var player: Player,
    private val baseVolume: () -> Float = { 1f },
) : Player.Listener {
    private companion object {
        const val FADE_OUT_MS = 12_000L
        const val FADE_TICK_MS = 250L
    }

    private var sleepTimerJob: Job? = null
    var triggerTime by mutableLongStateOf(-1L)
        private set
    var pauseWhenSongEnd by mutableStateOf(false)
        private set
    var isFadingOut by mutableStateOf(false)
        private set
    val isActive: Boolean
        get() = triggerTime != -1L || pauseWhenSongEnd

    fun start(minute: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (isFadingOut) {
            isFadingOut = false
            player.volume = baseVolume().coerceIn(0f, 1f)
        }
        if (minute == -1) {
            pauseWhenSongEnd = true
            triggerTime = -1L
            sleepTimerJob = scope.launch {
                while (pauseWhenSongEnd) {
                    val dur = player.duration
                    if (player.isPlaying && dur != C.TIME_UNSET && dur > 15_000L) {
                        val rem = (dur - player.currentPosition).coerceAtLeast(0L)
                        if (rem in 1L..FADE_OUT_MS) {
                            isFadingOut = true
                            val ratio = (rem.toFloat() / FADE_OUT_MS).coerceIn(0f, 1f)
                            player.volume = (baseVolume() * ratio * ratio).coerceIn(0f, 1f)
                        } else if (isFadingOut && rem > FADE_OUT_MS) {
                            isFadingOut = false
                            player.volume = baseVolume().coerceIn(0f, 1f)
                        }
                    }
                    delay(FADE_TICK_MS)
                }
            }
        } else {
            pauseWhenSongEnd = false
            triggerTime = System.currentTimeMillis() + minute.minutes.inWholeMilliseconds
            sleepTimerJob =
                scope.launch {
                    while (triggerTime > 0L) {
                        val remaining = triggerTime - System.currentTimeMillis()
                        if (remaining <= 0L) {
                            finishAndRestoreVolume()
                            break
                        }
                        if (remaining <= FADE_OUT_MS) {
                            isFadingOut = true
                            val ratio = (remaining.toFloat() / FADE_OUT_MS).coerceIn(0f, 1f)
                            player.volume = (baseVolume() * ratio * ratio).coerceIn(0f, 1f)
                            delay(FADE_TICK_MS)
                        } else {
                            delay(minOf(1_000L, (remaining - FADE_OUT_MS).coerceAtLeast(FADE_TICK_MS)))
                        }
                    }
                }
        }
    }

    private fun finishAndRestoreVolume() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pauseWhenSongEnd = false
        triggerTime = -1L
        player.volume = 0f
        player.pause()
        isFadingOut = false
        val target = baseVolume().coerceIn(0f, 1f)
        scope.launch {
            delay(100L)
            if (!isFadingOut) {
                player.volume = target
            }
        }
    }

    fun notifySongTransition() {
        if (pauseWhenSongEnd) {
            finishAndRestoreVolume()
        }
    }

    fun clear() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (isFadingOut) {
            isFadingOut = false
            player.volume = baseVolume().coerceIn(0f, 1f)
        }
        pauseWhenSongEnd = false
        triggerTime = -1L
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (pauseWhenSongEnd) {
            finishAndRestoreVolume()
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        if (playbackState == Player.STATE_ENDED && pauseWhenSongEnd) {
            finishAndRestoreVolume()
        }
    }
}
