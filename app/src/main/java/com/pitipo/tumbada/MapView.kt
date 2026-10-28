package com.pitipo.tumbada

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------ Proyección Web Mercator (0..1)

fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

fun mercY(lat: Double): Double {
    val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

// ------------------------------------------------------------ Teselas del mapa

/**
 * Fondo del mapa:
 *  - OSM: OpenStreetMap estándar, sin clave. Se oscurece en el móvil (negativo en grises) para el estilo Glifo.
 *    Política de uso de OSM: User-Agent propio, máx. 2 descargas a la vez y caché local de al menos 7 días.
 *  - CARTO: «Dark Matter», el oscuro original. Desde septiembre de 2026 exige una clave gratuita
 *    (uso personal: hasta 5 millones de teselas al mes); sin ella sale la marca «API KEY REQUIRED».
 */
enum class MapStyle { OSM, CARTO }

object TileCache {
    private const val UA = "MyRideStats/1.8 (Android; com.pitipo.tumbada)"
    /** Una tesela real pesa < 100 KB; más de 1 MiB no es una tesela. */
    private const val MAX_TILE_BYTES = 1L shl 20
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(2)
    private val pending = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val views = HashSet<MapView>()
    /** Mapas que esperan cada tesela: al llegar solo se redibujan esos, no todos. */
    private val waiters = HashMap<String, HashSet<MapView>>()
    /** Mapas cuya petición se descartó por la cola llena: se reintentan al liberarse hueco. */
    private val starved = HashSet<MapView>()
    private var base: File? = null
    private var prefs: android.content.SharedPreferences? = null

    var style = MapStyle.OSM
        private set
    var cartoKey = ""
        private set

    /** Las teselas de OSM son de 256 px: se pide un nivel más de zoom para que se vean nítidas. */
    val zoomOffset: Int get() = if (style == MapStyle.OSM) 1 else 0

    /** Tema claro: teselas claras (OSM tal cual, o CARTO «light»). Tema oscuro: oscurecidas. */
    var light = false
        private set

    fun setLight(isLight: Boolean) {
        if (light == isLight) return
        light = isLight
        mem.evictAll()
        failed.clear()
        for (v in views) v.invalidate()
    }

    /** Oscuro: OSM en negativo gris y algo atenuado; CARTO ya es oscuro y solo se atenúa. Claro: OSM algo desaturado. */
    val filter: android.graphics.ColorFilter?
        get() = when {
            light -> if (style == MapStyle.OSM) osmLightFilter else null
            style == MapStyle.OSM -> osmFilter
            else -> cartoFilter
        }

    private val osmLightFilter = android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix().apply { setSaturation(0.35f) }
    )

    private val cartoFilter = android.graphics.LightingColorFilter(0xFFA8A8A8.toInt(), 0)
    private val osmFilter = run {
        val a = 0.82f
        val b = 255f * a + 6f
        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
            -a * 0.299f, -a * 0.587f, -a * 0.114f, 0f, b,
            -a * 0.299f, -a * 0.587f, -a * 0.114f, 0f, b,
            -a * 0.299f, -a * 0.587f, -a * 0.114f, 0f, b,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    val attribution: String
        get() = if (style == MapStyle.OSM) "© OpenStreetMap" else "© OpenStreetMap · © CARTO"

    private val mem = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtMost(48 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun dirName(): String = when {
        style == MapStyle.OSM -> "tiles-osm"
        light -> "tiles-carto-light"
        else -> "tiles-carto"
    }

    private fun dir(): File? = base?.let { File(it, dirName()).apply { mkdirs() } }

    fun init(ctx: Context) {
        if (base != null) return
        val b = ctx.cacheDir
        base = b
        val pr = ctx.getSharedPreferences("map", Context.MODE_PRIVATE)
        prefs = pr
        cartoKey = pr.getString("carto_key", "") ?: ""
        style = if (pr.getString("style", "OSM") == "CARTO" && cartoKey.isNotBlank()) MapStyle.CARTO else MapStyle.OSM
        exec.execute {
            // La caché de versiones anteriores tiene las teselas con la marca de agua: fuera
            File(b, "tiles").deleteRecursively()
            // Si crece demasiado, borra la mitad más antigua; y nada de más de 30 días
            val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            for (name in listOf("tiles-osm", "tiles-carto", "tiles-carto-light")) {
                val files = File(b, name).listFiles() ?: continue
                files.filter { it.lastModified() < limit || it.name.endsWith(".tmp") }.forEach { it.delete() }
                val rest = files.filter { it.exists() }
                if (rest.size > 4000) rest.sortedBy { it.lastModified() }.take(rest.size / 2).forEach { it.delete() }
            }
        }
    }

    fun configure(newStyle: MapStyle, key: String) {
        cartoKey = key.trim()
        style = if (newStyle == MapStyle.CARTO && cartoKey.isNotBlank()) MapStyle.CARTO else MapStyle.OSM
        prefs?.edit()?.putString("style", style.name)?.putString("carto_key", cartoKey)?.apply()
        mem.evictAll()
        failed.clear()
        exec.execute { for (n in listOf("tiles-carto", "tiles-carto-light")) File(base ?: return@execute, n).deleteRecursively() } // por si cambió la clave
        for (v in views) v.invalidate()
    }

    fun attach(v: MapView) { views.add(v) }
    fun detach(v: MapView) {
        views.remove(v)
        starved.remove(v)
        for (w in waiters.values) w.remove(v)
    }

    private fun key(z: Int, x: Int, y: Int) = "${style.name}_${if (light) "L" else "D"}_${z}_${x}_$y"

    fun get(z: Int, x: Int, y: Int): Bitmap? = mem.get(key(z, x, y))

    fun request(z: Int, x: Int, y: Int, v: MapView) {
        val k = key(z, x, y)
        if (k in pending) { waiters.getOrPut(k) { HashSet() }.add(v); return }
        val f = failed[k]
        if (f != null && System.currentTimeMillis() - f < 30_000) return
        // Como mucho una pantalla de teselas en cola: al mover el mapa no se acumulan descargas viejas
        if (pending.size > 40) { starved.add(v); return }
        pending.add(k)
        waiters.getOrPut(k) { HashSet() }.add(v)
        val d = dir()
        val st = style
        val ck = cartoKey
        val lt = light
        exec.execute {
            var bmp: Bitmap? = null
            try {
                val file = if (d != null) File(d, "${z}_${x}_$y.png") else null
                val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                if (file != null && file.exists()) {
                    val b = file.readBytes()
                    bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o)
                    if (bmp == null) file.delete() // caché corrupta: se volverá a descargar
                }
                if (bmp == null) {
                    val b = download(st, ck, lt, z, x, y)
                    if (b != null) {
                        bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o)
                        // Solo se guarda lo que decodifica bien, y de forma atómica (temporal + renombrar)
                        if (bmp != null && file != null) {
                            val tmp = File(file.parentFile, file.name + ".tmp")
                            tmp.writeBytes(b)
                            if (!tmp.renameTo(file)) tmp.delete()
                        }
                    }
                }
            } catch (_: Exception) {
            }
            // Sube la textura a la GPU fuera del hilo principal (evita el tirón al pintarla por primera vez)
            bmp?.prepareToDraw()
            val result = bmp
            main.post {
                pending.remove(k)
                val w = waiters.remove(k)
                if (result != null) {
                    mem.put(k, result)
                    w?.forEach { if (it in views) it.invalidate() }
                } else {
                    failed[k] = System.currentTimeMillis()
                }
                if (starved.isNotEmpty()) {
                    val again = starved.toList()
                    starved.clear()
                    again.forEach { if (it in views) it.invalidate() }
                }
            }
        }
    }

    private fun download(st: MapStyle, ck: String, lt: Boolean, z: Int, x: Int, y: Int): ByteArray? {
        val url = when (st) {
            MapStyle.OSM -> URL("https://tile.openstreetmap.org/$z/$x/$y.png")
            MapStyle.CARTO -> {
                val sub = "abcd"[(x + y) and 3]
                URL("https://$sub.basemaps.cartocdn.com/${if (lt) "light_all" else "dark_all"}/$z/$x/$y@2x.png?key=" +
                    java.net.URLEncoder.encode(ck, "UTF-8"))
            }
        }
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) return null
            if (c.contentType?.startsWith("image/") != true) return null
            if (c.contentLengthLong > MAX_TILE_BYTES) return null
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TILE_BYTES) return null
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}

// ------------------------------------------------------------ Vista de mapa de una ruta

/**
 * Mapa de una ruta: recorrido coloreado por inclinación (gris < 5°, azul izquierda, naranja derecha,
 * más intenso cuanto más tumbado) y las curvas marcadas con sus grados.
 *
 * interactive = false -> miniatura del historial (sin gestos, solo los máximos de cada lado).
 */
class MapView(
    ctx: Context,
    rides: List<Ride>,
    private val markers: List<Curve>,
    private val interactive: Boolean,
    private val heightDp: Int,
    /** Mapa de calor: varias rutas, color por cuántos grados (no por lado), algo translúcido. */
    private val heat: Boolean = false,
) : View(ctx) {

    companion object {
        /** Recorridos ya proyectados a Mercator (no se recalculan al volver a una pantalla). */
        private val projected = LruCache<String, Projected>(80)

        private fun project(r: Ride): Projected {
            val k = "${r.id}_${r.trackLat.size}"
            projected.get(k)?.let { return it }
            val xs = DoubleArray(r.trackLat.size) { mercX(r.trackLon[it]) }
            val ys = DoubleArray(r.trackLat.size) { mercY(r.trackLat[it]) }
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
            for (i in xs.indices) { x0 = min(x0, xs[i]); y0 = min(y0, ys[i]) }
            return Projected(xs, ys, x0, y0).also { projected.put(k, it) }
        }

        /** Grupos de color: 1° por grupo, de 0 a 60°, y separados por lado. */
        private const val BUCKETS_PER_SIDE = 61

        /** Mapa de una sola ruta: interactivo con todas sus curvas; miniatura con el máximo de cada lado. */
        fun forRide(ctx: Context, ride: Ride, interactive: Boolean, heightDp: Int): MapView {
            val located = ride.curves.filter { it.hasLocation }
            val markers = if (interactive) located.sortedByDescending { abs(it.peakDeg) }
            else listOfNotNull(
                located.filter { !it.isRight }.maxByOrNull { -it.peakDeg },
                located.filter { it.isRight }.maxByOrNull { it.peakDeg },
            )
            return MapView(ctx, listOf(ride), markers, interactive, heightDp)
        }
    }

    var onCurveSelected: ((Curve?) -> Unit)? = null
    /** Texto propio para el globo de un marcador (por ejemplo el nombre de una curva de siempre). */
    var tooltipText: ((Curve) -> String)? = null
    /** Si es true, el cursor de repetición se mantiene centrado. */
    var follow = true

    private class Projected(val xs: DoubleArray, val ys: DoubleArray, val minX: Double, val minY: Double)

    /** Lista de segmentos (x1, y1, x2, y2...) reutilizable, para pintarlos con un solo drawLines. */
    private class Segs {
        var pts = FloatArray(64)
        var n = 0
        fun add(a: Float, b: Float, c: Float, d: Float) {
            if (n + 4 > pts.size) pts = pts.copyOf(pts.size * 2)
            pts[n] = a; pts[n + 1] = b; pts[n + 2] = c; pts[n + 3] = d
            n += 4
        }
    }

    /**
     * Recorrido con su versión simplificada para el zoom actual: los puntos a menos de ~1,5 px se funden
     * (conservando la inclinación máxima del tramo) y los segmentos se agrupan por color. Así cada fotograma
     * son unas pocas llamadas drawLines en vez de miles de drawLine, y al desplazar el mapa no se recalcula.
     */
    private class Track(val p: Projected, val lean: FloatArray) {
        val xs get() = p.xs
        val ys get() = p.ys
        var zoom = Double.NaN
        var world = 0.0
        val shadow = Segs()
        val buckets = arrayOfNulls<Segs>(BUCKETS_PER_SIDE * 2)
    }

    private val density = ctx.resources.displayMetrics.density
    private val tileDisplay = 256f * density // tamaño de una tesela en pantalla

    private val tracks: List<Track> = rides.filter { it.hasTrack }.map { r -> Track(project(r), r.trackLean) }

    // Cursor de la repetición
    private var cursorX = Double.NaN
    private var cursorY = Double.NaN
    private var cursorLean = 0f
    private val markerX = HashMap<Curve, Float>()
    private val markerY = HashMap<Curve, Float>()
    private val drawnMarkers = ArrayList<Curve>()

    private var selected: Curve? = null

    // Estado de cámara: centro en coordenadas Mercator 0..1 y zoom continuo
    private var cx = 0.5
    private var cy = 0.5
    private var zoom = 2.0
    private var fitted = false

    // Teselas oscurecidas para que el negro de la app y el trazado manden (el filtro depende del fondo)
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.WHITE
        textAlign = Paint.Align.CENTER
        typeface = F.sans(ctx, 700)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.MAP_ATTR
        typeface = F.sans(ctx, 400)
    }
    private val src = Rect()
    private val dst = RectF()

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(log2(d.scaleFactor.toDouble()), d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            follow = false
            val world = worldSize()
            cx += dx / world
            cy = (cy + dy / world).coerceIn(0.0, 1.0)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAround(1.0, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            var best: Curve? = null
            var bestD = 30f * density
            for (c in drawnMarkers) {
                val d = hypot(e.x - (markerX[c] ?: continue), e.y - (markerY[c] ?: continue))
                if (d < bestD) { bestD = d; best = c }
            }
            selected = best
            onCurveSelected?.invoke(best)
            invalidate()
            return true
        }
    })

    init {
        TileCache.init(ctx)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TileCache.attach(this)
    }

    override fun onDetachedFromWindow() {
        TileCache.detach(this)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec)
        else (heightDp * density).roundToInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted) fitRoute()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    private fun worldSize(): Double = tileDisplay * 2.0.pow(zoom)

    private fun zoomAround(dz: Double, fx: Float, fy: Float) {
        val before = worldSize()
        val nx = cx + (fx - width / 2.0) / before
        val ny = cy + (fy - height / 2.0) / before
        zoom = (zoom + dz).coerceIn(3.0, 19.0)
        val after = worldSize()
        cx = nx - (fx - width / 2.0) / after
        cy = (ny - (fy - height / 2.0) / after).coerceIn(0.0, 1.0)
        invalidate()
    }

    /** Encaja toda la ruta en la vista. */
    fun fitRoute() {
        if (width == 0 || height == 0) return
        fitted = true
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        fun incl(x: Double, y: Double) {
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        for (t in tracks) for (i in t.xs.indices) incl(t.xs[i], t.ys[i])
        for (c in markers) incl(mercX(c.lon), mercY(c.lat))
        if (minX == Double.MAX_VALUE) {
            cx = 0.5; cy = 0.5; zoom = 3.0
        } else {
            cx = (minX + maxX) / 2
            cy = (minY + maxY) / 2
            val dx = max(maxX - minX, 1e-7)
            val dy = max(maxY - minY, 1e-7)
            val zx = log2(width * 0.82 / (dx * tileDisplay))
            val zy = log2(height * 0.78 / (dy * tileDisplay))
            zoom = min(zx, zy).coerceIn(3.0, 17.0)
        }
        invalidate()
    }

    /** Mueve el cursor de la repetición. */
    fun setCursor(lat: Double, lon: Double, lean: Float) {
        cursorX = mercX(lon)
        cursorY = mercY(lat)
        cursorLean = lean
        if (follow && width > 0) {
            cx = cursorX
            cy = cursorY
        }
        invalidate()
    }

    fun clearCursor() {
        cursorX = Double.NaN
        invalidate()
    }

    fun zoomTo(z: Double) {
        zoom = z.coerceIn(3.0, 19.0)
        invalidate()
    }

    /** Dibuja el mapa en un lienzo externo (tarjeta para compartir). */
    fun renderTo(canvas: Canvas, w: Int, h: Int) {
        measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
        fitted = false
        draw(canvas)
    }

    /** Centra y resalta una curva (al tocarla en la lista). */
    fun focusCurve(c: Curve) {
        if (!c.hasLocation) return
        selected = c
        cx = mercX(c.lon)
        cy = mercY(c.lat)
        if (zoom < 15.5) zoom = 15.5
        invalidate()
    }

    // ------------------------------------------------------------ Dibujo

    override fun onDraw(canvas: Canvas) {
        if (!fitted) fitRoute()
        canvas.drawColor(C.MAP_BG)
        val world = worldSize()
        val left = cx * world - width / 2.0
        val top = cy * world - height / 2.0

        drawTiles(canvas, world, left, top)
        for (t in tracks) drawTrack(canvas, t, world, left, top)
        drawMarkers(canvas, world, left, top)
        drawCursor(canvas, world, left, top)

        small.textSize = 9f * density
        small.textAlign = Paint.Align.RIGHT
        canvas.drawText(TileCache.attribution, width - 6f * density, height - 6f * density, small)
    }

    private fun drawTiles(canvas: Canvas, world: Double, left: Double, top: Double) {
        tilePaint.colorFilter = TileCache.filter
        val z = (floor(zoom + 0.3).toInt() + TileCache.zoomOffset).coerceIn(1, 19)
        val n = 1 shl z
        val tilePx = world / n
        val x0 = floor(left / tilePx).toInt()
        val x1 = floor((left + width) / tilePx).toInt()
        val y0 = floor(top / tilePx).toInt().coerceAtLeast(0)
        val y1 = floor((top + height) / tilePx).toInt().coerceAtMost(n - 1)
        for (tx in x0..x1) {
            val wx = ((tx % n) + n) % n
            for (ty in y0..y1) {
                val sx = (tx * tilePx - left).toFloat()
                val sy = (ty * tilePx - top).toFloat()
                dst.set(sx, sy, sx + tilePx.toFloat() + 0.5f, sy + tilePx.toFloat() + 0.5f)
                val bmp = TileCache.get(z, wx, ty)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, dst, tilePaint)
                } else {
                    TileCache.request(z, wx, ty, this)
                    // Mientras carga, se estira una tesela de menos zoom si ya está en memoria
                    for (k in 1..4) {
                        val pz = z - k
                        if (pz < 0) break
                        val parent = TileCache.get(pz, wx shr k, ty shr k) ?: continue
                        val part = parent.width shr k
                        val ox = (wx - ((wx shr k) shl k)) * part
                        val oy = (ty - ((ty shr k) shl k)) * part
                        src.set(ox, oy, ox + part, oy + part)
                        canvas.drawBitmap(parent, src, dst, tilePaint)
                        break
                    }
                }
            }
        }
    }

    private fun leanColor(lean: Float): Int {
        val a = abs(lean)
        val gray = C.STRAIGHT
        if (heat) {
            // Calor: gris oscuro -> blanco (20°) -> rojo (40°), sin distinguir lado
            if (a < 8f) return 0x66808080
            if (a < 20f) return blend(0xFF6E6E6E.toInt(), C.WHITE, (a - 8f) / 12f) and 0xDDFFFFFF.toInt()
            return blend(C.WHITE, C.RIGHT, ((a - 20f) / 20f).coerceAtMost(1f)) and 0xEEFFFFFF.toInt()
        }
        if (a < RideRecorder.SIDE_THRESHOLD) return gray
        val t = ((a - 5f) / 30f).coerceIn(0.25f, 1f)
        return blend(gray, if (lean > 0) C.RIGHT else C.LEFT, t)
    }

    private fun blend(c1: Int, c2: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        val r = (ch(c1, 16) + (ch(c2, 16) - ch(c1, 16)) * t).roundToInt()
        val g = (ch(c1, 8) + (ch(c2, 8) - ch(c1, 8)) * t).roundToInt()
        val b = (ch(c1, 0) + (ch(c2, 0) - ch(c1, 0)) * t).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun bucketOf(lean: Float): Int {
        val q = min(abs(lean).toInt(), BUCKETS_PER_SIDE - 1)
        return if (!heat && lean > 0f && abs(lean) >= RideRecorder.SIDE_THRESHOLD) BUCKETS_PER_SIDE + q else q
    }

    /** Inclinación representativa de un grupo (centro del grado), con su lado. */
    private fun bucketLean(b: Int): Float =
        if (b >= BUCKETS_PER_SIDE) (b - BUCKETS_PER_SIDE) + 0.5f else -(b + 0.5f)

    /** Recalcula los segmentos simplificados de un recorrido para este zoom (en px relativos a su esquina). */
    private fun rebuild(t: Track, world: Double) {
        t.zoom = zoom
        t.world = world
        t.shadow.n = 0
        for (b in t.buckets) b?.n = 0
        val xs = t.xs
        val ys = t.ys
        val ox = t.p.minX * world
        val oy = t.p.minY * world
        val minD2 = (1.5f * density).let { it * it }
        var px = (xs[0] * world - ox).toFloat()
        var py = (ys[0] * world - oy).toFloat()
        var peak = 0f
        val last = xs.size - 1
        for (i in 1..last) {
            val l = t.lean[i]
            if (abs(l) > abs(peak)) peak = l
            val x = (xs[i] * world - ox).toFloat()
            val y = (ys[i] * world - oy).toFloat()
            val dx = x - px
            val dy = y - py
            if (dx * dx + dy * dy < minD2 && i != last) continue
            val b = bucketOf(peak)
            val segs = t.buckets[b] ?: Segs().also { t.buckets[b] = it }
            segs.add(px, py, x, y)
            if (!heat) t.shadow.add(px, py, x, y)
            px = x; py = y
            peak = 0f
        }
    }

    private fun drawTrack(canvas: Canvas, t: Track, world: Double, left: Double, top: Double) {
        val xs = t.xs
        val ys = t.ys
        if (xs.size < 2) return
        if (t.zoom != zoom || t.world != world) rebuild(t, world)
        val base = (if (heat) 3f else if (interactive) 4f else 3f) * density

        canvas.save()
        canvas.translate((t.p.minX * world - left).toFloat(), (t.p.minY * world - top).toFloat())
        if (!heat && t.shadow.n > 0) {
            // Sombra para que se lea sobre cualquier fondo
            line.color = C.MAP_SHADOW
            line.strokeWidth = base + 3f * density
            canvas.drawLines(t.shadow.pts, 0, t.shadow.n, line)
        }
        for (b in t.buckets.indices) {
            val segs = t.buckets[b] ?: continue
            if (segs.n == 0) continue
            val lean = bucketLean(b)
            line.color = leanColor(lean)
            line.strokeWidth = if (heat) base + (abs(lean) / 40f).coerceAtMost(1f) * 3f * density
            else base + (abs(lean) / 45f).coerceAtMost(1f) * 2.5f * density
            canvas.drawLines(segs.pts, 0, segs.n, line)
        }
        canvas.restore()
        if (heat) return

        // Salida y llegada
        val r = 5f * density
        val sx = (xs[0] * world - left).toFloat()
        val sy = (ys[0] * world - top).toFloat()
        fill.color = C.BLACK
        canvas.drawCircle(sx, sy, r + 2 * density, fill)
        fill.color = C.WHITE
        canvas.drawCircle(sx, sy, r, fill)
        fill.color = C.BLACK
        canvas.drawCircle(sx, sy, r - 2f * density, fill)
        val l = xs.size - 1
        val ex = (xs[l] * world - left).toFloat()
        val ey = (ys[l] * world - top).toFloat()
        fill.color = C.BLACK
        canvas.drawCircle(ex, ey, r + 2 * density, fill)
        fill.color = C.WHITE
        canvas.drawCircle(ex, ey, r, fill)
    }

    private fun drawMarkers(canvas: Canvas, world: Double, left: Double, top: Double) {
        drawnMarkers.clear()
        val rad = (if (interactive) 13f else 12f) * density
        // De más tumbada a menos: si dos etiquetas se pisan, se queda la de más grados
        for (c in markers) {
            val x = (mercX(c.lon) * world - left).toFloat()
            val y = (mercY(c.lat) * world - top).toFloat()
            if (x < -rad || y < -rad || x > width + rad || y > height + rad) continue
            val overlaps = drawnMarkers.any { o -> hypot(x - markerX[o]!!, y - markerY[o]!!) < rad * 2.1f }
            if (overlaps && c != selected) continue
            markerX[c] = x
            markerY[c] = y
            drawnMarkers.add(c)
        }
        label.textSize = 10.5f * density
        for (c in drawnMarkers.asReversed()) {
            if (c == selected) continue
            drawMarker(canvas, c, rad)
        }
        selected?.let { s ->
            if (s in drawnMarkers) {
                label.textSize = 12.5f * density
                drawMarker(canvas, s, rad * 1.3f, selectedRing = true)
                drawTooltip(canvas, s, rad * 1.3f)
            }
        }
    }

    private fun drawMarker(canvas: Canvas, c: Curve, rad: Float, selectedRing: Boolean = false) {
        val x = markerX[c] ?: return
        val y = markerY[c] ?: return
        // Izquierda: negro con anillo blanco. Derecha: rojo. Seleccionada: anillo blanco grueso.
        val ring = if (selectedRing) 2.5f * density else if (c.isRight) 0f else 2f * density
        if (ring > 0f) {
            fill.color = C.WHITE
            canvas.drawCircle(x, y, rad, fill)
        }
        fill.color = if (c.isRight) C.RIGHT else C.BLACK
        canvas.drawCircle(x, y, rad - ring, fill)
        label.color = C.WHITE
        canvas.drawText(fmtDeg(c.peakDeg), x, y + label.textSize / 3f, label)
    }

    private fun drawCursor(canvas: Canvas, world: Double, left: Double, top: Double) {
        if (cursorX.isNaN()) return
        val x = (cursorX * world - left).toFloat()
        val y = (cursorY * world - top).toFloat()
        fill.color = (C.MAP_SHADOW and 0x00FFFFFF) or 0x55000000
        canvas.drawCircle(x, y, 14f * density, fill)
        fill.color = C.WHITE
        canvas.drawCircle(x, y, 9f * density, fill)
        fill.color = if (abs(cursorLean) >= RideRecorder.SIDE_THRESHOLD) C.side(cursorLean) else C.BLACK
        if (fill.color == C.WHITE) fill.color = C.BLACK
        canvas.drawCircle(x, y, 5.5f * density, fill)
    }

    private fun drawTooltip(canvas: Canvas, c: Curve, rad: Float) {
        val x = markerX[c] ?: return
        val y = markerY[c] ?: return
        val txt = tooltipText?.invoke(c) ?: (tr(R.string.tooltip_curve, tr(if (c.isRight) R.string.right else R.string.left),
            fmtDeg(c.peakDeg), fmtSecs(c.durationMs), fmtClock(c.tOffsetMs)) +
            (if (c.hasSpeeds) " · ${c.minKmh.roundToInt()} km/h" else ""))
        small.textSize = 12f * density
        small.color = C.WHITE
        small.textAlign = Paint.Align.CENTER
        val w = small.measureText(txt) + 20f * density
        val h = 26f * density
        var bx = x - w / 2
        bx = bx.coerceIn(4f * density, width - w - 4f * density)
        var by = y - rad - h - 6f * density
        if (by < 4f * density) by = y + rad + 6f * density
        dst.set(bx, by, bx + w, by + h)
        fill.color = C.TOOLTIP_BG
        canvas.drawRoundRect(dst, 8f * density, 8f * density, fill)
        line.color = C.GRID
        line.strokeWidth = 1f * density
        canvas.drawRoundRect(dst, 8f * density, 8f * density, line)
        canvas.drawText(txt, bx + w / 2, by + h / 2 + small.textSize / 3f, small)
        small.color = C.MAP_ATTR
    }
}
