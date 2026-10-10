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
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes frames with MediaMetadataRetriever, swaps the face in each frame, re-encodes to
 * H.264 (MP4) and copies the original audio track across.
 */
class VideoProcessor(private val ctx: Context) {

    companion object {
        const val OUTPUT_FPS = 15      // lower = faster processing
        const val MAX_SIDE = 720       // output is downscaled so longest side <= this
    }

    /** @return true when finished, false when cancelled. Throws on errors. */
    fun process(
        videoUri: Uri,
        swapper: FaceSwapper,
        outUri: Uri,
        startMs: Long,
        endMs: Long,
        keepRest: Boolean,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): Boolean {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(ctx, videoUri)

        val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: throw IllegalStateException("Can't read video duration")
        var vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: throw IllegalStateException("Can't read video size")
        var vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: throw IllegalStateException("Can't read video size")
        val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) { val t = vw; vw = vh; vh = t }

        val scale = min(1f, MAX_SIDE.toFloat() / max(vw, vh))
        val ow = max(2, (vw * scale).toInt() and 1.inv())
        val oh = max(2, (vh * scale).toInt() and 1.inv())

        // Swap range, clamped to the video. Output is either just that range, or the whole video
        // with only the range swapped.
        val swapStartMs = startMs.coerceIn(0L, durationMs)
        val swapEndMs = endMs.coerceIn(0L, durationMs)
        if (swapEndMs <= swapStartMs) throw IllegalStateException("End must be after start")
        val outStartMs = if (keepRest) 0L else swapStartMs
        val outEndMs = if (keepRest) durationMs else swapEndMs
        val outStartUs = outStartMs * 1000L
        val totalFrames = max(1, ((outEndMs - outStartMs) * OUTPUT_FPS / 1000).toInt())

        // Audio track (optional).
        val audioExtractor = MediaExtractor()
        var audioIndex = -1
        var audioFormat: MediaFormat? = null
        try {
            audioExtractor.setDataSource(ctx, videoUri, null)
            for (i in 0 until audioExtractor.trackCount) {
                val f = audioExtractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioIndex = i; audioFormat = f; break
                }
            }
        } catch (_: Exception) { audioIndex = -1; audioFormat = null }

        // Encoder.
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, max(2_000_000, (ow * oh * OUTPUT_FPS * 0.12).toInt()))
            setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val pfd = ctx.contentResolver.openFileDescriptor(outUri, "rw")
            ?: throw IllegalStateException("Can't open output file")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var muxerStarted = false
        var videoTrack = -1
        var audioTrack = -1
        var outCount = 0L
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        audioTrack = if (audioFormat != null) {
                            try { muxer.addTrack(audioFormat!!) } catch (_: Exception) { -1 }
                        } else -1
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            // Surface input stamps frames with wall-clock time; use our own timeline.
                            info.presentationTimeUs = outCount * 1_000_000L / OUTPUT_FPS
                            outCount++
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        var finished = false
        try {
            var last: Bitmap? = null
            val dstRect = Rect(0, 0, ow, oh)
            for (i in 0 until totalFrames) {
                if (cancelled()) return false

                val timeUs = outStartUs + i * 1_000_000L / OUTPUT_FPS
                val timeMs = timeUs / 1000L
                val frame = mmr.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST, ow, oh
                ) ?: last
                if (frame != null) {
                    val inRange = timeMs in swapStartMs..swapEndMs
                    val swapped = if (inRange) swapper.swap(frame) else frame
                    val canvas = surface.lockCanvas(null)
                    canvas.drawBitmap(swapped, null, dstRect, null)
                    surface.unlockCanvasAndPost(canvas)
                    if (swapped !== frame) swapped.recycle()
                    if (frame !== last) { last?.recycle(); last = frame }
                }
                drain(false)
                onProgress((i + 1) * 100 / totalFrames)
            }

            codec.signalEndOfInputStream()
            drain(true)

            // Copy original audio.
            if (muxerStarted && audioTrack >= 0 && audioIndex >= 0) {
                audioExtractor.selectTrack(audioIndex)
                audioExtractor.seekTo(outStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buf = ByteBuffer.allocate(1 shl 20)
                val ai = MediaCodec.BufferInfo()
                val videoEndUs = outCount * 1_000_000L / OUTPUT_FPS
                while (true) {
                    val size = audioExtractor.readSampleData(buf, 0)
                    val sampleUs = audioExtractor.sampleTime
                    if (size < 0 || sampleUs < 0) break
                    val t = sampleUs - outStartUs   // shift so the clip starts at 0
                    if (t > videoEndUs) break
                    if (t < 0) { audioExtractor.advance(); continue }
                    ai.set(
                        0, size, t,
                        if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    )
                    muxer.writeSampleData(audioTrack, buf, ai)
                    audioExtractor.advance()
                }
            }
            finished = true
            return true
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            surface.release()
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            audioExtractor.release()
            mmr.release()
            if (!finished) { /* caller deletes the partial output */ }
        }
    }
}
