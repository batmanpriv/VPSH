package com.batman.vpsh.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREFS = "vpsh_locale"
    private const val KEY_LANG = "lang"

    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val PERSIAN = "fa"

    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, lang).apply()
    }

    fun wrap(context: Context): Context {
        val lang = getLanguage(context)
        if (lang.isEmpty()) return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
