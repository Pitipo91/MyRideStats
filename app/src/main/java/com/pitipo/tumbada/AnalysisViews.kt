package com.pitipo.tumbada

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Barras por semana o mes: máximos (pares izquierda/derecha), km o curvas. */
class ProgressChart(ctx: Context, private val periods: List<Period>, private val metric: Metric) : View(ctx) {
    enum class Metric { MAX, KM, CURVES }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(190))
    }

    private fun value(pd: Period): Float = when (metric) {
        Metric.MAX -> max(pd.maxLeft, pd.maxRight)
        Metric.KM -> pd.km
        Metric.CURVES -> pd.curves.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val d = context.dp(1f)
        val labelH = 22 * d
        val valueH = 18 * d
        val chartTop = valueH
        val chartBottom = height - labelH
        val chartH = chartBottom - chartTop
        val n = periods.size
        if (n == 0) return
        val slot = width.toFloat() / n
        val maxV = max(if (metric == Metric.MAX) 30f else 1f, periods.maxOf { value(it) })

        // Línea base
        p.color = C.GRID
        p.strokeWidth = d
        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, p)

        p.typeface = F.sans(context, 500)
        p.textAlign = Paint.Align.CENTER
        periods.forEachIndexed { i, pd ->
            val cx = slot * i + slot / 2
            val r = 2.5f * d
            if (metric == Metric.MAX) {
                val bw = (slot * 0.26f).coerceAtMost(14 * d)
                fun bar(x: Float, v: Float, color: Int) {
                    if (v <= 0f) return
                    val top = chartBottom - chartH * v / maxV
                    p.color = color
                    rect.set(x - bw / 2, top, x + bw / 2, chartBottom)
                    canvas.drawRoundRect(rect, r, r, p)
                }
                bar(cx - bw * 0.65f, pd.maxLeft, C.LEFT)
                bar(cx + bw * 0.65f, pd.maxRight, C.RIGHT)
                if (pd.rides > 0) {
                    p.textSize = 10 * d
                    p.color = C.SUB2
                    val v = max(pd.maxLeft, pd.maxRight)
                    canvas.drawText("${v.roundToInt()}°", cx, chartBottom - chartH * v / maxV - 5 * d, p)
                }
            } else {
                val v = value(pd)
                val bw = (slot * 0.5f).coerceAtMost(22 * d)
                if (v > 0f) {
                    val top = chartBottom - chartH * v / maxV
                    p.color = C.WHITE
                    rect.set(cx - bw / 2, top, cx + bw / 2, chartBottom)
                    canvas.drawRoundRect(rect, r, r, p)
                    p.textSize = 10 * d
                    p.color = C.SUB2
                    canvas.drawText(if (metric == Metric.KM) "${v.roundToInt()}" else "${v.toInt()}", cx, top - 5 * d, p)
                }
            }
            p.textSize = 10.5f * d
            p.color = if (pd.rides > 0) C.SUB2 else C.MUTED
            canvas.drawText(pd.label, cx, height - 5 * d, p)
        }
    }
}

/** Evolución de tu inclinación en una curva concreta, pasada a pasada. */
class EvolutionView(ctx: Context, private val values: List<Float>, private val right: Boolean,
                    private val heightDp: Int = 120, private val labels: Boolean = true) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(heightDp))
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val d = context.dp(1f)
        val padX = if (labels) 18 * d else 5 * d
        val padTop = if (labels) 22 * d else 5 * d
        val padBottom = 6 * d
        val lo = (values.min() - 3f).coerceAtLeast(0f)
        val hi = values.max() + 2f
        val w = width - 2 * padX
        val h = height - padTop - padBottom
        fun x(i: Int) = if (values.size == 1) width / 2f else padX + w * i / (values.size - 1)
        fun y(v: Float) = padTop + h * (1 - (v - lo) / (hi - lo))

        val color = if (right) C.RIGHT else C.LEFT
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (labels) 2.5f else 2f) * d
        p.strokeJoin = Paint.Join.ROUND
        p.color = color
        path.reset()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        canvas.drawPath(path, p)

        val best = values.indices.maxByOrNull { values[it] } ?: 0
        p.typeface = F.sans(context, 700)
        p.textAlign = Paint.Align.CENTER
        values.forEachIndexed { i, v ->
            val isLast = i == values.size - 1
            p.style = Paint.Style.FILL
            p.color = C.BLACK
            val rr = (if (isLast || i == best) 5f else 3.5f) * d
            canvas.drawCircle(x(i), y(v), rr + 1.5f * d, p)
            p.color = if (i == best) color else if (isLast) C.WHITE else C.STRAIGHT
            canvas.drawCircle(x(i), y(v), rr, p)
            if (labels && (i == best || isLast || i == 0)) {
                p.textSize = 11 * d
                p.color = if (i == best) color else C.SUB2
                canvas.drawText("${v.roundToInt()}°", x(i), y(v) - 10 * d, p)
            }
        }
    }
}

fun trendText(t: Float): String = when {
    abs(t) < 1f -> "="
    t > 0 -> "+${t.roundToInt()}°"
    else -> "−${abs(t).roundToInt()}°"
}
