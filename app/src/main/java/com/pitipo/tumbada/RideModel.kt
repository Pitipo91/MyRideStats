package com.pitipo.tumbada

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Una curva detectada. peakDeg con signo: + derecha, - izquierda. */
data class Curve(
    val tOffsetMs: Long,
    val durationMs: Long,
    val peakDeg: Float,
    /** Dónde se alcanzó el pico (NaN si la ruta no tenía GPS o es anterior a los mapas). */
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    /** Velocidades en km/h: al entrar, la mínima dentro de la curva y al salir (NaN en rutas antiguas). */
    val entryKmh: Float = Float.NaN,
    val minKmh: Float = Float.NaN,
    val exitKmh: Float = Float.NaN,
) {
    val isRight: Boolean get() = peakDeg > 0
    val hasLocation: Boolean get() = !lat.isNaN() && !lon.isNaN()
    val hasSpeeds: Boolean get() = !entryKmh.isNaN() && !minKmh.isNaN() && !exitKmh.isNaN()

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", tOffsetMs); put("d", durationMs); put("p", r1(peakDeg))
        if (hasLocation) { put("la", lat); put("lo", lon) }
        if (hasSpeeds) { put("ve", r1(entryKmh)); put("vm", r1(minKmh)); put("vs", r1(exitKmh)) }
    }

    companion object {
        private fun r1(v: Float): Double = (v * 10).roundToInt() / 10.0

        fun fromJson(o: JSONObject) = Curve(
            o.getLong("t"), o.getLong("d"), o.getDouble("p").toFloat(),
            o.optDouble("la", Double.NaN), o.optDouble("lo", Double.NaN),
            o.optDouble("ve", Double.NaN).toFloat(), o.optDouble("vm", Double.NaN).toFloat(),
            o.optDouble("vs", Double.NaN).toFloat(),
        )

        /** Formato de la versión 1.2: [t, d, pico, (lat, lon)] */
        fun fromLegacy(a: JSONArray) =
            if (a.length() >= 5) Curve(a.getLong(0), a.getLong(1), a.getDouble(2).toFloat(), a.getDouble(3), a.getDouble(4))
            else Curve(a.getLong(0), a.getLong(1), a.getDouble(2).toFloat())
    }
}

class Ride(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val distanceM: Float,
    val maxSpeedKmh: Float,
    val movingMs: Long,
    val maxLeft: Float,
    val maxRight: Float,
    val timeLeftMs: Long,
    val timeRightMs: Long,
    val curves: List<Curve>,
    val sampleMs: Long,
    val samples: FloatArray,
    /** Recorrido: un punto por lectura GPS y la inclinación máxima del tramo que acaba en él. */
    val trackLat: DoubleArray = DoubleArray(0),
    val trackLon: DoubleArray = DoubleArray(0),
    val trackLean: FloatArray = FloatArray(0),
    /** Momento de cada punto (ms desde el inicio) y velocidad en km/h. */
    val trackT: IntArray = IntArray(0),
    val trackSpeed: FloatArray = FloatArray(0),
) {
    val durationMs: Long get() = endTime - startTime
    val hasTrack: Boolean get() = trackLat.size >= 2

    /** Tiempo de cada punto; en rutas antiguas sin tiempos se reparte uniformemente. */
    fun pointTimeMs(i: Int): Long =
        if (trackT.size == trackLat.size) trackT[i].toLong()
        else if (trackLat.size <= 1) 0L
        else durationMs * i / (trackLat.size - 1)

    fun pointSpeed(i: Int): Float = if (trackSpeed.size == trackLat.size) trackSpeed[i] else Float.NaN

    /** Inclinación en un instante, desde la serie muestreada cada sampleMs. */
    fun leanAt(tMs: Long): Float {
        if (samples.isEmpty()) return 0f
        val f = tMs.toFloat() / sampleMs
        val i = f.toInt().coerceIn(0, samples.size - 1)
        val j = (i + 1).coerceAtMost(samples.size - 1)
        val k = (f - i).coerceIn(0f, 1f)
        return samples[i] + (samples[j] - samples[i]) * k
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 2)
        put("id", id)
        put("startTime", startTime)
        put("endTime", endTime)
        put("distanceM", distanceM.toDouble())
        put("maxSpeedKmh", maxSpeedKmh.toDouble())
        put("movingMs", movingMs)
        put("maxLeft", maxLeft.toDouble())
        put("maxRight", maxRight.toDouble())
        put("timeLeftMs", timeLeftMs)
        put("timeRightMs", timeRightMs)
        put("sampleMs", sampleMs)
        put("curves", JSONArray().apply { curves.forEach { put(it.toJson()) } })
        put("samples", JSONArray().apply {
            samples.forEach { put((it * 10).roundToInt()) } // décimas de grado
        })
        // Recorrido compacto: [lat·1e6, lon·1e6, inclinación·10, ms, km/h·10, ...]
        put("track2", JSONArray().apply {
            for (i in trackLat.indices) {
                put((trackLat[i] * 1e6).roundToLong())
                put((trackLon[i] * 1e6).roundToLong())
                put((trackLean[i] * 10).roundToInt())
                put(pointTimeMs(i))
                val sp = pointSpeed(i)
                put(if (sp.isNaN()) -1 else (sp * 10).roundToInt())
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Ride {
            val cj = o.optJSONArray("curves") ?: JSONArray()
            val curves = (0 until cj.length()).map { i ->
                val e = cj.get(i)
                if (e is JSONObject) Curve.fromJson(e) else Curve.fromLegacy(e as JSONArray)
            }
            val sj = o.optJSONArray("samples") ?: JSONArray()
            val samples = FloatArray(sj.length()) { i -> sj.getInt(i) / 10f }

            val t2 = o.optJSONArray("track2")
            val tLat: DoubleArray
            val tLon: DoubleArray
            val tLean: FloatArray
            var tT = IntArray(0)
            var tSp = FloatArray(0)
            if (t2 != null) {
                val n = t2.length() / 5
                tLat = DoubleArray(n) { i -> t2.getLong(i * 5) / 1e6 }
                tLon = DoubleArray(n) { i -> t2.getLong(i * 5 + 1) / 1e6 }
                tLean = FloatArray(n) { i -> t2.getInt(i * 5 + 2) / 10f }
                tT = IntArray(n) { i -> t2.getLong(i * 5 + 3).toInt() }
                tSp = FloatArray(n) { i -> t2.getInt(i * 5 + 4).let { v -> if (v < 0) Float.NaN else v / 10f } }
            } else {
                val tj = o.optJSONArray("track") ?: JSONArray()
                val n = tj.length() / 3
                tLat = DoubleArray(n) { i -> tj.getLong(i * 3) / 1e6 }
                tLon = DoubleArray(n) { i -> tj.getLong(i * 3 + 1) / 1e6 }
                tLean = FloatArray(n) { i -> tj.getInt(i * 3 + 2) / 10f }
            }
            return Ride(
                id = o.getString("id"),
                startTime = o.getLong("startTime"),
                endTime = o.getLong("endTime"),
                distanceM = o.optDouble("distanceM", 0.0).toFloat(),
                maxSpeedKmh = o.optDouble("maxSpeedKmh", 0.0).toFloat(),
                movingMs = o.optLong("movingMs", 0),
                maxLeft = o.optDouble("maxLeft", 0.0).toFloat(),
                maxRight = o.optDouble("maxRight", 0.0).toFloat(),
                timeLeftMs = o.optLong("timeLeftMs", 0),
                timeRightMs = o.optLong("timeRightMs", 0),
                curves = curves,
                sampleMs = o.optLong("sampleMs", 500),
                samples = samples,
                trackLat = tLat,
                trackLon = tLon,
                trackLean = tLean,
                trackT = tT,
                trackSpeed = tSp,
            )
        }
    }
}

/** Acumula estadísticas durante la ruta. */
class RideRecorder(val startTime: Long) {

    companion object {
        /** A partir de aquí cuenta como "inclinado hacia ese lado" para el tiempo. */
        const val SIDE_THRESHOLD = 5f
        /** Entrada/salida de curva (histéresis). */
        const val CURVE_IN = 10f
        const val CURVE_OUT = 5f
        /** Curvas más cortas se descartan (baches, cambios de carril bruscos). */
        const val MIN_CURVE_MS = 700L
        const val SAMPLE_MS = 500L
    }

    var distanceM = 0f; private set
    var maxSpeedKmh = 0f; private set
    var movingMs = 0L; private set
    var timeLeftMs = 0L; private set
    var timeRightMs = 0L; private set
    private var maxLeftClosed = 0f
    private var maxRightClosed = 0f
    val curves = ArrayList<Curve>()
    private val samples = ArrayList<Float>()

    private var lastTick = 0L
    private var lastSample = 0L
    private var curveStart = -1L
    private var curvePeak = 0f
    private var curvePeakT = 0L

    // Recorrido
    private val tLat = ArrayList<Double>()
    private val tLon = ArrayList<Double>()
    private val tLean = ArrayList<Float>()
    private val tTime = ArrayList<Long>()
    private val tSpeed = ArrayList<Float>()
    private var segLean = 0f

    // Velocidades de la curva en curso
    private var curveEntry = 0f
    private var curveMin = 0f
    private var lastSpeed = 0f
    /** Velocidad ~1 s antes (la de "entrada" se toma antes de empezar a tumbar). */
    private val speedHist = FloatArray(10)
    private var speedHistI = 0
    private val curveSpeeds = ArrayList<FloatArray>()
    /** Momento del pico de cada curva cerrada (para situarla en el mapa al terminar). */
    private val curvePeakTimes = ArrayList<Long>()

    /** Máximos: curvas cerradas + la curva en curso (si ya supera el mínimo de duración). */
    fun maxLeft(now: Long): Float {
        val live = if (curveStart >= 0 && curvePeak < 0 && now - curveStart >= MIN_CURVE_MS) -curvePeak else 0f
        return maxOf(maxLeftClosed, live)
    }

    fun maxRight(now: Long): Float {
        val live = if (curveStart >= 0 && curvePeak > 0 && now - curveStart >= MIN_CURVE_MS) curvePeak else 0f
        return maxOf(maxRightClosed, live)
    }

    fun addDistance(m: Float) {
        distanceM += m
    }

    /** Llamar con cada lectura GPS buena. */
    fun onLocation(now: Long, lat: Double, lon: Double, speedKmh: Float) {
        tLat.add(lat); tLon.add(lon); tTime.add(now); tSpeed.add(speedKmh)
        tLean.add(segLean)
        segLean = 0f
    }

    fun tick(now: Long, lean: Float, speedKmh: Float) {
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 1000L)
        lastTick = now

        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
        if (speedKmh >= 15f) movingMs += dt

        if (lean >= SIDE_THRESHOLD) timeRightMs += dt
        else if (lean <= -SIDE_THRESHOLD) timeLeftMs += dt

        if (abs(lean) > abs(segLean)) segLean = lean
        lastSpeed = speedKmh
        speedHist[speedHistI] = speedKmh
        speedHistI = (speedHistI + 1) % speedHist.size
        if (curveStart >= 0 && speedKmh < curveMin) curveMin = speedKmh

        // Detección de curvas con histéresis
        val a = abs(lean)
        if (curveStart < 0) {
            if (a >= CURVE_IN) startCurve(now, lean)
        } else {
            val sameSide = (lean > 0) == (curvePeak > 0)
            if (sameSide && a > abs(curvePeak)) { curvePeak = lean; curvePeakT = now }
            if (a < CURVE_OUT || (!sameSide && a >= CURVE_OUT)) {
                closeCurve(now)
                if (a >= CURVE_IN) startCurve(now, lean) // enlazada (ese)
            }
        }

        if (now - lastSample >= SAMPLE_MS) {
            samples.add((lean * 10f).roundToInt() / 10f)
            lastSample = now
        }
    }

    private fun startCurve(now: Long, lean: Float) {
        curveStart = now
        curvePeak = lean
        curvePeakT = now
        // speedHistI apunta a la muestra más antigua (~1 s atrás)
        curveEntry = maxOf(speedHist[speedHistI], lastSpeed)
        curveMin = lastSpeed
    }

    private fun closeCurve(now: Long) {
        val d = now - curveStart
        if (d >= MIN_CURVE_MS) {
            curves.add(Curve(curveStart - startTime, d, curvePeak))
            curvePeakTimes.add(curvePeakT)
            curveSpeeds.add(floatArrayOf(curveEntry, minOf(curveMin, curveEntry), lastSpeed))
            if (curvePeak > 0) maxRightClosed = maxOf(maxRightClosed, curvePeak)
            else maxLeftClosed = maxOf(maxLeftClosed, -curvePeak)
        }
        curveStart = -1L
        curvePeak = 0f
    }

    /** Posición en el instante t, interpolando entre lecturas GPS. */
    private fun locationAt(t: Long): Pair<Double, Double>? {
        if (tTime.isEmpty()) return null
        if (t <= tTime[0]) return if (tTime[0] - t < 5000) tLat[0] to tLon[0] else null
        val last = tTime.size - 1
        if (t >= tTime[last]) return if (t - tTime[last] < 5000) tLat[last] to tLon[last] else null
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (tTime[mid] <= t) lo = mid else hi = mid
        }
        if (tTime[hi] - tTime[lo] > 10_000) return null // hueco sin GPS
        val f = (t - tTime[lo]).toDouble() / (tTime[hi] - tTime[lo])
        return (tLat[lo] + (tLat[hi] - tLat[lo]) * f) to (tLon[lo] + (tLon[hi] - tLon[lo]) * f)
    }

    fun finish(end: Long): Ride {
        if (curveStart >= 0) closeCurve(end)
        val located = curves.mapIndexed { i, c ->
            val sp = curveSpeeds[i]
            val withSpeeds = c.copy(entryKmh = sp[0], minKmh = sp[1], exitKmh = sp[2])
            val p = locationAt(curvePeakTimes[i])
            if (p != null) withSpeeds.copy(lat = p.first, lon = p.second) else withSpeeds
        }
        return Ride(
            id = startTime.toString(),
            startTime = startTime,
            endTime = end,
            distanceM = distanceM,
            maxSpeedKmh = maxSpeedKmh,
            movingMs = movingMs,
            maxLeft = maxLeftClosed,
            maxRight = maxRightClosed,
            timeLeftMs = timeLeftMs,
            timeRightMs = timeRightMs,
            curves = located,
            sampleMs = SAMPLE_MS,
            samples = samples.toFloatArray(),
            trackLat = tLat.toDoubleArray(),
            trackLon = tLon.toDoubleArray(),
            trackLean = tLean.toFloatArray(),
            trackT = IntArray(tTime.size) { (tTime[it] - startTime).toInt() },
            trackSpeed = tSpeed.toFloatArray(),
        )
    }
}

class RideRepository(context: Context) {
    private val dir = File(context.filesDir, "rides").apply { mkdirs() }

    fun save(ride: Ride) {
        val tmp = File(dir, "${ride.id}.json.tmp")
        tmp.writeText(ride.toJson().toString())
        tmp.renameTo(File(dir, "${ride.id}.json"))
    }

    /** Solo ids numéricos (el instante de inicio): nunca un id del contenido puede apuntar fuera de rides/. */
    private val idPattern = Regex("^[0-9]{1,19}$")

    fun list(): List<Ride> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Ride.fromJson(JSONObject(f.readText())) }.getOrNull()
                // El id interno debe coincidir con el nombre del fichero (descarta ficheros manipulados)
                ?.takeIf { idPattern.matches(it.id) && f.name == "${it.id}.json" } }
            .sortedByDescending { it.startTime }

    fun delete(id: String) {
        if (!idPattern.matches(id)) return
        File(dir, "$id.json").delete()
    }
}

/** Estado en vivo compartido entre el servicio y la interfaz. */
data class LiveStats(
    val recording: Boolean = false,
    val startTime: Long = 0L,
    val elapsedMs: Long = 0L,
    val leanDeg: Float = 0f,
    val speedKmh: Float = 0f,
    val maxLeft: Float = 0f,
    val maxRight: Float = 0f,
    val timeLeftMs: Long = 0L,
    val timeRightMs: Long = 0L,
    val curves: Int = 0,
    val distanceM: Float = 0f,
    val gpsOk: Boolean = false,
    val calibrated: Boolean = false,
)

object TrackingState {
    @Volatile
    var live = LiveStats()
        private set

    /** Se incrementa cada vez que se guarda una ruta para refrescar el historial. */
    @Volatile
    var savedVersion = 0
        private set

    @Volatile
    var lastSavedId: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: Runnable) { listeners.add(l) }
    fun removeListener(l: Runnable) { listeners.remove(l) }

    fun setLive(s: LiveStats) {
        live = s
        notifyListeners()
    }

    fun onSaved(id: String) {
        lastSavedId = id
        savedVersion++
        notifyListeners()
    }

    private fun notifyListeners() {
        main.post { for (l in listeners) l.run() }
    }
}
