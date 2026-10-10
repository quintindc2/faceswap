package com.example.faceswap

import kotlin.math.max
import kotlin.math.min

/** Small Bowyer-Watson Delaunay triangulation. Fine for a few hundred points. */
object Delaunay {

    private class Tri(val a: Int, val b: Int, val c: Int, xs: DoubleArray, ys: DoubleArray) {
        var cx = 0.0
        var cy = 0.0
        var r2 = -1.0 // negative = degenerate, never "contains" anything

        init {
            val ax = xs[a]; val ay = ys[a]
            val bx = xs[b]; val by = ys[b]
            val cx0 = xs[c]; val cy0 = ys[c]
            val d = 2.0 * (ax * (by - cy0) + bx * (cy0 - ay) + cx0 * (ay - by))
            if (Math.abs(d) > 1e-12) {
                val a2 = ax * ax + ay * ay
                val b2 = bx * bx + by * by
                val c2 = cx0 * cx0 + cy0 * cy0
                cx = (a2 * (by - cy0) + b2 * (cy0 - ay) + c2 * (ay - by)) / d
                cy = (a2 * (cx0 - bx) + b2 * (ax - cx0) + c2 * (bx - ax)) / d
                val dx = ax - cx
                val dy = ay - cy
                r2 = dx * dx + dy * dy
            }
        }

        fun circumcircleContains(x: Double, y: Double): Boolean {
            if (r2 < 0) return false
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= r2
        }
    }

    /**
     * @param pts interleaved x,y floats
     * @return flat array of triangle vertex indices (3 per triangle)
     */
    fun triangulate(pts: FloatArray): IntArray {
        val n = pts.size / 2
        val xs = DoubleArray(n + 3)
        val ys = DoubleArray(n + 3)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until n) {
            xs[i] = pts[i * 2].toDouble()
            ys[i] = pts[i * 2 + 1].toDouble()
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        }
        val d = max(max(maxX - minX, maxY - minY), 1.0) * 20.0
        val midX = (minX + maxX) / 2
        val midY = (minY + maxY) / 2
        xs[n] = midX - d; ys[n] = midY - d
        xs[n + 1] = midX; ys[n + 1] = midY + d
        xs[n + 2] = midX + d; ys[n + 2] = midY - d

        var tris = ArrayList<Tri>()
        tris.add(Tri(n, n + 1, n + 2, xs, ys))

        for (i in 0 until n) {
            val px = xs[i]
            val py = ys[i]
            val edgeCount = HashMap<Long, Int>()
            val kept = ArrayList<Tri>(tris.size)
            for (t in tris) {
                if (t.circumcircleContains(px, py)) {
                    for (e in arrayOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                        val lo = min(e.first, e.second).toLong()
                        val hi = max(e.first, e.second).toLong()
                        val key = (lo shl 32) or hi
                        edgeCount[key] = (edgeCount[key] ?: 0) + 1
                    }
                } else {
                    kept.add(t)
                }
            }
            for ((key, count) in edgeCount) {
                if (count == 1) {
                    val lo = (key shr 32).toInt()
                    val hi = (key and 0xFFFFFFFFL).toInt()
                    kept.add(Tri(lo, hi, i, xs, ys))
                }
            }
            tris = kept
        }

        val out = ArrayList<Int>()
        for (t in tris) {
            if (t.a < n && t.b < n && t.c < n) {
                out.add(t.a); out.add(t.b); out.add(t.c)
            }
        }
        return out.toIntArray()
    }
}
