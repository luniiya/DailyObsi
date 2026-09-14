package dev.ayaya.dailyobsi.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.model.SectionMode

@Composable
fun ModeToggleFab(
    mode: SectionMode,
    bottomPadding: Dp,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onToggle,
        modifier = modifier.navigationBarsPadding()
            .padding(end = 16.dp, bottom = bottomPadding),
    ) {
        if (mode == SectionMode.READ) {
            Icon(Icons.Filled.Edit, "Switch to writing mode")
        } else {
            Icon(Icons.Filled.Check, "Finish writing and switch to reading mode")
        }
    }
}
