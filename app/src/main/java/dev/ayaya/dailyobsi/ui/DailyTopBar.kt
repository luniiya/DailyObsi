package dev.ayaya.dailyobsi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.model.memoryDates
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.SaveStatus
import dev.ayaya.dailyobsi.model.SectionMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyTopBar(
    state: EditorUiState,
    showMemories: Boolean,
    model: DailyObsiViewModel,
) {
    val writing = !state.isHistorical && if (state.layoutMode == LayoutMode.CLASSIC) {
        state.classicMode == SectionMode.WRITE
    } else {
        state.selectedSection?.let { state.sectionModes[it.id] == SectionMode.WRITE } == true
    }
    TopAppBar(
        navigationIcon = {
            if (state.isHistorical && !state.showSettings) {
                IconButton(onClick = { model.loadDate(java.time.LocalDate.now()) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to today",
                    )
                }
            }
        },
        title = {
            if (state.showSettings) Text("Settings")
            else if (state.isHistorical) {
                Text("${state.viewingDate} · Read only", maxLines = 1)
            }
            else if (writing) {
                TextButton(
                    onClick = model::saveNow,
                    enabled = state.saveStatus !is SaveStatus.Saving,
                ) { Text("Save") }
            }
            else if (showMemories) MemoryChips(state, model)
            else Spacer(Modifier.width(1.dp))
        },
        actions = {
            if (state.showSettings) {
                if (state.dailyUri != null) {
                    TextButton(onClick = model::closeSettings) { Text("Done") }
                }
            } else {
                IconButton(
                    onClick = model::openCalendar,
                    enabled = state.indexedNotes.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.DateRange, "Open daily-note calendar")
                }
                IconButton(onClick = model::openSettings) {
                    Icon(Icons.Filled.Settings, "Settings")
                }
            }
        },
    )
}

@Composable
private fun MemoryChips(state: EditorUiState, model: DailyObsiViewModel) {
    val dates = memoryDates(state.viewingDate, state.indexedNotes.keys)
    LazyRow(
        contentPadding = PaddingValues(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(dates, key = { it.toEpochDay() }) { date ->
            FilterChip(
                selected = false,
                onClick = { model.loadDate(date) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text(date.year.toString(), maxLines = 1) },
            )
        }
    }
}
