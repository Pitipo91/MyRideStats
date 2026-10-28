package com.pitipo.tumbada

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Estima la inclinación de la moto SIN depender de cómo vaya el móvil en el bolsillo.
 *
 * Por qué no vale "medir el ángulo del móvil":
 *   En una curva bien trazada, gravedad + fuerza centrípeta apuntan justo a lo largo del
 *   eje vertical de la moto. El acelerómetro "siente" que va recto aunque vayas a 40°.
 *
 * Se combinan dos estimaciones independientes:
 *
 *  1) Cinemática (giroscopio + GPS):  tan(φ) = v·ω / g
 *     El acelerómetro filtrado da el "arriba" de la moto, sea cual sea la postura del móvil.
 *     Proyectando el giroscopio sobre ese eje se obtiene ω·cos(φ), y queda sin(φ) = v·ω_p / g.
 *     Da el lado (signo). Si el GPS va retrasado al frenar, sobrestima.
 *
 *  2) Carga lateral (solo acelerómetro):  |a| = g / cos(φ)
 *     No necesita velocidad ni lado. Frenar o acelerar solo puede AUMENTAR |a|,
 *     así que esta estimación nunca se queda corta: es una cota superior.
 *
 *  Inclinación = min(|1|, |2|) con el signo de (1). Así se corta la sobrestimación del GPS
 *  al frenar en curva, que es justo donde más falla.
 *
 * La orientación del móvil en el bolsillo se cancela sola. Lo único que se calibra
 * (automáticamente) es el sesgo del giroscopio y la escala del acelerómetro.
 *
 * Convención de signo: positivo = DERECHA, negativo = IZQUIERDA.
 */
class LeanEstimator {

    companion object {
        const val G = 9.80665f
        /** Por debajo de esta velocidad no se calcula inclinación (maniobras, semáforos). */
        const val MIN_SPEED_MS = 15f / 3.6f
        /** Constante de tiempo del filtro del vector "arriba" (s). */
        private const val UP_TAU = 0.35f
        /** Constante de tiempo del filtro de la velocidad de giro (s). */
        private const val YAW_TAU = 0.3f
        /** Muestras de giroscopio en reposo necesarias para dar el sesgo por bueno (~3 s). */
        private const val BIAS_SAMPLES_NEEDED = 150
        private const val MAX_SIN = 0.94f // ~70°, límite físico razonable
    }

    private val up = FloatArray(3)
    private var upReady = false
    private var lastAccelNs = 0L
    private var lastGyroNs = 0L

    private val bias = FloatArray(3)
    private var biasCount = 0

    /** |a| medido cuando la moto va derecha: corrige el error de escala del acelerómetro. */
    private var gRef = G
    private var gRefCount = 0

    private var yawRate = 0f

    /** Velocidad actual en m/s, la actualiza el servicio con el GPS. */
    @Volatile
    var speedMs = 0f

    /** Inclinación estimada en grados (+ derecha, - izquierda). */
    @Volatile
    var leanDeg = 0f
        private set

    val calibrated: Boolean get() = biasCount >= BIAS_SAMPLES_NEEDED

    fun onAccelerometer(v: FloatArray, tsNs: Long) {
        if (!upReady) {
            up[0] = v[0]; up[1] = v[1]; up[2] = v[2]
            upReady = true
            lastAccelNs = tsNs
            return
        }
        val dt = ((tsNs - lastAccelNs) / 1e9f).coerceIn(0f, 0.2f)
        lastAccelNs = tsNs
        val a = dt / (UP_TAU + dt)
        for (i in 0..2) up[i] += a * (v[i] - up[i])
    }

    fun onGyroscope(w: FloatArray, tsNs: Long) {
        if (!upReady) return
        val dt = if (lastGyroNs == 0L) 0.02f else ((tsNs - lastGyroNs) / 1e9f).coerceIn(0f, 0.2f)
        lastGyroNs = tsNs

        val n = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
        if (n < 4f) return // lectura absurda (caída libre, golpe)

        val wMag = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        val stopped = speedMs < 0.5f && wMag < 0.25f

        // Sesgo del giroscopio, aprendido parado y quieto
        if (stopped) {
            val k = if (biasCount < BIAS_SAMPLES_NEEDED) 1f / (biasCount + 1) else 0.005f
            for (i in 0..2) bias[i] += k * (w[i] - bias[i])
            if (biasCount < Int.MAX_VALUE) biasCount++
        }

        // Giro alrededor del eje vertical de la moto (+ = antihorario visto desde arriba = curva a la izquierda)
        val yaw = ((w[0] - bias[0]) * up[0] + (w[1] - bias[1]) * up[1] + (w[2] - bias[2]) * up[2]) / n
        val a = dt / (YAW_TAU + dt)
        yawRate += a * (yaw - yawRate)

        // Escala del acelerómetro: parado, o en recta a velocidad constante
        if (stopped || (speedMs > MIN_SPEED_MS && abs(yawRate) < 0.03f)) {
            val k = if (gRefCount < 200) 1f / (gRefCount + 1) else if (stopped) 0.01f else 0.002f
            gRef += k * (n - gRef)
            if (gRefCount < Int.MAX_VALUE) gRefCount++
        }

        if (speedMs < MIN_SPEED_MS) {
            leanDeg = 0f
            return
        }

        // 1) Cinemática
        val s = (speedMs * yawRate / G).coerceIn(-MAX_SIN, MAX_SIN)
        val kin = Math.toDegrees(asin(s.toDouble())).toFloat()

        // 2) Carga lateral (cota superior)
        val load = Math.toDegrees(acos((gRef / n).coerceIn(0f, 1f).toDouble())).toFloat()

        // Curva a la izquierda (yaw > 0) => negativo
        leanDeg = -sign(kin) * minOf(abs(kin), load)
    }
}

/**
 * Velocidad entre lecturas del GPS (que llegan a 1 Hz): extrapola la tendencia de las dos
 * últimas lecturas en vez de quedarse con la vieja. Reduce el retraso al frenar/acelerar.
 */
class SpeedPredictor {
    private var t1 = 0L
    private var v1 = 0f
    private var slope = 0f

    fun onFix(tMs: Long, v: Float) {
        if (t1 != 0L && tMs > t1) {
            val dt = (tMs - t1) / 1000f
            slope = if (dt < 3f) ((v - v1) / dt).coerceIn(-9f, 6f) else 0f
        }
        t1 = tMs
        v1 = v
    }

    fun speedAt(tMs: Long): Float {
        if (t1 == 0L) return 0f
        val dt = ((tMs - t1) / 1000f).coerceIn(0f, 1.5f)
        return (v1 + slope * dt).coerceAtLeast(0f)
    }

    fun lastFixMs(): Long = t1
}
