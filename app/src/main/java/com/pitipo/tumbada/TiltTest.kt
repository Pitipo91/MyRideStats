package com.pitipo.tumbada

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Modo prueba: comprueba que los sensores detectan la inclinación sin tener que salir con la moto.
 *
 * Con el móvil quieto (en la mano, sin curva ni fuerza centrífuga) el acelerómetro SÍ marca la
 * inclinación directamente, así que aquí se usa eso: se toma la postura actual como "recto"
 * (Poner a cero) y se mide cuánto giras el móvil hacia la izquierda o la derecha.
 *
 * En marcha la app NO usa este método (en curva no sirve); usa giroscopio + GPS.
 */
class TiltTester(private val ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val up = FloatArray(3)
    private var upReady = false
    private var lastNs = 0L

    // Referencia "recto": eje vertical y eje lateral (derecha) en coordenadas del móvil
    private val ref = FloatArray(3)
    private val lat = FloatArray(3)
    private var hasRef = false
    private var zeroPending = true

    private var roll = 0f
    /** El modo prueba no puede saber hacia dónde miras: si el lado sale al revés, se invierte a mano. */
    private var invert = false
    private lateinit var invertChip: TextView
    private var maxLeft = 0f
    private var maxRight = 0f
    private var timeLeftMs = 0L
    private var timeRightMs = 0L
    private var lastUiNs = 0L
    private var gyroRate = 0f
    private var gyroSeen = false

    private lateinit var gauge: GaugeView
    private lateinit var deg: TextView
    private lateinit var side: TextView
    private lateinit var left: SideCardView
    private lateinit var right: SideCardView
    private lateinit var balance: BalanceView
    private lateinit var sensors: TextView

    fun buildInto(col: LinearLayout, onBack: () -> Unit) {
        fun add(v: View, top: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
                topMargin = ctx.dpi(top)
            })
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = android.widget.FrameLayout(ctx).apply {
            isClickable = true
            contentDescription = tr(R.string.cd_back)
            setOnClickListener { onBack() }
            addView(IconView(ctx, IconView.Kind.BACK, C.TEXT, 22f), android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        bar.addView(back, LinearLayout.LayoutParams(ctx.dpi(44), ctx.dpi(44)).apply { marginStart = -ctx.dpi(10) })
        bar.addView(ctx.dotText(tr(R.string.title_test), 26f))
        add(bar)

        add(ctx.text(
tr(R.string.test_help), 13f, C.SUB2
        ).apply { setLineSpacing(0f, 1.2f) }, 8)

        sensors = ctx.text("", 12f, C.TEXT, weight = 500, spacing = 0.04f)
        add(sensors, 12)

        gauge = GaugeView(ctx)
        add(gauge, 12)
        deg = ctx.dotText("0°", 92f).apply { gravity = Gravity.CENTER }
        add(deg)
        side = ctx.text(tr(R.string.straight_upper), 13f, C.SUB2, weight = 500, spacing = 0.3f).apply { gravity = Gravity.CENTER }
        add(side, 6)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        left = SideCardView(ctx, tr(R.string.left), right = false)
        right = SideCardView(ctx, tr(R.string.right), right = true)
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dpi(5) })
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dpi(5) })
        add(row, 22)
        balance = BalanceView(ctx)
        add(balance, 14)

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(ctx.pill(tr(R.string.btn_zero), primary = true, dot = true) { zeroPending = true; resetMax() },
            LinearLayout.LayoutParams(0, ctx.dpi(54), 1f).apply { marginEnd = ctx.dpi(5) })
        buttons.addView(ctx.pill(tr(R.string.btn_reset), primary = false) { resetMax() },
            LinearLayout.LayoutParams(0, ctx.dpi(54), 1f).apply { marginStart = ctx.dpi(5) })
        add(buttons, 22)

        val invRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        invRow.addView(ctx.text(tr(R.string.invert_question), 12f, C.SUB2),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        invertChip = ctx.chip(tr(R.string.chip_invert), false) { toggleInvert() }
        invRow.addView(invertChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpi(10) })
        add(invRow, 14)

        add(ctx.text(
            tr(R.string.test_note),
            12f, C.SUB
        ).apply { setLineSpacing(0f, 1.2f) }, 18)

        refresh()
    }

    private fun toggleInvert() {
        invert = !invert
        invertChip.setTextColor(if (invert) C.BLACK else C.TEXT)
        invertChip.typeface = F.sans(ctx, if (invert) 700 else 500)
        invertChip.background = if (invert) rounded(C.WHITE, ctx.dp(16f))
        else rounded(C.BLACK, ctx.dp(16f), C.OUTLINE, ctx.dpi(1))
        resetMax()
    }

    private fun resetMax() {
        maxLeft = 0f; maxRight = 0f; timeLeftMs = 0L; timeRightMs = 0L
        refresh()
    }

    fun start() {
        upReady = false
        lastNs = 0L
        zeroPending = true
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            gyroRate += 0.2f * (w - gyroRate)
            gyroSeen = true
            return
        }

        val v = e.values
        if (!upReady) {
            up[0] = v[0]; up[1] = v[1]; up[2] = v[2]
            upReady = true
            lastNs = e.timestamp
        }
        val dt = ((e.timestamp - lastNs) / 1e9f).coerceIn(0f, 0.2f)
        lastNs = e.timestamp
        val a = dt / (0.12f + dt)
        for (i in 0..2) up[i] += a * (v[i] - up[i])

        if (zeroPending) {
            setReference()
            zeroPending = false
        }
        if (!hasRef) return

        // Ángulo de giro alrededor del eje perpendicular a "recto" y al eje lateral
        val n = norm(up)
        if (n < 4f) return
        val y = dot(up, ref) / n
        val x = dot(up, lat) / n
        // El vector "arriba" se va hacia la izquierda del móvil cuando lo tumbas a la derecha
        roll = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat() * (if (invert) -1f else 1f)

        val ms = (dt * 1000).toLong()
        if (roll > maxRight) maxRight = roll
        if (-roll > maxLeft) maxLeft = -roll
        if (roll >= RideRecorder.SIDE_THRESHOLD) timeRightMs += ms
        else if (roll <= -RideRecorder.SIDE_THRESHOLD) timeLeftMs += ms

        if (e.timestamp - lastUiNs > 50_000_000L) {
            lastUiNs = e.timestamp
            refresh()
        }
    }

    private fun setReference() {
        val n = norm(up)
        if (n < 4f) return
        for (i in 0..2) ref[i] = up[i] / n
        // Eje lateral: el eje X del móvil (su "derecha") sin la parte que coincide con la vertical.
        // Si el móvil está casi tumbado sobre ese eje, se usa el eje Y como respaldo.
        var cand = floatArrayOf(1f, 0f, 0f)
        if (abs(ref[0]) > 0.9f) cand = floatArrayOf(0f, -1f, 0f)
        val d = dot(cand, ref)
        for (i in 0..2) lat[i] = cand[i] - d * ref[i]
        val ln = norm(lat)
        for (i in 0..2) lat[i] /= ln
        hasRef = true
    }

    private fun refresh() {
        if (!::gauge.isInitialized) return
        gauge.set(roll, maxLeft, maxRight)
        deg.text = "${abs(roll).roundToInt()}°"
        when {
            roll >= 1f -> { deg.setTextColor(C.RIGHT); side.text = tr(R.string.right_upper) }
            roll <= -1f -> { deg.setTextColor(C.LEFT); side.text = tr(R.string.left_upper) }
            else -> { deg.setTextColor(C.TEXT); side.text = tr(R.string.straight_upper) }
        }
        left.set(maxLeft, timeLeftMs)
        right.set(maxRight, timeRightMs)
        balance.set(timeLeftMs, timeRightMs)

        val acc = tr(if (upReady) R.string.sensor_acc_ok else R.string.sensor_acc_wait)
        val gyr = when {
            !gyroSeen -> tr(R.string.sensor_gyro_wait)
            gyroRate > 0.15f -> tr(R.string.sensor_gyro_rate, Math.toDegrees(gyroRate.toDouble()).roundToInt())
            else -> tr(R.string.sensor_gyro_ok)
        }
        sensors.text = "$acc   ·   $gyr"
        sensors.setTextColor(if (upReady && gyroSeen) C.SUB2 else C.ACCENT)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: FloatArray) = sqrt(dot(a, a))
}
