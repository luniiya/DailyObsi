package dev.ayaya.dailyobsi.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.DailyNote
import dev.ayaya.dailyobsi.VaultPrefs

private val LINE_INDEX_KEY = ActionParameters.Key<Int>("line_index")

class TodoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
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

                items.forEach { item ->
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

/** Manual "pull latest from disk" tap -- provideGlance always re-reads the
 *  file fresh, so forcing an update is enough to reflect an external change
 *  (e.g. Termux's git pull) without waiting for the ~30min OS refresh tick. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TodoWidget().update(context, glanceId)
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

        val text = DailyNote.readText(context, file.uri)
        val updated = DailyNote.toggleCheckbox(text, lineIndex)
        DailyNote.writeText(context, file.uri, updated)

        TodoWidget().update(context, glanceId)
    }
}
