package dev.ayaya.dailyobsi.widget

import android.content.Context
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
import dev.ayaya.dailyobsi.ui.appColorScheme
import dev.ayaya.dailyobsi.Block
import dev.ayaya.dailyobsi.VaultPrefs
import dev.ayaya.dailyobsi.parseBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val light = appColorScheme(context, dark = false)
        val dark = appColorScheme(context, dark = true)

        val initialVersion = widgetDataVersion.get()
        val initial = loadReadingView(context)

        provideContent {
            // Read but not otherwise used -- see the stateDefinition doc
            // comment above; this is purely what makes recomposition happen
            // on refresh at all.
            currentState<Preferences>()
            val version = widgetDataVersion.get()
            // The note load runs on Dispatchers.IO, never in this composable
            // body: Glance composes on the app's main thread (see
            // widgetDataVersion). The first frame uses the load done above,
            // before provideContent, so there's no empty flash on placement.
            val loaded by produceState(initialValue = initial, version) {
                if (version != initialVersion) value = loadReadingView(context)
            }
            val sync = loaded.sync
            val embeds = loaded.embeds

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
                                    // itemId derived from content (not just
                                    // position) -- an experimental fix for a
                                    // real, still-reproducing bug: list items
                                    // specifically (not the header Text above)
                                    // kept showing stale checkbox state after
                                    // a confirmed-correct write + refresh,
                                    // while non-list content updated fine.
                                    // Glance's default itemId is position-only
                                    // ("maintains scroll position through
                                    // updates" per its own doc, nothing about
                                    // content) -- RemoteViews list-backed
                                    // widgets are documented to need an
                                    // explicit notify when *content* at an
                                    // existing position changes, not just a
                                    // fresh RemoteViews push, and a stable
                                    // per-position id with no content signal
                                    // is exactly the shape of input that
                                    // triggers that gap. Folding the block's
                                    // own hashCode (content-derived, since
                                    // Block.Line's raw text includes the
                                    // checkbox mark) into the id means a
                                    // content change looks like a genuinely
                                    // different item at that position.
                                    items(
                                        count = sync.blocks.size,
                                        itemId = { i -> (i.toLong() shl 20) xor (sync.blocks[i].hashCode().toLong() and 0xFFFFF) }
                                    ) { i ->
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

private class ReadingLoad(val sync: ReadingSync, val embeds: Map<String, ImageProvider>)

/** File lookup + read + parse + embed decode, all on Dispatchers.IO. */
private suspend fun loadReadingView(context: Context): ReadingLoad = withContext(Dispatchers.IO) {
    val treeUri = VaultPrefs.getTreeUri(context)
    val note = treeUri?.let { readTodayNote(context, it) }
    val sync = when {
        treeUri == null -> ReadingSync.NoFolder
        note == null -> ReadingSync.NoNoteToday
        else -> ReadingSync.Loaded(note.name, parseBlocks(note.text), treeUri)
    }
    val embeds = if (sync is ReadingSync.Loaded) {
        resolveEmbedImagesForGlance(context, sync.treeUri, sync.blocks)
    } else {
        emptyMap()
    }
    ReadingLoad(sync, embeds)
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
