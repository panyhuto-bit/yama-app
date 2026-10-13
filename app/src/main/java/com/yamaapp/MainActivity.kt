package com.yamaapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var btnRec: Button
    private lateinit var editBar: View
    private lateinit var mainBar: View

    private val routeLine by lazy { Polyline(map).apply { outlinePaint.color = Color.rgb(140, 40, 200); outlinePaint.strokeWidth = 12f } }
    private val trackLine by lazy { Polyline(map).apply { outlinePaint.color = Color.rgb(20, 110, 230); outlinePaint.strokeWidth = 8f } }
    private val editLine by lazy { Polyline(map).apply { outlinePaint.color = Color.rgb(230, 60, 40); outlinePaint.strokeWidth = 9f } }
    private val editMarkers = ArrayList<Marker>()
    private val summitMarkers = ArrayList<Marker>()
    private lateinit var myLoc: MyLocationNewOverlay

    private var editMode = false
    private val editPts = ArrayList<GeoPoint>()

    private val gsi = XYTileSource(
        "GSI_STD", 5, 18, 256, ".png",
        arrayOf("https://cyberjapandata.gsi.go.jp/xyz/std/")
    )

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) myLoc.enableMyLocation()
    }

    private val gpxLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importGpx(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            val base = File(filesDir, "osmdroid")
            setOsmdroidBasePath(base)
            setOsmdroidTileCache(File(base, "tiles"))
            setExpirationOverrideDuration(1000L * 60 * 60 * 24 * 365)  // 保存した地図を1年間使う
        }
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        info = findViewById(R.id.info)
        btnRec = findViewById(R.id.btnRec)
        editBar = findViewById(R.id.editBar)
        mainBar = findViewById(R.id.mainBar)

        map.setTileSource(gsi)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(31.934085, 130.861585))

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!editMode) return false
                editPts.add(p)
                redrawEdit()
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                if (editMode) return false
                askSummit(p)
                return true
            }
        }))
        map.overlays.add(routeLine)
        map.overlays.add(trackLine)
        map.overlays.add(editLine)
        myLoc = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        map.overlays.add(myLoc)

        // ---- ボタン ----
        btnRec.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.btnEdit).setOnClickListener { setEditMode(true) }
        findViewById<Button>(R.id.btnRoute).setOnClickListener { showRouteMenu(it) }
        findViewById<Button>(R.id.btnHere).setOnClickListener {
            myLoc.myLocation?.let { map.controller.animateTo(it) } ?: toast("現在地を取得中です")
            myLoc.enableFollowLocation()
        }
        findViewById<Button>(R.id.btnCache).setOnClickListener { downloadMap() }
        findViewById<Button>(R.id.btnStamp).setOnClickListener {
            startActivity(Intent(this, StampsActivity::class.java))
        }
        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            if (editPts.isNotEmpty()) editPts.removeAt(editPts.size - 1)
            redrawEdit()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { editPts.clear(); redrawEdit() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { setEditMode(false) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveEdit() }

        // ---- 保存済みのルート ----
        if (TrackState.route == null) TrackState.route = Storage.loadActive(this)
        showRoute(TrackState.route, center = true)
        drawSummits()

        if (hasLocation()) myLoc.enableMyLocation() else requestPerms()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackState.info.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        drawSummits()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    // ================= 表示 =================

    private fun render(i: TrackInfo) {
        btnRec.text = if (i.recording) "■ 記録停止" else "● 記録開始"
        val pts = synchronized(TrackState.trackPts) { ArrayList(TrackState.trackPts) }
        trackLine.setPoints(pts)

        val r = TrackState.route
        val sb = StringBuilder()
        if (r != null) {
            sb.append("ルート：").append(r.name).append("\n")
            sb.append("%.1f km　登り %d m　下り %d m　標準 %s".format(
                r.totalDist / 1000, r.totalUp.toInt(), r.totalDown.toInt(), fmtMin(r.totalStd)))
            if (!r.hasElevation) sb.append("\n※標高なし（時間は距離だけで計算）")
        } else {
            sb.append("ルート未設定（「ルート作成」か「ルート ▼」から）")
        }
        if (i.recording) {
            val el = (System.currentTimeMillis() - i.startTime) / 60000.0
            sb.append("\n\n経過 ").append(fmtMin(el))
            sb.append("　歩いた %.2f km".format(i.walked / 1000))
            if (!i.lat.isNaN()) sb.append("　精度 %d m".format(i.accuracy.toInt()))
            if (r != null && !i.offRoute.isNaN()) {
                val off = i.offRoute.toInt()
                sb.append("\n")
                if (i.offRoute > TrackingService.OFF_ROUTE_M) sb.append("⚠ ルートから %d m 外れています".format(off))
                else sb.append("ルート上（ずれ %d m）".format(off))
                sb.append("\n残り %.1f km　残り時間 %s　到着 %s".format(
                    i.remainDist / 1000, fmtMin(i.remainMin), fmtClock(i.eta)))
                sb.append("\nペース 標準の %d%%".format((i.pace * 100).toInt()))
            } else if (i.lat.isNaN()) {
                sb.append("\nGPSを探しています…")
            }
        }
        i.stampMsg?.let { sb.append("\n★ ").append(it) }
        info.text = sb.toString()
        info.setBackgroundColor(
            if (i.recording && i.offRoute > TrackingService.OFF_ROUTE_M) Color.argb(230, 255, 210, 200)
            else Color.argb(220, 255, 255, 255)
        )
        map.invalidate()
    }

    private fun showRoute(r: Route?, center: Boolean) {
        if (r == null) {
            routeLine.setPoints(emptyList())
        } else {
            val pts = (0 until r.n).map { GeoPoint(r.lat[it], r.lon[it]) }
            routeLine.setPoints(pts)
            if (center && pts.size >= 2) {
                map.post {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false)
                }
            }
        }
        render(TrackState.info.value)
    }

    private fun drawSummits() {
        if (!::map.isInitialized) return
        summitMarkers.forEach { map.overlays.remove(it) }
        summitMarkers.clear()
        val stamps = Storage.stamps(this)
        for (m in Storage.mountains(this)) {
            val mk = Marker(map)
            mk.position = GeoPoint(m.lat, m.lon)
            mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            val got = stamps[m.name]?.isNotEmpty() == true
            mk.title = (if (got) "★ " else "☆ ") + m.name
            mk.snippet = if (got) "登頂 " + stamps[m.name]!!.joinToString(", ") else "まだスタンプなし"
            map.overlays.add(mk)
            summitMarkers.add(mk)
        }
        map.invalidate()
    }

    // ================= 記録 =================

    private fun toggleRecording() {
        if (TrackState.info.value.recording) {
            AlertDialog.Builder(this)
                .setMessage("記録を止めますか？")
                .setPositiveButton("止める") { _, _ ->
                    stopService(Intent(this, TrackingService::class.java))
                }
                .setNegativeButton("続ける", null)
                .show()
            return
        }
        if (!hasLocation()) { requestPerms(); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        myLoc.enableFollowLocation()
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(p.toTypedArray())
    }

    // ================= ルート作成 =================

    private fun setEditMode(on: Boolean) {
        if (on && TrackState.info.value.recording) {
            toast("記録中はルートを作れません"); return
        }
        editMode = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        mainBar.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            myLoc.disableFollowLocation()
            toast("登山道に沿って地図をタップしてください")
        } else {
            editPts.clear()
            redrawEdit()
        }
    }

    private fun redrawEdit() {
        editLine.setPoints(ArrayList(editPts))
        editMarkers.forEach { map.overlays.remove(it) }
        editMarkers.clear()
        if (editPts.isNotEmpty()) {
            // 始点と終点だけ印を付ける
            listOf(editPts.first(), editPts.last()).distinct().forEach { p ->
                val mk = Marker(map)
                mk.position = p
                mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mk.setInfoWindow(null)
                map.overlays.add(mk)
                editMarkers.add(mk)
            }
        }
        var d = 0.0
        for (i in 1 until editPts.size) d += Geo.dist(
            editPts[i - 1].latitude, editPts[i - 1].longitude, editPts[i].latitude, editPts[i].longitude)
        info.text = "ルート作成中：点 %d 個　%.2f km\n登山道の点線に沿って細かくタップ".format(editPts.size, d / 1000)
        map.invalidate()
    }

    private fun saveEdit() {
        if (editPts.size < 2) { toast("2点以上タップしてください"); return }
        askName("ルート名") { name ->
            val pts = editPts.map { doubleArrayOf(it.latitude, it.longitude, Double.NaN) }
            buildAndSave(name, pts) { setEditMode(false) }
        }
    }

    /** 点列を40 m間隔にして、標高を国土地理院から取ってルートにする */
    private fun buildAndSave(name: String, raw: List<DoubleArray>, onDone: () -> Unit = {}) {
        val pts = Geo.densify(Geo.thin(raw, 10.0), 40.0)
        val needEle = pts.any { it[2].isNaN() }
        val msg = TextView(this).apply { setPadding(60, 40, 60, 20); text = "準備中…" }
        val dlg = AlertDialog.Builder(this).setTitle("ルートを作成中").setView(msg).setCancelable(false).show()
        lifecycleScope.launch {
            var fail = 0
            if (needEle) {
                withContext(Dispatchers.IO) {
                    pts.forEachIndexed { i, p ->
                        if (p[2].isNaN()) {
                            p[2] = Elevation.fetch(p[0], p[1])
                            if (p[2].isNaN()) fail++
                        }
                        if (i % 5 == 0) withContext(Dispatchers.Main) { msg.text = "標高を取得中 ${i + 1} / ${pts.size}" }
                    }
                }
            }
            val r = Route.fromPoints(name, pts)
            Storage.saveRoute(this@MainActivity, r)
            activate(r)
            dlg.dismiss()
            onDone()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("ルートを保存しました")
                .setMessage(
                    "%s\n距離 %.1f km\n登り %d m　下り %d m\n標準タイム %s".format(
                        r.name, r.totalDist / 1000, r.totalUp.toInt(), r.totalDown.toInt(), fmtMin(r.totalStd)) +
                        (if (fail > 0) "\n\n※標高が取れなかった点が $fail 個あります（通信を確認）" else "") +
                        "\n\n圏外に備えて「地図保存」もしておいてください"
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun activate(r: Route?) {
        TrackState.route = r
        Storage.setActive(this, r?.name)
        showRoute(r, center = true)
    }

    // ================= ルートメニュー =================

    private fun showRouteMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "保存したルートを選ぶ")
        pm.menu.add(0, 2, 1, "GPXファイルを読み込む")
        pm.menu.add(0, 3, 2, "前回の記録をルートにする")
        pm.menu.add(0, 4, 3, "前回の記録をGPXで書き出す")
        pm.menu.add(0, 5, 4, "ルートを消す（表示を外す）")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> chooseRoute()
                2 -> gpxLauncher.launch(arrayOf("*/*"))
                3 -> trackToRoute()
                4 -> exportTrack()
                5 -> activate(null)
            }
            true
        }
        pm.show()
    }

    private fun chooseRoute() {
        val names = Storage.listRoutes(this)
        if (names.isEmpty()) { toast("保存したルートはまだありません"); return }
        AlertDialog.Builder(this)
            .setTitle("ルートを選ぶ（長押しで削除）")
            .setItems(names.toTypedArray()) { _, w ->
                activate(Storage.loadRoute(this, names[w]))
            }
            .show().listView.setOnItemLongClickListener { _, _, pos, _ ->
                AlertDialog.Builder(this)
                    .setMessage("「${names[pos]}」を削除しますか？")
                    .setPositiveButton("削除") { _, _ -> Storage.deleteRoute(this, names[pos]); toast("削除しました") }
                    .setNegativeButton("やめる", null).show()
                true
            }
    }

    private fun importGpx(uri: Uri) {
        lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { Route.parseGpx(it) } ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (pts.size < 2) { toast("GPXからルートを読めませんでした"); return@launch }
            askName("ルート名") { name -> buildAndSave(name, pts) }
        }
    }

    private fun trackToRoute() {
        val f = Storage.listTracks(this).firstOrNull { Storage.readTrack(it).size >= 2 }
        if (f == null) { toast("記録がまだありません"); return }
        askName("ルート名（${f.nameWithoutExtension}）") { name ->
            buildAndSave(name, Storage.readTrack(f))
        }
    }

    private fun exportTrack() {
        val f = Storage.listTracks(this).firstOrNull()
        if (f == null) { toast("記録がまだありません"); return }
        val out = File(File(cacheDir, "export"), f.nameWithoutExtension + ".gpx")
        Storage.trackToGpx(f, out)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "GPXを送る"))
    }

    // ================= 地図の保存（圏外対策） =================

    private fun downloadMap() {
        val r = TrackState.route
        if (r == null || r.n < 2) { toast("先にルートを設定してください"); return }
        val pts = (0 until r.n).map { GeoPoint(r.lat[it], r.lon[it]) }
        val bb = BoundingBox.fromGeoPoints(pts).increaseByScale(1.6f)
        val cm = CacheManager(map)
        val count = cm.possibleTilesInArea(bb, 12, 17)
        AlertDialog.Builder(this)
            .setTitle("ルート周辺の地図を保存")
            .setMessage("約 $count 枚の地図画像を保存します。Wi-Fiでの実行がおすすめです。")
            .setPositiveButton("保存") { _, _ ->
                cm.downloadAreaAsync(this, bb, 12, 17, object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() { toast("地図を保存しました。圏外でも表示できます") }
                    override fun onTaskFailed(errors: Int) { toast("一部保存できませんでした（$errors 枚）。もう一度試してください") }
                    override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                    override fun downloadStarted() {}
                    override fun setPossibleTilesInArea(total: Int) {}
                })
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    // ================= 山頂の登録 =================

    private fun askSummit(p: GeoPoint) {
        askName("ここを山頂として登録（山の名前）") { name ->
            Storage.addMountain(this, Mountain(name, p.latitude, p.longitude))
            drawSummits()
            toast("$name を登録しました。半径50 m以内でスタンプが押されます")
        }
    }

    // ================= 小物 =================

    private fun askName(title: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty()) toast("名前を入れてください") else onOk(n)
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
