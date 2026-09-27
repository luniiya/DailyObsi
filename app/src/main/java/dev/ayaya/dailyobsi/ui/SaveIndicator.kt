package dev.ayaya.dailyobsi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ayaya.dailyobsi.model.SaveStatus
import kotlinx.coroutines.delay

private const val SAVED_VISIBLE_MS = 1_000L

/** Save state of the open note, shown in the top bar for every layout.
 *  "Saved" only flashes briefly after each save; a permanent "Saved" says
 *  nothing, while "Saving…"/"Unsaved"/errors stay up as long as they apply. */
@Composable
fun SaveStatusLabel(status: SaveStatus, onRetry: () -> Unit) {
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        showSaved = status == SaveStatus.Saved
        if (showSaved) {
            delay(SAVED_VISIBLE_MS)
            showSaved = false
        }
    }
    when (status) {
        SaveStatus.Clean -> Unit
        SaveStatus.Unsaved -> Text("Unsaved", style = MaterialTheme.typography.labelMedium)
        SaveStatus.Saving -> Text("Saving…", style = MaterialTheme.typography.labelMedium)
        SaveStatus.Saved -> if (showSaved) {
            Text("Saved", style = MaterialTheme.typography.labelMedium)
        }
        is SaveStatus.Error -> TextButton(onClick = onRetry) { Text("Save failed · Retry") }
    }
}
