package dev.ayaya.dailyobsi.ui

import android.net.Uri
import dev.ayaya.dailyobsi.model.IndexedNote
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.NoteDocument
import dev.ayaya.dailyobsi.model.NoteSection
import dev.ayaya.dailyobsi.model.SaveStatus
import dev.ayaya.dailyobsi.model.SectionId
import dev.ayaya.dailyobsi.model.SectionMode
import dev.ayaya.dailyobsi.model.TODO_TAB_ID
import java.time.LocalDate

data class EditorUiState(
    val dailyUri: Uri? = null,
    val templateUri: Uri? = null,
    val showSettings: Boolean = false,
    val showCalendar: Boolean = false,
    val layoutMode: LayoutMode = LayoutMode.TABBED,
    val classicMode: SectionMode = SectionMode.READ,
    val document: NoteDocument? = null,
    val viewingDate: LocalDate = LocalDate.now(),
    val indexedNotes: Map<LocalDate, IndexedNote> = emptyMap(),
    val sections: List<NoteSection> = emptyList(),
    val selectedSectionId: SectionId? = null,
    val sectionModes: Map<SectionId, SectionMode> = emptyMap(),
    val saveStatus: SaveStatus = SaveStatus.Clean,
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val message: String? = null,
) {
    val isHistorical: Boolean get() = viewingDate != LocalDate.now()
    val todoTabSelected: Boolean get() = selectedSectionId == TODO_TAB_ID

    /** The open note section; null while the todo tab is open. */
    val selectedSection: NoteSection?
        get() = if (todoTabSelected) null
        else sections.firstOrNull { it.id == selectedSectionId } ?: sections.firstOrNull()
}
