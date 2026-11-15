package com.mochyy.mochymusic.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Nén và giải mã ảnh đại diện siêu nhẹ cho Mochy Cloud.
 * Giới hạn nghiêm ngặt <= 24 KB raw WebP (<= 32 KB chuỗi Base64 Data URI) để tránh nặng CSDL.
 */
object MochyAvatarCodec {

    const val MaxRawWebpBytes = 24_000
    const val MaxDataUriLength = 32_768
    private const val CloudAvatarFileName = "mochy_cloud_avatar.webp"

    suspend fun encodeUriToWebpDataUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val decoded = decodeSquaredBitmap(app, uri) ?: return@withContext null
        try {
            val targets = listOf(128, 96)
            val qualities = listOf(80, 68, 54, 40, 28)
            for (dim in targets) {
                val scaled = if (decoded.width == dim && decoded.height == dim) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, dim, dim, true)
                }
                try {
                    for (q in qualities) {
                        val bytes = compressWebp(scaled, q)
                        if (bytes.size <= MaxRawWebpBytes) {
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            val uriStr = "data:image/webp;base64,$b64"
                            if (uriStr.length <= MaxDataUriLength) return@withContext uriStr
                        }
                    }
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            }
            null
        } finally {
            decoded.recycle()
        }
    }

    suspend fun decodeDataUriToFile(context: Context, dataUri: String): File? = withContext(Dispatchers.IO) {
        val prefix = "data:image/webp;base64,"
        if (!dataUri.startsWith(prefix) || dataUri.length > MaxDataUriLength) return@withContext null
        runCatching {
            val raw = Base64.decode(dataUri.substring(prefix.length), Base64.DEFAULT)
            if (raw.isEmpty() || raw.size > MaxRawWebpBytes + 512) return@runCatching null
            val dest = File(context.applicationContext.filesDir, CloudAvatarFileName)
            dest.writeBytes(raw)
            dest
        }.getOrNull()
    }

    private fun decodeSquaredBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
        val src = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val minSide = minOf(info.size.width, info.size.height).coerceAtLeast(1)
                if (minSide > 512) {
                    val scale = 512f / minSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(128),
                        (info.size.height * scale).toInt().coerceAtLeast(128),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val minSide = minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (minSide / (sample * 2) >= 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } ?: return@runCatching null

        try {
            val side = minOf(src.width, src.height)
            if (side <= 0) return@runCatching null
            val left = (src.width - side) / 2
            val top = (src.height - side) / 2
            val out = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(src, Rect(left, top, left + side, top + side), Rect(0, 0, 128, 128), paint)
            out
        } finally {
            src.recycle()
        }
    }.getOrNull()

    private fun compressWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }
}
