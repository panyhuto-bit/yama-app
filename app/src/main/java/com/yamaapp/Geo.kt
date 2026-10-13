package com.yamaapp

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 距離計算などの幾何ユーティリティ */
object Geo {
    private const val R = 6371000.0
    private val M_PER_DEG = Math.toRadians(1.0) * R

    /** 2点間の距離 [m] */
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(sqrt(min(1.0, a)))
    }

    /**
     * 点Pから線分ABまでの距離 [m] と、線分上の位置 t (0〜1)
     * 数km程度なので平面近似で十分
     */
    fun pointToSegment(
        plat: Double, plon: Double,
        alat: Double, alon: Double,
        blat: Double, blon: Double
    ): Pair<Double, Double> {
        val k = cos(Math.toRadians(plat))
        val ax = (alon - plon) * k
        val ay = alat - plat
        val bx = (blon - plon) * k
        val by = blat - plat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0) -(ax * dx + ay * dy) / len2 else 0.0
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return Pair(sqrt(cx * cx + cy * cy) * M_PER_DEG, t)
    }

    /**
     * 元の点を残したまま、step [m] より長い区間に点を補う。
     * 入力・出力とも [lat, lon, ele]（ele は NaN 可）
     */
    fun densify(pts: List<DoubleArray>, step: Double): List<DoubleArray> {
        if (pts.size < 2) return pts
        val out = ArrayList<DoubleArray>()
        out.add(pts[0])
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val d = dist(a[0], a[1], b[0], b[1])
            val n = ceil(d / step).toInt()
            for (j in 1 until n) {
                val f = j.toDouble() / n
                out.add(
                    doubleArrayOf(
                        a[0] + (b[0] - a[0]) * f,
                        a[1] + (b[1] - a[1]) * f,
                        a[2] + (b[2] - a[2]) * f
                    )
                )
            }
            out.add(b)
        }
        return out
    }

    /** 細かすぎる点（GPXなど）を間引く */
    fun thin(pts: List<DoubleArray>, minStep: Double): List<DoubleArray> {
        if (pts.size < 3) return pts
        val out = ArrayList<DoubleArray>()
        out.add(pts[0])
        for (i in 1 until pts.size - 1) {
            val last = out.last()
            if (dist(last[0], last[1], pts[i][0], pts[i][1]) >= minStep) out.add(pts[i])
        }
        out.add(pts.last())
        return out
    }
}
