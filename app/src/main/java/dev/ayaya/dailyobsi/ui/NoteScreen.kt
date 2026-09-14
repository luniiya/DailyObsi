package dev.ayaya.dailyobsi.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ayaya.dailyobsi.DailyNote
import dev.ayaya.dailyobsi.MarkdownTextField
import dev.ayaya.dailyobsi.MarkdownView
import dev.ayaya.dailyobsi.headerColorFor
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.NoteSection
import dev.ayaya.dailyobsi.model.SaveStatus
import dev.ayaya.dailyobsi.model.SectionMode
import dev.ayaya.dailyobsi.model.canSwipeBetweenTabs
import dev.ayaya.dailyobsi.model.effectiveSectionMode
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteScreen(
    state: EditorUiState,
    model: DailyObsiViewModel,
    padding: PaddingValues,
    onAtTopChanged: (Boolean) -> Unit,
) {
    val keyboardVisible = WindowInsets.isImeVisible
    val showBottomBar = state.layoutMode == LayoutMode.TABBED &&
        state.sections.isNotEmpty() && !keyboardVisible
    val activeMode = if (state.layoutMode == LayoutMode.CLASSIC) {
        state.classicMode
    } else {
        state.selectedSection?.let { state.sectionModes[it.id] } ?: SectionMode.READ
    }
    Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            state.message?.let { ErrorNotice(it, model::clearMessage) }

            when {
                state.document == null -> EmptyNote(state, model)
                state.layoutMode == LayoutMode.CLASSIC -> {
                    ClassicNote(state, model, onAtTopChanged)
                }
                state.sections.isEmpty() -> NoSectionsMessage()
                else -> TabbedNote(
                    state,
                    model,
                    showBottomBar,
                    onAtTopChanged,
                )
            }
        }
        if (state.document != null && !state.isHistorical) {
            ModeToggleFab(
                mode = activeMode,
                // Keep the confirmation FAB clear of the editor utility
                // bubble and leave a comfortable text-safe area below it.
                bottomPadding = if (showBottomBar) 80.dp else 16.dp,
                onToggle = {
                    if (activeMode == SectionMode.WRITE) model.saveNow()
                    val next = if (activeMode == SectionMode.READ) {
                        SectionMode.WRITE
                    } else {
                        SectionMode.READ
                    }
                    if (state.layoutMode == LayoutMode.CLASSIC) {
                        model.setClassicMode(next)
                    } else {
                        state.selectedSection?.let { model.setSectionMode(it.id, next) }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).imePadding(),
            )
        }
        if (state.isLoading || state.isCreating) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun EmptyNote(state: EditorUiState, model: DailyObsiViewModel) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No note for ${DailyNote.fileNameFor(state.viewingDate)}.")
        if (!state.isHistorical) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.viewingDate.minusDays(1) in state.indexedNotes) {
                    OutlinedButton(onClick = model::openYesterday) { Text("Open yesterday") }
                }
                Button(onClick = model::createToday, enabled = !state.isCreating) {
                    Text("Create today's note")
                }
            }
        }
    }
}

@Composable
private fun NoSectionsMessage() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "No H2 sections found. Choose Classic layout in Settings to view this note.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabbedNote(
    state: EditorUiState,
    model: DailyObsiViewModel,
    showBottomBar: Boolean,
    onAtTopChanged: (Boolean) -> Unit,
) {
    val selectedIndex = state.sections.indexOfFirst { it.id == state.selectedSectionId }
        .coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = selectedIndex) { state.sections.size }
    val latestSections by rememberUpdatedState(state.sections)

    LaunchedEffect(selectedIndex) {
        if (pager.currentPage != selectedIndex) pager.scrollToPage(selectedIndex)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                latestSections.getOrNull(page)?.let { section ->
                    model.saveNow()
                    model.selectSection(section.id)
                }
            }
    }

    val activeMode = state.sections.getOrNull(pager.currentPage)?.let { section ->
        state.sectionModes[section.id]
    } ?: SectionMode.READ

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = canSwipeBetweenTabs(state.isHistorical, activeMode),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            state.sections.getOrNull(page)?.let { section ->
                SectionPage(
                    section,
                    state,
                    model,
                    onAtTopChanged = if (page == pager.currentPage) {
                        onAtTopChanged
                    } else {
                        {}
                    },
                )
            }
        }
        if (showBottomBar) {
            SectionBottomBar(
                sections = state.sections,
                selectedId = state.selectedSectionId,
                onSelect = { id ->
                    model.saveNow()
                    model.selectSection(id)
                },
            )
        }
    }
}

@Composable
private fun SectionPage(
    section: NoteSection,
    state: EditorUiState,
    model: DailyObsiViewModel,
    onAtTopChanged: (Boolean) -> Unit,
) {
    val mode = effectiveSectionMode(
        state.isHistorical,
        state.sectionModes[section.id] ?: SectionMode.READ,
    )
    Column(Modifier.fillMaxHeight()) {
        SectionHeading(section.title, state, mode, model)
        if (mode == SectionMode.WRITE && !state.isHistorical) {
            SectionTextEditor(section, model, onAtTopChanged)
        } else {
            val treeUri = state.dailyUri ?: return@Column
            MarkdownView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                text = section.body,
                dailyUri = treeUri,
                viewingDate = state.viewingDate,
                onToggleCheckbox = { line ->
                    model.updateSection(
                        section.id,
                        DailyNote.toggleCheckbox(section.body, line),
                        immediate = true,
                    )
                },
                onShiftIndent = { line, delta ->
                    model.updateSection(
                        section.id,
                        DailyNote.shiftIndent(section.body, line, delta),
                        immediate = true,
                    )
                },
                onMoveLine = { line, delta ->
                    model.updateSection(
                        section.id,
                        DailyNote.moveLine(section.body, line, delta),
                        immediate = true,
                    )
                },
                onSetLine = { line, value ->
                    model.updateSection(
                        section.id,
                        DailyNote.replaceLine(section.body, line, value),
                        immediate = true,
                    )
                },
                readOnly = state.isHistorical,
                onAtTopChanged = onAtTopChanged,
            )
        }
    }
}

@Composable
private fun SectionTextEditor(
    section: NoteSection,
    model: DailyObsiViewModel,
    onAtTopChanged: (Boolean) -> Unit,
) {
    MarkdownTextField(
        value = section.body,
        onValueChange = { model.updateSection(section.id, it) },
        modifier = Modifier.fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        bottomContentPadding = 64.dp,
        onShiftIndent = { line, delta ->
            model.updateSection(
                section.id,
                DailyNote.shiftIndent(section.body, line, delta),
            )
        },
        onMoveLine = { line, delta ->
            model.updateSection(section.id, DailyNote.moveLine(section.body, line, delta))
        },
        onAtTopChanged = onAtTopChanged,
    )
}

@Composable
private fun ClassicNote(
    state: EditorUiState,
    model: DailyObsiViewModel,
    onAtTopChanged: (Boolean) -> Unit,
) {
    val document = state.document ?: return
    val treeUri = state.dailyUri ?: return
    val mode = effectiveSectionMode(state.isHistorical, state.classicMode)
    Column(Modifier.fillMaxSize()) {
        ClassicStatusRow(state, mode, model)
        if (mode == SectionMode.WRITE && !state.isHistorical) {
            MarkdownTextField(
                value = document.text,
                onValueChange = { model.updateWholeNote(it) },
                modifier = Modifier.fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                bottomContentPadding = 64.dp,
                onShiftIndent = { line, delta ->
                    model.updateWholeNote(DailyNote.shiftIndent(document.text, line, delta))
                },
                onMoveLine = { line, delta ->
                    model.updateWholeNote(DailyNote.moveLine(document.text, line, delta))
                },
                onAtTopChanged = onAtTopChanged,
            )
        } else {
            MarkdownView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                text = document.text,
                dailyUri = treeUri,
                viewingDate = state.viewingDate,
                onToggleCheckbox = { line ->
                    model.updateWholeNote(
                        DailyNote.toggleCheckbox(document.text, line),
                        immediate = true,
                    )
                },
                onShiftIndent = { line, delta ->
                    model.updateWholeNote(
                        DailyNote.shiftIndent(document.text, line, delta),
                        immediate = true,
                    )
                },
                onMoveLine = { line, delta ->
                    model.updateWholeNote(
                        DailyNote.moveLine(document.text, line, delta),
                        immediate = true,
                    )
                },
                onSetLine = { line, value ->
                    model.updateWholeNote(
                        DailyNote.replaceLine(document.text, line, value),
                        immediate = true,
                    )
                },
                readOnly = state.isHistorical,
                onAtTopChanged = onAtTopChanged,
            )
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    state: EditorUiState,
    mode: SectionMode,
    model: DailyObsiViewModel,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = headerColorFor(2),
            style = MaterialTheme.typography.headlineSmall,
        )
        SaveStatusLabel(state.saveStatus, model::retrySave)
        if (mode == SectionMode.WRITE && !state.isHistorical) {
            TextButton(
                onClick = model::saveNow,
                enabled = state.saveStatus !is SaveStatus.Saving,
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ClassicStatusRow(
    state: EditorUiState,
    mode: SectionMode,
    model: DailyObsiViewModel,
) {
    if (state.isHistorical) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        SaveStatusLabel(state.saveStatus, model::retrySave)
        if (mode == SectionMode.WRITE) {
            TextButton(
                onClick = model::saveNow,
                enabled = state.saveStatus !is SaveStatus.Saving,
            ) { Text("Save") }
        }
    }
}

@Composable
private fun SaveStatusLabel(status: SaveStatus, onRetry: () -> Unit) {
    when (status) {
        SaveStatus.Clean -> Unit
        SaveStatus.Unsaved -> Text("Unsaved", style = MaterialTheme.typography.labelMedium)
        SaveStatus.Saving -> Text("Saving…", style = MaterialTheme.typography.labelMedium)
        SaveStatus.Saved -> Text("Saved", style = MaterialTheme.typography.labelMedium)
        is SaveStatus.Error -> TextButton(onClick = onRetry) { Text("Save failed · Retry") }
    }
}

@Composable
private fun ErrorNotice(message: String, dismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = dismiss) { Text("Dismiss") }
    }
}
