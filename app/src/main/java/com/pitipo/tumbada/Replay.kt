package com.pitipo.tumbada

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Repetición de una ruta: un punto recorre el mapa mientras el medidor muestra la inclinación
 * y la velocidad de cada momento. Acelerada x10 / x30 / x60.
 */
class Replay(private val ctx: Context, private val ride: Ride, private val map: MapView) {

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var tMs = 0L
    private var factor = 30
    private var lastFrame = 0L

    private lateinit var gauge: GaugeView
    private lateinit var deg: TextView
    private lateinit var side: TextView
    private lateinit var speed: TextView
    private lateinit var clock: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBox: LinearLayout
    private val speedChips = ArrayList<Pair<Int, TextView>>()

    private val frame = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            tMs += (now - lastFrame) * factor
            lastFrame = now
            if (tMs >= ride.durationMs) {
                tMs = ride.durationMs
                playing = false
                updatePlayButton()
            }
            show()
            if (playing) handler.postDelayed(this, 33)
        }
    }

    fun build(): View {
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.card()
            setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(12))
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gauge = GaugeView(ctx)
        top.addView(gauge, LinearLayout.LayoutParams(ctx.dpi(150), ViewGroup.LayoutParams.WRAP_CONTENT))
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), 0, 0, 0)
        }
        val degRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        deg = ctx.dotText("0°", 44f)
        degRow.addView(deg)
        side = ctx.text(tr(R.string.straight_upper), 11f, C.SUB2, weight = 500, spacing = 0.2f).apply { setPadding(ctx.dpi(8), 0, 0, ctx.dpi(6)) }
        degRow.addView(side)
        info.addView(degRow)
        speed = ctx.text("— km/h", 15f, C.TEXT, weight = 700)
        info.addView(speed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(4)
        })
        clock = ctx.text("0:00 / ${fmtClock(ride.durationMs)}", 12f, C.SUB)
        info.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(2)
        })
        top.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(top)

        seek = SeekBar(ctx).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(C.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(C.AXIS)
            thumbTintList = ColorStateList.valueOf(C.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    tMs = ride.durationMs * progress / 1000
                    map.follow = true
                    show()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        panel.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(36)).apply {
            topMargin = ctx.dpi(8)
        })

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playBox = LinearLayout(ctx)
        controls.addView(playBox, LinearLayout.LayoutParams(0, ctx.dpi(44), 1f))
        for (f in listOf(10, 30, 60)) {
            val chip = ctx.chip("x$f", f == factor) { setFactor(f) }
            speedChips.add(f to chip)
            controls.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dpi(6)
            })
        }
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(6)
        })
        updatePlayButton()
        return panel
    }

    private fun setFactor(f: Int) {
        factor = f
        for ((v, chip) in speedChips) {
            val sel = v == f
            chip.setTextColor(if (sel) C.BLACK else C.TEXT)
            chip.typeface = F.sans(ctx, if (sel) 700 else 500)
            chip.background = if (sel) rounded(C.WHITE, ctx.dp(16f)) else rounded(C.BLACK, ctx.dp(16f), C.OUTLINE, ctx.dpi(1))
        }
    }

    private fun updatePlayButton() {
        playBox.removeAllViews()
        val label = tr(if (playing) R.string.btn_pause else if (tMs >= ride.durationMs) R.string.btn_again else R.string.btn_play)
        playBox.addView(ctx.pill(label, primary = !playing, dot = !playing) { toggle() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun start() {
        map.follow = true
        map.zoomTo(15.0)
        show()
    }

    private fun toggle() {
        if (playing) {
            playing = false
        } else {
            if (tMs >= ride.durationMs) tMs = 0L
            playing = true
            map.follow = true
            lastFrame = SystemClock.uptimeMillis()
            handler.post(frame)
        }
        updatePlayButton()
    }

    fun stop() {
        playing = false
        handler.removeCallbacks(frame)
        map.clearCursor()
    }

    /** Posición en el instante tMs, interpolando entre puntos del recorrido. */
    private fun show() {
        val n = ride.trackLat.size
        if (n == 0) return
        var lo = 0
        var hi = n - 1
        if (tMs <= ride.pointTimeMs(0)) hi = 0
        else if (tMs >= ride.pointTimeMs(n - 1)) lo = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (ride.pointTimeMs(mid) <= tMs) lo = mid else hi = mid
        }
        val t0 = ride.pointTimeMs(lo)
        val t1 = ride.pointTimeMs(hi)
        val f = if (t1 > t0) ((tMs - t0).toDouble() / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
        val lat = ride.trackLat[lo] + (ride.trackLat[hi] - ride.trackLat[lo]) * f
        val lon = ride.trackLon[lo] + (ride.trackLon[hi] - ride.trackLon[lo]) * f
        val lean = ride.leanAt(tMs)
        map.setCursor(lat, lon, lean)

        gauge.set(lean, ride.maxLeft, ride.maxRight)
        deg.text = "${abs(lean).roundToInt()}°"
        when {
            lean >= 1f -> { deg.setTextColor(C.RIGHT); side.text = tr(R.string.right_upper) }
            lean <= -1f -> { deg.setTextColor(C.LEFT); side.text = tr(R.string.left_upper) }
            else -> { deg.setTextColor(C.TEXT); side.text = tr(R.string.straight_upper) }
        }
        val s0 = ride.pointSpeed(lo)
        val s1 = ride.pointSpeed(hi)
        speed.text = if (s0.isNaN() || s1.isNaN()) "— km/h" else "${(s0 + (s1 - s0) * f).roundToInt()} km/h"
        clock.text = "${fmtClock(tMs)} / ${fmtClock(ride.durationMs)}"
        if (ride.durationMs > 0) seek.progress = (tMs * 1000 / ride.durationMs).toInt()
    }
}
