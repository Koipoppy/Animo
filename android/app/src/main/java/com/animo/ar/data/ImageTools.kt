package com.animo.ar.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Normalizes a picked photo for the Lux3D upload pipeline. */
object ImageTools {

    private const val MAX_SIDE = 2048
    private const val MIN_SIDE = 128
    private const val MAX_INPUT_BYTES = 30 * 1024 * 1024
    private const val JPEG_QUALITY = 90

    /**
     * Decodes any format BitmapFactory understands (JPEG/PNG/WebP/GIF), applies EXIF rotation,
     * caps the longest side at 2048 px, flattens transparency onto white and returns JPEG bytes.
     */
    fun normalize(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) throw IllegalArgumentException("图片内容为空")
        if (bytes.size > MAX_INPUT_BYTES) throw IllegalArgumentException("图片太大了，请压缩后再试")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw IllegalArgumentException("这张图片无法读取，请换一张（JPG、PNG、WebP、GIF 均可）")
        }
        if (min(sourceWidth, sourceHeight) < MIN_SIDE) {
            throw IllegalArgumentException("图片太小了，请换一张至少 ${MIN_SIDE}×${MIN_SIDE} 的图")
        }

        var sample = 1
        while (max(sourceWidth, sourceHeight) / sample > MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IllegalArgumentException("这张图片无法读取，请换一张")

        val oriented = applyExif(decoded, orientationOf(bytes))
        val scale = min(1f, MAX_SIDE.toFloat() / max(oriented.width, oriented.height))
        val width = (oriented.width * scale).toInt().coerceAtLeast(1)
        val height = (oriented.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(oriented, width, height, true)
        } else {
            oriented
        }

        val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }

        val out = ByteArrayOutputStream()
        flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)

        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        flattened.recycle()
        return out.toByteArray()
    }

    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExif(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
