// LowEndModeManager.kt

package com.mochyy.mochymusic.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import coil3.SingletonImageLoader
import com.mochyy.mochymusic.constants.*
import com.mochyy.mochymusic.ui.utils.AppMotion
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Manages the opt-in "Low-end device mode" toggle (Settings -> Customise -> Blur).
 *
 * - Default OFF on standard devices (keeps 100% authentic Mochy defaults and behaviour).
 * - Automatically enables ONCE on first run if hardware is Android <= 8.1 and RAM <= 3 GB (e.g. Galaxy J6 / J7),
 *   and remains freely toggleable at any time.
 * - Snapshots prior user settings before optimizing and restores them verbatim when turned OFF.
 */
object LowEndModeManager {
    private const val LOW_RAM_THRESHOLD_BYTES = 3_300_000_000L

    @Volatile
    var isActive: Boolean = false

    private val boolKeys: List<Preferences.Key<Boolean>> = listOf(
        DisableBlurKey,
        ElementBlurKey,
        EnableProgressiveBlurKey,
        LyricsStandardBlurKey,
        AppleMusicLyricsBlurKey,
        LyricsGlowEffectKey,
        AlbumCanvasEnabledKey,
        SpotifyCanvasKey,
        CanvasThumbnailAnimationKey,
        PlayVideoBackgroundKey,
        ShowArtistVideoKey,
        ShowArtistBackgroundVideoKey,
        DisableVideoAutoplayKey,
        EnableSpatialAudioKey,
    )

    private val stringKeys: List<Preferences.Key<String>> = listOf(
        BlurQualityKey,
        LyricsMotionStyleKey,
        VideoQualityKey,
        CrossmixModeKey,
        StreamBackendKey,
    )

    private val intKeys: List<Preferences.Key<Int>> = listOf(
        MaxImageCacheSizeKey,
        MaxSongCacheSizeKey,
    )

    private val floatKeys: List<Preferences.Key<Float>> = listOf(
        AnimationSpeedKey,
    )

    fun isAndroid8LowRamHardware(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem in 1L..LOW_RAM_THRESHOLD_BYTES
    }

    suspend fun initializeOnLaunch(context: Context) {
        val store = context.dataStore
        val prefs = store.data.first()
        if (prefs[LowEndAutoEvaluatedKey] != true) {
            if (isAndroid8LowRamHardware(context) && prefs[LowEndModeKey] != true) {
                setEnabled(context, enabled = true, markAutoEvaluated = true)
                return
            }
            store.edit { it[LowEndAutoEvaluatedKey] = true }
        }
        val activeNow = (store.data.first()[LowEndModeKey] == true)
        isActive = activeNow
        if (activeNow) {
            AppMotion.elementBlur = false
            YTPlayerUtils.backend = StreamBackend.CLASSIC
        }
    }

    suspend fun setEnabled(
        context: Context,
        enabled: Boolean,
        markAutoEvaluated: Boolean = true,
    ) {
        val store = context.dataStore
        store.edit { prefs ->
            if (enabled) {
                if (prefs[LowEndSnapshotJsonKey].isNullOrBlank()) {
                    prefs[LowEndSnapshotJsonKey] = captureSnapshot(prefs).toString()
                }
                applyLowEndValues(prefs)
            } else {
                restoreSnapshot(prefs, prefs[LowEndSnapshotJsonKey])
                prefs.remove(LowEndSnapshotJsonKey)
                prefs[LowEndModeKey] = false
            }
            if (markAutoEvaluated) {
                prefs[LowEndAutoEvaluatedKey] = true
            }
        }

        isActive = enabled
        if (enabled) {
            AppMotion.elementBlur = false
            AppMotion.speed = 1.5f
            YTPlayerUtils.backend = StreamBackend.CLASSIC
            runCatching {
                SingletonImageLoader.get(context).memoryCache?.let { cache ->
                    cache.trimToSize(cache.size / 4)
                }
            }
        }
    }

    private fun captureSnapshot(prefs: Preferences): JSONObject = JSONObject().apply {
        for (k in boolKeys) {
            put("has_${k.name}", prefs.contains(k))
            prefs[k]?.let { put(k.name, it) }
        }
        for (k in stringKeys) {
            put("has_${k.name}", prefs.contains(k))
            prefs[k]?.let { put(k.name, it) }
        }
        for (k in intKeys) {
            put("has_${k.name}", prefs.contains(k))
            prefs[k]?.let { put(k.name, it) }
        }
        for (k in floatKeys) {
            put("has_${k.name}", prefs.contains(k))
            prefs[k]?.let { put(k.name, it.toDouble()) }
        }
    }

    private fun applyLowEndValues(prefs: MutablePreferences) {
        prefs[DisableBlurKey] = true
        prefs[BlurQualityKey] = BlurQuality.LOW.name
        prefs[ElementBlurKey] = false
        prefs[EnableProgressiveBlurKey] = false
        prefs[LyricsStandardBlurKey] = false
        prefs[AppleMusicLyricsBlurKey] = false
        prefs[LyricsGlowEffectKey] = false
        prefs[LyricsMotionStyleKey] = LyricsMotionStyle.PLAIN.name
        prefs[AnimationSpeedKey] = 1.5f

        prefs[AlbumCanvasEnabledKey] = false
        prefs[SpotifyCanvasKey] = false
        prefs[CanvasThumbnailAnimationKey] = false
        prefs[PlayVideoBackgroundKey] = false
        prefs[ShowArtistVideoKey] = false
        prefs[ShowArtistBackgroundVideoKey] = false
        prefs[DisableVideoAutoplayKey] = true
        prefs[VideoQualityKey] = VideoQuality.Q480.name

        prefs[CrossmixModeKey] = CrossmixMode.OFF.name
        prefs[CrossmixDefaultMovedKey] = true
        prefs[StreamBackendKey] = StreamBackend.CLASSIC.name
        prefs[EnableSpatialAudioKey] = false

        if ((prefs[MaxImageCacheSizeKey] ?: 512) > 128) {
            prefs[MaxImageCacheSizeKey] = 128
        }
        if ((prefs[MaxSongCacheSizeKey] ?: 1024) > 256) {
            prefs[MaxSongCacheSizeKey] = 256
        }
        prefs[LowEndModeKey] = true
    }

    private fun restoreSnapshot(prefs: MutablePreferences, rawJson: String?) {
        val json = rawJson?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        if (json == null) {
            for (k in boolKeys) prefs.remove(k)
            for (k in stringKeys) prefs.remove(k)
            for (k in intKeys) prefs.remove(k)
            for (k in floatKeys) prefs.remove(k)
            prefs[DisableBlurKey] = false
            prefs[ElementBlurKey] = true
            prefs[EnableProgressiveBlurKey] = true
            prefs[AnimationSpeedKey] = 1f
            return
        }

        for (k in boolKeys) {
            if (json.optBoolean("has_${k.name}", false) && json.has(k.name)) {
                prefs[k] = json.optBoolean(k.name, false)
            } else {
                prefs.remove(k)
            }
        }
        for (k in stringKeys) {
            if (json.optBoolean("has_${k.name}", false) && json.has(k.name)) {
                prefs[k] = json.optString(k.name)
            } else {
                prefs.remove(k)
            }
        }
        for (k in intKeys) {
            if (json.optBoolean("has_${k.name}", false) && json.has(k.name)) {
                prefs[k] = json.optInt(k.name)
            } else {
                prefs.remove(k)
            }
        }
        for (k in floatKeys) {
            if (json.optBoolean("has_${k.name}", false) && json.has(k.name)) {
                prefs[k] = json.optDouble(k.name, 1.0).toFloat()
            } else {
                prefs.remove(k)
            }
        }
    }
}
