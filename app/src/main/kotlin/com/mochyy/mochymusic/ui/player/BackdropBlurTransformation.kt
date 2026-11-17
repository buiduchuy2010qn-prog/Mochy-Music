// BackdropBlurTransformation.kt

package com.mochyy.mochymusic.ui.player

import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.scale
import coil3.size.Size
import coil3.transform.Transformation
import com.mochyy.mochymusic.utils.LowEndModeManager
import kotlin.math.max
import kotlin.math.roundToInt

class BackdropBlurTransformation(private val radiusPx: Int) : Transformation() {

    private val useLowEndPath: Boolean
        get() = LowEndModeManager.isActive

    override val cacheKey: String = "${this::class.qualifiedName}-$radiusPx-$useLowEndPath"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (radiusPx <= 0) return input
        if (input.width == 0 || input.height == 0) return input

        val working: Bitmap
        val effectiveRadius: Int
        if (useLowEndPath && (input.width > LOW_END_MAX_SIDE_PX || input.height > LOW_END_MAX_SIDE_PX)) {
            val scale = LOW_END_MAX_SIDE_PX.toFloat() / max(input.width, input.height).toFloat()
            val targetW = (input.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (input.height * scale).roundToInt().coerceAtLeast(1)
            working = input.scale(targetW, targetH, filter = true)
            effectiveRadius = (radiusPx * scale).roundToInt().coerceAtLeast(1)
        } else {
            working = input
            effectiveRadius = radiusPx
        }

        val width = working.width
        val height = working.height
        val pixels = IntArray(width * height)
        working.getPixels(pixels, 0, width, 0, 0, width, height)
        val hasAlpha = working.hasAlpha()
        if (working !== input) working.recycle()

        val scratch = IntArray(pixels.size)
        repeat(3) {
            slidingBoxBlur(pixels, scratch, width, height, effectiveRadius, horizontal = true)
            slidingBoxBlur(scratch, pixels, width, height, effectiveRadius, horizontal = false)
        }

        val argb = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (!hasAlpha && useLowEndPath) {
            val rgb565 = argb.copy(Bitmap.Config.RGB_565, false)
            if (rgb565 != null) {
                argb.recycle()
                return rgb565
            }
        }
        return argb
    }

    private fun slidingBoxBlur(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val step = if (horizontal) 1 else width
        val window = 2 * radius + 1

        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[start + i.coerceIn(0, length - 1) * step]
                a += p ushr 24
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                dst[start + i * step] =
                    ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = src[start + (i - radius).coerceAtLeast(0) * step]
                val entering = src[start + (i + radius + 1).coerceAtMost(length - 1) * step]
                a += (entering ushr 24) - (leaving ushr 24)
                r += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
                g += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    private companion object {
        const val LOW_END_MAX_SIDE_PX = 96
    }
}
