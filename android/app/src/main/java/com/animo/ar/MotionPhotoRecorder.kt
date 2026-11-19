package com.animo.ar

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Captures the AR surface directly (no MediaProjection / system recording dialog) and packs a
 * Google Motion Photo 1.0 JPEG: a still frame plus a short H.264 clip appended after the JPEG.
 * WeChat on Android 8.0.63+ can recognise this container as a Live Photo when it is in the
 * camera roll; the MP4 is also returned as a fallback.
 */
class MotionPhotoRecorder {

    data class Result(val motionPhoto: File)

    suspend fun capture(textureView: TextureView, cacheDir: File): Result? {
        cacheDir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val still = withContext(Dispatchers.Main) { textureView.getBitmap(1080, 2400) } ?: return null
        val video = File(cacheDir, "IMG_$stamp.mp4")
        val recorded = runCatching { encode(textureView, video) }.getOrDefault(false)
        if (!recorded || !video.exists() || video.length() <= 0L) {
            still.recycle()
            video.delete()
            return null
        }
        val photo = File(cacheDir, "IMG_$stamp.jpg")
        val packed = runCatching { writeMotionPhoto(still, video, photo) }.getOrDefault(false)
        still.recycle()
        if (!packed) {
            video.delete()
            return null
        }
        // The MP4 is embedded in the JPEG; do not leave a second gallery item behind.
        video.delete()
        return Result(photo)
    }

    private suspend fun encode(textureView: TextureView, output: File): Boolean {
        val width = 720
        val height = 1600
        val fps = 10
        val frameCount = 30
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var muxerStarted = false
        return try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val colorFormat = when {
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> return false
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val info = MediaCodec.BufferInfo()
            val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) return
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(encoder.outputFormat)
                        mux.start()
                        muxerStarted = true
                    } else if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index) ?: continue
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (muxerStarted && track >= 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                mux.writeSampleData(track, buffer, info)
                            }
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }

            val started = SystemClock.elapsedRealtime()
            for (frame in 0 until frameCount) {
                val bitmap = withContext(Dispatchers.Main) {
                    textureView.getBitmap(width, height)
                } ?: return false
                val yuv = argbToYuv(bitmap, width, height, semiPlanar)
                bitmap.recycle()
                val pts = frame * 1_000_000L / fps
                var inputIndex = encoder.dequeueInputBuffer(20_000)
                while (inputIndex < 0) {
                    drain(false)
                    inputIndex = encoder.dequeueInputBuffer(20_000)
                }
                val input = encoder.getInputBuffer(inputIndex) ?: return false
                input.clear()
                input.put(yuv)
                encoder.queueInputBuffer(inputIndex, 0, yuv.size, pts, 0)
                drain(false)
                val target = started + (frame + 1L) * 1000L / fps
                val wait = target - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
            }
            val inputIndex = encoder.dequeueInputBuffer(20_000)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    frameCount * 1_000_000L / fps,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drain(true)
            true
        } catch (t: Throwable) {
            android.util.Log.w("AnimoMotion", "capture failed", t)
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            }
        }
    }

    private fun argbToYuv(bitmap: Bitmap, width: Int, height: Int, semiPlanar: Boolean): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        for (j in 0 until height) {
            val row = j * width
            for (i in 0 until width) {
                val c = argb[row + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = (77 * r + 150 * g + 29 * b + 128) shr 8
                out[row + i] = y.coerceIn(0, 255).toByte()
            }
        }
        val chromaSize = ySize / 4
        val u = ByteArray(chromaSize)
        val v = ByteArray(chromaSize)
        for (j in 0 until height step 2) {
            val row = j * width
            val cRow = (j / 2) * (width / 2)
            for (i in 0 until width step 2) {
                val c = argb[row + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                u[cRow + i / 2] = (((-43 * r - 85 * g + 128 * b + 128) shr 8) + 128)
                    .coerceIn(0, 255).toByte()
                v[cRow + i / 2] = (((128 * r - 107 * g - 21 * b + 128) shr 8) + 128)
                    .coerceIn(0, 255).toByte()
            }
        }
        if (semiPlanar) {
            for (i in 0 until chromaSize) {
                out[ySize + i * 2] = u[i]
                out[ySize + i * 2 + 1] = v[i]
            }
        } else {
            System.arraycopy(u, 0, out, ySize, chromaSize)
            System.arraycopy(v, 0, out, ySize + chromaSize, chromaSize)
        }
        return out
    }

    private fun writeMotionPhoto(still: Bitmap, video: File, output: File): Boolean {
        val jpegOut = ByteArrayOutputStream()
        if (!still.compress(Bitmap.CompressFormat.JPEG, 94, jpegOut)) return false
        val jpeg = jpegOut.toByteArray()
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) {
            return false
        }
        val videoBytes = video.readBytes()
        val xmp = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Animo">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
               xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
               xmlns:Container="http://ns.google.com/photos/1.0/container/"
               xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
               GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"
               GCamera:MotionPhotoPresentationTimestampUs="0"
               GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1"
               GCamera:MicroVideoOffset="${videoBytes.size}"
               GCamera:MicroVideoPresentationTimestampUs="0">
               <Container:Directory><rdf:Seq>
                <rdf:li rdf:parseType="Resource"><Container:Item
                 Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
                <rdf:li rdf:parseType="Resource"><Container:Item
                 Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${videoBytes.size}"/></rdf:li>
               </rdf:Seq></Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val identifier = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val segmentLength = 2 + identifier.size + xmp.size
        if (segmentLength > 0xFFFF) return false
        val out = ByteArrayOutputStream(jpeg.size + videoBytes.size + segmentLength + 8)
        out.write(0xFF)
        out.write(0xD8)
        out.write(0xFF)
        out.write(0xE1)
        out.write((segmentLength shr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(identifier)
        out.write(xmp)
        out.write(jpeg, 2, jpeg.size - 2)
        out.write(videoBytes)
        output.writeBytes(out.toByteArray())
        return true
    }
}
