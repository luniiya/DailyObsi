package dev.ayaya.dailyobsi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DailyObsiApp(model: DailyObsiViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var noteAtTop by remember(
        state.viewingDate,
        state.selectedSectionId,
        state.layoutMode,
    ) { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> model.flushForBackground()
                Lifecycle.Event.ON_START -> model.refreshAfterResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.showCalendar) { model.closeCalendar() }
    BackHandler(enabled = state.showSettings) { model.closeSettings() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Memories stay available while scrolling; writing mode replaces
            // them with the Save action inside the top bar.
            DailyTopBar(state, showMemories = true, model)
        },
    ) { padding ->
        if (state.showSettings) SettingsScreen(state, model, padding)
        else NoteScreen(state, model, padding, onAtTopChanged = { noteAtTop = it })
    }

    if (state.showCalendar) {
        DailyCalendarDialog(
            availableDates = state.indexedNotes.keys,
            displayedDate = state.viewingDate,
            onSelect = model::loadDate,
            onDismiss = model::closeCalendar,
        )
    }
}
