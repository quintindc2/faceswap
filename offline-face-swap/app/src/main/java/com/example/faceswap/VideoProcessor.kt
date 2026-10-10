package com.example.faceswap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

/**
 * Reads frames between start and end, swaps the face on every frame, and encodes
 * the result to an H.264 MP4 (original audio is copied across when possible).
 */
class VideoProcessor(private val ctx: Context, private val swapper: FaceSwapper) {

    companion object {
        private const val FPS = 24
        private const val MAX_SIDE = 720
    }

    suspend fun process(
        uri: Uri,
        source: FaceSwapper.Source,
        startMs: Long,
        endMs: Long,
        expression: Float,
        onProgress: (percent: Int, text: String) -> Unit
    ): File {
        val mmr = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        var muxerStarted = false
        val outFile = File(ctx.cacheDir, "swap_${System.currentTimeMillis()}.mp4")
        var success = false

        try {
            mmr.setDataSource(ctx, uri)
            val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: error("Cannot read video size")
            val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: error("Cannot read video size")
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dw = if (rot == 90 || rot == 270) vh else vw
            val dh = if (rot == 90 || rot == 270) vw else vh
            val scale = min(1f, MAX_SIDE.toFloat() / max(dw, dh))
            val ow = max(2, ((dw * scale).toInt() / 2) * 2)
            val oh = max(2, ((dh * scale).toInt() / 2) * 2)

            val frameCount = max(1, ((endMs - startMs) * FPS / 1000).toInt())

            // ---- encoder + muxer ----
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (ow.toLong() * oh * FPS / 8).toInt().coerceIn(1_500_000, 10_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            surface = inputSurface
            enc.start()

            val mux = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            // optional audio track (must be added before the muxer starts)
            var audioExtractor: MediaExtractor? = null
            var audioTrackOut = -1
            try {
                val ex = MediaExtractor()
                ex.setDataSource(ctx, uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        ex.selectTrack(i)
                        audioTrackOut = mux.addTrack(f)
                        audioExtractor = ex
                        break
                    }
                }
                if (audioExtractor == null) ex.release()
            } catch (e: Exception) {
                audioExtractor?.release()
                audioExtractor = null
                audioTrackOut = -1
            }

            val info = MediaCodec.BufferInfo()
            var videoTrack = -1
            var outCount = 0L

            // pulls finished data out of the encoder; with end=true waits for end-of-stream
            fun drain(end: Boolean) {
                if (end) enc.signalEndOfInputStream()
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, 10_000)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end) return
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    } else if (idx >= 0) {
                        val buf = enc.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            // frames are drawn faster than real time, so write our own timestamps
                            info.presentationTimeUs = outCount * 1_000_000L / FPS
                            outCount++
                            mux.writeSampleData(videoTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }

            // ---- frame loop ----
            swapper.resetTracking()
            var lastFace: FaceSwapper.Face? = null
            var missed = 0
            var lastFrame: Bitmap? = null
            val dstRect = Rect(0, 0, ow, oh)

            for (i in 0 until frameCount) {
                coroutineContext.ensureActive()
                val tUs = startMs * 1000L + i * 1_000_000L / FPS
                val frame = mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, ow, oh) ?: lastFrame
                    ?: error("Could not read video frame at ${tUs / 1000} ms")
                lastFrame = frame

                var face = swapper.detect(frame)
                if (face == null) {
                    missed++
                    if (missed <= 3) face = lastFace // brief detection drop-outs
                } else {
                    missed = 0
                    lastFace = face
                }
                val result = swapper.compose(frame, source, face, expression)

                val canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawBitmap(result, null, dstRect, null)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                onProgress(((i + 1) * 100) / frameCount, "Frame ${i + 1} of $frameCount")
            }

            drain(true)
            enc.stop()

            // ---- audio (original sound for the chosen range) ----
            if (audioExtractor != null && audioTrackOut >= 0 && muxerStarted) {
                onProgress(100, "Copying audio…")
                copyAudio(audioExtractor, mux, audioTrackOut, startMs * 1000L, endMs * 1000L)
                audioExtractor.release()
            }

            mux.stop()
            muxerStarted = false
            success = true
            return outFile
        } finally {
            try { surface?.release() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { mmr.release() } catch (_: Exception) {}
            if (!success) outFile.delete()
        }
    }

    private fun copyAudio(ex: MediaExtractor, mux: MediaMuxer, track: Int, startUs: Long, endUs: Long) {
        val fmt = ex.getTrackFormat(ex.sampleTrackIndexOrZero())
        val maxSize = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
        val buf = ByteBuffer.allocate(max(maxSize, 64 * 1024))
        val info = MediaCodec.BufferInfo()
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            val t = ex.sampleTime
            if (t < 0 || t > endUs) break
            if (t >= startUs) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, t - startUs, ex.sampleFlags)
                mux.writeSampleData(track, buf, info)
            }
            if (!ex.advance()) break
        }
    }

    private fun MediaExtractor.sampleTrackIndexOrZero(): Int {
        val i = sampleTrackIndex
        return if (i >= 0) i else 0
    }
}
