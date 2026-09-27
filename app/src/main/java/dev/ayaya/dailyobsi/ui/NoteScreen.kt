package dev.ayaya.dailyobsi.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.NoteSection
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
    NoteBody(
        text = section.body,
        mode = state.sectionModes[section.id] ?: SectionMode.READ,
        state = state,
        onChange = { body, immediate -> model.updateSection(section.id, body, immediate) },
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
    NoteBody(
        text = document.text,
        mode = state.classicMode,
        state = state,
        onChange = model::updateWholeNote,
        onAtTopChanged = onAtTopChanged,
    )
}

/** Renders any chunk of markdown -- one tab's section body or the whole
 *  note -- as either the raw editor or the interactive reader. [onChange]
 *  receives the full new [text]; reader interactions (checkbox, indent,
 *  reorder, progress +/-) save immediately, editor typing is debounced. */
@Composable
private fun NoteBody(
    text: String,
    mode: SectionMode,
    state: EditorUiState,
    onChange: (text: String, immediate: Boolean) -> Unit,
    onAtTopChanged: (Boolean) -> Unit,
) {
    val writing = effectiveSectionMode(state.isHistorical, mode) == SectionMode.WRITE &&
        !state.isHistorical
    if (writing) {
        MarkdownTextField(
            value = text,
            onValueChange = { onChange(it, false) },
            modifier = Modifier.fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            onShiftIndent = { line, delta -> onChange(DailyNote.shiftIndent(text, line, delta), false) },
            onMoveLine = { line, delta -> onChange(DailyNote.moveLine(text, line, delta), false) },
            onAtTopChanged = onAtTopChanged,
        )
    } else {
        val treeUri = state.dailyUri ?: return
        MarkdownView(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            text = text,
            dailyUri = treeUri,
            viewingDate = state.viewingDate,
            onToggleCheckbox = { line -> onChange(DailyNote.toggleCheckbox(text, line), true) },
            onShiftIndent = { line, delta -> onChange(DailyNote.shiftIndent(text, line, delta), true) },
            onMoveLine = { line, delta -> onChange(DailyNote.moveLine(text, line, delta), true) },
            onSetLine = { line, value -> onChange(DailyNote.replaceLine(text, line, value), true) },
            readOnly = state.isHistorical,
            onAtTopChanged = onAtTopChanged,
        )
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
