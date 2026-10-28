package com.pitipo.tumbada

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// ============================================================ GPX

object Gpx {
    private fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(t))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun num(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

    /**
     * GPX 1.1 con la inclinación de cada punto (+ derecha, - izquierda) y la velocidad como extensiones,
     * y cada curva como waypoint.
     */
    fun build(ride: Ride): String {
        val name = tr(R.string.gpx_name, fmtDate(ride.startTime))
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MyRideStats\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:mrs=\"https://myridestats.app/gpx/1\">\n")
        sb.append("  <metadata><name>").append(esc(name)).append("</name><time>").append(iso(ride.startTime))
            .append("</time></metadata>\n")
        for (c in ride.curves) {
            if (!c.hasLocation) continue
            val side = tr(if (c.isRight) R.string.right else R.string.left)
            sb.append("  <wpt lat=\"").append(num(c.lat, 6)).append("\" lon=\"").append(num(c.lon, 6)).append("\">")
            sb.append("<time>").append(iso(ride.startTime + c.tOffsetMs)).append("</time>")
            sb.append("<name>").append(esc("$side ${fmtDeg(c.peakDeg)}")).append("</name>")
            val desc = StringBuilder(tr(R.string.leaning_time, fmtSecs(c.durationMs)))
            if (c.hasSpeeds) desc.append(tr(R.string.gpx_speeds, c.entryKmh.roundToInt(), c.minKmh.roundToInt(), c.exitKmh.roundToInt()))
            sb.append("<desc>").append(esc(desc.toString())).append("</desc>")
            sb.append("<extensions><mrs:lean>").append(num(c.peakDeg.toDouble(), 1)).append("</mrs:lean></extensions>")
            sb.append("</wpt>\n")
        }
        sb.append("  <trk><name>").append(esc(name)).append("</name><type>motorcycling</type><trkseg>\n")
        for (i in ride.trackLat.indices) {
            sb.append("    <trkpt lat=\"").append(num(ride.trackLat[i], 6)).append("\" lon=\"")
                .append(num(ride.trackLon[i], 6)).append("\">")
            sb.append("<time>").append(iso(ride.startTime + ride.pointTimeMs(i))).append("</time>")
            sb.append("<extensions><mrs:lean>").append(num(ride.trackLean[i].toDouble(), 1)).append("</mrs:lean>")
            val sp = ride.pointSpeed(i)
            if (!sp.isNaN()) sb.append("<mrs:speed>").append(num(sp / 3.6, 2)).append("</mrs:speed>")
            sb.append("</extensions></trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }
}

// ============================================================ Archivos para compartir

object Share {
    const val AUTHORITY = "com.pitipo.tumbada.export"

    fun dir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    /** Tiempo que se conservan los ficheros compartidos (la app receptora ya los habrá leído). */
    private const val KEEP_MS = 24L * 3600 * 1000

    /** Nombre único por exportación: id de la ruta + sufijo aleatorio. Nunca se reutiliza una URI ya concedida. */
    private fun fileName(ride: Ride, ext: String): String {
        val rnd = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(ride.startTime))
        return "myridestats_${date}_${ride.id}_$rnd.$ext"
    }

    /** Borra exportaciones antiguas (o todas las de una ruta concreta, al borrarla). */
    fun cleanup(ctx: Context, rideId: String? = null) {
        val now = System.currentTimeMillis()
        val files = dir(ctx).listFiles() ?: return
        for (f in files) {
            val ofRide = rideId != null && f.name.contains("_${rideId}_")
            if (ofRide || now - f.lastModified() > KEEP_MS) f.delete()
        }
    }

    fun shareGpx(ctx: Context, ride: Ride) {
        val f = File(dir(ctx), fileName(ride, "gpx"))
        f.writeText(Gpx.build(ride))
        send(ctx, f, "application/gpx+xml", tr(R.string.share_gpx_title))
    }

    fun shareImage(ctx: Context, ride: Ride, bmp: Bitmap) {
        val f = File(dir(ctx), fileName(ride, "png"))
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        send(ctx, f, "image/png", tr(R.string.share_img_title))
    }

    private fun send(ctx: Context, f: File, mime: String, title: String) {
        val uri = Uri.parse("content://$AUTHORITY/${Uri.encode(f.name)}")
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** Sirve de solo lectura los archivos de cache/exports a la app con la que compartes. */
class ExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        if (uri.pathSegments.size != 1) return null
        val name = uri.lastPathSegment ?: return null
        if (!NAME.matches(name)) return null
        val base = Share.dir(ctx).canonicalFile
        val f = File(base, name)
        // Defensa en profundidad: fichero regular y directamente dentro de exports/
        if (!f.isFile || f.canonicalFile.parentFile != base) return null
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Solo lectura")
        val f = fileFor(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
        private val NAME = Regex("^myridestats_[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{4}_[0-9]{1,19}_[0-9a-f]{12}\\.(gpx|png)$")
    }

    override fun getType(uri: Uri): String? = when {
        uri.lastPathSegment?.endsWith(".gpx") == true -> "application/gpx+xml"
        uri.lastPathSegment?.endsWith(".png") == true -> "image/png"
        else -> "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

// ============================================================ Tarjeta para compartir (estilo Glifo)

object ShareCard {
    const val W = 1080
    const val H = 1350

    fun render(ctx: Context, ride: Ride, map: MapView?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(C.BLACK)
        val pad = 72f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val dot = F.dot(ctx)
        val sans = F.sans(ctx, 400)
        val sansB = F.sans(ctx, 700)

        // Cabecera
        p.typeface = dot; p.textSize = 64f; p.color = C.WHITE
        c.drawText("MYRIDESTATS", pad, 130f, p)
        p.typeface = sans; p.textSize = 32f; p.color = C.SUB2; p.textAlign = Paint.Align.RIGHT
        c.drawText(fmtDate(ride.startTime), W - pad, 126f, p)
        p.textAlign = Paint.Align.LEFT

        // Mapa
        val mapTop = 180f
        val mapH = 560
        val mapRect = RectF(pad, mapTop, W - pad, mapTop + mapH)
        if (map != null && ride.hasTrack) {
            c.save()
            val clip = Path().apply { addRoundRect(mapRect, 36f, 36f, Path.Direction.CW) }
            c.clipPath(clip)
            c.translate(pad, mapTop)
            map.renderTo(c, (W - 2 * pad).toInt(), mapH)
            c.restore()
        } else {
            p.color = C.CARD
            c.drawRoundRect(mapRect, 36f, 36f, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = C.BORDER
        c.drawRoundRect(mapRect, 36f, 36f, p)
        p.style = Paint.Style.FILL

        // Máximos
        val colW = (W - 2 * pad) / 2f
        val labY = mapTop + mapH + 90f
        fun side(x: Float, label: String, deg: Float, timeMs: Long, right: Boolean) {
            val dotR = 9f
            if (right) {
                p.color = C.RIGHT; c.drawCircle(x + dotR, labY - 11f, dotR, p)
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = C.WHITE
                c.drawCircle(x + dotR, labY - 11f, dotR - 2f, p)
                p.style = Paint.Style.FILL
            }
            p.typeface = F.sans(ctx, 500); p.textSize = 30f; p.color = if (right) C.RIGHT else C.WHITE
            p.letterSpacing = 0.12f
            c.drawText(label, x + 2 * dotR + 14f, labY, p)
            p.letterSpacing = 0f
            p.typeface = dot; p.textSize = 200f; p.color = C.WHITE
            c.drawText("${deg.roundToInt()}°", x - 8f, labY + 190f, p)
            p.typeface = sans; p.textSize = 30f; p.color = C.SUB2
            c.drawText(tr(R.string.leaning_time, fmtDuration(timeMs)), x, labY + 245f, p)
        }
        side(pad, tr(R.string.left_upper), ride.maxLeft, ride.timeLeftMs, false)
        side(pad + colW + 12f, tr(R.string.right_upper), ride.maxRight, ride.timeRightMs, true)

        // Reparto
        val barY = labY + 290f
        val total = (ride.timeLeftMs + ride.timeRightMs).coerceAtLeast(1)
        val lf = ride.timeLeftMs.toFloat() / total
        val bw = W - 2 * pad - 10f
        p.color = C.WHITE
        c.drawRoundRect(RectF(pad, barY, pad + bw * lf, barY + 10f), 5f, 5f, p)
        p.color = C.RIGHT
        c.drawRoundRect(RectF(pad + bw * lf + 10f, barY, W - pad, barY + 10f), 5f, 5f, p)

        // Datos
        val statY = barY + 92f
        val stats = listOf(fmtKm(ride.distanceM) to tr(R.string.stat_distance), fmtDuration(ride.durationMs) to tr(R.string.stat_duration),
            "${ride.curves.size}" to tr(R.string.stat_curves), "${ride.maxSpeedKmh.roundToInt()} km/h" to tr(R.string.stat_top_speed))
        val sw = (W - 2 * pad) / stats.size
        stats.forEachIndexed { i, (v, l) ->
            val x = pad + sw * i
            p.typeface = sansB; p.textSize = 40f; p.color = C.WHITE
            c.drawText(v, x, statY, p)
            p.typeface = sans; p.textSize = 26f; p.color = C.SUB
            c.drawText(l, x, statY + 40f, p)
        }

        p.typeface = sans; p.textSize = 22f; p.color = C.MUTED
        c.drawText(tr(R.string.card_map, TileCache.attribution), pad, H - 48f, p)
        return bmp
    }
}
