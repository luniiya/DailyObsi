package dev.ayaya.dailyobsi.storage

import android.content.Context
import android.util.Base64
import dev.ayaya.dailyobsi.model.BedtimeStyle
import dev.ayaya.dailyobsi.model.DEFAULT_BEDTIME
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.SectionId
import dev.ayaya.dailyobsi.model.SectionMode
import dev.ayaya.dailyobsi.model.normalizeHeading
import java.time.LocalDate
import java.time.LocalTime

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

    /** Last tab the user had open on [date]'s note; survives the process being killed. */
    fun lastSection(date: LocalDate): SectionId? {
        if (prefs.getString(KEY_LAST_SECTION_DATE, null) != date.toString()) return null
        val title = prefs.getString(KEY_LAST_SECTION_TITLE, null) ?: return null
        return SectionId(title, prefs.getInt(KEY_LAST_SECTION_OCCURRENCE, 0))
    }

    fun setLastSection(date: LocalDate, id: SectionId) {
        prefs.edit()
            .putString(KEY_LAST_SECTION_DATE, date.toString())
            .putString(KEY_LAST_SECTION_TITLE, id.normalizedTitle)
            .putInt(KEY_LAST_SECTION_OCCURRENCE, id.occurrence)
            .apply()
    }

    fun clearLastSection() {
        prefs.edit()
            .remove(KEY_LAST_SECTION_DATE)
            .remove(KEY_LAST_SECTION_TITLE)
            .remove(KEY_LAST_SECTION_OCCURRENCE)
            .apply()
    }

    /** The static bedtime the bedtime widget counts down to. */
    var bedtime: LocalTime
        get() = LocalTime.ofSecondOfDay(
            prefs.getInt(KEY_BEDTIME_MINUTES, DEFAULT_BEDTIME.toSecondOfDay() / 60).coerceIn(0, 1439) * 60L,
        )
        set(value) {
            prefs.edit().putInt(KEY_BEDTIME_MINUTES, value.hour * 60 + value.minute).apply()
        }

    var bedtimeStyle: BedtimeStyle
        get() = prefs.getString(KEY_BEDTIME_STYLE, null)
            ?.let { value -> runCatching { BedtimeStyle.valueOf(value) }.getOrNull() }
            ?: BedtimeStyle.ACCENT
        set(value) {
            prefs.edit().putString(KEY_BEDTIME_STYLE, value.name).apply()
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
        const val KEY_LAST_SECTION_DATE = "last_section_date"
        const val KEY_LAST_SECTION_TITLE = "last_section_title"
        const val KEY_LAST_SECTION_OCCURRENCE = "last_section_occurrence"
        const val KEY_BEDTIME_MINUTES = "bedtime_minutes"
        const val KEY_BEDTIME_STYLE = "bedtime_style"
    }
}
