package dev.ayaya.dailyobsi.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.ayaya.dailyobsi.DailyNote
import dev.ayaya.dailyobsi.HEADER
import dev.ayaya.dailyobsi.VaultPrefs
import dev.ayaya.dailyobsi.ui.DailyObsiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shown as EditShortcutWidget's icon (and, where shown, next to HeadingWidget's
 *  title) when an instance hasn't been through the emoji step -- either
 *  because the user tapped "Skip" or because it was configured before this
 *  feature existed. */
const val DEFAULT_WIDGET_EMOJI = "✏️"

/** A handful of one-tap suggestions on the emoji step -- not exhaustive
 *  (there's a real text field for anything else, which opens the system
 *  keyboard's own emoji picker), just covering what this vault's notes
 *  actually tend to be about (todos, meds, health, goals). */
private val EMOJI_SUGGESTIONS = listOf("✏️", "📝", "✅", "📋", "🎯", "💊", "❤️", "📌", "⏰", "🔥")

/**
 * Configuration activity (ACTION_APPWIDGET_CONFIGURE) shared by both
 * EditShortcutWidget and HeadingWidget -- both are "pick one heading from
 * today's note" widgets, just with different faces (a bare shortcut vs. a
 * rendered body). The system launches this automatically the instant either
 * widget is dropped on the home screen (see each widget's *_widget_info.xml
 * android:configure attribute) and only actually adds the widget if this
 * finishes with RESULT_OK -- so the selection is saved and every widget
 * class is force-refreshed *before* setResult, not left to whatever
 * onUpdate the system may or may not send afterward (it doesn't, when a
 * configure activity is declared -- that's on the app to do explicitly).
 *
 * Two steps: pick a heading, then pick an emoji for the widget's icon --
 * a flat `pickedHeading` (null = step 1, non-null = step 2) is enough state,
 * no need for a sealed step type since there's nothing else to carry between
 * them.
 */
class HeadingPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default result if the user backs out without picking anything --
        // the system won't add the widget at all in that case.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var headings by remember { mutableStateOf<List<String>?>(null) }
            var pickedHeading by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                headings = withContext(Dispatchers.IO) {
                    val treeUri = VaultPrefs.getTreeUri(context) ?: return@withContext emptyList()
                    val file = DailyNote.findTodayFile(context, treeUri) ?: return@withContext emptyList()
                    DailyNote.readText(context, file.uri).lines().filter { HEADER.matches(it) }
                }
            }

            fun finishConfiguring(heading: String, emoji: String) {
                android.util.Log.d("DailyObsiWidget", "finishConfiguring: TAPPED appWidgetId=$appWidgetId heading=\"$heading\" emoji=\"$emoji\"")
                scope.launch {
                    try {
                        val glanceId = withContext(Dispatchers.IO) {
                            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                                prefs.toMutablePreferences().apply {
                                    this[SELECTED_HEADING_KEY] = heading
                                    this[SELECTED_EMOJI_KEY] = emoji
                                }
                            }
                            glanceId
                        }
                        android.util.Log.d("DailyObsiWidget", "finishConfiguring: saved, glanceId=$glanceId")
                        // The save itself always lands fine -- confirmed via a
                        // real logcat round-trip during debugging. What used to
                        // not land was a *refresh*, for two separate reasons
                        // (both real, both found via on-device retesting, see
                        // CLAUDE.md's "Bug found via real on-device testing"):
                        // (1) refreshAllWidgets()/updateAll() enumerates each
                        // widget class's *officially bound* instances, and a
                        // widget with a configure activity isn't "fully added"
                        // until this activity returns RESULT_OK -- so an
                        // enumeration-based refresh run before that can miss
                        // exactly this instance. Fixed by also updating this
                        // specific glanceId directly. (2) Even a direct
                        // .update() call on an ALREADY-RUNNING session doesn't
                        // re-invoke provideGlance() at all -- it only pushes
                        // state through stateDefinition/currentState()
                        // reactively, which is why every widget now declares a
                        // stateDefinition and reads it inside provideContent.
                        val providerClassName = AppWidgetManager.getInstance(context)
                            .getAppWidgetInfo(appWidgetId)?.provider?.className
                        android.util.Log.d("DailyObsiWidget", "finishConfiguring: providerClassName=$providerClassName")
                        when (providerClassName) {
                            EditShortcutWidgetReceiver::class.java.name -> EditShortcutWidget().update(context, glanceId)
                            HeadingWidgetReceiver::class.java.name -> HeadingWidget().update(context, glanceId)
                        }
                        android.util.Log.d("DailyObsiWidget", "finishConfiguring: direct update() call returned, refreshing rest + finishing")
                        // Also sweep everything else normally (other already-
                        // placed instances of any widget class that might be
                        // showing stale data for an unrelated reason).
                        refreshAllWidgets(context)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                        finish()
                    } catch (e: Throwable) {
                        android.util.Log.e("DailyObsiWidget", "finishConfiguring: FAILED", e)
                    }
                }
            }

            DailyObsiTheme {
                Surface(Modifier.fillMaxSize()) {
                    val heading = pickedHeading
                    if (heading == null) {
                        HeadingPickerScreen(headings = headings, onChoose = { pickedHeading = it })
                    } else {
                        EmojiPickerScreen(
                            heading = heading,
                            onBack = { pickedHeading = null },
                            onConfirm = { emoji -> finishConfiguring(heading, emoji) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadingPickerScreen(headings: List<String>?, onChoose: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text("Choose a heading", style = MaterialTheme.typography.titleLarge)
        Text(
            "Shown as this widget's content -- place it again to change later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.height(12.dp)) {}
        when {
            headings == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            }
            headings.isEmpty() -> Text(
                "No headings found in today's note. Open DailyObsi, make sure a daily " +
                    "folder is picked and today's note has at least one heading, then place this widget again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            else -> LazyColumn {
                items(headings) { heading ->
                    Text(
                        heading.trimStart('#', ' '),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(heading) }
                            .padding(vertical = 14.dp)
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Second step: pick an icon for the widget. A row of one-tap suggestions
 *  (each immediately confirms) plus a free-text field for anything else --
 *  typing into it opens the system keyboard, whose own emoji key is the
 *  simplest way to get a real emoji picker without building one from
 *  scratch. "Use default" confirms with DEFAULT_WIDGET_EMOJI without
 *  requiring the user to type/tap anything. */
@Composable
private fun EmojiPickerScreen(heading: String, onBack: () -> Unit, onConfirm: (String) -> Unit) {
    var customEmoji by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text("Choose an icon", style = MaterialTheme.typography.titleLarge)
        Text(
            "Shown on \"${heading.trimStart('#', ' ')}\" -- tap one, or type your own below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.height(16.dp)) {}

        // A LazyColumn wrapping a manually-wrapped row wouldn't buy anything
        // here (10 items, all on screen at once) -- a plain wrapping Row
        // would be simplest, but Compose's basic Row doesn't wrap, so this
        // splits the suggestions into two fixed rows of five instead of
        // pulling in a FlowRow dependency for one screen.
        for (rowItems in EMOJI_SUGGESTIONS.chunked(5)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                for (emoji in rowItems) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onConfirm(emoji) }
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 24.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        Column(Modifier.height(12.dp)) {}
        OutlinedTextField(
            value = customEmoji,
            onValueChange = { customEmoji = it },
            label = { Text("Or type/paste your own") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Column(Modifier.height(16.dp)) {}
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(Modifier.weight(1f)) {}
            TextButton(onClick = { onConfirm(DEFAULT_WIDGET_EMOJI) }) { Text("Use default") }
            Button(
                onClick = { onConfirm(customEmoji.trim().ifEmpty { DEFAULT_WIDGET_EMOJI }) },
                enabled = customEmoji.isNotBlank()
            ) { Text("Done") }
        }
    }
}
