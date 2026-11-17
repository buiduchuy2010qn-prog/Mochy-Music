// CoilBitmapLoader.kt

package com.mochyy.mochymusic.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import timber.log.Timber

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {

    private val isLowEndActive: Boolean
        get() = LowEndModeManager.isActive

    private val maxNotificationArtworkSide: Int
        get() = if (isLowEndActive) 256 else 512

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun createFallbackBitmap(): Bitmap =
        createBitmap(64, 64)

    private fun Bitmap.copyIfNeeded(): Bitmap {
        return if (isRecycled) {
            createFallbackBitmap()
        } else {
            try {
                val preferredConfig = if (isLowEndActive && !hasAlpha()) {
                    Bitmap.Config.RGB_565
                } else {
                    Bitmap.Config.ARGB_8888
                }
                copy(preferredConfig, false) ?: createFallbackBitmap()
            } catch (e: Exception) {
                createFallbackBitmap()
            }
        }
    }

    private fun computeInSampleSize(srcWidth: Int, srcHeight: Int, reqSide: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqSide || srcWidth > reqSide) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqSide && (halfWidth / inSampleSize) >= reqSide) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                val maxSide = maxNotificationArtworkSide
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                    if (isLowEndActive) {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                }
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
                bitmap?.copyIfNeeded() ?: createFallbackBitmap()
            } catch (e: Exception) {
                Timber.tag("CoilBitmapLoader").w(e, "Failed to decode bitmap data")
                createFallbackBitmap()
            }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            val maxSide = maxNotificationArtworkSide
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(Size(maxSide, maxSide))
                .allowHardware(false)
                .build()

            val result = context.imageLoader.execute(request)

            when (result) {
                is ErrorResult -> {
                    createFallbackBitmap()
                }
                is SuccessResult -> {
                    try {
                        val bitmap = result.image.toBitmap()
                        bitmap.copyIfNeeded()
                    } catch (e: Exception) {
                        Timber.tag("CoilBitmapLoader").w(e, "Failed to convert image to bitmap")
                        createFallbackBitmap()
                    }
                }
            }
        }
}
