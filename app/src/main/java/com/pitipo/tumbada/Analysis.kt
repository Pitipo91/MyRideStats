package com.pitipo.tumbada

import android.content.Context
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ============================================================ Tus curvas de siempre

/** Una pasada por una curva concreta en una ruta concreta. */
class Pass(val ride: Ride, val curve: Curve)

/** Un sitio donde has tumbado más de una vez. */
class Place(
    val id: String,
    val lat: Double,
    val lon: Double,
    val isRight: Boolean,
    val number: Int,
) {
    val passes = ArrayList<Pass>()
    val best: Pass get() = passes.maxByOrNull { abs(it.curve.peakDeg) }!!
    val first: Pass get() = passes.first()
    val last: Pass get() = passes.last()
    val rideCount: Int get() = passes.size
    /** Diferencia entre la última pasada y la primera (grados). */
    val trend: Float get() = abs(last.curve.peakDeg) - abs(first.curve.peakDeg)

    fun defaultName(): String = tr(R.string.place_default, number, tr(if (isRight) R.string.right_lower else R.string.left_lower))
}

object Places {
    /** Radio para considerar que dos curvas son "la misma" (m). */
    private const val RADIUS_M = 40.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Agrupa las curvas de todas las rutas por sitio y lado. El sitio queda fijado por la primera
     * vez que pasaste (ruta más antigua), así que su identificador es estable al añadir rutas.
     * Devuelve solo los sitios por los que has pasado en al menos 2 rutas distintas.
     */
    fun build(rides: List<Ride>): List<Place> {
        val all = ArrayList<Place>()
        var n = 0
        for (ride in rides.sortedBy { it.startTime }) {
            for (c in ride.curves) {
                if (!c.hasLocation) continue
                var target: Place? = null
                var bestD = RADIUS_M
                for (p in all) {
                    if (p.isRight != c.isRight) continue
                    val d = distanceM(p.lat, p.lon, c.lat, c.lon)
                    if (d < bestD) { bestD = d; target = p }
                }
                if (target == null) {
                    n++
                    target = Place(
                        String.format(Locale.US, "%.5f,%.5f,%s", c.lat, c.lon, if (c.isRight) "R" else "L"),
                        c.lat, c.lon, c.isRight, n,
                    )
                    all.add(target)
                }
                // Si en la misma ruta pasas dos veces (ida y vuelta), cuenta la más tumbada
                val same = target.passes.indexOfFirst { it.ride.id == ride.id }
                if (same >= 0) {
                    if (abs(c.peakDeg) > abs(target.passes[same].curve.peakDeg)) target.passes[same] = Pass(ride, c)
                } else {
                    target.passes.add(Pass(ride, c))
                }
            }
        }
        return all.filter { it.rideCount >= 2 }
            .sortedWith(compareByDescending<Place> { it.rideCount }.thenByDescending { abs(it.best.curve.peakDeg) })
    }
}

/** Nombres que pones a tus curvas, guardados en el móvil. */
class PlaceNames(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("place_names", Context.MODE_PRIVATE)
    fun name(p: Place): String = prefs.getString(p.id, null)?.takeIf { it.isNotBlank() } ?: p.defaultName()
    fun hasCustom(p: Place): Boolean = !prefs.getString(p.id, null).isNullOrBlank()
    fun rename(p: Place, name: String) {
        prefs.edit().putString(p.id, name.trim()).apply()
    }
}

// ============================================================ Simetría

class Symmetry(
    /** Media de tus curvas más tumbadas a cada lado (grados). */
    val left: Float,
    val right: Float,
) {
    /** 100 = igual a los dos lados. */
    val index: Int get() = (min(left, right) / max(left, right) * 100).toInt()
    val weakerIsLeft: Boolean get() = left < right
    val gap: Float get() = abs(left - right)

    fun verdict(): String = when {
        gap < 2f -> tr(R.string.verdict_equal)
        else -> tr(R.string.verdict_less, gap.toInt(), tr(if (weakerIsLeft) R.string.left_lower else R.string.right_lower))
    }

    companion object {
        /** Compara la media de las N curvas más tumbadas de cada lado. Null si falta un lado. */
        fun of(curves: List<Curve>, topN: Int = 10): Symmetry? {
            val l = curves.filter { !it.isRight }.map { -it.peakDeg }.sortedDescending().take(topN)
            val r = curves.filter { it.isRight }.map { it.peakDeg }.sortedDescending().take(topN)
            if (l.size < 2 || r.size < 2) return null
            return Symmetry(l.average().toFloat(), r.average().toFloat())
        }
    }
}

// ============================================================ Progreso

class Period(
    val start: Long,
    val label: String,
) {
    var maxLeft = 0f
    var maxRight = 0f
    var km = 0f
    var curves = 0
    var rides = 0
}

object Progress {
    private val Es: Locale get() = L.locale()
    /** Mes abreviado en el idioma activo, sin punto final ("oct", "Oct"). */
    private fun month(cal: Calendar): String =
        java.text.SimpleDateFormat("MMM", Es).format(cal.time).trimEnd('.')

    /** weeks = true: últimas 8 semanas (lunes a domingo); si no, últimos 6 meses. Incluye periodos vacíos. */
    fun build(rides: List<Ride>, weeks: Boolean, now: Long = System.currentTimeMillis()): List<Period> {
        val count = if (weeks) 8 else 6
        val cal = Calendar.getInstance(Es)
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (weeks) {
            cal.firstDayOfWeek = Calendar.MONDAY
            while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_MONTH, -1)
            cal.add(Calendar.WEEK_OF_YEAR, -(count - 1))
        } else {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, -(count - 1))
        }
        val periods = ArrayList<Period>()
        repeat(count) {
            val label = if (weeks) "${cal.get(Calendar.DAY_OF_MONTH)} ${month(cal)}"
            else month(cal)
            periods.add(Period(cal.timeInMillis, label))
            if (weeks) cal.add(Calendar.WEEK_OF_YEAR, 1) else cal.add(Calendar.MONTH, 1)
        }
        val end = cal.timeInMillis
        for (r in rides) {
            if (r.startTime < periods[0].start || r.startTime >= end) continue
            val p = periods.last { it.start <= r.startTime }
            p.maxLeft = max(p.maxLeft, r.maxLeft)
            p.maxRight = max(p.maxRight, r.maxRight)
            p.km += r.distanceM / 1000f
            p.curves += r.curves.size
            p.rides++
        }
        return periods
    }
}
