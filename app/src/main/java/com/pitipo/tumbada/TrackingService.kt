package com.pitipo.tumbada

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Servicio en primer plano: sigue grabando con la pantalla apagada y el móvil en el bolsillo.
 */
class TrackingService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val CHANNEL_ID = "ride"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.pitipo.tumbada.STOP"
        private const val TICK_MS = 100L
        /** Rutas más cortas que esto no se guardan. */
        private const val MIN_RIDE_MS = 60_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TrackingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val estimator = LeanEstimator()
    private var recorder: RideRecorder? = null

    private var speedMs = 0f
    private var lastFixMs = 0L
    private val speedPredictor = SpeedPredictor()
    private var lastLoc: Location? = null
    private var lastNotifMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppSettings.wrap(base))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (recorder == null) begin()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun begin() {
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(0f, 0f, 0L), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, buildNotification(0f, 0f, 0L))
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        recorder = RideRecorder(now)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRideStats:ride")
            .apply { acquire(8 * 60 * 60 * 1000L) }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, 20_000)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, 20_000)
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopSelf()
            return
        }

        TrackingState.setLive(LiveStats(recording = true, startTime = now))
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        wakeLock?.let { if (it.isHeld) it.release() }

        val rec = recorder
        recorder = null
        if (rec != null) {
            val ride = rec.finish(System.currentTimeMillis())
            if (ride.durationMs >= MIN_RIDE_MS) {
                val repo = RideRepository(applicationContext)
                thread {
                    repo.save(ride)
                    TrackingState.onSaved(ride.id)
                }
            }
        }
        TrackingState.setLive(LiveStats(recording = false))
        super.onDestroy()
    }

    private fun onTick() {
        val rec = recorder ?: return
        val now = System.currentTimeMillis()
        val gpsOk = now - lastFixMs < 4000
        val predicted = if (gpsOk) speedPredictor.speedAt(now) else 0f
        estimator.speedMs = predicted

        val lean = estimator.leanDeg
        val kmh = predicted * 3.6f
        rec.tick(now, lean, kmh)

        val maxL = rec.maxLeft(now)
        val maxR = rec.maxRight(now)
        TrackingState.setLive(LiveStats(
            recording = true,
            startTime = rec.startTime,
            elapsedMs = now - rec.startTime,
            leanDeg = lean,
            speedKmh = kmh,
            maxLeft = maxL,
            maxRight = maxR,
            timeLeftMs = rec.timeLeftMs,
            timeRightMs = rec.timeRightMs,
            curves = rec.curves.size,
            distanceM = rec.distanceM,
            gpsOk = gpsOk,
            calibrated = estimator.calibrated,
        ))

        if (now - lastNotifMs > 2000) {
            lastNotifMs = now
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(maxL, maxR, rec.distanceM.toLong()))
        }
    }

    // ---- Sensores ----

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> estimator.onAccelerometer(event.values, event.timestamp)
            Sensor.TYPE_GYROSCOPE -> estimator.onGyroscope(event.values, event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- GPS ----

    override fun onLocationChanged(loc: Location) {
        val now = System.currentTimeMillis()
        val prev = lastLoc
        var sp = when {
            loc.hasSpeed() -> loc.speed
            prev != null && loc.time > prev.time ->
                prev.distanceTo(loc) / ((loc.time - prev.time) / 1000f)
            else -> 0f
        }
        // Filtro de saltos absurdos del GPS (> ~1 g de aceleración)
        if (lastFixMs != 0L && now - lastFixMs < 2500 && sp - speedMs > 10f) sp = speedMs

        if (prev != null && loc.accuracy < 30f && prev.accuracy < 30f && sp > 1f) {
            recorder?.addDistance(prev.distanceTo(loc))
        }
        // Punto del recorrido para el mapa (parado no se repiten puntos)
        if (loc.accuracy < 30f && (sp > 1f || prev == null)) {
            recorder?.onLocation(now, loc.latitude, loc.longitude, sp * 3.6f)
        }
        speedMs = sp
        lastFixMs = now
        speedPredictor.onFix(now, sp)
        lastLoc = loc
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---- Notificación ----

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(maxL: Float, maxR: Float, distM: Long): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val km = distM / 1000f
        val stopAction = Notification.Action.Builder(null, getString(R.string.btn_stop), stopPi).build()
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lean)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(R.string.notif_text, maxL.roundToInt(), maxR.roundToInt(), String.format(resources.configuration.locales[0], "%.1f", km))
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFE8322F.toInt()) // rojo de marca, igual en ambos temas
            .setContentIntent(openPi)
            .addAction(stopAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }
}
