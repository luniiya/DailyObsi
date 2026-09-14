package dev.ayaya.dailyobsi.storage

import android.content.Context
import android.net.Uri
import dev.ayaya.dailyobsi.DailyNote
import dev.ayaya.dailyobsi.model.IndexedNote
import dev.ayaya.dailyobsi.model.NoteDocument
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class NoteRepository(private val context: Context) {
    suspend fun index(treeUri: Uri): Map<LocalDate, IndexedNote> = withContext(Dispatchers.IO) {
        DailyNote.indexFiles(context, treeUri)
    }

    suspend fun load(note: IndexedNote): NoteDocument = withContext(Dispatchers.IO) {
        NoteDocument(
            date = note.date,
            uri = note.uri,
            fileName = note.fileName,
            text = DailyNote.readText(context, note.uri),
        )
    }

    suspend fun createToday(
        treeUri: Uri,
        templateUri: Uri?,
    ) = withContext(Dispatchers.IO) {
        noteWriteMutex.withLock {
            DailyNote.createTodayFile(context, treeUri, templateUri)
        }
    }

    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        noteWriteMutex.withLock { DailyNote.writeText(context, uri, text) }
    }
}
