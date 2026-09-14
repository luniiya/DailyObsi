package dev.ayaya.dailyobsi.model

import android.net.Uri
import java.time.LocalDate

enum class LayoutMode { TABBED, CLASSIC }

enum class SectionMode { READ, WRITE }

sealed interface SaveStatus {
    data object Clean : SaveStatus
    data object Unsaved : SaveStatus
    data object Saving : SaveStatus
    data object Saved : SaveStatus
    data class Error(val message: String) : SaveStatus
}

data class SectionId(
    val normalizedTitle: String,
    val occurrence: Int,
)

data class NoteSection(
    val id: SectionId,
    val title: String,
    val headerLineIndex: Int,
    val bodyRange: IntRange,
    val body: String,
)

data class IndexedNote(
    val date: LocalDate,
    val uri: Uri,
    val fileName: String,
)

data class NoteDocument(
    val date: LocalDate,
    val uri: Uri,
    val fileName: String,
    val text: String,
)
