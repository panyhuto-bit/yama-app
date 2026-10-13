package com.yamaapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Mountain(val name: String, val lat: Double, val lon: Double)

/** ルート・記録・山・スタンプの保存 */
object Storage {
    private const val PREFS = "yama"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- ルート ----------
    fun routesDir(c: Context) = File(c.filesDir, "routes").apply { mkdirs() }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    fun listRoutes(c: Context): List<String> =
        routesDir(c).listFiles()?.filter { it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(".json") } ?: emptyList()

    fun saveRoute(c: Context, r: Route) {
        File(routesDir(c), safe(r.name) + ".json").writeText(r.toJson())
    }

    fun loadRoute(c: Context, name: String): Route? = try {
        Route.fromJson(File(routesDir(c), safe(name) + ".json").readText())
    } catch (e: Exception) {
        null
    }

    fun deleteRoute(c: Context, name: String) {
        File(routesDir(c), safe(name) + ".json").delete()
    }

    fun setActive(c: Context, name: String?) {
        prefs(c).edit().putString("active", name).apply()
    }

    fun loadActive(c: Context): Route? {
        val n = prefs(c).getString("active", null) ?: return null
        return loadRoute(c, n)
    }

    // ---------- 記録 ----------
    fun tracksDir(c: Context) = File(c.filesDir, "tracks").apply { mkdirs() }

    fun listTracks(c: Context): List<File> =
        tracksDir(c).listFiles()?.filter { it.name.endsWith(".csv") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** 記録CSV（time,lat,lon,alt,acc）を [lat, lon, NaN] の列にする */
    fun readTrack(f: File): List<DoubleArray> =
        f.readLines().mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 3) null
            else {
                val la = p[1].toDoubleOrNull()
                val lo = p[2].toDoubleOrNull()
                if (la == null || lo == null) null else doubleArrayOf(la, lo, Double.NaN)
            }
        }

    fun trackToGpx(f: File, out: File) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"yama-app\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>")
            .append(f.nameWithoutExtension).append("</name><trkseg>\n")
        f.readLines().forEach { line ->
            val p = line.split(",")
            if (p.size >= 4) {
                sb.append("<trkpt lat=\"").append(p[1]).append("\" lon=\"").append(p[2]).append("\">")
                sb.append("<ele>").append(p[3]).append("</ele>")
                p[0].toLongOrNull()?.let { sb.append("<time>").append(iso.format(Date(it))).append("</time>") }
                sb.append("</trkpt>\n")
            }
        }
        sb.append("</trkseg></trk></gpx>\n")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
    }

    // ---------- 山とスタンプ ----------
    private val defaultMountains = listOf(
        Mountain("韓国岳", 31.934085, 130.861585)
    )

    fun mountains(c: Context): List<Mountain> {
        val txt = prefs(c).getString("mountains", null) ?: return defaultMountains
        return try {
            val a = JSONArray(txt)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Mountain(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: Exception) {
            defaultMountains
        }
    }

    fun addMountain(c: Context, m: Mountain) {
        val list = mountains(c).filter { it.name != m.name } + m
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
        prefs(c).edit().putString("mountains", a.toString()).apply()
    }

    /** 山名 → 登頂日のリスト */
    fun stamps(c: Context): Map<String, List<String>> {
        val txt = prefs(c).getString("stamps", null) ?: return emptyMap()
        return try {
            val o = JSONObject(txt)
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                (0 until a.length()).map { a.getString(it) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun today(): String = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date())

    /** 今日まだ押していなければ押して true を返す */
    fun stampIfNew(c: Context, name: String): Boolean {
        val all = stamps(c).toMutableMap()
        val dates = all[name] ?: emptyList()
        val t = today()
        if (t in dates) return false
        all[name] = dates + t
        val o = JSONObject()
        all.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs(c).edit().putString("stamps", o.toString()).apply()
        return true
    }
}

/** 国土地理院の標高API（ルート作成時のみ、通信が必要） */
object Elevation {
    fun fetch(lat: Double, lon: Double): Double {
        val url = URL(
            "https://cyberjapandata2.gsi.go.jp/general/dem/scripts/getelevation.php" +
                "?lon=$lon&lat=$lat&outtype=JSON"
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        return try {
            val txt = c.inputStream.bufferedReader().readText()
            val v = JSONObject(txt).opt("elevation")
            when (v) {
                is Number -> v.toDouble()
                else -> v?.toString()?.toDoubleOrNull() ?: Double.NaN
            }
        } catch (e: Exception) {
            Double.NaN
        } finally {
            c.disconnect()
        }
    }
}
