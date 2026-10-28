package com.pitipo.tumbada

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------ Estilo «Glifo»: negro, blanco y un rojo (oscuro) o su inverso (claro)

/**
 * Paleta activa. Se rellena con [apply] según el tema.
 * WHITE = primer plano fuerte y BLACK = fondo base: en el tema claro se invierten, así todo el
 * dibujo (botones, anillos, marcadores) se adapta sin tocar cada vista.
 */
object C {
    var dark = true; private set
    var BG = 0; private set
    var NAV = 0; private set
    var CARD = 0; private set
    var BORDER = 0; private set
    var LINE = 0; private set
    var TRACK = 0; private set
    var TICK = 0; private set
    var TEXT = 0; private set
    var SUB = 0; private set
    var SUB2 = 0; private set
    var STRAIGHT = 0; private set
    /** Izquierda = primer plano (blanco u negro), derecha = rojo: se distinguen también por luminosidad. */
    var LEFT = 0; private set
    var RIGHT = 0; private set
    var ACCENT = 0; private set
    var RED = 0; private set
    var WHITE = 0; private set
    var BLACK = 0; private set
    /** Contornos de chips y botones secundarios. */
    var OUTLINE = 0; private set
    /** Separadores de listas y rejillas de gráficas. */
    var DIVIDER = 0; private set
    var GRID = 0; private set
    var AXIS = 0; private set
    /** Elementos apagados (sin datos, iconos inactivos). */
    var MUTED = 0; private set
    var NAV_OFF = 0; private set
    // Mapa
    var MAP_BG = 0; private set
    var MAP_SHADOW = 0; private set
    var MAP_ATTR = 0; private set
    var TOOLTIP_BG = 0; private set

    init { apply(true) }

    fun apply(isDark: Boolean) {
        dark = isDark
        if (isDark) {
            BG = 0xFF000000.toInt(); NAV = BG; CARD = 0xFF111111.toInt(); BORDER = 0xFF222222.toInt()
            LINE = 0xFF1E1E1E.toInt(); TRACK = 0xFF1E1E1E.toInt(); TICK = 0xFF6B6B6B.toInt()
            TEXT = 0xFFFFFFFF.toInt(); SUB = 0xFF9E9E9E.toInt(); SUB2 = 0xFFBDBDBD.toInt()
            STRAIGHT = 0xFF8C8C8C.toInt(); LEFT = 0xFFFFFFFF.toInt(); RIGHT = 0xFFE8322F.toInt()
            WHITE = 0xFFFFFFFF.toInt(); BLACK = 0xFF000000.toInt(); OUTLINE = 0xFF333333.toInt()
            DIVIDER = 0xFF1C1C1C.toInt(); GRID = 0xFF2A2A2A.toInt(); AXIS = 0xFF4A4A4A.toInt()
            MUTED = 0xFF5A5A5A.toInt(); NAV_OFF = 0xFF8A8A8A.toInt()
            MAP_BG = 0xFF0A0A0A.toInt(); MAP_SHADOW = 0x99000000.toInt(); MAP_ATTR = 0xCCFFFFFF.toInt()
            TOOLTIP_BG = 0xF2161616.toInt()
        } else {
            BG = 0xFFF3F3F1.toInt(); NAV = BG; CARD = 0xFFFFFFFF.toInt(); BORDER = 0xFFE0E0DC.toInt()
            LINE = 0xFFE0E0DC.toInt(); TRACK = 0xFFE4E4E0.toInt(); TICK = 0xFF8E8E89.toInt()
            TEXT = 0xFF111111.toInt(); SUB = 0xFF65655F.toInt(); SUB2 = 0xFF45453F.toInt()
            STRAIGHT = 0xFF8C8C88.toInt(); LEFT = 0xFF111111.toInt(); RIGHT = 0xFFD42A27.toInt()
            WHITE = 0xFF111111.toInt(); BLACK = 0xFFFFFFFF.toInt(); OUTLINE = 0xFFCFCFCA.toInt()
            DIVIDER = 0xFFE6E6E2.toInt(); GRID = 0xFFE6E6E2.toInt(); AXIS = 0xFFB4B4AE.toInt()
            MUTED = 0xFFA6A6A0.toInt(); NAV_OFF = 0xFF8A8A85.toInt()
            MAP_BG = 0xFFEDEDEA.toInt(); MAP_SHADOW = 0x99FFFFFF.toInt(); MAP_ATTR = 0xCC222222.toInt()
            TOOLTIP_BG = 0xF2FFFFFF.toInt()
        }
        ACCENT = RIGHT
        RED = RIGHT
    }

    fun side(lean: Float): Int = if (lean > 0) RIGHT else LEFT
}

/** Tipografías incluidas en la app: Doto (dot-matrix) para cifras y Space Grotesk para el resto. */
object F {
    private var dot: Typeface? = null
    private val sans = HashMap<Int, Typeface>()

    fun dot(ctx: Context): Typeface = dot ?: load(ctx, "fonts/Doto.ttf", "'wght' 900, 'ROND' 0").also { dot = it }

    fun sans(ctx: Context, weight: Int = 400): Typeface =
        sans[weight] ?: load(ctx, "fonts/SpaceGrotesk.ttf", "'wght' $weight").also { sans[weight] = it }

    private fun load(ctx: Context, path: String, variation: String): Typeface = try {
        Typeface.Builder(ctx.assets, path).setFontVariationSettings(variation).build() ?: Typeface.DEFAULT
    } catch (e: Exception) {
        Typeface.DEFAULT
    }
}

/** Idioma activo para números y fechas (coma decimal en español, punto en inglés). */
private val Es: Locale get() = L.locale()

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> String.format(Es, "%dh %02dm", h, m)
        m > 0 -> String.format(Es, "%dm %02ds", m, sec)
        else -> String.format(Es, "%ds", sec)
    }
}

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return String.format(Es, "%d:%02d", s / 60, s % 60)
}

fun fmtKm(m: Float): String = String.format(Es, "%.1f km", m / 1000f)

fun fmtDate(t: Long): String =
    SimpleDateFormat(if (Es.language == "es") "EEE d MMM · HH:mm" else "EEE, MMM d · HH:mm", Es).format(Date(t)).replaceFirstChar { it.uppercase() }

fun fmtDeg(d: Float): String = "${abs(d).roundToInt()}°"

fun fmtSecs(ms: Long): String = String.format(Es, "%.1f s", ms / 1000f)

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

fun Context.card(): GradientDrawable = rounded(C.CARD, dp(18f), C.BORDER, dpi(1))

fun Context.text(
    s: String = "",
    sizeSp: Float = 15f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    weight: Int = if (bold) 700 else 400,
    spacing: Float = 0f,
): TextView = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(color)
    typeface = F.sans(this@text, weight)
    if (spacing != 0f) letterSpacing = spacing
    includeFontPadding = false
}

/** Cifra grande en dot-matrix. */
fun Context.dotText(s: String, sizeSp: Float, color: Int = C.TEXT): TextView = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(color)
    typeface = F.dot(this@dotText)
    includeFontPadding = false
}

/** Etiqueta de sección: mayúsculas, espaciada, gris. */
fun Context.section(s: String): TextView = text(s.uppercase(), 12f, C.SUB2, weight = 500, spacing = 0.14f)

/** Botón píldora. primary = blanco relleno; si no, contorno blanco. dot = cuadradito rojo delante. */
fun Context.pill(label: String, primary: Boolean, dot: Boolean = false, onClick: () -> Unit): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = if (primary) rounded(C.WHITE, dp(28f)) else rounded(C.BLACK, dp(28f), C.WHITE, dpi(1) + 1)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        contentDescription = label
    }
    if (dot) {
        row.addView(View(this).apply { background = rounded(C.ACCENT, dp(2f)) },
            LinearLayout.LayoutParams(dpi(12), dpi(12)).apply { marginEnd = dpi(10) })
    }
    row.addView(text(label.uppercase(), 15f, if (primary) C.BLACK else C.WHITE, weight = 700, spacing = 0.06f))
    return row
}

/** Chip pequeño: seleccionado = blanco relleno; si no, contorno gris. */
fun Context.chip(label: String, selected: Boolean, onClick: (() -> Unit)? = null): TextView =
    text(label, 12f, if (selected) C.BLACK else C.TEXT, weight = if (selected) 700 else 500).apply {
        background = if (selected) rounded(C.WHITE, dp(16f)) else rounded(C.BLACK, dp(16f), C.OUTLINE, dpi(1))
        setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
        if (onClick != null) setOnClickListener { onClick() }
    }

/** Punto de lado: izquierda = anillo blanco, derecha = punto rojo. */
class SideDot(ctx: Context, private val right: Boolean, private val sizeDp: Float = 8f) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(sizeDp).roundToInt()
        setMeasuredDimension(s, s)
    }
    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        if (right) {
            p.style = Paint.Style.FILL; p.color = C.RIGHT
            canvas.drawCircle(r, r, r, p)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(2f); p.color = C.LEFT
            canvas.drawCircle(r, r, r - p.strokeWidth / 2, p)
        }
    }
}

fun Context.sideLabel(label: String, right: Boolean, sizeSp: Float = 12f): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(SideDot(this@sideLabel, right), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(6) })
        addView(text(label.uppercase(), sizeSp, if (right) C.RIGHT else C.TEXT, weight = 500, spacing = 0.12f))
    }

// ------------------------------------------------------------ Iconos de trazo

class IconView(ctx: Context, private val kind: Kind, var color: Int = C.TEXT, private val sizeDp: Float = 20f) : View(ctx) {
    enum class Kind { GAUGE, LIST, BACK, CHART, GEAR }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(sizeDp).roundToInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val u = width / 24f
        p.color = color
        p.strokeWidth = 2f * u
        when (kind) {
            Kind.GAUGE -> {
                rect.set(3 * u, 8 * u, 21 * u, 26 * u)
                canvas.drawArc(rect, 180f, 180f, false, p)
                canvas.drawLine(12 * u, 17 * u, 16 * u, 10 * u, p)
            }
            Kind.LIST -> {
                canvas.drawLine(4 * u, 6 * u, 20 * u, 6 * u, p)
                canvas.drawLine(4 * u, 12 * u, 20 * u, 12 * u, p)
                canvas.drawLine(4 * u, 18 * u, 14 * u, 18 * u, p)
            }
            Kind.CHART -> {
                canvas.drawLine(5 * u, 20 * u, 5 * u, 13 * u, p)
                canvas.drawLine(10 * u, 20 * u, 10 * u, 6 * u, p)
                canvas.drawLine(15 * u, 20 * u, 15 * u, 10 * u, p)
                canvas.drawLine(20 * u, 20 * u, 20 * u, 4 * u, p)
            }
            Kind.GEAR -> {
                val cx = 12 * u; val cy = 12 * u
                canvas.drawCircle(cx, cy, 3.2f * u, p)
                canvas.drawCircle(cx, cy, 7f * u, p)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                    canvas.drawLine(cx + c * 7f * u, cy + s * 7f * u, cx + c * 10f * u, cy + s * 10f * u, p)
                }
            }
            Kind.BACK -> {
                path.reset()
                path.moveTo(15 * u, 5 * u); path.lineTo(8 * u, 12 * u); path.lineTo(15 * u, 19 * u)
                canvas.drawPath(path, p)
            }
        }
    }
}

// ------------------------------------------------------------ Medidor semicircular

class GaugeView(ctx: Context) : View(ctx) {
    var lean = 0f
    var maxLeft = 0f
    var maxRight = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val dots = DashPathEffect(floatArrayOf(1f, 1f), 0f)

    fun set(lean: Float, maxLeft: Float, maxRight: Float) {
        this.lean = lean; this.maxLeft = maxLeft; this.maxRight = maxRight
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w / 1.8f).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = context.dp(16f)
        val r = min(width / 2f, height.toFloat()) - stroke
        val cx = width / 2f
        val cy = height - stroke / 2f
        rect.set(cx - r, cy - r, cx + r, cy + r)

        p.pathEffect = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.ROUND
        p.color = C.TRACK
        canvas.drawArc(rect, 180f, 180f, false, p)

        // Línea de puntos sobre la pista (guiño dot-matrix)
        p.strokeWidth = context.dp(2f)
        p.color = C.AXIS
        p.pathEffect = DashPathEffect(floatArrayOf(context.dp(0.5f), context.dp(7f)), 0f)
        canvas.drawArc(rect, 180f, 180f, false, p)
        p.pathEffect = null

        val cl = lean.coerceIn(-90f, 90f)
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.BUTT
        p.color = C.side(cl)
        if (abs(cl) >= 0.5f) canvas.drawArc(rect, 270f, cl, false, p)

        fun px(deg: Float, rad: Float) = cx + rad * sin(Math.toRadians(deg.toDouble())).toFloat()
        fun py(deg: Float, rad: Float) = cy - rad * cos(Math.toRadians(deg.toDouble())).toFloat()

        p.strokeWidth = context.dp(2f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = C.TICK
        var d = -75
        while (d <= 75) {
            val len = if (d % 45 == 0) 2.6f else 1.85f
            val f = d.toFloat()
            canvas.drawLine(px(f, r - stroke * 1.1f), py(f, r - stroke * 1.1f),
                px(f, r - stroke * len), py(f, r - stroke * len), p)
            d += 15
        }

        // Máximos: anillos sobre la pista
        val mr = stroke * 0.56f
        fun ring(a: Float, color: Int) {
            p.style = Paint.Style.FILL; p.color = C.BLACK
            canvas.drawCircle(px(a, r), py(a, r), mr, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(3f); p.color = color
            canvas.drawCircle(px(a, r), py(a, r), mr, p)
        }
        if (maxLeft > 0f) ring(-maxLeft.coerceAtMost(90f), C.LEFT)
        if (maxRight > 0f) ring(maxRight.coerceAtMost(90f), C.RIGHT)

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = context.dp(4f)
        p.color = C.TEXT
        canvas.drawLine(cx, cy, px(cl, r - stroke * 3f), py(cl, r - stroke * 3f), p)
        p.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, context.dp(8f), p)
    }
}

// ------------------------------------------------------------ Gráfica de la ruta

class ChartView(ctx: Context, private val samples: FloatArray) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(150))
    }

    override fun onDraw(canvas: Canvas) {
        if (samples.isEmpty()) return
        var peak = 30f
        for (v in samples) peak = max(peak, abs(v))
        val maxAbs = ceil(peak / 15f) * 15f
        val mid = height / 2f
        val scale = mid / maxAbs
        val w = width.toFloat()

        p.strokeWidth = context.dp(1f)
        p.color = C.DIVIDER
        var g = 15f
        while (g <= maxAbs) {
            canvas.drawLine(0f, mid - g * scale, w, mid - g * scale, p)
            canvas.drawLine(0f, mid + g * scale, w, mid + g * scale, p)
            g += 15f
        }

        val cols = max(1, (w / context.dp(4f)).toInt())
        val colW = w / cols
        p.strokeWidth = colW * 0.6f
        p.strokeCap = Paint.Cap.BUTT
        for (col in 0 until cols) {
            val from = (col.toLong() * samples.size / cols).toInt()
            if (from >= samples.size) break
            val to = min(samples.size, max(from + 1, ((col + 1).toLong() * samples.size / cols).toInt()))
            var hi = 0f
            var lo = 0f
            for (i in from until to) {
                val v = samples[i]
                if (v > hi) hi = v
                if (v < lo) lo = v
            }
            val x = col * colW + colW / 2f
            if (hi > 0f) { p.color = C.RIGHT; canvas.drawLine(x, mid, x, mid - hi * scale, p) }
            if (lo < 0f) { p.color = C.LEFT; canvas.drawLine(x, mid, x, mid - lo * scale, p) }
        }
        p.strokeWidth = context.dp(1.5f)
        p.color = C.AXIS
        canvas.drawLine(0f, mid, w, mid, p)
    }
}

// ------------------------------------------------------------ Reparto izquierda/derecha

class BalanceView(ctx: Context, private val showLabels: Boolean = true) : View(ctx) {
    private var leftMs = 0L
    private var rightMs = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(l: Long, r: Long) {
        leftMs = l; rightMs = r; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(if (showLabels) 26 else 6))
    }

    override fun onDraw(canvas: Canvas) {
        val barH = context.dp(if (showLabels) 6f else 4f)
        val rad = barH / 2f
        val gap = context.dp(3f)
        val w = width.toFloat()
        p.style = Paint.Style.FILL
        val total = leftMs + rightMs
        if (total <= 0) {
            p.color = C.TRACK
            rect.set(0f, 0f, w, barH)
            canvas.drawRoundRect(rect, rad, rad, p)
            return
        }
        val lf = leftMs.toFloat() / total
        val lw = (w - gap) * lf
        if (lw > 0.5f) {
            p.color = C.LEFT
            rect.set(0f, 0f, lw, barH)
            canvas.drawRoundRect(rect, rad, rad, p)
        }
        if (lf < 1f) {
            p.color = C.RIGHT
            rect.set(if (lw > 0.5f) lw + gap else 0f, 0f, w, barH)
            canvas.drawRoundRect(rect, rad, rad, p)
        }
        if (!showLabels) return

        p.textSize = context.dp(11f)
        p.typeface = F.sans(context, 400)
        p.color = C.SUB2
        val pl = (lf * 100).roundToInt()
        val base = height.toFloat() - context.dp(2f)
        p.textAlign = Paint.Align.LEFT
        canvas.drawText(tr(R.string.balance_left, pl), 0f, base, p)
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText(tr(R.string.balance_right, 100 - pl), w, base, p)
    }
}

// ------------------------------------------------------------ Histograma de curvas

class HistogramView(ctx: Context, curves: List<Curve>) : View(ctx) {
    private val buckets = listOf(10 to 20, 20 to 30, 30 to 40, 40 to 90)
    private val counts: List<Pair<Int, Int>> = buckets.map { (a, b) ->
        val l = curves.count { !it.isRight && -it.peakDeg >= a && -it.peakDeg < b }
        val r = curves.count { it.isRight && it.peakDeg >= a && it.peakDeg < b }
        l to r
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(26) * buckets.size)
    }

    override fun onDraw(canvas: Canvas) {
        val rowH = context.dp(26f)
        val numW = context.dp(26f)
        val labelW = context.dp(66f)
        val barH = context.dp(10f)
        val w = width.toFloat()
        val barArea = (w - 2 * numW - labelW) / 2f
        val maxC = max(1, counts.maxOf { max(it.first, it.second) })
        p.textSize = context.dp(12f)
        p.typeface = F.sans(context, 500)
        val rr = context.dp(2f)

        buckets.forEachIndexed { i, (a, b) ->
            val (l, r) = counts[i]
            val top = i * rowH + (rowH - barH) / 2f
            val textY = i * rowH + rowH / 2f + p.textSize / 3f

            p.color = C.TEXT
            p.textAlign = Paint.Align.LEFT
            canvas.drawText("$l", 0f, textY, p)
            val lw = barArea * l / maxC
            val centerL = numW + barArea
            if (lw > 0f) {
                p.color = C.LEFT
                rect.set(centerL - lw, top, centerL, top + barH)
                canvas.drawRoundRect(rect, rr, rr, p)
            }

            p.color = C.SUB2
            p.textAlign = Paint.Align.CENTER
            canvas.drawText(if (b >= 90) "$a°+" else "$a–$b°", centerL + labelW / 2f, textY, p)

            val startR = centerL + labelW
            val rw = barArea * r / maxC
            if (rw > 0f) {
                p.color = C.RIGHT
                rect.set(startR, top, startR + rw, top + barH)
                canvas.drawRoundRect(rect, rr, rr, p)
            }
            p.color = C.TEXT
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText("$r", w, textY, p)
        }
    }
}

// ------------------------------------------------------------ Tarjeta de lado (máx + tiempo)

class SideCardView(ctx: Context, title: String, right: Boolean, compact: Boolean = false) : LinearLayout(ctx) {
    private val maxText: TextView
    private val timeText: TextView
    private val compact = compact

    init {
        orientation = VERTICAL
        background = ctx.card()
        setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(14))
        addView(ctx.sideLabel(title, right, 11f))
        maxText = ctx.dotText("0°", if (compact) 40f else 44f)
        addView(maxText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(4) })
        if (!compact) addView(ctx.text(tr(R.string.max_label), 11f, C.SUB).apply { setPadding(0, ctx.dpi(2), 0, 0) })
        timeText = ctx.text("0s", if (compact) 12f else 17f, if (compact) C.SUB2 else C.TEXT, weight = if (compact) 400 else 500)
        addView(timeText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(if (compact) 4 else 10)
        })
        if (!compact) addView(ctx.text(tr(R.string.leaning_label), 11f, C.SUB).apply { setPadding(0, ctx.dpi(2), 0, 0) })
    }

    fun set(maxDeg: Float, timeMs: Long) {
        maxText.text = "${maxDeg.roundToInt()}°"
        timeText.text = if (compact) tr(R.string.leaning_time, fmtDuration(timeMs)) else fmtDuration(timeMs)
    }
}

/** Valor + etiqueta pequeña, centrados. */
fun Context.stat(value: String, label: String, valueSp: Float = 17f): Pair<LinearLayout, TextView> {
    val v = text(value, valueSp, C.TEXT, weight = 700).apply { gravity = Gravity.CENTER }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(v)
        addView(text(label, 11f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dpi(4), 0, 0) })
    }
    return col to v
}
