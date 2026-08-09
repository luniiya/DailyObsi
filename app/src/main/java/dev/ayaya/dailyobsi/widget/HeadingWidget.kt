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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
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
import dev.ayaya.dailyobsi.headerBodyLineRange
import dev.ayaya.dailyobsi.parseBlocks

/**
 * Widget 2 of 3 (see CLAUDE.md's "Planned: three-widget system"): renders
 * one heading's body, read-only-but-interactive (checkboxes tappable,
 * progress +/- tappable, images shown), via the shared Glance renderer in
 * GlanceMarkdown.kt. Configured the same way as EditShortcutWidget --
 * HeadingPickerActivity, shared between the two -- but shows the section's
 * actual content instead of just being a shortcut into it.
 */
class HeadingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    // See EditShortcutWidget's doc comment on the same property: without
    // this, refreshAllWidgets()/updateAll() on an already-running session
    // (true for this widget the instant it's first placed) never actually
    // reaches this composable again -- confirmed via Glance's own source,
    // not a guess.
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val light = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context) else lightColorScheme()
        val dark = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()

        provideContent {
            // currentState() subscribes this composable to LocalState, which
            // is what actually makes a later .update()/.updateAll() call
            // (checkbox tap, progress tap, in-app edit via
            // MainActivity.persist(), or HeadingPickerActivity reconfiguring
            // this instance) reach this code again -- without reading it
            // here, recomposition never happens and everything below stays
            // frozen at whatever it was on first placement.
            val prefs = currentState<Preferences>()
            val rawHeading = prefs[SELECTED_HEADING_KEY]
            val emoji = prefs[SELECTED_EMOJI_KEY]?.ifBlank { null } ?: DEFAULT_WIDGET_EMOJI

            // Deliberately NOT behind remember()/produceState's key-based
            // memoization for the synchronous parts -- these are plain
            // (non-suspend) blocking calls, called directly and
            // unconditionally on every recomposition, exactly like
            // TodoWidget.kt's proven-working pattern. Only the async embed-
            // image resolution below needs produceState, since it's the one
            // genuinely suspend part.
            val treeUri = VaultPrefs.getTreeUri(context)
            val sync: HeadingSync = when {
                treeUri == null -> HeadingSync.NoFolder
                else -> {
                    val file = DailyNote.findTodayFile(context, treeUri)
                    if (file == null) {
                        HeadingSync.NoNoteToday
                    } else {
                        val text = DailyNote.readText(context, file.uri)
                        val lines = text.lines()
                        val headerLineIndex = rawHeading?.let { h -> lines.indexOfFirst { it == h } }
                        if (rawHeading == null || headerLineIndex == null || headerLineIndex == -1) {
                            HeadingSync.HeadingNotFound
                        } else {
                            val range = headerBodyLineRange(text, headerLineIndex)
                            val blocks = parseBlocks(text).filter {
                                val idx = when (it) { is Block.Line -> it.lineIndex; is Block.Code -> it.firstBodyLine }
                                idx in range
                            }
                            HeadingSync.Loaded(rawHeading.trimStart('#', ' '), blocks, treeUri)
                        }
                    }
                }
            }

            val embeds by produceState(initialValue = emptyMap<String, GlanceEmbedImage>(), key1 = sync) {
                value = if (sync is HeadingSync.Loaded) resolveEmbedImagesForGlance(context, sync.treeUri, sync.blocks) else emptyMap()
            }

            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(16.dp)
                ) {
                    when (sync) {
                        is HeadingSync.NoFolder -> HeadingWidgetMessage("Open DailyObsi and pick your daily folder.")
                        is HeadingSync.NoNoteToday -> HeadingWidgetMessage("No note for today yet.")
                        is HeadingSync.HeadingNotFound -> HeadingWidgetMessage("Configured heading not found in today's note. Place this widget again to reconfigure.")
                        is HeadingSync.Loaded -> {
                            Text(
                                "$emoji ${sync.title}",
                                style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                                modifier = GlanceModifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 4.dp)
                            )
                            if (sync.blocks.isEmpty()) {
                                HeadingWidgetMessage("Nothing here yet.")
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

/** Synchronous half of the load (file read + block slicing) -- everything
 *  here is a plain blocking call, safe to run directly in the composable
 *  body on every recomposition. [Loaded] additionally carries [treeUri] so
 *  the async embed-resolution step below has what it needs without a second
 *  state read. */
private sealed class HeadingSync {
    object NoFolder : HeadingSync()
    object NoNoteToday : HeadingSync()
    object HeadingNotFound : HeadingSync()
    data class Loaded(val title: String, val blocks: List<Block>, val treeUri: Uri) : HeadingSync()
}

@androidx.compose.runtime.Composable
private fun HeadingWidgetMessage(message: String) {
    Text(message, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.padding(12.dp))
}

class HeadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HeadingWidget()
}
