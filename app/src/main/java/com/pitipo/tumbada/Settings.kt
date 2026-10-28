package com.pitipo.tumbada

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.WindowInsetsController
import java.util.Locale

// ------------------------------------------------------------ Textos traducidos

/** Recursos del idioma activo (los fija MainActivity al crearse). */
object L {
    lateinit var res: Resources

    fun locale(): Locale = res.configuration.locales[0]
}

fun tr(id: Int): String = L.res.getString(id)
fun tr(id: Int, vararg args: Any): String = L.res.getString(id, *args)
fun trq(id: Int, n: Int): String = L.res.getQuantityString(id, n, n)

// ------------------------------------------------------------ Ajustes de la app

object AppSettings {
    enum class Theme { SYSTEM, DARK, LIGHT }

    /** Idiomas incluidos. "" = el del sistema. */
    val LANGUAGES = listOf("", "es", "en")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun theme(ctx: Context): Theme =
        runCatching { Theme.valueOf(prefs(ctx).getString("theme", "SYSTEM")!!) }.getOrDefault(Theme.SYSTEM)

    fun setTheme(ctx: Context, t: Theme) {
        prefs(ctx).edit().putString("theme", t.name).apply()
    }

    fun isDark(ctx: Context): Boolean = when (theme(ctx)) {
        Theme.DARK -> true
        Theme.LIGHT -> false
        Theme.SYSTEM -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** Idioma elegido ("" = sistema). En Android 13+ lo guarda el propio sistema (idioma por app). */
    fun language(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = ctx.getSystemService(LocaleManager::class.java)
            val tags = lm.applicationLocales
            if (tags.isEmpty) "" else tags[0].language
        } else {
            prefs(ctx).getString("lang", "") ?: ""
        }

    fun setLanguage(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // El sistema recrea la actividad con el nuevo idioma
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString("lang", tag).commit()
            activity.recreate()
        }
    }

    /** Android 9-12: aplica el idioma guardado envolviendo el contexto. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "") ?: ""
        if (tag.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(cfg)
    }

    /** Paleta de colores y fondo del mapa según el tema. */
    fun applyColors(activity: Activity) {
        val dark = isDark(activity)
        C.apply(dark)
        TileCache.setLight(!dark)
    }

    /** Color e iconos de las barras del sistema. Nunca debe impedir que la app arranque. */
    @Suppress("DEPRECATION")
    fun applySystemBars(activity: Activity) = runCatching {
        val dark = C.dark
        val w = activity.window
        w.statusBarColor = C.BG
        w.navigationBarColor = C.BG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val v = w.decorView
            v.systemUiVisibility = if (dark) v.systemUiVisibility and flags.inv() else v.systemUiVisibility or flags
        }
    }
}
