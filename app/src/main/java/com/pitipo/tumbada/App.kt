package com.pitipo.tumbada

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Aplicación: instala lo antes posible un registro de errores. Si la app se cierra por un fallo,
 * el detalle se guarda en files/last_crash.txt y se muestra en el siguiente arranque.
 */
class MyRideStatsApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashLog.install(this)
    }
}

object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = ctx.filesDir
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
                File(dir, FILE).writeText(
                    "MyRideStats ${info?.versionName} (${info?.longVersionCode}) · Android ${android.os.Build.VERSION.RELEASE} " +
                        "(API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "Hilo: ${t.name}\n\n$sw"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Devuelve y borra el último error guardado, o null si no hay. */
    fun take(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text
    }

    /** Pantalla mínima, sin tema ni traducciones (no puede depender de lo que haya fallado). */
    fun show(activity: Activity, report: String, onContinue: () -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(0xFF000000.toInt())
        }
        col.addView(TextView(activity).apply {
            text = "La app se cerró por un error / The app closed because of an error"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, pad)
        })
        col.addView(TextView(activity).apply {
            text = "Copia este informe y envíaselo al desarrollador. / Copy this report and send it to the developer."
            textSize = 14f
            setTextColor(0xFFBDBDBD.toInt())
            setPadding(0, 0, 0, pad)
        })
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        buttons.addView(Button(activity).apply {
            text = "Copiar / Copy"
            setOnClickListener {
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("MyRideStats crash", report))
                text = "Copiado ✓"
            }
        })
        buttons.addView(Button(activity).apply {
            text = "Continuar / Continue"
            setOnClickListener { onContinue() }
        })
        col.addView(buttons)
        col.addView(TextView(activity).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE0E0E0.toInt())
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        })
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(col)
        })
    }
}
