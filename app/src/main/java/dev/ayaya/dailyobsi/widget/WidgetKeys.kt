package dev.ayaya.dailyobsi.widget

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.updateAll

/**
 * Per-widget-instance persisted state: "which heading is this instance
 * showing/editing", picked once at placement via [HeadingPickerActivity] and
 * stored keyed by GlanceId via PreferencesGlanceStateDefinition -- shared by
 * both the heading-shortcut widget (EditShortcutWidget) and the single-
 * heading render widget (HeadingWidget). Stores the header's literal raw
 * line text (e.g. "## Health"), not a line index -- a line index would go
 * stale the instant the note is a fresh file next day or lines shift above
 * it from an edit. Matched by exact line content in today's note at render
 * time; "not found" (heading no longer exists verbatim) is handled by each
 * widget's own fallback UI rather than crashing.
 */
val SELECTED_HEADING_KEY = stringPreferencesKey("selected_heading")

/** The emoji shown as EditShortcutWidget's center icon (and, where shown,
 *  next to HeadingWidget's title) -- picked in the same HeadingPickerActivity
 *  flow right after the heading, stored alongside it. Null/blank falls back
 *  to a default (see EditShortcutWidget), so an instance configured before
 *  this existed still renders fine without needing to be re-placed. */
val SELECTED_EMOJI_KEY = stringPreferencesKey("selected_emoji")

/**
 * Every widget reads the note fresh from disk on each provideGlance, so
 * "refresh" just means "recompose all placed instances of all three widget
 * classes" -- called after any edit that could have changed what's on
 * screen, whether the edit came from inside the app (MainActivity.persist)
 * or from a tap on a widget itself (checkbox toggle, progress +/-). Keeping
 * this in one place instead of each call site reaching for three separate
 * updateAll() calls is what makes it safe to add a fourth widget later
 * without hunting down every place that needed to know about it.
 */
suspend fun refreshAllWidgets(context: Context) {
    TodoWidget().updateAll(context)
    EditShortcutWidget().updateAll(context)
    HeadingWidget().updateAll(context)
    ReadingViewWidget().updateAll(context)
}
