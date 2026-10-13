package com.yamaapp

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * 標準タイムの計算式
 * 時間[分] = 27.8 × 水平距離[km] + 7.2 × 登り[100 m] + 3.3 × 下り[100 m]
 * （韓国岳のYAMAP行動時間グラフに合わせて決めた係数）
 */
object TimeModel {
    var perKm = 27.8
    var perUp100 = 7.2
    var perDown100 = 3.3

    fun minutes(distM: Double, upM: Double, downM: Double): Double =
        perKm * distM / 1000.0 + perUp100 * upM / 100.0 + perDown100 * downM / 100.0
}

/** ルート（点列＋標高）と、そこから計算した累積距離・累積標準タイム */
class Route(
    val name: String,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val ele: DoubleArray
) {
    val n = lat.size
    val cumDist = DoubleArray(n)
    val cumStd = DoubleArray(n)
    var totalUp = 0.0
        private set
    var totalDown = 0.0
        private set

    init {
        // 標高は3点移動平均でならしてから登り・下りを数える
        val es = DoubleArray(n) { i ->
            var s = 0.0
            var c = 0
            for (j in i - 1..i + 1) {
                if (j in 0 until n && !ele[j].isNaN()) { s += ele[j]; c++ }
            }
            if (c > 0) s / c else Double.NaN
        }
        for (i in 1 until n) {
            val d = Geo.dist(lat[i - 1], lon[i - 1], lat[i], lon[i])
            var dz = es[i] - es[i - 1]
            if (dz.isNaN()) dz = 0.0
            val up = if (dz > 0) dz else 0.0
            val down = if (dz < 0) -dz else 0.0
            totalUp += up
            totalDown += down
            cumDist[i] = cumDist[i - 1] + d
            cumStd[i] = cumStd[i - 1] + TimeModel.minutes(d, up, down)
        }
    }

    val totalDist: Double get() = if (n > 0) cumDist[n - 1] else 0.0
    val totalStd: Double get() = if (n > 0) cumStd[n - 1] else 0.0
    val hasElevation: Boolean get() = ele.any { !it.isNaN() }

    /** ルート上の距離 s [m] 地点までの標準タイム [分] */
    fun stdAt(s: Double): Double {
        if (n < 2) return 0.0
        if (s <= 0) return 0.0
        if (s >= totalDist) return totalStd
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumDist[mid] <= s) lo = mid else hi = mid
        }
        val seg = cumDist[hi] - cumDist[lo]
        val f = if (seg > 0) (s - cumDist[lo]) / seg else 0.0
        return cumStd[lo] + f * (cumStd[hi] - cumStd[lo])
    }

    fun toJson(): String {
        val pts = JSONArray()
        for (i in 0 until n) {
            pts.put(JSONArray().put(lat[i]).put(lon[i]).put(if (ele[i].isNaN()) JSONObject.NULL else ele[i]))
        }
        return JSONObject().put("name", name).put("pts", pts).toString()
    }

    companion object {
        fun fromPoints(name: String, pts: List<DoubleArray>): Route =
            Route(
                name,
                DoubleArray(pts.size) { pts[it][0] },
                DoubleArray(pts.size) { pts[it][1] },
                DoubleArray(pts.size) { pts[it][2] }
            )

        fun fromJson(txt: String): Route {
            val o = JSONObject(txt)
            val a = o.getJSONArray("pts")
            val pts = ArrayList<DoubleArray>()
            for (i in 0 until a.length()) {
                val p = a.getJSONArray(i)
                val e = if (p.isNull(2)) Double.NaN else p.getDouble(2)
                pts.add(doubleArrayOf(p.getDouble(0), p.getDouble(1), e))
            }
            return fromPoints(o.getString("name"), pts)
        }

        /** GPXの trkpt / rtept を読む。戻り値は [lat, lon, ele] の列 */
        fun parseGpx(input: InputStream): List<DoubleArray> {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(input, null)
            val pts = ArrayList<DoubleArray>()
            var cur: DoubleArray? = null
            var inEle = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        if (tag == "trkpt" || tag == "rtept") {
                            val la = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lo = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            cur = if (la != null && lo != null) doubleArrayOf(la, lo, Double.NaN) else null
                        } else if (tag == "ele" && cur != null) {
                            inEle = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inEle) cur?.set(2, parser.text.trim().toDoubleOrNull() ?: Double.NaN)
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name
                        if (tag == "ele") inEle = false
                        if ((tag == "trkpt" || tag == "rtept") && cur != null) {
                            pts.add(cur)
                            cur = null
                        }
                    }
                }
                ev = parser.next()
            }
            return pts
        }
    }
}
