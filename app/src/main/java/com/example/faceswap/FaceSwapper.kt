package com.example.faceswap

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Replaces the main face in a frame with the face from a source image.
 *
 * Offline: uses ML Kit's bundled face-contour model (no network, no Play Services model download).
 * Method: fit a similarity transform (scale + rotation + translation) from the source face
 * contours to the target face contours, warp the source image, mask it with the target's face
 * outline (feathered), match average colour, and composite.
 */
class FaceSwapper(private val source: Bitmap) {

    private class Landmarks(val all: FloatArray, val outline: FloatArray)

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
    )

    // Contours used for alignment, in a fixed order so source/target points correspond.
    private val alignTypes = intArrayOf(
        FaceContour.FACE,
        FaceContour.LEFT_EYEBROW_TOP,
        FaceContour.RIGHT_EYEBROW_TOP,
        FaceContour.LEFT_EYE,
        FaceContour.RIGHT_EYE,
        FaceContour.NOSE_BRIDGE,
        FaceContour.NOSE_BOTTOM,
        FaceContour.UPPER_LIP_TOP,
        FaceContour.LOWER_LIP_BOTTOM
    )

    private val sourceLandmarks: Landmarks? = detect(source)

    fun sourceHasFace(): Boolean = sourceLandmarks != null

    private fun detect(bitmap: Bitmap): Landmarks? {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return null

        val all = ArrayList<Float>()
        var outline = FloatArray(0)
        for (type in alignTypes) {
            val pts = face.getContour(type)?.points ?: return null
            if (pts.isEmpty()) return null
            for (p in pts) {
                all.add(p.x); all.add(p.y)
            }
            if (type == FaceContour.FACE) {
                outline = FloatArray(pts.size * 2).also { arr ->
                    pts.forEachIndexed { i, p -> arr[i * 2] = p.x; arr[i * 2 + 1] = p.y }
                }
            }
        }
        return Landmarks(all.toFloatArray(), outline)
    }

    /** Least-squares similarity transform mapping src points onto dst points. */
    private fun similarity(src: FloatArray, dst: FloatArray): Matrix? {
        val n = min(src.size, dst.size) / 2
        if (n < 3) return null
        var pmx = 0.0; var pmy = 0.0; var qmx = 0.0; var qmy = 0.0
        for (i in 0 until n) {
            pmx += src[2 * i]; pmy += src[2 * i + 1]
            qmx += dst[2 * i]; qmy += dst[2 * i + 1]
        }
        pmx /= n; pmy /= n; qmx /= n; qmy /= n

        var dot = 0.0; var cross = 0.0; var norm = 0.0
        for (i in 0 until n) {
            val px = src[2 * i] - pmx; val py = src[2 * i + 1] - pmy
            val qx = dst[2 * i] - qmx; val qy = dst[2 * i + 1] - qmy
            dot += px * qx + py * qy
            cross += px * qy - py * qx
            norm += px * px + py * py
        }
        if (norm < 1e-6) return null
        val a = dot / norm
        val b = cross / norm
        val tx = qmx - (a * pmx - b * pmy)
        val ty = qmy - (b * pmx + a * pmy)

        return Matrix().apply {
            setValues(
                floatArrayOf(
                    a.toFloat(), (-b).toFloat(), tx.toFloat(),
                    b.toFloat(), a.toFloat(), ty.toFloat(),
                    0f, 0f, 1f
                )
            )
        }
    }

    /** Returns a new bitmap with the face swapped, or [frame] itself if no face was found. */
    fun swap(frame: Bitmap): Bitmap {
        val src = sourceLandmarks ?: return frame
        val dst = detect(frame) ?: return frame
        val matrix = similarity(src.all, dst.all) ?: return frame

        val w = frame.width
        val h = frame.height

        // Feathered mask of the target face outline.
        val path = Path()
        for (i in 0 until dst.outline.size / 2) {
            val x = dst.outline[2 * i]; val y = dst.outline[2 * i + 1]
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val bounds = android.graphics.RectF().also { path.computeBounds(it, true) }
        val feather = max(2f, bounds.width() * 0.05f)

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        })

        // Warped source face.
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(layer).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

        // Per-channel colour gain so the pasted face matches the target's skin tone/lighting.
        val gains = colourGains(frame, layer, mask, bounds)

        // Keep only the face region of the warped layer.
        Canvas(layer).drawBitmap(mask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })

        val result = frame.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        gains[0], 0f, 0f, 0f, 0f,
                        0f, gains[1], 0f, 0f, 0f,
                        0f, 0f, gains[2], 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        })

        mask.recycle()
        layer.recycle()
        return result
    }

    private fun colourGains(
        frame: Bitmap, layer: Bitmap, mask: Bitmap, bounds: android.graphics.RectF
    ): FloatArray {
        val r = Rect(
            max(0, bounds.left.toInt()), max(0, bounds.top.toInt()),
            min(frame.width, bounds.right.toInt() + 1), min(frame.height, bounds.bottom.toInt() + 1)
        )
        val bw = r.width(); val bh = r.height()
        if (bw <= 0 || bh <= 0) return floatArrayOf(1f, 1f, 1f)

        val fp = IntArray(bw * bh); frame.getPixels(fp, 0, bw, r.left, r.top, bw, bh)
        val lp = IntArray(bw * bh); layer.getPixels(lp, 0, bw, r.left, r.top, bw, bh)
        val mp = IntArray(bw * bh); mask.getPixels(mp, 0, bw, r.left, r.top, bw, bh)

        val fs = DoubleArray(3); val ls = DoubleArray(3)
        var count = 0
        var i = 0
        while (i < fp.size) {
            if (Color.alpha(mp[i]) > 160 && Color.alpha(lp[i]) > 200) {
                fs[0] += Color.red(fp[i]); fs[1] += Color.green(fp[i]); fs[2] += Color.blue(fp[i])
                ls[0] += Color.red(lp[i]); ls[1] += Color.green(lp[i]); ls[2] += Color.blue(lp[i])
                count++
            }
            i += 3
        }
        if (count < 20) return floatArrayOf(1f, 1f, 1f)
        return FloatArray(3) { c ->
            if (ls[c] < 1.0) 1f else (fs[c] / ls[c]).toFloat().coerceIn(0.6f, 1.6f)
        }
    }

    fun close() = detector.close()
}
