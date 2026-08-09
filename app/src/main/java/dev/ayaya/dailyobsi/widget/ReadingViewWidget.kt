package dev.ayaya.dailyobsi.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import android.net.Uri
import dev.ayaya.dailyobsi.Block
import dev.ayaya.dailyobsi.DailyNote
import dev.ayaya.dailyobsi.VaultPrefs
import dev.ayaya.dailyobsi.parseBlocks

/**
 * Widget 3 of 3 (see CLAUDE.md's "Planned: three-widget system"): the whole
 * note's reading view, same interactive Glance renderer as HeadingWidget
 * (checkboxes/progress +/- tappable, images shown) but scoped to every
 * block in the file instead of one heading's body -- no per-widget config
 * screen needed, there's nothing to pick. Tapping the header row opens the
 * app to today's note (reading mode default) for anything this compact
 * renderer can't do (folding sections, the section editor, swipe-to-indent).
 */
class ReadingViewWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    // This widget has no per-instance config of its own, but still needs
    // *a* stateDefinition + a currentState() read inside provideContent --
    // see EditShortcutWidget's doc comment. Without subscribing to
    // LocalState somewhere, refreshAllWidgets()/updateAll() on an already-
    // running session (true the instant this widget is first placed) never
    // reaches this composable again, so a checkbox tap or an in-app edit
    // would silently stop updating the widget's face after the first render.
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val light = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context) else lightColorScheme()
        val dark = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()

        provideContent {
            // Read but not otherwise used -- see the stateDefinition doc
            // comment above; this is purely what makes recomposition happen
            // on refresh at all.
            currentState<Preferences>()

            // Plain blocking calls, called directly and unconditionally on
            // every recomposition -- same pattern as TodoWidget.kt and
            // HeadingWidget.kt's HeadingSync, not memoized behind
            // remember()/produceState, so this always reflects the file as
            // it is right now rather than whatever it was on first placement.
            val treeUri = VaultPrefs.getTreeUri(context)
            val sync: ReadingSync = if (treeUri == null) {
                ReadingSync.NoFolder
            } else {
                val file = DailyNote.findTodayFile(context, treeUri)
                if (file == null) {
                    ReadingSync.NoNoteToday
                } else {
                    val text = DailyNote.readText(context, file.uri)
                    ReadingSync.Loaded(file.name ?: "Today", parseBlocks(text), treeUri)
                }
            }

            val embeds by produceState(initialValue = emptyMap<String, ImageProvider>(), key1 = sync) {
                value = if (sync is ReadingSync.Loaded) resolveEmbedImagesForGlance(context, sync.treeUri, sync.blocks) else emptyMap()
            }

            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .clickable(actionStartActivity<dev.ayaya.dailyobsi.MainActivity>())
                            .padding(12.dp, 12.dp, 12.dp, 4.dp)
                    ) {
                        Text(
                            when (sync) {
                                is ReadingSync.Loaded -> sync.title
                                else -> "DailyObsi"
                            },
                            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface)
                        )
                    }
                    when (sync) {
                        is ReadingSync.NoFolder -> ReadingWidgetMessage("Open DailyObsi and pick your daily folder.")
                        is ReadingSync.NoNoteToday -> ReadingWidgetMessage("No note for today yet. Open the app to create one.")
                        is ReadingSync.Loaded -> {
                            if (sync.blocks.all { it is Block.Line && it.raw.isBlank() }) {
                                ReadingWidgetMessage("Nothing here yet.")
                            } else {
                                LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                                    items(sync.blocks.size) { i ->
                                        Column(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                            GlanceMarkdownBlocks(listOf(sync.blocks[i]), embeds)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed class ReadingSync {
    object NoFolder : ReadingSync()
    object NoNoteToday : ReadingSync()
    data class Loaded(val title: String, val blocks: List<Block>, val treeUri: Uri) : ReadingSync()
}

@androidx.compose.runtime.Composable
private fun ReadingWidgetMessage(message: String) {
    Text(message, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.padding(12.dp))
}

class ReadingViewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadingViewWidget()
}
