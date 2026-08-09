package dev.ayaya.dailyobsi.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.DailyNote
import dev.ayaya.dailyobsi.VaultPrefs
import kotlinx.coroutines.sync.withLock

private val LINE_INDEX_KEY = ActionParameters.Key<Int>("line_index")

class TodoWidget : GlanceAppWidget() {
    // See EditShortcutWidget's doc comment on the same property (widget/
    // EditShortcutWidget.kt) -- without a stateDefinition + a currentState()
    // read inside provideContent, refreshAllWidgets()/updateAll() on an
    // already-running session (true the instant any widget is first placed)
    // never actually reaches this composable again. This widget's data
    // loading was already unconditional/direct (not memoized), which is the
    // *other* half of the fix -- but that alone doesn't help if
    // recomposition itself never gets triggered in the first place.
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            currentState<Preferences>() // read but unused -- see doc comment above
            val treeUri = VaultPrefs.getTreeUri(context)

            Column(
                modifier = GlanceModifier.fillMaxSize().background(Color.White).padding(12.dp)
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        "Today's to-dos",
                        style = TextStyle(fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        "↻",
                        modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>())
                    )
                }

                if (treeUri == null) {
                    Text("Open DailyObsi and pick your daily folder.")
                    return@Column
                }

                val file = DailyNote.findTodayFile(context, treeUri)
                if (file == null) {
                    Text("No note for today. Open the app to create one.")
                    return@Column
                }

                val text = DailyNote.readText(context, file.uri)
                val items = DailyNote.parseCheckboxes(text)

                if (items.isEmpty()) {
                    Text("No checkbox items in today's note.")
                    return@Column
                }

                // A plain Column here (one Row per item, unbounded) hits a
                // real RemoteViews limit -- "Column container cannot have
                // more than 10 elements" -- the instant a real daily note
                // has more than 10 checkbox items, which it does. LazyColumn
                // has no such cap since it doesn't inflate every child into
                // one flat container up front.
                LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                    items(items.size) { i ->
                        val item = items[i]
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable(
                                    actionRunCallback<ToggleTodoAction>(
                                        actionParametersOf(LINE_INDEX_KEY to item.lineIndex)
                                    )
                                )
                        ) {
                            Text(if (item.checked) "☑ " else "☐ ")
                            Text(item.text)
                        }
                    }
                }
            }
        }
    }
}

/** Manual "pull latest from disk" tap -- provideGlance always re-reads the
 *  file fresh, so forcing an update is enough to reflect an external change
 *  (e.g. Termux's git pull) without waiting for the ~30min OS refresh tick.
 *  Refreshes every widget class (not just this one) since HeadingWidget/
 *  ReadingViewWidget read the exact same file and can go equally stale. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        refreshAllWidgets(context)
    }
}

class ToggleTodoAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val lineIndex = parameters[LINE_INDEX_KEY] ?: return
        val treeUri = VaultPrefs.getTreeUri(context) ?: return
        val file = DailyNote.findTodayFile(context, treeUri) ?: return

        // See WidgetKeys.kt's noteWriteMutex doc comment -- shared across
        // every widget's read-modify-write, not just this one, since a tap
        // here and a tap on HeadingWidget/ReadingViewWidget both write the
        // exact same file.
        noteWriteMutex.withLock {
            val text = DailyNote.readText(context, file.uri)
            val updated = DailyNote.toggleCheckbox(text, lineIndex)
            DailyNote.writeText(context, file.uri, updated)
        }

        refreshAllWidgets(context)
    }
}
