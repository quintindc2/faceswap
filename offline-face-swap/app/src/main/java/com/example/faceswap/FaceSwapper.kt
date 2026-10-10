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
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Face swap based on the 468-point ML Kit face mesh (runs fully on-device).
 *
 * The source face is cut into triangles; each triangle is warped onto the matching
 * triangle of the face found in the video frame, then blended with a feathered mask
 * and a simple colour correction.
 *
 * "Expression" handling: the inner mesh points (eyes, brows, mouth, nose...) are placed
 * somewhere between the video face's points (blend = 0, copies the video's expression)
 * and the image face's own points aligned to the video face (blend = 1, keeps the
 * expression from the picture). The outline of the face always follows the video.
 */
class FaceSwapper {

    class Face(val pts: FloatArray, val ovalIdx: IntArray, val box: Rect) {
        val count get() = pts.size / 2
    }

    class Source(val bitmap: Bitmap, val face: Face, val tris: IntArray)

    private val detector = FaceMeshDetection.getClient()

    // ---- reusable buffers (resized when the frame size changes) ----
    private var work: Bitmap? = null
    private var layer: Bitmap? = null
    private var mask: Bitmap? = null
    private var workCanvas: Canvas? = null
    private var layerCanvas: Canvas? = null
    private var maskCanvas: Canvas? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val matrix = Matrix()
    private val srcTri = FloatArray(6)
    private val dstTri = FloatArray(6)

    // temporal state
    private var prevDst: FloatArray? = null
    private var prevGain = floatArrayOf(1f, 1f, 1f)

    fun resetTracking() {
        prevDst = null
        prevGain = floatArrayOf(1f, 1f, 1f)
    }

    // ------------------------------------------------------------------ detection

    /** Detects the largest face. Must be called off the main thread. */
    fun detect(bmp: Bitmap): Face? {
        val meshes = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        val mesh = meshes.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
        val all = mesh.allPoints
        if (all.isEmpty()) return null
        val pts = FloatArray(all.size * 2)
        for (p in all) {
            val i = p.index
            if (i < 0 || i >= all.size) continue
            pts[i * 2] = p.position.x
            pts[i * 2 + 1] = p.position.y
        }
        val oval = mesh.getPoints(FaceMesh.FACE_OVAL).map { it.index }.toIntArray()
        return Face(pts, oval, mesh.boundingBox)
    }

    fun prepareSource(bmp: Bitmap): Source? {
        val face = detect(bmp) ?: return null
        val all = Delaunay.triangulate(face.pts)
        val poly = orderedOval(face.pts, face.ovalIdx)
        // keep only triangles whose centre lies inside the face outline
        val kept = ArrayList<Int>()
        var t = 0
        while (t < all.size) {
            val a = all[t]; val b = all[t + 1]; val c = all[t + 2]
            val cx = (face.pts[a * 2] + face.pts[b * 2] + face.pts[c * 2]) / 3f
            val cy = (face.pts[a * 2 + 1] + face.pts[b * 2 + 1] + face.pts[c * 2 + 1]) / 3f
            if (pointInPolygon(cx, cy, poly)) {
                kept.add(a); kept.add(b); kept.add(c)
            }
            t += 3
        }
        return Source(bmp, face, kept.toIntArray())
    }

    // ------------------------------------------------------------------ compositing

    /**
     * Draws the swapped face onto a copy of [frame] and returns it (the returned bitmap is
     * reused between calls). If [target] is null the frame is returned unchanged.
     */
    fun compose(frame: Bitmap, source: Source, target: Face?, expression: Float): Bitmap {
        ensureBuffers(frame.width, frame.height)
        val workBmp = work!!
        val wc = workCanvas!!
        wc.drawBitmap(frame, 0f, 0f, null)
        if (target == null) {
            resetTracking()
            return workBmp
        }

        // 1. where do the source points go in this frame?
        var dst = destinationPoints(source.face, target, expression)
        val prev = prevDst
        val faceW = max(1, target.box.width()).toFloat()
        if (prev != null && prev.size == dst.size) {
            var disp = 0f
            for (i in dst.indices) disp += Math.abs(dst[i] - prev[i])
            disp /= dst.size
            if (disp < faceW * 0.15f) { // small motion -> smooth, big jump -> cut
                val s = FloatArray(dst.size)
                for (i in dst.indices) s[i] = prev[i] * 0.35f + dst[i] * 0.65f
                dst = s
            }
        }
        prevDst = dst

        // 2. warp the source triangles into the layer
        val layerBmp = layer!!
        val lc = layerCanvas!!
        layerBmp.eraseColor(Color.TRANSPARENT)
        val sp = source.face.pts
        val tris = source.tris
        var t = 0
        while (t < tris.size) {
            val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
            t += 3
            srcTri[0] = sp[a * 2]; srcTri[1] = sp[a * 2 + 1]
            srcTri[2] = sp[b * 2]; srcTri[3] = sp[b * 2 + 1]
            srcTri[4] = sp[c * 2]; srcTri[5] = sp[c * 2 + 1]
            dstTri[0] = dst[a * 2]; dstTri[1] = dst[a * 2 + 1]
            dstTri[2] = dst[b * 2]; dstTri[3] = dst[b * 2 + 1]
            dstTri[4] = dst[c * 2]; dstTri[5] = dst[c * 2 + 1]
            if (!matrix.setPolyToPoly(srcTri, 0, dstTri, 0, 3)) continue

            // grow the clip triangle slightly so neighbouring triangles overlap (no seams)
            val gx = (dstTri[0] + dstTri[2] + dstTri[4]) / 3f
            val gy = (dstTri[1] + dstTri[3] + dstTri[5]) / 3f
            path.reset()
            for (k in 0 until 3) {
                val px = dstTri[k * 2]
                val py = dstTri[k * 2 + 1]
                val dx = px - gx
                val dy = py - gy
                val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.001f)
                val ex = px + dx / len * 0.8f
                val ey = py + dy / len * 0.8f
                if (k == 0) path.moveTo(ex, ey) else path.lineTo(ex, ey)
            }
            path.close()
            lc.save()
            lc.clipPath(path)
            lc.drawBitmap(source.bitmap, matrix, bmpPaint)
            lc.restore()
        }

        // 3. colour correction (match average skin colour of the video face)
        val gain = colourGain(frame, layerBmp, dst, target.ovalIdx)

        // 4. feathered mask following the face outline
        val maskBmp = mask!!
        maskBmp.eraseColor(Color.TRANSPARENT)
        val outline = orderedOval(dst, target.ovalIdx)
        var cx = 0f
        var cy = 0f
        for (i in 0 until outline.size / 2) { cx += outline[i * 2]; cy += outline[i * 2 + 1] }
        cx /= (outline.size / 2); cy /= (outline.size / 2)
        path.reset()
        for (i in 0 until outline.size / 2) {
            val x = cx + (outline[i * 2] - cx) * 0.95f
            val y = cy + (outline[i * 2 + 1] - cy) * 0.95f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val feather = max(3f, faceW * 0.04f)
        maskPaint.maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        maskCanvas!!.drawPath(path, maskPaint)
        lc.drawBitmap(maskBmp, 0f, 0f, dstInPaint)

        // 5. composite
        val cm = ColorMatrix(
            floatArrayOf(
                gain[0], 0f, 0f, 0f, 0f,
                0f, gain[1], 0f, 0f, 0f,
                0f, 0f, gain[2], 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        wc.drawBitmap(layerBmp, 0f, 0f, p)
        return workBmp
    }

    // ------------------------------------------------------------------ helpers

    private fun ensureBuffers(w: Int, h: Int) {
        val cur = work
        if (cur != null && cur.width == w && cur.height == h) return
        work = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        workCanvas = Canvas(work!!)
        layerCanvas = Canvas(layer!!)
        maskCanvas = Canvas(mask!!)
        resetTracking()
    }

    /** Positions for all mesh points of the swapped face in the video frame. */
    private fun destinationPoints(src: Face, tgt: Face, expression: Float): FloatArray {
        val out = tgt.pts.copyOf()
        if (expression <= 0.001f || src.count != tgt.count) return out

        // similarity transform (scale + rotation + translation) src -> tgt, fitted on the outline
        val idx = tgt.ovalIdx
        if (idx.size < 3) return out
        var sx = 0.0; var sy = 0.0; var tx = 0.0; var ty = 0.0
        for (i in idx) {
            sx += src.pts[i * 2]; sy += src.pts[i * 2 + 1]
            tx += tgt.pts[i * 2]; ty += tgt.pts[i * 2 + 1]
        }
        sx /= idx.size; sy /= idx.size; tx /= idx.size; ty /= idx.size
        var num = 0.0 // sum conj(s) * t  (real, imag)
        var numI = 0.0
        var den = 0.0
        for (i in idx) {
            val a = src.pts[i * 2] - sx
            val b = src.pts[i * 2 + 1] - sy
            val c = tgt.pts[i * 2] - tx
            val d = tgt.pts[i * 2 + 1] - ty
            num += a * c + b * d
            numI += a * d - b * c
            den += a * a + b * b
        }
        if (den < 1e-6) return out
        val zr = num / den
        val zi = numI / den

        val onOutline = BooleanArray(tgt.count)
        for (i in idx) onOutline[i] = true
        for (i in 0 until tgt.count) {
            if (onOutline[i]) continue // outline always follows the video
            val a = src.pts[i * 2] - sx
            val b = src.pts[i * 2 + 1] - sy
            val ax = (zr * a - zi * b + tx).toFloat()
            val ay = (zr * b + zi * a + ty).toFloat()
            out[i * 2] = tgt.pts[i * 2] + (ax - tgt.pts[i * 2]) * expression
            out[i * 2 + 1] = tgt.pts[i * 2 + 1] + (ay - tgt.pts[i * 2 + 1]) * expression
        }
        return out
    }

    /** Per-channel gain that makes the warped face match the colour of the original face. */
    private fun colourGain(frame: Bitmap, layer: Bitmap, dst: FloatArray, oval: IntArray): FloatArray {
        val onOutline = HashSet<Int>()
        for (i in oval) onOutline.add(i)
        var tr = 0L; var tg = 0L; var tb = 0L
        var lr = 0L; var lg = 0L; var lb = 0L
        var n = 0
        val count = dst.size / 2
        for (i in 0 until count) {
            if (i in onOutline) continue
            val x = dst[i * 2].toInt()
            val y = dst[i * 2 + 1].toInt()
            if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) continue
            val l = layer.getPixel(x, y)
            if (Color.alpha(l) < 200) continue
            val f = frame.getPixel(x, y)
            tr += Color.red(f); tg += Color.green(f); tb += Color.blue(f)
            lr += Color.red(l); lg += Color.green(l); lb += Color.blue(l)
            n++
        }
        if (n < 20) return prevGain
        val gain = floatArrayOf(
            (tr.toFloat() / max(1L, lr)).coerceIn(0.75f, 1.33f),
            (tg.toFloat() / max(1L, lg)).coerceIn(0.75f, 1.33f),
            (tb.toFloat() / max(1L, lb)).coerceIn(0.75f, 1.33f)
        )
        for (k in 0 until 3) gain[k] = prevGain[k] * 0.5f + gain[k] * 0.5f
        prevGain = gain
        return gain
    }

    /** Outline points as a polygon, sorted by angle so it is always a valid loop. */
    private fun orderedOval(pts: FloatArray, idx: IntArray): FloatArray {
        var cx = 0f
        var cy = 0f
        for (i in idx) { cx += pts[i * 2]; cy += pts[i * 2 + 1] }
        cx /= max(1, idx.size); cy /= max(1, idx.size)
        val sorted = idx.sortedBy { atan2((pts[it * 2 + 1] - cy).toDouble(), (pts[it * 2] - cx).toDouble()) }
        val out = FloatArray(sorted.size * 2)
        for ((k, i) in sorted.withIndex()) {
            out[k * 2] = pts[i * 2]
            out[k * 2 + 1] = pts[i * 2 + 1]
        }
        return out
    }

    private fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun close() {
        detector.close()
    }
}
