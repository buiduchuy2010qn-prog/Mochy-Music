package com.mochyy.mochymusic.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.mochyy.mochymusic.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class BrandingSnapshot(
    val customName: String? = null,
    val logoVersion: Long = 0L,
) {
    val hasCustomName: Boolean get() = !customName.isNullOrBlank()
    val hasCustomLogo: Boolean get() = logoVersion > 0L
    val isCustomized: Boolean get() = hasCustomName || hasCustomLogo

    fun displayName(defaultName: String = "Mochy"): String =
        customName?.takeIf { it.isNotBlank() } ?: defaultName
}

object CustomAppBranding {
    const val MAX_NAME_LENGTH = 28
    private const val PREFS_NAME = "app_icon"
    private const val KEY_CUSTOM_NAME = "custom_app_name"
    private const val KEY_LOGO_VERSION = "custom_logo_version"
    private const val KEY_PINNED_ID = "pinned_shortcut_id"
    private const val CUSTOM_LAUNCHER_SHORTCUT_PREFIX = "mochy_custom_launcher"
    private const val DISPLAY_LOGO_FILE = "custom_app_logo.png"
    private const val ADAPTIVE_LOGO_FILE = "custom_app_logo_adaptive.png"
    private const val DISPLAY_LOGO_PX = 512
    private const val ADAPTIVE_CANVAS_PX = 432
    private const val ADAPTIVE_VISIBLE_PX = 288
    private const val BACKDROP_COLOR = 0xFF0E0E10.toInt()

    enum class PinOutcome {
        REQUESTED_PIN,
        UPDATED_EXISTING,
        UNSUPPORTED,
    }

    private val stateFlow = MutableStateFlow(BrandingSnapshot())
    val state: StateFlow<BrandingSnapshot> = stateFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var cachedLogoVersion: Long = -1L

    @Volatile
    private var cachedImageBitmap: ImageBitmap? = null

    fun ensureInit(context: Context): BrandingSnapshot {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val appCtx = context.applicationContext
                    val prefs = prefs(appCtx)
                    val savedName = prefs.getString(KEY_CUSTOM_NAME, null)?.takeIf { it.isNotBlank() }
                    val fileExists = File(appCtx.filesDir, DISPLAY_LOGO_FILE).let { it.exists() && it.length() > 0L }
                    val savedVer = if (fileExists) prefs.getLong(KEY_LOGO_VERSION, 0L).coerceAtLeast(1L) else 0L
                    stateFlow.value = BrandingSnapshot(customName = savedName, logoVersion = savedVer)
                    initialized = true
                }
            }
        }
        return stateFlow.value
    }

    @Composable
    fun rememberBranding(): BrandingSnapshot {
        val context = LocalContext.current
        remember(context) { ensureInit(context) }
        val snapshot by state.collectAsState()
        return snapshot
    }

    @Composable
    fun rememberCustomLogoBitmap(): ImageBitmap? {
        val context = LocalContext.current
        val branding = rememberBranding()
        return remember(context, branding.logoVersion) {
            if (!branding.hasCustomLogo) {
                cachedLogoVersion = 0L
                cachedImageBitmap = null
                null
            } else {
                val cached = cachedImageBitmap
                if (cached != null && cachedLogoVersion == branding.logoVersion) {
                    cached
                } else {
                    val loaded = loadDisplayBitmap(context.applicationContext)?.asImageBitmap()
                    if (loaded != null) {
                        cachedLogoVersion = branding.logoVersion
                        cachedImageBitmap = loaded
                    }
                    loaded
                }
            }
        }
    }

    fun appName(context: Context): String {
        val current = ensureInit(context)
        return current.displayName(context.getString(R.string.app_name))
    }

    suspend fun decodeUriBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val maxSide = 1600
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(appCtx.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val srcW = info.size.width
                    val srcH = info.size.height
                    if (srcW > maxSide || srcH > maxSide) {
                        val scale = maxSide.toFloat() / maxOf(srcW, srcH)
                        decoder.setTargetSize(
                            (srcW * scale).toInt().coerceAtLeast(1),
                            (srcH * scale).toInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appCtx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > maxSide * 2 || bounds.outHeight / sampleSize > maxSide * 2) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = appCtx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@runCatching null
                applyExifOrientation(appCtx, uri, raw)
            }
        }.getOrNull()
    }

    private fun applyExifOrientation(context: Context, uri: Uri, src: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return src
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (rotated !== src) src.recycle()
            rotated
        }.getOrDefault(src)
    }

    suspend fun saveCroppedLogo(
        context: Context,
        src: Bitmap,
        zoom: Float,
        panXNorm: Float,
        panYNorm: Float,
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val displayBmp = renderCroppedLogo(
            src = src,
            canvasSize = DISPLAY_LOGO_PX,
            viewportSize = DISPLAY_LOGO_PX,
            zoom = zoom,
            panXNorm = panXNorm,
            panYNorm = panYNorm,
        )
        val adaptiveBmp = renderCroppedLogo(
            src = src,
            canvasSize = ADAPTIVE_CANVAS_PX,
            viewportSize = ADAPTIVE_VISIBLE_PX,
            zoom = zoom,
            panXNorm = panXNorm,
            panYNorm = panYNorm,
        )
        val displayOk = writePngAtomic(File(appCtx.filesDir, DISPLAY_LOGO_FILE), displayBmp)
        val adaptiveOk = writePngAtomic(File(appCtx.filesDir, ADAPTIVE_LOGO_FILE), adaptiveBmp)
        adaptiveBmp.recycle()
        if (!displayOk || !adaptiveOk) {
            displayBmp.recycle()
            return@withContext false
        }
        val version = System.currentTimeMillis().coerceAtLeast(1L)
        val asCompose = displayBmp.asImageBitmap()
        cachedLogoVersion = version
        cachedImageBitmap = asCompose
        prefs(appCtx).edit { putLong(KEY_LOGO_VERSION, version) }
        val current = ensureInit(appCtx)
        stateFlow.value = current.copy(logoVersion = version)
        initialized = true
        updatePinnedShortcutIfPresent(appCtx)
        true
    }

    fun setCustomName(context: Context, rawName: String?) {
        val appCtx = context.applicationContext
        val cleaned = rawName?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() }
        prefs(appCtx).edit {
            if (cleaned != null) putString(KEY_CUSTOM_NAME, cleaned) else remove(KEY_CUSTOM_NAME)
        }
        val current = ensureInit(appCtx)
        stateFlow.value = current.copy(customName = cleaned)
        initialized = true
        updatePinnedShortcutIfPresent(appCtx)
    }

    fun clearCustomLogo(context: Context) {
        val appCtx = context.applicationContext
        File(appCtx.filesDir, DISPLAY_LOGO_FILE).delete()
        File(appCtx.filesDir, ADAPTIVE_LOGO_FILE).delete()
        cachedLogoVersion = 0L
        cachedImageBitmap = null
        prefs(appCtx).edit { remove(KEY_LOGO_VERSION) }
        val current = ensureInit(appCtx)
        stateFlow.value = current.copy(logoVersion = 0L)
        initialized = true
        updatePinnedShortcutIfPresent(appCtx)
    }

    fun resetAll(context: Context) {
        val appCtx = context.applicationContext
        File(appCtx.filesDir, DISPLAY_LOGO_FILE).delete()
        File(appCtx.filesDir, ADAPTIVE_LOGO_FILE).delete()
        cachedLogoVersion = 0L
        cachedImageBitmap = null
        prefs(appCtx).edit {
            remove(KEY_CUSTOM_NAME)
            remove(KEY_LOGO_VERSION)
        }
        stateFlow.value = BrandingSnapshot(customName = null, logoVersion = 0L)
        initialized = true
        updatePinnedShortcutIfPresent(appCtx)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun isPinnedAliasActive(pm: PackageManager, info: ShortcutInfo): Boolean {
        if (!info.id.startsWith(CUSTOM_LAUNCHER_SHORTCUT_PREFIX) || !info.isEnabled) return false
        val act = info.activity ?: return false
        val setting = pm.getComponentEnabledSetting(act)
        return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && act.className.endsWith(".IconDefault"))
    }

    fun isShortcutPinned(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val appCtx = context.applicationContext
        return runCatching {
            val sm = appCtx.getSystemService(ShortcutManager::class.java) ?: return@runCatching false
            val pm = appCtx.packageManager
            sm.pinnedShortcuts.any { isPinnedAliasActive(pm, it) }
        }.getOrDefault(false)
    }

    fun updatePinnedShortcutIfPresent(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appCtx = context.applicationContext
        runCatching {
            val sm = appCtx.getSystemService(ShortcutManager::class.java) ?: return
            val pm = appCtx.packageManager
            val activePinned = sm.pinnedShortcuts.filter { isPinnedAliasActive(pm, it) }
            if (activePinned.isEmpty()) return
            val updated = activePinned.mapNotNull { existing ->
                val targetActivity = existing.activity ?: return@mapNotNull null
                buildShortcutInfo(appCtx, existing.id, targetActivity)
            }
            if (updated.isNotEmpty()) {
                sm.updateShortcuts(updated)
            }
        }
    }

    fun pinOrUpdateHomeIcon(context: Context, forceNewPin: Boolean = false): PinOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return PinOutcome.UNSUPPORTED
        val appCtx = context.applicationContext
        val sm = appCtx.getSystemService(ShortcutManager::class.java) ?: return PinOutcome.UNSUPPORTED
        return runCatching {
            val pm = appCtx.packageManager
            val currentAliasComponent = ComponentName(appCtx, "com.mochyy.mochymusic.${AppIcons.current(appCtx).alias}")
            val activePinned = sm.pinnedShortcuts.firstOrNull { isPinnedAliasActive(pm, it) }

            if (activePinned != null && !forceNewPin) {
                val targetActivity = activePinned.activity ?: currentAliasComponent
                val updated = buildShortcutInfo(appCtx, activePinned.id, targetActivity)
                sm.updateShortcuts(listOf(updated))
                prefs(appCtx).edit { putString(KEY_PINNED_ID, activePinned.id) }
                return@runCatching PinOutcome.UPDATED_EXISTING
            }

            if (!sm.isRequestPinShortcutSupported) return@runCatching PinOutcome.UNSUPPORTED

            val newId = if (activePinned == null && sm.pinnedShortcuts.none { it.id == CUSTOM_LAUNCHER_SHORTCUT_PREFIX }) {
                CUSTOM_LAUNCHER_SHORTCUT_PREFIX
            } else {
                "${CUSTOM_LAUNCHER_SHORTCUT_PREFIX}_${System.currentTimeMillis()}"
            }
            val shortcut = buildShortcutInfo(appCtx, newId, currentAliasComponent)
            val ok = sm.requestPinShortcut(shortcut, null)
            if (ok) {
                prefs(appCtx).edit { putString(KEY_PINNED_ID, newId) }
                PinOutcome.REQUESTED_PIN
            } else {
                PinOutcome.UNSUPPORTED
            }
        }.getOrDefault(PinOutcome.UNSUPPORTED)
    }

    @Suppress("DEPRECATION")
    fun syncTaskDescription(activity: Activity) {
        val appCtx = activity.applicationContext
        val current = ensureInit(appCtx)
        val label = current.displayName(activity.getString(R.string.app_name))
        val bmp = if (current.hasCustomLogo) loadDisplayBitmap(appCtx, 192) else null
        runCatching {
            val td = when {
                bmp != null -> ActivityManager.TaskDescription(label, bmp, AndroidColor.BLACK)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(label)
                        .setPrimaryColor(AndroidColor.BLACK)
                        .build()
                else -> ActivityManager.TaskDescription(label, null, AndroidColor.BLACK)
            }
            activity.setTaskDescription(td)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildShortcutInfo(
        context: Context,
        shortcutId: String,
        activityComponent: ComponentName,
    ): ShortcutInfo {
        val current = ensureInit(context)
        val adaptiveBmp = if (current.hasCustomLogo) {
            loadAdaptiveBitmap(context)
        } else {
            null
        } ?: buildAdaptivePresetBitmap(context, AppIcons.current(context))

        val label = appName(context)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, "com.mochyy.mochymusic.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(label)
            .setLongLabel(label)
            .setActivity(activityComponent)
            .setIcon(Icon.createWithAdaptiveBitmap(adaptiveBmp))
            .setIntent(launchIntent)
            .build()
    }

    private fun buildAdaptivePresetBitmap(context: Context, appIcon: AppIcon): Bitmap {
        val out = Bitmap.createBitmap(ADAPTIVE_CANVAS_PX, ADAPTIVE_CANVAS_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bgColor = appIcon.background?.let { ContextCompat.getColor(context, it) } ?: AndroidColor.BLACK
        canvas.drawColor(bgColor)
        ContextCompat.getDrawable(context, appIcon.foreground)?.let { drawable ->
            if (appIcon.family == AppIconFamily.NewLogo) {
                val inset = (ADAPTIVE_CANVAS_PX * 0.14f).toInt()
                drawable.setBounds(inset, inset, ADAPTIVE_CANVAS_PX - inset, ADAPTIVE_CANVAS_PX - inset)
            } else {
                drawable.setBounds(0, 0, ADAPTIVE_CANVAS_PX, ADAPTIVE_CANVAS_PX)
            }
            drawable.draw(canvas)
        }
        return out
    }

    private fun renderCroppedLogo(
        src: Bitmap,
        canvasSize: Int,
        viewportSize: Int,
        zoom: Float,
        panXNorm: Float,
        panYNorm: Float,
    ): Bitmap {
        val vp = viewportSize.toFloat()
        val safeW = src.width.coerceAtLeast(1).toFloat()
        val safeH = src.height.coerceAtLeast(1).toFloat()
        val baseScale = maxOf(vp / safeW, vp / safeH)
        val effectiveScale = baseScale * zoom.coerceIn(1f, 4f)
        val drawW = safeW * effectiveScale
        val drawH = safeH * effectiveScale
        val maxPanX = maxOf(0f, (drawW - vp) / 2f)
        val maxPanY = maxOf(0f, (drawH - vp) / 2f)
        val panX = panXNorm.coerceIn(-1f, 1f) * maxPanX
        val panY = panYNorm.coerceIn(-1f, 1f) * maxPanY
        val cx = canvasSize / 2f + panX
        val cy = canvasSize / 2f + panY
        val dst = RectF(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f)

        val out = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(BACKDROP_COLOR)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, null, dst, paint)
        return out
    }

    private fun loadDisplayBitmap(context: Context, maxTargetPx: Int = DISPLAY_LOGO_PX): Bitmap? {
        val file = File(context.filesDir, DISPLAY_LOGO_FILE)
        if (!file.exists() || file.length() <= 0L) return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (decoded.width <= maxTargetPx && decoded.height <= maxTargetPx) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, maxTargetPx, maxTargetPx, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun loadAdaptiveBitmap(context: Context): Bitmap? {
        val file = File(context.filesDir, ADAPTIVE_LOGO_FILE)
        if (!file.exists() || file.length() <= 0L) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun writePngAtomic(target: File, bitmap: Bitmap): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.outputStream().buffered().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                error("Bitmap.compress failed")
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
