// SleepTimer.kt

package com.mochyy.mochymusic.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SleepTimer(
    private val scope: CoroutineScope,
    var player: Player,
) : Player.Listener {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepTimerJob: Job? = null
    @Volatile
    private var preFadeVolume: Float? = null
    var triggerTime by mutableLongStateOf(-1L)
        private set
    var totalDurationMillis by mutableLongStateOf(0L)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var remainingMillisWhenPaused by mutableLongStateOf(0L)
        private set
    var pauseWhenSongEnd by mutableStateOf(false)
        private set
    var isFadingOut by mutableStateOf(false)
        private set

    // a paused timer still counts as set, so the menu row and the cancel button keep showing it
    val isActive: Boolean
        get() = triggerTime != -1L || isPaused || pauseWhenSongEnd

    val isRunning: Boolean
        get() = triggerTime != -1L && !isPaused

    fun start(minute: Int) {
        if (minute == -1) {
            cancelJob()
            pauseWhenSongEnd = true
            triggerTime = -1L
            isPaused = false
            totalDurationMillis = 0L
            remainingMillisWhenPaused = 0L
        } else {
            startSeconds(minute * 60L)
        }
    }

    fun startSeconds(seconds: Long) {
        cancelJob()
        if (seconds <= 0L) {
            clear()
            return
        }
        pauseWhenSongEnd = false
        isPaused = false
        val millis = seconds * 1000L
        totalDurationMillis = millis
        triggerTime = System.currentTimeMillis() + millis
        scheduleJob(millis)
    }

    fun pause() {
        if (triggerTime > 0L && !isPaused) {
            val left = (triggerTime - System.currentTimeMillis()).coerceAtLeast(0L)
            cancelJob()
            remainingMillisWhenPaused = left
            isPaused = true
            triggerTime = -1L
        }
    }

    fun resume() {
        if (isPaused && remainingMillisWhenPaused > 0L) {
            isPaused = false
            val millis = remainingMillisWhenPaused
            triggerTime = System.currentTimeMillis() + millis
            scheduleJob(millis)
        }
    }

    // the -1 / +1 min buttons; going below zero ends the timer right away, like it ran out
    fun adjust(deltaSeconds: Long) {
        val deltaMillis = deltaSeconds * 1000L
        if (isPaused) {
            val newRemaining = (remainingMillisWhenPaused + deltaMillis).coerceAtLeast(0L)
            remainingMillisWhenPaused = newRemaining
            totalDurationMillis = maxOf(totalDurationMillis, newRemaining)
            if (newRemaining == 0L) {
                clear()
            }
        } else if (triggerTime > 0L) {
            val left = (triggerTime - System.currentTimeMillis()) + deltaMillis
            if (left <= 0L) {
                clear()
                runOnMain {
                    restorePreFadeVolumeNow()
                    player.pause()
                }
            } else {
                cancelJob()
                totalDurationMillis = maxOf(totalDurationMillis, left)
                triggerTime = System.currentTimeMillis() + left
                scheduleJob(left)
            }
        }
    }

    fun clear() {
        cancelJob()
        restorePreFadeVolume()
        pauseWhenSongEnd = false
        isPaused = false
        triggerTime = -1L
        totalDurationMillis = 0L
        remainingMillisWhenPaused = 0L
    }

    private fun scheduleJob(millis: Long) {
        sleepTimerJob = scope.launch {
            val currentJob = coroutineContext[Job]
            try {
                val fadeMs = if (millis >= 10_000L) minOf(25_000L, millis / 2L) else 0L
                val stepMs = 250L
                val fadeSteps = if (fadeMs > 0L) (fadeMs / stepMs).toInt().coerceAtLeast(1) else 0
                val actualFadeMs = fadeSteps * stepMs
                val waitMs = (millis - actualFadeMs).coerceAtLeast(0L)

                if (waitMs > 0L) {
                    if (isFadingOut || preFadeVolume != null) {
                        withContext(Dispatchers.Main.immediate) {
                            restorePreFadeVolumeNow()
                        }
                    }
                    delay(waitMs)
                }

                withContext(Dispatchers.Main.immediate) {
                    if (fadeSteps > 0) {
                        val baseVol = (preFadeVolume ?: player.volume).coerceIn(0f, 1f)
                        preFadeVolume = baseVol
                        isFadingOut = true

                        for (step in 1..fadeSteps) {
                            val ratio = ((fadeSteps - step).toFloat() / fadeSteps.toFloat()).coerceIn(0f, 1f)
                            if (baseVol > 0f) {
                                player.volume = (baseVol * ratio * ratio).coerceIn(0f, baseVol)
                            }
                            delay(stepMs)
                        }
                        player.pause()
                        player.volume = baseVol
                        preFadeVolume = null
                        isFadingOut = false
                    } else {
                        restorePreFadeVolumeNow()
                        player.pause()
                    }
                }
                if (isActive && sleepTimerJob === currentJob) {
                    sleepTimerJob = null
                    clear()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            } finally {
                if (sleepTimerJob === currentJob) {
                    sleepTimerJob = null
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        restorePreFadeVolumeNow()
                    }
                }
            }
        }
    }

    private fun cancelJob() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        restorePreFadeVolume()
    }

    private fun runOnMain(block: () -> Unit) {
        mainScope.launch {
            block()
        }
    }

    private fun restorePreFadeVolume() {
        if (preFadeVolume != null || isFadingOut) {
            runOnMain {
                restorePreFadeVolumeNow()
            }
        }
    }

    private fun restorePreFadeVolumeNow() {
        val saved = preFadeVolume
        preFadeVolume = null
        isFadingOut = false
        if (saved != null) {
            player.volume = saved
        }
    }

    fun notifySongTransition() {
        if (pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            runOnMain {
                restorePreFadeVolumeNow()
                player.pause()
            }
        } else if (!isFadingOut && preFadeVolume != null) {
            restorePreFadeVolume()
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            runOnMain {
                restorePreFadeVolumeNow()
                player.pause()
            }
        } else if (!isFadingOut && preFadeVolume != null) {
            restorePreFadeVolume()
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        if (playbackState == Player.STATE_ENDED && pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            runOnMain {
                restorePreFadeVolumeNow()
                player.pause()
            }
        }
    }
}
