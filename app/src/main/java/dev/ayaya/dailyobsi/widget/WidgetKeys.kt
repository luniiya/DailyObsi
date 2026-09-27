package dev.ayaya.dailyobsi.widget

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import android.net.Uri
import dev.ayaya.dailyobsi.DailyNote
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import dev.ayaya.dailyobsi.storage.noteWriteMutex

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
 * updateAll() calls is what makes it safe to add another widget later
 * without hunting down every place that needed to know about it.
 *
 * Prefer [requestWidgetRefresh] over calling this directly from a widget
 * action -- see its doc comment for why a raw, un-debounced call here is
 * exactly what caused real, confirmed data-staleness bugs under rapid
 * repeated taps.
 */
suspend fun refreshAllWidgets(context: Context) {
    val tag = "DailyObsiWidget"
    // updateAll() = manager.getGlanceIds(javaClass).forEach { update(context, it) } --
    // if getGlanceIds() resolves to an empty list (its own internal
    // provider->receiver mapping datastore not knowing about this class
    // yet), the forEach silently does nothing and updateAll() still
    // "returns" with no error at all. Logging the enumeration result
    // directly (not just "updateAll() returned") is the only way to tell
    // those two cases apart from logcat.
    val manager = GlanceAppWidgetManager(context)
    widgetDataVersion.incrementAndGet()
    android.util.Log.d(tag, "refreshAllWidgets: starting")
    val editIds = manager.getGlanceIds(EditShortcutWidget::class.java)
    android.util.Log.d(tag, "refreshAllWidgets: EditShortcutWidget glanceIds=$editIds")
    EditShortcutWidget().updateAll(context)
    val headingIds = manager.getGlanceIds(HeadingWidget::class.java)
    android.util.Log.d(tag, "refreshAllWidgets: HeadingWidget glanceIds=$headingIds")
    HeadingWidget().updateAll(context)
    val readingIds = manager.getGlanceIds(ReadingViewWidget::class.java)
    android.util.Log.d(tag, "refreshAllWidgets: ReadingViewWidget glanceIds=$readingIds")
    ReadingViewWidget().updateAll(context)
    android.util.Log.d(tag, "refreshAllWidgets: all done")
}

/**
 * Serializes every widget-triggered read-modify-write of the daily note
 * (checkbox toggle, progress +/-) -- a real race, not theoretical: each
 * ActionCallback independently does `readText` → compute new content →
 * `writeText`, with no coordination between calls. Two taps close enough
 * together (rapid taps across different lines, or -- the bug that actually
 * surfaced this -- a single tap on a checkbox firing two separate handlers,
 * see GlanceMarkdownLine's Row/CheckBox comment) can interleave: the second
 * call's `readText` can happen before the first call's `writeText` lands,
 * so the second call computes its new content from a file that doesn't yet
 * include the first call's change -- and its own `writeText` then silently
 * overwrites (loses) that change entirely. `withLock { }` around each
 * action's own read-modify-write (write only -- see [requestWidgetRefresh]
 * for the refresh side, deliberately *not* held under this lock) forces
 * writes to run one at a time instead.
 */
/**
 * Round 1 of fixing "widget shows stale data after rapid taps" held
 * [refreshAllWidgets] (plus a fixed settle delay) inside [noteWriteMutex]'s
 * lock for every single tap. Wrong, and confirmed worse by the very next
 * real-device test: since every tap's ActionCallback now blocked for the
 * full delay before returning, tapping a progress-bar +/- rapidly (the
 * reported case: "pressing plus 200 times and it still shows 0/4") made the
 * widget feel completely dead, not just laggy -- each tap could only start
 * once the previous one's artificial wait had fully elapsed.
 *
 * Round 2 replaced that with a `MutableSharedFlow.debounce(200)` collected
 * on a detached, always-running `CoroutineScope(SupervisorJob() +
 * Dispatchers.Default)`, fired via a non-suspend `tryEmit`. This looked
 * right (individual taps stayed instant, a burst collapsed into one trailing
 * refresh) and fixed the interleaved-write race it targeted, but a *harder*
 * bug survived it and took direct on-device `adb shell input tap` + logcat
 * tracing to actually pin down: recompose would silently never land at all
 * -- not late, not stale, just never -- whenever a tap happened without
 * `MainActivity` having been foregrounded recently. Confirmed by tapping a
 * widget checkbox seconds after opening the app (full recompose landed
 * within ~200ms) versus the identical tap 30+ seconds after backgrounding it
 * (the write and the `refreshAllWidgets()` enumeration both completed and
 * logged successfully, but no `provideGlance`/recompose ever followed, for
 * any widget class, even after 40+ seconds of polling). Root cause: Glance's
 * session runs on a `SessionWorker` (WorkManager, see AppWidgetSession.kt's
 * own doc comment), and Android's background execution limits can defer
 * WorkManager scheduling for an app with no current foreground
 * activity/exemption. A widget tap's `ActionCallback.onAction` itself gets a
 * legitimate temporary execution grant from the system (similar to a
 * BroadcastReceiver) -- but Round 2's `tryEmit` handed the actual refresh
 * off to a *detached* scope with no relationship to that grant at all, and
 * by the time the 200ms debounce elapsed and that independent coroutine
 * actually ran `refreshAllWidgets()`, it had nothing but the app's ordinary
 * (possibly long-backgrounded, possibly throttled) process state to run
 * under.
 *
 * The actual fix: make the debounce something each caller `suspend`s through
 * as part of its *own* call, instead of firing an event at a detached
 * collector. A monotonic generation counter replaces the SharedFlow: each
 * call stamps the next generation, delays 200ms *inline*, and then only
 * performs the real `refreshAllWidgets()` if no newer call has since
 * superseded it (bailing out otherwise) -- collapsing a burst into one
 * trailing refresh exactly as before, but the delay and the refresh both now
 * run inside whichever coroutine the caller already had (an
 * `ActionCallback.onAction`, or `MainActivity.persist()`'s `scope.launch`),
 * inheriting whatever execution standing that context legitimately has
 * instead of escaping it.
 */
private val refreshGeneration = AtomicLong(0)

/** Call this from a widget action (or `MainActivity.persist()`) after
 *  writing to the note, instead of calling [refreshAllWidgets] directly --
 *  see the doc comment above [refreshGeneration] for why. Now `suspend`:
 *  callers should call it directly (not fire-and-forget in a separate
 *  scope), so the debounce wait and the eventual refresh both stay part of
 *  the caller's own execution context. */
suspend fun requestWidgetRefresh(context: Context) {
    val myGeneration = refreshGeneration.incrementAndGet()
    android.util.Log.d("DailyObsiWidget", "requestWidgetRefresh: requested gen=$myGeneration")
    delay(200)
    if (refreshGeneration.get() == myGeneration) {
        android.util.Log.d("DailyObsiWidget", "requestWidgetRefresh: gen=$myGeneration is latest, refreshing now")
        refreshAllWidgets(context)
    } else {
        android.util.Log.d("DailyObsiWidget", "requestWidgetRefresh: gen=$myGeneration superseded by ${refreshGeneration.get()}, skipping")
    }
}

/**
 * Bumped by [refreshAllWidgets] right before it recomposes every widget.
 * HeadingWidget/ReadingViewWidget key their `produceState` note load on it,
 * so a refresh reloads the note on Dispatchers.IO instead of in the
 * composable body. Glance runs widget composition on the app's *main*
 * thread, so the old "plain blocking read directly in the composable" pattern
 * put a full SAF folder listing + file read on the UI thread per widget per
 * refresh -- the real cause of multi-second in-app freezes on every save
 * (caught by sampling main-thread stacks: SessionWorker -> provideContent ->
 * DailyNote.findTodayFile, only with widgets placed).
 */
val widgetDataVersion = AtomicLong(0)

data class TodayNote(val name: String, val uri: Uri, val text: String)

private data class CachedTodayFile(val treeUri: Uri, val date: LocalDate, val uri: Uri, val name: String)

@Volatile private var cachedTodayFile: CachedTodayFile? = null

/**
 * Reads today's note, remembering its URI so repeat reads skip the
 * folder listing (one query over ~500 files on the real vault). A cached URI
 * that no longer reads (file deleted/renamed by a git pull) falls back to a
 * fresh lookup. Blocking -- call from Dispatchers.IO only.
 */
fun readTodayNote(context: Context, treeUri: Uri): TodayNote? {
    val today = LocalDate.now()
    cachedTodayFile?.takeIf { it.treeUri == treeUri && it.date == today }?.let { cached ->
        val text = runCatching { DailyNote.readText(context, cached.uri) }.getOrNull()
        if (text != null) return TodayNote(cached.name, cached.uri, text)
        cachedTodayFile = null
    }
    val file = DailyNote.findTodayFile(context, treeUri) ?: return null
    val name = file.name ?: "Today"
    cachedTodayFile = CachedTodayFile(treeUri, today, file.uri, name)
    return TodayNote(name, file.uri, DailyNote.readText(context, file.uri))
}
