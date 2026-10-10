package com.example.faceswap

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Replaces the main face in a frame with the face from a source image, following the
 * expression (eyes, brows, mouth) of the face in the video.
 *
 * Offline: uses ML Kit's bundled face-contour model (no network needed).
 *
 * Method:
 *  1. Detect ~100 contour points (outline, brows, eyes, nose, lips) on the photo and on each frame.
 *  2. Triangulate the photo's points once (Delaunay). For every frame, move the mesh vertices to
 *     where those facial points are in the video (so blinks and mouth shapes carry over) and
 *     draw the photo through that mesh as a texture.
 *  3. Mask with the video face's outline, match colour, composite.
 *  4. When the mouth is open, keep the video's real mouth interior (teeth/dark) on top.
 *  5. Landmarks are lightly smoothed between frames to reduce jitter.
 */
class FaceSwapper(private val source: Bitmap) {

    /** 0 = keep the photo's face shape (rigid paste), 1 = fully follow the video face's expression. */
    var expressionStrength = 1f

    private class Landmarks(
        val all: FloatArray,
        val ranges: Map<Int, IntRange>,
        val outlineCount: Int
    ) {
        fun points(type: Int): List<PointF> {
            val r = ranges[type] ?: return emptyList()
            return r.map { PointF(all[2 * it], all[2 * it + 1]) }
        }
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
    )

    // Contours used for the mesh, in a fixed order so photo/video points correspond.
    private val alignTypes = intArrayOf(
        FaceContour.FACE,
        FaceContour.LEFT_EYEBROW_TOP,
        FaceContour.RIGHT_EYEBROW_TOP,
        FaceContour.LEFT_EYE,
        FaceContour.RIGHT_EYE,
        FaceContour.NOSE_BRIDGE,
        FaceContour.NOSE_BOTTOM,
        FaceContour.UPPER_LIP_TOP,
        FaceContour.UPPER_LIP_BOTTOM,
        FaceContour.LOWER_LIP_TOP,
        FaceContour.LOWER_LIP_BOTTOM
    )

    private val sourceLandmarks: Landmarks? = detect(source)
    private val triangles: ShortArray? = sourceLandmarks?.let { delaunay(it.all) }
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private var previous: FloatArray? = null

    fun sourceHasFace(): Boolean = sourceLandmarks != null && triangles != null

    private fun detect(bitmap: Bitmap): Landmarks? {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return null

        val all = ArrayList<Float>()
        val ranges = HashMap<Int, IntRange>()
        var count = 0
        var outlineCount = 0
        for (type in alignTypes) {
            val pts = face.getContour(type)?.points ?: return null
            if (pts.isEmpty()) return null
            ranges[type] = count until (count + pts.size)
            for (p in pts) {
                all.add(p.x); all.add(p.y)
            }
            if (type == FaceContour.FACE) outlineCount = pts.size
            count += pts.size
        }
        return Landmarks(all.toFloatArray(), ranges, outlineCount)
    }

    /** Bowyer-Watson Delaunay triangulation; returns triangle vertex indices. */
    private fun delaunay(pts: FloatArray): ShortArray {
        val n = pts.size / 2
        val xs = DoubleArray(n + 3)
        val ys = DoubleArray(n + 3)
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until n) {
            xs[i] = pts[2 * i].toDouble(); ys[i] = pts[2 * i + 1].toDouble()
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        }
        val d = max(maxX - minX, maxY - minY).coerceAtLeast(1.0) * 20.0
        val mx = (minX + maxX) / 2
        val my = (minY + maxY) / 2
        xs[n] = mx - d; ys[n] = my - d
        xs[n + 1] = mx; ys[n + 1] = my + d
        xs[n + 2] = mx + d; ys[n + 2] = my - d

        fun inCircumcircle(t: IntArray, px: Double, py: Double): Boolean {
            val ax = xs[t[0]]; val ay = ys[t[0]]
            val bx = xs[t[1]]; val by = ys[t[1]]
            val cx = xs[t[2]]; val cy = ys[t[2]]
            val dd = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
            if (kotlin.math.abs(dd) < 1e-12) return false
            val a2 = ax * ax + ay * ay
            val b2 = bx * bx + by * by
            val c2 = cx * cx + cy * cy
            val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / dd
            val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / dd
            val r2 = (ax - ux) * (ax - ux) + (ay - uy) * (ay - uy)
            return (px - ux) * (px - ux) + (py - uy) * (py - uy) < r2
        }

        var tris = ArrayList<IntArray>()
        tris.add(intArrayOf(n, n + 1, n + 2))
        for (i in 0 until n) {
            val bad = ArrayList<IntArray>()
            val keep = ArrayList<IntArray>()
            for (t in tris) {
                if (inCircumcircle(t, xs[i], ys[i])) bad.add(t) else keep.add(t)
            }
            val edgeCount = HashMap<Long, Int>()
            for (t in bad) {
                for (e in 0 until 3) {
                    val a = t[e]
                    val b = t[(e + 1) % 3]
                    val key = min(a, b).toLong() * 1000L + max(a, b).toLong()
                    edgeCount[key] = (edgeCount[key] ?: 0) + 1
                }
            }
            for ((key, c) in edgeCount) {
                if (c == 1) {
                    keep.add(intArrayOf((key / 1000L).toInt(), (key % 1000L).toInt(), i))
                }
            }
            tris = keep
        }

        val out = ArrayList<Short>()
        for (t in tris) {
            if (t[0] < n && t[1] < n && t[2] < n) {
                out.add(t[0].toShort()); out.add(t[1].toShort()); out.add(t[2].toShort())
            }
        }
        return out.toShortArray()
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

    /** Exponential smoothing of landmarks between consecutive frames (reduces jitter). */
    private fun smooth(cur: FloatArray, outlineCount: Int): FloatArray {
        val prev = previous
        var result = cur
        if (prev != null && prev.size == cur.size) {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            for (i in 0 until outlineCount) {
                minX = min(minX, cur[2 * i]); maxX = max(maxX, cur[2 * i])
            }
            val faceW = max(1f, maxX - minX)
            var sum = 0f
            val count = cur.size / 2
            for (i in 0 until count) {
                val dx = cur[2 * i] - prev[2 * i]
                val dy = cur[2 * i + 1] - prev[2 * i + 1]
                sum += sqrt(dx * dx + dy * dy)
            }
            // Big jump (cut or fast motion): don't smooth.
            if (sum / count < 0.2f * faceW) {
                val out = FloatArray(cur.size)
                for (i in cur.indices) {
                    out[i] = prev[i] + 0.65f * (cur[i] - prev[i])
                }
                result = out
            }
        }
        previous = result
        return result
    }

    /** Returns a new bitmap with the face swapped, or [frame] itself if no face was found. */
    fun swap(frame: Bitmap): Bitmap {
        val src = sourceLandmarks ?: return frame
        val tris = triangles ?: return frame
        val raw = detect(frame)
        if (raw == null || raw.all.size != src.all.size) {
            previous = null
            return frame
        }

        val dstAll = smooth(raw.all, raw.outlineCount)
        val matrix = similarity(src.all, dstAll) ?: return frame

        // Where the photo's points would sit with a rigid paste, vs. where they are in the video.
        val base = FloatArray(src.all.size)
        matrix.mapPoints(base, src.all)
        val k = expressionStrength.coerceIn(0f, 1f)
        val mesh = FloatArray(src.all.size)
        for (i in mesh.indices) {
            // Outline always follows the video face so the mesh covers the mask.
            mesh[i] = if (i < src.outlineCount * 2) dstAll[i]
            else base[i] + k * (dstAll[i] - base[i])
        }

        val w = frame.width
        val h = frame.height

        // Feathered mask of the video face outline.
        val path = Path()
        for (i in 0 until raw.outlineCount) {
            val x = dstAll[2 * i]; val y = dstAll[2 * i + 1]
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val feather = max(2f, bounds.width() * 0.05f)

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        })

        // Photo drawn through the moved mesh (texture coords = photo pixel positions).
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(layer).drawVertices(
            Canvas.VertexMode.TRIANGLES, mesh.size, mesh, 0,
            src.all, 0, null, 0, tris, 0, tris.size, meshPaint
        )

        val gains = colourGains(frame, layer, mask, bounds)

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

        restoreMouth(result, frame, Landmarks(dstAll, raw.ranges, raw.outlineCount), bounds.width())

        mask.recycle()
        layer.recycle()
        return result
    }

    /** When the mouth is open, put the video's real mouth interior back on top. */
    private fun restoreMouth(result: Bitmap, frame: Bitmap, lm: Landmarks, faceWidth: Float) {
        val pts = ArrayList<PointF>()
        pts.addAll(lm.points(FaceContour.UPPER_LIP_BOTTOM))
        pts.addAll(lm.points(FaceContour.LOWER_LIP_TOP))
        if (pts.size < 6) return

        var cx = 0f; var cy = 0f
        for (p in pts) { cx += p.x; cy += p.y }
        cx /= pts.size; cy /= pts.size
        // Order points around the centre to form the mouth-opening polygon.
        pts.sortBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }

        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        path.close()
        val shrink = Matrix().apply { setScale(0.92f, 0.92f, cx, cy) }
        path.transform(shrink)

        val b = RectF()
        path.computeBounds(b, true)
        // Only when clearly open.
        if (b.height() < 0.035f * faceWidth) return

        val feather = max(1.5f, b.width() * 0.05f)
        val pad = (feather * 3).toInt() + 1
        val region = Rect(
            max(0, b.left.toInt() - pad), max(0, b.top.toInt() - pad),
            min(frame.width, b.right.toInt() + pad), min(frame.height, b.bottom.toInt() + pad)
        )
        val rw = region.width()
        val rh = region.height()
        if (rw <= 0 || rh <= 0) return

        val mouthMask = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mouthMask)
        mc.translate(-region.left.toFloat(), -region.top.toFloat())
        mc.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        })

        val crop = Bitmap.createBitmap(frame, region.left, region.top, rw, rh)
        val cropCopy = crop.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(cropCopy).drawBitmap(mouthMask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        Canvas(result).drawBitmap(cropCopy, region.left.toFloat(), region.top.toFloat(), null)

        mouthMask.recycle()
        cropCopy.recycle()
        if (crop !== frame) crop.recycle()
    }

    private fun colourGains(
        frame: Bitmap, layer: Bitmap, mask: Bitmap, bounds: RectF
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
                val f = fp[i]
                val l = lp[i]
                fs[0] = fs[0] + ((f shr 16) and 0xFF).toDouble()
                fs[1] = fs[1] + ((f shr 8) and 0xFF).toDouble()
                fs[2] = fs[2] + (f and 0xFF).toDouble()
                ls[0] = ls[0] + ((l shr 16) and 0xFF).toDouble()
                ls[1] = ls[1] + ((l shr 8) and 0xFF).toDouble()
                ls[2] = ls[2] + (l and 0xFF).toDouble()
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
