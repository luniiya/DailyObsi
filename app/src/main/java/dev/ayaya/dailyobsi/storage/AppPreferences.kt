package dev.ayaya.dailyobsi.storage

import android.content.Context
import android.util.Base64
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.SectionMode
import dev.ayaya.dailyobsi.model.normalizeHeading

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var layoutMode: LayoutMode
        get() = prefs.getString(KEY_LAYOUT, null)
            ?.let { value -> runCatching { LayoutMode.valueOf(value) }.getOrNull() }
            ?: LayoutMode.TABBED
        set(value) {
            prefs.edit().putString(KEY_LAYOUT, value.name).apply()
        }

    fun sectionMode(title: String): SectionMode {
        val normalized = normalizeHeading(title)
        val stored = prefs.getString(modeKey(normalized), null)
        return stored?.let { value -> runCatching { SectionMode.valueOf(value) }.getOrNull() }
            ?: if (normalized == "report") SectionMode.WRITE else SectionMode.READ
    }

    fun setSectionMode(title: String, mode: SectionMode) {
        prefs.edit().putString(modeKey(normalizeHeading(title)), mode.name).apply()
    }

    private fun modeKey(normalizedTitle: String): String {
        val encoded = Base64.encodeToString(
            normalizedTitle.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$MODE_PREFIX$encoded"
    }

    private companion object {
        const val PREFS = "dailyobsi_ui"
        const val KEY_LAYOUT = "layout_mode"
        const val MODE_PREFIX = "section_mode_"
    }
}
