package com.pitipo.tumbada

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var repo: RideRepository
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var navLive: NavItem
    private lateinit var navHistory: NavItem
    private lateinit var navAnalysis: NavItem
    private lateinit var placeNames: PlaceNames

    private var rides: List<Ride> = emptyList()
    private var places: List<Place> = emptyList()
    private var loadedVersion = -1

    private enum class Screen { LIVE, HISTORY, DETAIL, TEST, ANALYSIS, PLACE, SETTINGS }
    private var screen = Screen.LIVE
        set(v) { field = v; updateBackCallback() }

    /** Android 13+: gesto atrás predictivo. Solo se intercepta en pantallas secundarias. */
    private var backCallback: Any? = null

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val needed = screen == Screen.DETAIL || screen == Screen.TEST || screen == Screen.PLACE || screen == Screen.SETTINGS
        val d = onBackInvokedDispatcher
        if (needed && backCallback == null) {
            val cb = OnBackInvokedCallback { goBack() }
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!needed && backCallback != null) {
            d.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }
    private var detailId: String? = null
    private var placeId: String? = null
    private var progressWeeks = true
    private var progressMetric = ProgressChart.Metric.MAX
    private var replay: Replay? = null
    /** Pantalla a la que vuelve «Atrás» desde Ajustes. */
    private var settingsReturn = Screen.LIVE
    private var sortByPeak = true
    private var permDenied = false

    private var live: LiveUi? = null
    private var tester: TiltTester? = null

    private val listener = Runnable { onStateChanged() }
    /** True mientras se enseña el informe de error (la app no se ha inicializado). */
    private var crashShown = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si la última vez se cerró por un error, se muestra el informe antes de nada
        val crash = CrashLog.take(this)
        if (crash != null) {
            crashShown = true
            CrashLog.show(this, crash) { recreate() }
            return
        }
        L.res = resources
        AppSettings.applyColors(this)
        repo = RideRepository(applicationContext)
        placeNames = PlaceNames(this)
        thread { Share.cleanup(applicationContext) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(View(this).apply { setBackgroundColor(C.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1)))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.NAV)
        }
        navLive = NavItem(tr(R.string.nav_live), IconView.Kind.GAUGE) { showLive() }
        navHistory = NavItem(tr(R.string.nav_history), IconView.Kind.LIST) { showHistory() }
        navAnalysis = NavItem(tr(R.string.nav_analysis), IconView.Kind.CHART) { showAnalysis() }
        nav.addView(navLive.view, LinearLayout.LayoutParams(0, dpi(64), 1f))
        nav.addView(navHistory.view, LinearLayout.LayoutParams(0, dpi(64), 1f))
        nav.addView(navAnalysis.view, LinearLayout.LayoutParams(0, dpi(64), 1f))
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Respeta barra de estado y de navegación (Android 15+ dibuja de borde a borde)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        // Barras del sistema: cuando la ventana ya tiene contenido
        AppSettings.applySystemBars(this)

        if (savedInstanceState != null) {
            screen = Screen.entries[savedInstanceState.getInt("screen", 0)]
            detailId = savedInstanceState.getString("detail")
            placeId = savedInstanceState.getString("place")
            settingsReturn = Screen.entries[savedInstanceState.getInt("settingsReturn", 0)]
        }
        loadRides { render() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (crashShown) return
        outState.putInt("screen", screen.ordinal)
        outState.putString("detail", detailId)
        outState.putString("place", placeId)
        outState.putInt("settingsReturn", settingsReturn.ordinal)
    }

    override fun onResume() {
        super.onResume()
        if (crashShown) return
        TrackingState.addListener(listener)
        if (screen == Screen.LIVE) showLive()
        tester?.start()
        onStateChanged()
    }

    override fun onPause() {
        if (crashShown) { super.onPause(); return }
        TrackingState.removeListener(listener)
        tester?.stop()
        replay?.stop()
        super.onPause()
    }

    /** Vuelve a la pantalla anterior. Devuelve false si ya está en una principal. */
    private fun goBack(): Boolean {
        when (screen) {
            Screen.DETAIL -> showHistory()
            Screen.TEST -> showLive()
            Screen.PLACE -> showAnalysis()
            Screen.SETTINGS -> when (settingsReturn) {
                Screen.HISTORY -> showHistory()
                Screen.ANALYSIS -> showAnalysis()
                else -> showLive()
            }
            else -> return false
        }
        return true
    }

    /** Android 9-12 (en 13+ lo gestiona updateBackCallback). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!goBack()) super.onBackPressed()
    }

    private fun onStateChanged() {
        if (TrackingState.savedVersion != loadedVersion) {
            loadRides {
                when (screen) {
                    Screen.HISTORY -> showHistory()
                    Screen.LIVE -> showLive()
                    Screen.ANALYSIS -> showAnalysis()
                    else -> {}
                }
            }
        }
        live?.update(TrackingState.live)
    }

    private fun loadRides(then: () -> Unit) {
        val v = TrackingState.savedVersion
        thread {
            val list = repo.list()
            val pl = Places.build(list)
            runOnUiThread {
                rides = list
                places = pl
                loadedVersion = v
                then()
            }
        }
    }

    private fun render() {
        when (screen) {
            Screen.LIVE -> showLive()
            Screen.HISTORY -> showHistory()
            Screen.TEST -> showTest()
            Screen.ANALYSIS -> showAnalysis()
            Screen.SETTINGS -> showSettings()
            Screen.PLACE -> {
                val p = places.find { it.id == placeId }
                if (p != null) showPlace(p) else showAnalysis()
            }
            Screen.DETAIL -> {
                val r = rides.find { it.id == detailId }
                if (r != null) showDetail(r) else showHistory()
            }
        }
    }

    // ------------------------------------------------------------ Barra inferior

    private inner class NavItem(label: String, kind: IconView.Kind, onClick: () -> Unit) {
        val icon = IconView(this@MainActivity, kind, C.SUB, 18f)
        val label: TextView = text(label.uppercase(), 12f, C.SUB, weight = 500, spacing = 0.06f)
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(icon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpi(7)
            })
            addView(this@NavItem.label)
        }

        fun select(on: Boolean) {
            val c = if (on) C.TEXT else C.NAV_OFF
            icon.color = c; icon.invalidate()
            label.setTextColor(c)
            label.typeface = F.sans(this@MainActivity, if (on) 700 else 500)
        }
    }

    private fun setNav(selected: Screen?) {
        val show = selected != null
        nav.visibility = if (show) View.VISIBLE else View.GONE
        ((nav.parent as ViewGroup).getChildAt(1)).visibility = nav.visibility
        navLive.select(selected == Screen.LIVE)
        navHistory.select(selected == Screen.HISTORY)
        navAnalysis.select(selected == Screen.ANALYSIS)
    }

    // ------------------------------------------------------------ Utilidades de maquetación

    private fun scrollPage(): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24), dpi(18), dpi(24), dpi(28))
        }
        val sv = ScrollView(this).apply {
            addView(col)
            isVerticalScrollBarEnabled = false
        }
        tester?.stop()
        tester = null
        replay?.stop()
        replay = null
        content.removeAllViews()
        content.addView(sv)
        return sv to col
    }

    private fun LinearLayout.add(v: View, topDp: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dpi(topDp)
        })
    }

    private fun sidePair(compact: Boolean = false, leftTitle: String = tr(R.string.left), rightTitle: String = tr(R.string.right)):
        Triple<LinearLayout, SideCardView, SideCardView> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val l = SideCardView(this, leftTitle, right = false, compact = compact)
        val r = SideCardView(this, rightTitle, right = true, compact = compact)
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(5) })
        row.addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(5) })
        return Triple(row, l, r)
    }

    private fun statsRow(valueSp: Float, vararg items: Pair<String, String>): Pair<LinearLayout, List<TextView>> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val values = items.map { (value, label) ->
            val (col, v) = stat(value, label, valueSp)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            v
        }
        return row to values
    }

    private fun statusChip(): TextView = text("", 12f, C.TEXT, weight = 500).apply {
        setPadding(dpi(10), dpi(7), dpi(10), dpi(7))
    }

    private fun setChip(t: TextView, label: String, ok: Boolean) {
        t.text = label
        t.setTextColor(if (ok) C.SUB2 else C.ACCENT)
        t.background = rounded(C.BLACK, dp(16f), if (ok) C.OUTLINE else C.ACCENT, dpi(1))
    }

    // ============================================================ EN RUTA

    private inner class LiveUi(
        val status: TextView,
        val statusDot: View,
        val chips: View,
        val chipGps: TextView,
        val chipCal: TextView,
        val chipSpeed: TextView,
        val gauge: GaugeView,
        val deg: TextView,
        val side: TextView,
        val left: SideCardView,
        val right: SideCardView,
        val balance: BalanceView,
        val statsRow: View,
        val stats: List<TextView>,
        val buttonBox: FrameLayout,
        val idleExtras: List<View>,
    ) {
        var wasRecording: Boolean? = null

        fun update(s: LiveStats) {
            gauge.set(s.leanDeg, s.maxLeft, s.maxRight)
            deg.text = "${abs(s.leanDeg).roundToInt()}°"
            when {
                s.leanDeg >= 1f -> { deg.setTextColor(C.RIGHT); side.text = tr(R.string.right_upper) }
                s.leanDeg <= -1f -> { deg.setTextColor(C.LEFT); side.text = tr(R.string.left_upper) }
                else -> { deg.setTextColor(C.TEXT); side.text = tr(R.string.straight_upper) }
            }
            left.set(s.maxLeft, s.timeLeftMs)
            right.set(s.maxRight, s.timeRightMs)
            balance.set(s.timeLeftMs, s.timeRightMs)

            if (s.recording) {
                status.text = tr(R.string.status_recording, fmtClock(s.elapsedMs))
                setChip(chipGps, tr(if (s.gpsOk) R.string.chip_gps_ok else R.string.chip_gps_wait), s.gpsOk)
                setChip(chipCal, tr(if (s.calibrated) R.string.chip_cal_ok else R.string.chip_cal_wait), s.calibrated)
                setChip(chipSpeed, "${s.speedKmh.roundToInt()} km/h", true)
                stats[0].text = fmtKm(s.distanceM)
                stats[1].text = fmtDuration(s.elapsedMs)
                stats[2].text = "${s.curves}"
            }

            if (wasRecording != s.recording) {
                wasRecording = s.recording
                val rec = s.recording
                status.text = if (rec) status.text else tr(R.string.status_ready)
                statusDot.background = rounded(if (rec) C.ACCENT else C.AXIS, dp(4f))
                chips.visibility = if (rec) View.VISIBLE else View.GONE
                statsRow.visibility = if (rec) View.VISIBLE else View.GONE
                for (v in idleExtras) v.visibility = if (rec) View.GONE else View.VISIBLE
                buttonBox.removeAllViews()
                val b = if (rec) {
                    pill(tr(R.string.btn_stop), primary = false, dot = true) { TrackingService.stop(this@MainActivity) }
                } else {
                    pill(tr(R.string.btn_start), primary = true, dot = true) { startRide() }
                }
                buttonBox.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            if (!s.recording) {
                idleExtras.firstOrNull()?.visibility =
                    if (TrackingState.lastSavedId != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showLive() {
        screen = Screen.LIVE
        setNav(Screen.LIVE)
        val (_, col) = scrollPage()

        // Cabecera
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(dotText("MYRIDESTATS", 20f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val statusDot = View(this)
        header.addView(statusDot, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { marginEnd = dpi(8) })
        val status = text(tr(R.string.status_ready), 12f, C.SUB2, weight = 500, spacing = 0.08f)
        header.addView(status)
        header.addView(gearButton(), LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { marginStart = dpi(6); marginEnd = -dpi(10) })
        col.add(header)

        // Chips de estado (solo grabando)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipGps = statusChip()
        val chipCal = statusChip()
        val chipSpeed = statusChip()
        for (c in listOf(chipGps, chipCal, chipSpeed)) {
            chips.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpi(8)
            })
        }
        col.add(chips, 14)

        val gauge = GaugeView(this)
        col.add(gauge, 16)

        val deg = dotText("0°", 92f).apply { gravity = Gravity.CENTER }
        col.add(deg, 0)
        val side = text(tr(R.string.straight_upper), 13f, C.SUB2, weight = 500, spacing = 0.3f).apply { gravity = Gravity.CENTER }
        col.add(side, 6)

        val (pair, l, r) = sidePair()
        col.add(pair, 22)
        val balance = BalanceView(this)
        col.add(balance, 14)

        val (row, values) = statsRow(17f, fmtKm(0f) to tr(R.string.stat_distance), "0s" to tr(R.string.stat_time), "0" to tr(R.string.stat_curves))
        col.add(row, 16)

        val buttonBox = FrameLayout(this)
        col.add(buttonBox, 22, dpi(56))

        // Extras cuando no se graba
        val lastRide = text(tr(R.string.last_ride), 14f, C.TEXT, weight = 500).apply {
            gravity = Gravity.CENTER
            setPadding(0, dpi(16), 0, dpi(4))
            paint.isUnderlineText = true
            setOnClickListener {
                val id = TrackingState.lastSavedId
                rides.find { it.id == id }?.let { showDetail(it) }
            }
        }
        col.add(lastRide)

        val testBtn = pill(tr(R.string.btn_test), primary = false) { showTest() }
        col.add(testBtn, 12, dpi(52))

        val extras = mutableListOf<View>(lastRide, testBtn)

        if (permDenied) {
            val warn = text(tr(R.string.warn_no_location),
                13f, C.ACCENT)
            col.add(warn, 12)
            extras.add(warn)
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val bc = batteryCard()
            col.add(bc, 18)
            extras.add(bc)
        }

        val help = text(
            tr(R.string.help_live),
            12f, C.SUB
        ).apply { setLineSpacing(0f, 1.2f) }
        col.add(help, 18)
        extras.add(help)

        live = LiveUi(status, statusDot, chips, chipGps, chipCal, chipSpeed, gauge, deg, side, l, r, balance,
            row, values, buttonBox, extras)
        live?.update(TrackingState.live)
    }

    private fun batteryCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dpi(16), dpi(14), dpi(16), dpi(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(this).apply { background = rounded(C.ACCENT, dp(4f)) },
            LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { marginEnd = dpi(8) })
        head.addView(text(tr(R.string.battery_title), 12f, C.TEXT, weight = 700, spacing = 0.08f))
        card.addView(head)
        card.addView(text(tr(R.string.battery_body), 13f, C.SUB2).apply {
            setPadding(0, dpi(8), 0, dpi(12)); setLineSpacing(0f, 1.2f)
        })
        card.addView(chip(tr(R.string.battery_btn), selected = true) {
            // Sin el permiso restringido REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: se abre la ficha de la app
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun startRide() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            missing.none { it == Manifest.permission.POST_NOTIFICATIONS }
        ) {
            TrackingService.start(this)
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            permDenied = false
            TrackingService.start(this)
        } else {
            permDenied = true
            if (screen == Screen.LIVE) showLive()
        }
    }

    // ============================================================ PRUEBA

    private fun showTest() {
        screen = Screen.TEST
        live = null
        setNav(null)
        val (_, col) = scrollPage()
        col.setPadding(dpi(24), dpi(8), dpi(24), dpi(28))
        val t = TiltTester(this)
        t.buildInto(col) { showLive() }
        tester = t
        t.start()
    }

    // ============================================================ HISTORIAL

    private fun showHistory() {
        screen = Screen.HISTORY
        detailId = null
        live = null
        setNav(Screen.HISTORY)
        val (_, col) = scrollPage()

        col.add(titleRow(tr(R.string.title_history)))

        if (rides.isEmpty()) {
            col.add(text(tr(R.string.history_empty), 14f, C.SUB2).apply {
                setLineSpacing(0f, 1.2f)
            }, 16)
            return
        }

        val (pair, l, r) = sidePair(compact = true, leftTitle = tr(R.string.record_left), rightTitle = tr(R.string.record_right))
        l.set(rides.maxOf { it.maxLeft }, rides.sumOf { it.timeLeftMs })
        r.set(rides.maxOf { it.maxRight }, rides.sumOf { it.timeRightMs })
        col.add(pair, 16)

        val totalKm = rides.sumOf { it.distanceM.toDouble() }.toFloat()
        val n = rides.size
        val summary = "${trq(R.plurals.rides_upper, n)}   ·   ${fmtKm(totalKm).uppercase()}   ·   " +
            trq(R.plurals.curves_upper, rides.sumOf { it.curves.size })
        col.add(text(summary, 12f, C.SUB2, weight = 500, spacing = 0.06f).apply { gravity = Gravity.CENTER }, 16)

        for (ride in rides) col.add(rideRow(ride), 14)
    }

    private fun rideRow(r: Ride): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            clipToOutline = true
            isClickable = true
            setOnClickListener { showDetail(r) }
        }
        if (r.hasTrack) {
            outer.addView(MapView.forRide(this, r, interactive = false, heightDp = 150),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(12), dpi(16), dpi(14))
        }
        outer.addView(body)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        top.addView(text(fmtDate(r.startTime), 15f, C.TEXT, weight = 700),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text("${fmtKm(r.distanceM)} · ${fmtDuration(r.durationMs)}", 12f, C.SUB))
        body.addView(top)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(8), 0, dpi(10))
        }
        mid.addView(text(tr(R.string.left_abbr), 12f, C.TEXT, weight = 500, spacing = 0.1f))
        mid.addView(dotText("${r.maxLeft.roundToInt()}°", 30f).apply { setPadding(dpi(8), 0, dpi(18), 0) })
        mid.addView(text(tr(R.string.right_abbr), 12f, C.RIGHT, weight = 500, spacing = 0.1f))
        mid.addView(dotText("${r.maxRight.roundToInt()}°", 30f).apply { setPadding(dpi(8), 0, 0, 0) })
        mid.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        mid.addView(text(trq(R.plurals.curves_count, r.curves.size), 12f, C.SUB))
        body.addView(mid)

        body.addView(BalanceView(this, showLabels = false).apply { set(r.timeLeftMs, r.timeRightMs) })
        return outer
    }

    // ============================================================ DETALLE

    private fun showDetail(ride: Ride) {
        screen = Screen.DETAIL
        detailId = ride.id
        live = null
        setNav(null)
        val (sv, col) = scrollPage()
        col.setPadding(dpi(24), dpi(6), dpi(24), dpi(28))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = FrameLayout(this).apply {
            isClickable = true
            contentDescription = tr(R.string.cd_back)
            setOnClickListener { showHistory() }
            addView(IconView(this@MainActivity, IconView.Kind.BACK, C.TEXT, 22f),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        bar.addView(back, LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { marginStart = -dpi(10) })
        bar.addView(text(fmtDate(ride.startTime), 17f, C.TEXT, weight = 700),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(text(tr(R.string.btn_delete_upper), 13f, C.SUB, weight = 500, spacing = 0.08f).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(12), 0, 0, 0)
            setOnClickListener { confirmDelete(ride) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44)))
        col.add(bar)

        var map: MapView? = null
        if (ride.hasTrack) {
            val m = MapView.forRide(this, ride, interactive = true, heightDp = 320)
            m.background = card()
            m.clipToOutline = true
            col.add(m, 8)
            map = m

            // Acciones: repetir, compartir, GPX
            val replayBox = FrameLayout(this)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(pill(tr(R.string.btn_replay), primary = true, dot = true) {
                if (replay == null) {
                    val rp = Replay(this, ride, m)
                    replayBox.addView(rp.build())
                    replay = rp
                    rp.start()
                    sv.post { sv.smoothScrollTo(0, max(0, m.top - dpi(8))) }
                }
            }, LinearLayout.LayoutParams(0, dpi(48), 1.3f).apply { marginEnd = dpi(5) })
            actions.addView(pill(tr(R.string.btn_share), primary = false) { shareCard(ride) },
                LinearLayout.LayoutParams(0, dpi(48), 1.2f).apply { marginStart = dpi(5); marginEnd = dpi(5) })
            actions.addView(pill("GPX", primary = false) { Share.shareGpx(this, ride) },
                LinearLayout.LayoutParams(0, dpi(48), 0.8f).apply { marginStart = dpi(5) })
            col.add(actions, 12)
            col.add(replayBox, 12)

            val mapBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val legend = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((label, color) in listOf(tr(R.string.straight_lower) to C.STRAIGHT, tr(R.string.left_lower) to C.LEFT, tr(R.string.right_lower) to C.RIGHT)) {
                legend.addView(View(this).apply { setBackgroundColor(color) },
                    LinearLayout.LayoutParams(dpi(14), dpi(3)).apply { marginEnd = dpi(5) })
                legend.addView(text(label, 11f, C.SUB2),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dpi(12)
                    })
            }
            mapBar.addView(legend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            mapBar.addView(chip(tr(R.string.chip_fit), selected = false) { m.fitRoute() })
            col.add(mapBar, 12)
            val hintRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            hintRow.addView(text(tr(R.string.map_hint), 11f, C.SUB),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            hintRow.addView(fondoLink())
            col.add(hintRow, 4)
        } else {
            col.add(text(tr(R.string.no_track),
                12f, C.SUB), 8)
            col.add(pill(tr(R.string.btn_share), primary = false) { shareCard(ride) }, 12, dpi(48))
        }
        val mapRef = map

        val (pair, l, r) = sidePair(compact = true)
        l.set(ride.maxLeft, ride.timeLeftMs)
        r.set(ride.maxRight, ride.timeRightMs)
        col.add(pair, 18)
        col.add(BalanceView(this).apply { set(ride.timeLeftMs, ride.timeRightMs) }, 14)
        Symmetry.of(ride.curves, topN = 5)?.let { sym ->
            col.add(text(tr(R.string.symmetry_line, sym.index, sym.verdict()), 12f, C.SUB2), 2)
        }

        val (row, _) = statsRow(15f,
            fmtDuration(ride.durationMs) to tr(R.string.stat_duration),
            fmtKm(ride.distanceM) to tr(R.string.stat_distance),
            "${ride.maxSpeedKmh.roundToInt()} km/h" to tr(R.string.stat_top_speed),
            "${ride.curves.size}" to tr(R.string.stat_curves)
        )
        col.add(row, 14)

        col.add(section(tr(R.string.section_lean_chart)), 28)
        col.add(ChartView(this, ride.samples), 10)
        val chartFoot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chartFoot.addView(text(tr(R.string.chart_foot_left), 11f, C.SUB),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chartFoot.addView(text(tr(R.string.chart_foot_right), 11f, C.SUB))
        col.add(chartFoot, 6)

        if (ride.curves.isEmpty()) {
            col.add(text(tr(R.string.no_curves), 13f, C.SUB2), 20)
            return
        }

        col.add(section(tr(R.string.section_hist)), 28)
        col.add(HistogramView(this, ride.curves), 8)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(section(tr(R.string.section_all_curves)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(chip(tr(R.string.chip_most_leaned), sortByPeak) { sortByPeak = true; showDetail(ride) })
        head.addView(View(this), LinearLayout.LayoutParams(dpi(6), 1))
        head.addView(chip(tr(R.string.chip_in_order), !sortByPeak) { sortByPeak = false; showDetail(ride) })
        col.add(head, 28)

        val curves = if (sortByPeak) ride.curves.sortedByDescending { abs(it.peakDeg) } else ride.curves
        for (c in curves) {
            val onClick: (() -> Unit)? = if (mapRef != null && c.hasLocation) {
                {
                    mapRef.focusCurve(c)
                    sv.smoothScrollTo(0, max(0, mapRef.top - dpi(8)))
                }
            } else null
            col.add(curveRow(c, onClick), 4)
        }
    }

    private fun speedsText(c: Curve): String? =
        if (c.hasSpeeds) "${c.entryKmh.roundToInt()} → ${c.minKmh.roundToInt()} → ${c.exitKmh.roundToInt()} km/h" else null

    /** Fila de curva: hora (o fecha), lado + velocidades entrada→mínima→salida, duración y grados. */
    private fun curveRow(c: Curve, onClick: (() -> Unit)?, whenText: String = fmtClock(c.tOffsetMs)): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(whenText, 13f, C.SUB),
            LinearLayout.LayoutParams(dpi(if (whenText.length > 6) 84 else 56), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(SideDot(this, c.isRight), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(10) })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(tr(if (c.isRight) R.string.right else R.string.left), 14f, C.TEXT))
        speedsText(c)?.let { mid.addView(text(it, 11f, C.SUB).apply { setPadding(0, dpi(3), 0, 0) }) }
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(fmtSecs(c.durationMs), 12f, C.SUB))
        row.addView(dotText(fmtDeg(c.peakDeg), 24f).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(dpi(58), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (onClick != null) {
            row.addView(IconView(this, IconView.Kind.BACK, C.MUTED, 16f).apply { rotation = 180f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dpi(6)
                })
            row.isClickable = true
            row.setOnClickListener { onClick() }
        }

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(56)))
        wrap.addView(View(this).apply { setBackgroundColor(C.DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1)))
        return wrap
    }

    // ============================================================ COMPARTIR

    private fun shareCard(ride: Ride) {
        android.widget.Toast.makeText(this, tr(R.string.toast_preparing), android.widget.Toast.LENGTH_SHORT).show()
        val map = if (ride.hasTrack) MapView.forRide(this, ride, interactive = false, heightDp = 300) else null
        // Primer dibujo para pedir las teselas del mapa; el definitivo, cuando hayan llegado
        if (map != null) ShareCard.render(this, ride, map)
        content.postDelayed({
            try {
                Share.shareImage(this, ride, ShareCard.render(this, ride, map))
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, tr(R.string.toast_image_fail), android.widget.Toast.LENGTH_SHORT).show()
            }
        }, if (map != null) 1800L else 0L)
    }

    // ============================================================ ANÁLISIS

    private fun showAnalysis() {
        screen = Screen.ANALYSIS
        detailId = null
        placeId = null
        live = null
        setNav(Screen.ANALYSIS)
        val (_, col) = scrollPage()

        col.add(titleRow(tr(R.string.title_analysis)))
        if (rides.isEmpty()) {
            col.add(text(tr(R.string.analysis_empty),
                14f, C.SUB2).apply { setLineSpacing(0f, 1.2f) }, 16)
            return
        }

        // --- Simetría
        col.add(section(tr(R.string.section_symmetry)), 22)
        val sym = Symmetry.of(rides.flatMap { it.curves })
        if (sym == null) {
            col.add(text(tr(R.string.symmetry_need_more), 13f, C.SUB2), 8)
        } else {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = card()
                setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun sideCol(label: String, v: Float, right: Boolean): LinearLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (right) Gravity.END else Gravity.START
                addView(sideLabel(label, right, 11f))
                addView(dotText("${v.roundToInt()}°", 36f).apply { setPadding(0, dpi(4), 0, 0) })
            }
            row.addView(sideCol(tr(R.string.left_short), sym.left, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val center = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(dotText("${sym.index}%", 40f, if (sym.index >= 90) C.TEXT else C.ACCENT))
                addView(text(tr(R.string.symmetry_word), 11f, C.SUB).apply { setPadding(0, dpi(2), 0, 0) })
            }
            row.addView(center, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            row.addView(sideCol(tr(R.string.right_short), sym.right, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            card.addView(text(sym.verdict() + " " + tr(R.string.symmetry_note), 12f, C.SUB2).apply {
                setPadding(0, dpi(12), 0, 0); setLineSpacing(0f, 1.2f)
            })
            col.add(card, 10)
        }

        // --- Progreso
        val ph = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ph.addView(section(tr(R.string.section_progress)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ph.addView(chip(tr(R.string.chip_weeks), progressWeeks) { progressWeeks = true; showAnalysis() })
        ph.addView(View(this), LinearLayout.LayoutParams(dpi(6), 1))
        ph.addView(chip(tr(R.string.chip_months), !progressWeeks) { progressWeeks = false; showAnalysis() })
        col.add(ph, 28)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((m, label) in listOf(ProgressChart.Metric.MAX to tr(R.string.metric_max), ProgressChart.Metric.KM to tr(R.string.metric_km),
            ProgressChart.Metric.CURVES to tr(R.string.metric_curves))) {
            metrics.addView(chip(label, progressMetric == m) { progressMetric = m; showAnalysis() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpi(6)
                })
        }
        col.add(metrics, 10)
        col.add(ProgressChart(this, Progress.build(rides, progressWeeks), progressMetric), 14)
        if (progressMetric == ProgressChart.Metric.MAX) {
            col.add(text(tr(R.string.progress_legend), 11f, C.SUB), 6)
        }

        // --- Mapa de calor
        col.add(section(tr(R.string.section_heat)), 28)
        val withTrack = rides.filter { it.hasTrack }
        if (withTrack.isEmpty()) {
            col.add(text(tr(R.string.heat_empty), 13f, C.SUB2), 8)
        } else {
            val markers = places.map { it.best.curve }
            val byCurve = places.associateBy { it.best.curve }
            val heat = MapView(this, withTrack, markers, interactive = true, heightDp = 320, heat = true)
            heat.tooltipText = { c ->
                byCurve[c]?.let { p -> tr(R.string.heat_tooltip, placeNames.name(p), p.rideCount, fmtDeg(c.peakDeg)) }
                    ?: fmtDeg(c.peakDeg)
            }
            heat.background = card()
            heat.clipToOutline = true
            col.add(heat, 10)
            val legend = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((label, color) in listOf("< 8°" to C.MUTED, "20°" to C.WHITE, "40°+" to C.RIGHT)) {
                legend.addView(View(this).apply { setBackgroundColor(color) },
                    LinearLayout.LayoutParams(dpi(14), dpi(3)).apply { marginEnd = dpi(5) })
                legend.addView(text(label, 11f, C.SUB2), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(14) })
            }
            legend.addView(text(trq(R.plurals.rides_count, withTrack.size), 11f, C.SUB), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            legend.addView(fondoLink())
            col.add(legend, 6)
        }

        // --- Tus curvas de siempre
        col.add(section(tr(R.string.section_places)), 28)
        if (places.isEmpty()) {
            col.add(text(tr(R.string.places_empty),
                13f, C.SUB2).apply { setLineSpacing(0f, 1.2f) }, 8)
        } else {
            for (p in places) col.add(placeRow(p), 4)
        }
    }

    private fun placeRow(p: Place): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { showPlace(p) }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(SideDot(this, p.isRight), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8) })
        nameRow.addView(text(placeNames.name(p), 14f, C.TEXT, weight = 500).apply { maxLines = 1 })
        info.addView(nameRow)
        info.addView(text(tr(R.string.place_row_sub, p.rideCount, trendText(p.trend)), 11f, C.SUB).apply {
            setPadding(0, dpi(4), 0, 0)
        })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(EvolutionView(this, p.passes.map { abs(it.curve.peakDeg) }, p.isRight, heightDp = 34, labels = false),
            LinearLayout.LayoutParams(dpi(64), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(10) })
        row.addView(dotText(fmtDeg(p.best.curve.peakDeg), 24f).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(dpi(52), ViewGroup.LayoutParams.WRAP_CONTENT))

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(60)))
        wrap.addView(View(this).apply { setBackgroundColor(C.DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1)))
        return wrap
    }

    // ============================================================ UNA CURVA DE SIEMPRE

    private fun showPlace(p: Place) {
        screen = Screen.PLACE
        placeId = p.id
        live = null
        setNav(null)
        val (_, col) = scrollPage()
        col.setPadding(dpi(24), dpi(6), dpi(24), dpi(28))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = FrameLayout(this).apply {
            isClickable = true
            contentDescription = tr(R.string.cd_back)
            setOnClickListener { showAnalysis() }
            addView(IconView(this@MainActivity, IconView.Kind.BACK, C.TEXT, 22f),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        bar.addView(back, LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { marginStart = -dpi(10) })
        bar.addView(text(placeNames.name(p), 17f, C.TEXT, weight = 700).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(text(tr(R.string.btn_rename_upper), 13f, C.SUB, weight = 500, spacing = 0.08f).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(12), 0, 0, 0)
            setOnClickListener { renamePlace(p) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44)))
        col.add(bar)

        // Mapa centrado en la curva, con el recorrido de la mejor pasada
        val bestRide = p.best.ride
        val map = MapView(this, listOf(bestRide), p.passes.map { it.curve }, interactive = true, heightDp = 260)
        map.tooltipText = { c ->
            val pass = p.passes.find { it.curve == c }
            if (pass != null) "${fmtDate(pass.ride.startTime)} · ${fmtDeg(c.peakDeg)}" else fmtDeg(c.peakDeg)
        }
        map.background = card()
        map.clipToOutline = true
        col.add(map, 8)
        map.post { map.focusCurve(p.best.curve); map.zoomTo(16.5) }

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, v) in listOf(tr(R.string.place_best) to fmtDeg(p.best.curve.peakDeg), tr(R.string.place_first) to fmtDeg(p.first.curve.peakDeg),
            tr(R.string.place_last) to fmtDeg(p.last.curve.peakDeg))) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(dotText(v, 36f, if (label == tr(R.string.place_best)) (if (p.isRight) C.RIGHT else C.TEXT) else C.TEXT))
                addView(text(label, 11f, C.SUB).apply { setPadding(0, dpi(4), 0, 0) })
            }
            stats.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.add(stats, 18)
        col.add(text(tr(R.string.place_summary, p.rideCount,
            tr(if (p.isRight) R.string.curve_right_hand else R.string.curve_left_hand), trendText(p.trend)), 12f, C.SUB2).apply { gravity = Gravity.CENTER }, 10)

        col.add(section(tr(R.string.section_evolution)), 26)
        col.add(EvolutionView(this, p.passes.map { abs(it.curve.peakDeg) }, p.isRight), 8)

        // ¿Tumbas más cuando entras más rápido? Solo si hay velocidades
        val withSpeed = p.passes.filter { it.curve.hasSpeeds }
        if (withSpeed.size >= 2) {
            val fast = withSpeed.maxByOrNull { it.curve.minKmh }!!
            val slow = withSpeed.minByOrNull { it.curve.minKmh }!!
            if (fast !== slow) {
                col.add(text(tr(R.string.place_speed_note, fast.curve.minKmh.roundToInt(), fmtDeg(fast.curve.peakDeg),
                    slow.curve.minKmh.roundToInt(), fmtDeg(slow.curve.peakDeg)), 12f, C.SUB2).apply { setLineSpacing(0f, 1.2f) }, 10)
            }
        }

        col.add(section(tr(R.string.section_passes)), 26)
        for (pass in p.passes.asReversed()) {
            col.add(curveRow(pass.curve, { showDetail(pass.ride) },
                whenText = java.text.SimpleDateFormat("d MMM yy", L.locale())
                    .format(java.util.Date(pass.ride.startTime))), 4)
        }
    }

    private fun renamePlace(p: Place) {
        val input = android.widget.EditText(this).apply {
            setText(if (placeNames.hasCustom(p)) placeNames.name(p) else "")
            hint = p.defaultName()
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dpi(20), dpi(8), dpi(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(tr(R.string.rename_title))
            .setView(box)
            .setPositiveButton(tr(R.string.btn_save)) { _, _ ->
                placeNames.rename(p, input.text.toString())
                showPlace(p)
            }
            .setNegativeButton(tr(R.string.btn_cancel), null)
            .show()
    }

    // ============================================================ AJUSTES

    private fun dialogTheme(): Int =
        if (C.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** Engranaje que abre Ajustes (en las tres pantallas principales). */
    private fun gearButton(): View = FrameLayout(this).apply {
        isClickable = true
        contentDescription = tr(R.string.cd_settings)
        setOnClickListener {
            settingsReturn = if (screen == Screen.SETTINGS) settingsReturn else screen
            showSettings()
        }
        addView(IconView(this@MainActivity, IconView.Kind.GEAR, C.SUB2, 22f),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Título grande de pantalla con el engranaje a la derecha. */
    private fun titleRow(title: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(dotText(title, 30f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(gearButton(), LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { marginEnd = -dpi(10) })
        return row
    }

    /** Fila de opciones excluyentes con chips. */
    private fun optionRow(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, (label, selected) ->
            row.addView(chip(label, selected) { if (!selected) onPick(i) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpi(8)
                })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun settingsCard(title: String, vararg body: View): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dpi(16), dpi(14), dpi(16), dpi(16))
        }
        card.addView(section(title))
        for (v in body) card.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12) })
        return card
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        live = null
        setNav(null)
        val (_, col) = scrollPage()
        col.setPadding(dpi(24), dpi(6), dpi(24), dpi(28))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = FrameLayout(this).apply {
            isClickable = true
            contentDescription = tr(R.string.cd_back)
            setOnClickListener { goBack() }
            addView(IconView(this@MainActivity, IconView.Kind.BACK, C.TEXT, 22f),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        bar.addView(back, LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { marginStart = -dpi(10) })
        bar.addView(dotText(tr(R.string.title_settings), 26f))
        col.add(bar)

        // Tema
        val theme = AppSettings.theme(this)
        val themes = listOf(AppSettings.Theme.SYSTEM to R.string.theme_system, AppSettings.Theme.DARK to R.string.theme_dark,
            AppSettings.Theme.LIGHT to R.string.theme_light)
        col.add(settingsCard(tr(R.string.settings_theme),
            optionRow(themes.map { (t, label) -> tr(label) to (t == theme) }) { i ->
                AppSettings.setTheme(this, themes[i].first)
                recreate()
            }), 16)

        // Idioma (nombres de idioma en su propio idioma)
        val lang = AppSettings.language(this)
        val langs = AppSettings.LANGUAGES
        val labels = langs.map { t -> if (t.isEmpty()) tr(R.string.lang_system)
            else java.util.Locale.forLanguageTag(t).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.uppercase() } } }
        col.add(settingsCard(tr(R.string.settings_language),
            optionRow(labels.mapIndexed { i, l -> l to (langs[i] == lang) }) { i ->
                AppSettings.setLanguage(this, langs[i])
            }), 12)

        // Mapa
        val current = if (TileCache.style == MapStyle.CARTO) "CARTO" else "OpenStreetMap"
        val mapRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mapRow.addView(text(tr(R.string.settings_map_current, current), 14f, C.TEXT),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mapRow.addView(chip(tr(R.string.settings_map_change), false) { mapSettings { showSettings() } })
        col.add(settingsCard(tr(R.string.settings_map), mapRow), 12)

        // Acerca de
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        col.add(settingsCard(tr(R.string.settings_about),
            text("${tr(R.string.app_name)} · ${tr(R.string.about_version, version)}", 14f, C.TEXT, weight = 500),
            text(tr(R.string.about_text), 13f, C.SUB2).apply { setLineSpacing(0f, 1.2f) },
            text(tr(R.string.about_safety), 13f, C.SUB2).apply { setLineSpacing(0f, 1.2f) },
            text(tr(R.string.about_attribution), 11f, C.SUB).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chip(tr(R.string.about_privacy), false) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                })
                addView(chip(tr(R.string.about_licenses), false) { showLicenses() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dpi(8) })
            }), 12)
    }

    private fun showLicenses() {
        val fonts = listOf("OFL-Doto.txt", "OFL-SpaceGrotesk.txt").joinToString("\n\n────────\n\n") { f ->
            runCatching { assets.open("fonts/$f").bufferedReader().use { it.readText() } }.getOrDefault(f)
        }
        val tv = TextView(this).apply {
            text = tr(R.string.licenses_intro) + "\n\n" + fonts
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dpi(24), dpi(12), dpi(24), dpi(12))
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(tr(R.string.about_licenses))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(tr(R.string.btn_close), null)
            .show()
    }

    companion object {
        /** Política de privacidad publicada (GitHub Pages, carpeta docs/ del repositorio). */
        const val PRIVACY_URL = "https://pitipo91.github.io/MyRideStats/privacy/"
    }

    // ============================================================ FONDO DEL MAPA

    private fun fondoLink(): TextView = text(tr(R.string.map_bg_link), 12f, C.SUB2, weight = 500, spacing = 0.08f).apply {
        gravity = Gravity.CENTER
        setPadding(dpi(12), dpi(12), 0, dpi(12))
        contentDescription = tr(R.string.map_bg_title)
        setOnClickListener { mapSettings() }
    }

    private fun mapSettings(onSaved: () -> Unit = {}) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(8), dpi(22), 0)
        }
        val group = android.widget.RadioGroup(this)
        val osm = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = tr(R.string.map_osm)
        }
        val carto = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = tr(R.string.map_carto)
        }
        group.addView(osm)
        group.addView(carto)
        box.addView(group)
        val keyInput = android.widget.EditText(this).apply {
            hint = tr(R.string.carto_key_hint)
            setSingleLine()
            // Sin sugerencias ni aprendizaje del teclado
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(TileCache.cartoKey)
        }
        box.addView(keyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(8)
        })
        box.addView(text(tr(R.string.carto_help), 12f, 0xFF8A8A8A.toInt()).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, dpi(10), 0, dpi(4))
        })
        box.addView(text(tr(R.string.carto_open), 13f, C.ACCENT, weight = 700).apply {
            setPadding(0, dpi(10), 0, dpi(10))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.carto.com/basemaps/apikey/")))
                } catch (_: Exception) {
                }
            }
        })
        fun syncKeyField() {
            keyInput.isEnabled = carto.isChecked
            keyInput.alpha = if (carto.isChecked) 1f else 0.4f
        }
        group.check(if (TileCache.style == MapStyle.CARTO) carto.id else osm.id)
        syncKeyField()
        group.setOnCheckedChangeListener { _, _ -> syncKeyField() }

        AlertDialog.Builder(this, dialogTheme())
            .setTitle(tr(R.string.map_bg_title))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(tr(R.string.btn_save)) { _, _ ->
                val wantCarto = carto.isChecked
                val key = keyInput.text.toString()
                if (wantCarto && key.isBlank()) {
                    android.widget.Toast.makeText(this, tr(R.string.toast_no_key), android.widget.Toast.LENGTH_LONG).show()
                }
                TileCache.configure(if (wantCarto) MapStyle.CARTO else MapStyle.OSM, key)
                onSaved()
            }
            .setNegativeButton(tr(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmDelete(ride: Ride) {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(tr(R.string.delete_title))
            .setMessage(tr(R.string.delete_msg))
            .setPositiveButton(tr(R.string.btn_delete)) { _, _ ->
                repo.delete(ride.id)
                Share.cleanup(applicationContext, ride.id)
                rides = rides.filter { it.id != ride.id }
                showHistory()
            }
            .setNegativeButton(tr(R.string.btn_cancel), null)
            .show()
    }
}
