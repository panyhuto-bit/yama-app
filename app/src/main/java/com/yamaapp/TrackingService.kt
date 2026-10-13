package com.yamaapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.util.GeoPoint
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/** 画面に出す情報 */
data class TrackInfo(
    val recording: Boolean = false,
    val startTime: Long = 0L,
    val walked: Double = 0.0,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val accuracy: Float = 0f,
    val offRoute: Double = Double.NaN,
    val progress: Double = Double.NaN,
    val remainDist: Double = Double.NaN,
    val remainMin: Double = Double.NaN,
    val pace: Double = Double.NaN,
    val eta: Long = 0L,
    val stampMsg: String? = null,
    val version: Long = 0L
)

/** サービスと画面で共有する状態 */
object TrackState {
    val info = MutableStateFlow(TrackInfo())
    val trackPts: MutableList<GeoPoint> = Collections.synchronizedList(ArrayList())
    @Volatile var route: Route? = null
}

class TrackingService : Service() {

    companion object {
        const val OFF_ROUTE_M = 30.0      // これ以上離れたら振動
        const val BACK_ON_ROUTE_M = 20.0  // これ未満に戻ったら解除
        const val SUMMIT_M = 50.0         // 山頂スタンプの半径
        private const val CH_TRACK = "track"
        private const val CH_ALERT = "alert"
        private const val ID_TRACK = 1
        private const val ID_ALERT = 2
        private const val ID_STAMP = 3
    }

    private var lm: LocationManager? = null
    private var writer: BufferedWriter? = null
    private var startTime = 0L
    private var walked = 0.0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var progressS = 0.0
    private var alerted = false
    private var lastVibe = 0L
    private var mountains: List<Mountain> = emptyList()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, ID_TRACK, buildNotification("記録を始めました"), type)

        if (writer == null) start()
        return START_STICKY
    }

    private fun start() {
        if (TrackState.route == null) TrackState.route = Storage.loadActive(this)
        mountains = Storage.mountains(this)
        startTime = System.currentTimeMillis()
        walked = 0.0
        progressS = 0.0
        alerted = false
        lastLat = Double.NaN
        lastLon = Double.NaN
        TrackState.trackPts.clear()

        val name = "track_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".csv"
        writer = File(Storage.tracksDir(this), name).bufferedWriter()

        TrackState.info.value = TrackInfo(recording = true, startTime = startTime, version = 1)

        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 3f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_ALERT)
        TrackState.info.value = TrackState.info.value.copy(recording = false, version = TrackState.info.value.version + 1)
        super.onDestroy()
    }

    private fun onLocation(loc: Location) {
        if (loc.accuracy > 60f) return  // 精度が悪すぎる点は捨てる
        val now = System.currentTimeMillis()
        val lat = loc.latitude
        val lon = loc.longitude

        try {
            writer?.write("$now,$lat,$lon,${loc.altitude},${loc.accuracy}\n")
            writer?.flush()
        } catch (_: Exception) {}

        if (!lastLat.isNaN()) {
            val d = Geo.dist(lastLat, lastLon, lat, lon)
            if (d > loc.accuracy / 2) walked += d  // GPSのブレで距離が増えすぎないように
        }
        lastLat = lat
        lastLon = lon
        TrackState.trackPts.add(GeoPoint(lat, lon))

        var info = TrackState.info.value.copy(
            recording = true, walked = walked, lat = lat, lon = lon, accuracy = loc.accuracy,
            version = TrackState.info.value.version + 1
        )

        // ---- ルートとの比較 ----
        val r = TrackState.route
        var notifText = "歩いた距離 %.1f km".format(walked / 1000)
        if (r != null && r.n >= 2) {
            val (off, s) = locate(r, lat, lon)
            progressS = s
            val elapsedMin = (now - startTime) / 60000.0
            val stdDone = r.stdAt(s)
            val pace = if (stdDone >= 10) (elapsedMin / stdDone).coerceIn(0.5, 2.5) else 1.0
            val remainMin = (r.totalStd - stdDone) * pace
            info = info.copy(
                offRoute = off, progress = s, remainDist = r.totalDist - s,
                remainMin = remainMin, pace = pace, eta = now + (remainMin * 60000).toLong()
            )
            notifText = "残り %s（到着 %s）".format(fmtMin(remainMin), fmtClock(info.eta))

            if (loc.accuracy <= 40f) {
                if (off > OFF_ROUTE_M) {
                    if (!alerted || now - lastVibe > 60_000) {
                        vibrate(longArrayOf(0, 700, 250, 700, 250, 700))
                        alert(ID_ALERT, "ルートから外れています", "ルートまで約 ${off.toInt()} m")
                        alerted = true
                        lastVibe = now
                    }
                } else if (alerted && off < BACK_ON_ROUTE_M) {
                    alerted = false
                    vibrate(longArrayOf(0, 150))
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_ALERT)
                }
            }
        }

        // ---- 山頂スタンプ ----
        for (m in mountains) {
            if (Geo.dist(lat, lon, m.lat, m.lon) <= SUMMIT_M && Storage.stampIfNew(this, m.name)) {
                vibrate(longArrayOf(0, 100, 100, 100, 100, 400))
                alert(ID_STAMP, "${m.name} 登頂スタンプ！", Storage.today())
                info = info.copy(stampMsg = "${m.name} のスタンプを押しました")
            }
        }

        TrackState.info.value = info
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ID_TRACK, buildNotification(notifText))
    }

    /** ルート上で一番近い位置。戻り値は (離れている距離 m, ルート上の距離 s m) */
    private fun locate(r: Route, lat: Double, lon: Double): Pair<Double, Double> {
        fun search(sMin: Double, sMax: Double): Pair<Double, Double> {
            var best = Double.MAX_VALUE
            var bestS = 0.0
            for (i in 0 until r.n - 1) {
                if (r.cumDist[i + 1] < sMin || r.cumDist[i] > sMax) continue
                val (d, t) = Geo.pointToSegment(lat, lon, r.lat[i], r.lon[i], r.lat[i + 1], r.lon[i + 1])
                if (d < best) {
                    best = d
                    bestS = r.cumDist[i] + t * (r.cumDist[i + 1] - r.cumDist[i])
                }
            }
            return Pair(best, bestS)
        }
        // 周回コースで出発点と終点を取り違えないよう、まず今いる付近だけを探す
        var res = search(progressS - 300, progressS + 1500)
        if (res.first > 80) {
            val g = search(-1.0, Double.MAX_VALUE)
            if (g.first < res.first - 20) res = g
        }
        return res
    }

    private fun vibrate(pattern: LongArray) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_TRACK, "記録中", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "ルート逸脱・スタンプ", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_TRACK)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("やまナビ 記録中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun alert(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (_: SecurityException) {}
    }
}

fun fmtMin(min: Double): String {
    if (min.isNaN()) return "-"
    val m = Math.round(min).toInt()
    return "%d:%02d".format(m / 60, m % 60)
}

fun fmtClock(t: Long): String =
    if (t <= 0L) "-" else SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(t))
