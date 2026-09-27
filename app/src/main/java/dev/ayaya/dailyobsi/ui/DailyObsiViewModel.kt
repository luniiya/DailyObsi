package dev.ayaya.dailyobsi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.ayaya.dailyobsi.VaultPrefs
import dev.ayaya.dailyobsi.model.LayoutMode
import dev.ayaya.dailyobsi.model.NoteDocument
import dev.ayaya.dailyobsi.model.SaveStatus
import dev.ayaya.dailyobsi.model.SectionId
import dev.ayaya.dailyobsi.model.SectionMode
import dev.ayaya.dailyobsi.model.normalizeHeading
import dev.ayaya.dailyobsi.model.parseH2Sections
import dev.ayaya.dailyobsi.model.replaceSectionBody
import dev.ayaya.dailyobsi.storage.AppPreferences
import dev.ayaya.dailyobsi.storage.NoteRepository
import dev.ayaya.dailyobsi.storage.SaveCoordinator
import dev.ayaya.dailyobsi.storage.SaveRevision
import dev.ayaya.dailyobsi.widget.requestWidgetRefresh
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DailyObsiViewModel(
    application: Application,
    private val startInEditMode: Boolean,
    private val openSectionHeading: String?,
) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val preferences = AppPreferences(context)
    private val repository = NoteRepository(context)
    private var revision = 0L
    private var startDestinationApplied = false

    // Widgets aren't visible while the app is in the foreground, and
    // re-rendering them isn't free (Glance composes + translates RemoteViews
    // on this process's main thread), so saves only mark them stale; they
    // catch up once in flushForBackground.
    @Volatile private var widgetsStale = false

    private val saveCoordinator = SaveCoordinator<Uri>(viewModelScope) { value ->
        repository.write(value.target, value.text)
        widgetsStale = true
    }

    private val mutableState = MutableStateFlow(
        EditorUiState(
            dailyUri = VaultPrefs.getTreeUri(context),
            templateUri = VaultPrefs.getTemplateUri(context),
            showSettings = VaultPrefs.getTreeUri(context) == null,
            layoutMode = preferences.layoutMode,
        ),
    )
    val state: StateFlow<EditorUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            saveCoordinator.status.collectLatest { status ->
                mutableState.update { it.copy(saveStatus = status) }
            }
        }
        if (mutableState.value.dailyUri != null) refreshArchiveAndLoad(LocalDate.now())
    }

    fun openSettings() {
        saveNow()
        mutableState.update { it.copy(showSettings = true) }
    }

    fun closeSettings() = mutableState.update { it.copy(showSettings = false) }

    fun openCalendar() = mutableState.update { it.copy(showCalendar = true) }

    fun closeCalendar() = mutableState.update { it.copy(showCalendar = false) }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    fun setDailyUri(uri: Uri) {
        viewModelScope.launch {
            saveCoordinator.flush()
            if (saveCoordinator.status.value is SaveStatus.Error) {
                mutableState.update {
                    it.copy(message = "Save failed. Retry before changing folders.")
                }
                return@launch
            }
            VaultPrefs.setTreeUri(context, uri)
            mutableState.update { it.copy(dailyUri = uri, showSettings = false) }
            refreshArchiveAndLoadSuspend(LocalDate.now())
        }
    }

    fun setTemplateUri(uri: Uri) {
        VaultPrefs.setTemplateUri(context, uri)
        mutableState.update { it.copy(templateUri = uri) }
    }

    fun setLayoutMode(mode: LayoutMode) {
        preferences.layoutMode = mode
        mutableState.update { it.copy(layoutMode = mode) }
    }

    fun configuredMode(title: String): SectionMode = preferences.sectionMode(title)

    fun setConfiguredMode(title: String, mode: SectionMode) {
        preferences.setSectionMode(title, mode)
        mutableState.update { current ->
            val changed = current.sectionModes.toMutableMap()
            current.sections.filter { normalizeHeading(it.title) == normalizeHeading(title) }
                .forEach { changed[it.id] = mode }
            current.copy(sectionModes = changed)
        }
    }

    fun selectSection(id: SectionId) {
        mutableState.update { it.copy(selectedSectionId = id) }
        val date = mutableState.value.viewingDate
        if (date == LocalDate.now()) preferences.setLastSection(date, id)
    }

    fun setSectionMode(id: SectionId, mode: SectionMode) {
        if (mutableState.value.isHistorical) return
        mutableState.update { it.copy(sectionModes = it.sectionModes + (id to mode)) }
    }

    fun setClassicMode(mode: SectionMode) {
        if (mutableState.value.isHistorical) return
        mutableState.update { it.copy(classicMode = mode) }
    }

    fun updateSection(id: SectionId, body: String, immediate: Boolean = false) {
        val current = mutableState.value
        if (current.isHistorical) return
        val document = current.document ?: return
        updateDocument(document.copy(text = replaceSectionBody(document.text, id, body)), immediate)
    }

    fun updateWholeNote(text: String, immediate: Boolean = false) {
        val current = mutableState.value
        if (current.isHistorical) return
        val document = current.document ?: return
        updateDocument(document.copy(text = text), immediate)
    }

    fun saveNow() {
        viewModelScope.launch { saveCoordinator.flush() }
    }

    fun retrySave() {
        viewModelScope.launch { saveCoordinator.retry() }
    }

    fun loadDate(date: LocalDate) {
        if (date != LocalDate.now() && date !in mutableState.value.indexedNotes) return
        viewModelScope.launch {
            saveCoordinator.flush()
            if (saveCoordinator.status.value is SaveStatus.Error) {
                mutableState.update {
                    it.copy(message = "Save failed. Retry before leaving this note.")
                }
                return@launch
            }
            loadIndexedDate(date)
        }
    }

    fun openYesterday() = loadDate(LocalDate.now().minusDays(1))

    fun createToday() {
        val current = mutableState.value
        val treeUri = current.dailyUri ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(isCreating = true, message = null) }
            // A fresh note always opens on its first tab.
            preferences.clearLastSection()
            runCatching { repository.createToday(treeUri, current.templateUri) }
                .onSuccess { refreshArchiveAndLoadSuspend(LocalDate.now()) }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(message = error.message ?: "Could not create today's note")
                    }
                }
            mutableState.update { it.copy(isCreating = false) }
        }
    }

    fun refreshAfterResume() {
        val current = mutableState.value
        if (current.dailyUri == null || current.isLoading ||
            current.saveStatus !in listOf(SaveStatus.Clean, SaveStatus.Saved)
        ) {
            return
        }
        // Coming back from another app: re-read the file (widgets may have
        // written to it) without the loading screen, keeping the open tab.
        viewModelScope.launch { refreshArchiveAndLoadSuspend(current.viewingDate, silent = true) }
    }

    fun flushForBackground() {
        viewModelScope.launch {
            saveCoordinator.flush()
            if (widgetsStale) {
                widgetsStale = false
                withContext(Dispatchers.IO) { requestWidgetRefresh(context) }
            }
        }
    }

    private fun refreshArchiveAndLoad(date: LocalDate) {
        viewModelScope.launch { refreshArchiveAndLoadSuspend(date) }
    }

    private suspend fun refreshArchiveAndLoadSuspend(date: LocalDate, silent: Boolean = false) {
        val treeUri = mutableState.value.dailyUri ?: return
        if (!silent) mutableState.update { it.copy(isLoading = true, message = null) }
        try {
            val index = repository.index(treeUri)
            mutableState.update { it.copy(indexedNotes = index) }
            loadIndexedDate(date, silent)
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    isLoading = false,
                    message = error.message ?: "Could not read the daily-note folder",
                )
            }
        }
    }

    private suspend fun loadIndexedDate(date: LocalDate, silent: Boolean = false) {
        if (!silent) {
            mutableState.update {
                it.copy(isLoading = true, viewingDate = date, showCalendar = false, message = null)
            }
        }
        val indexed = mutableState.value.indexedNotes[date]
        if (indexed == null) {
            applyDocument(null, date)
            return
        }
        try {
            applyDocument(repository.load(indexed), date)
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    isLoading = false,
                    message = error.message ?: "Could not load the note",
                )
            }
        }
    }

    private fun applyDocument(document: NoteDocument?, date: LocalDate) {
        revision++
        saveCoordinator.markClean(revision)
        val previous = mutableState.value
        val sameNote = previous.document != null && previous.viewingDate == date
        val sections = document?.let { parseH2Sections(it.text) }.orEmpty()
        val modes = sections.associate { section ->
            section.id to (previous.sectionModes[section.id].takeIf { sameNote }
                ?: preferences.sectionMode(section.title))
        }.toMutableMap()
        // Reloading the note already on screen keeps its tab; otherwise fall
        // back to the tab remembered for that date (today only), then the first.
        val remembered = if (sameNote) previous.selectedSectionId
        else if (date == LocalDate.now()) preferences.lastSection(date)
        else null
        var selected = remembered?.takeIf { id -> sections.any { it.id == id } }
            ?: sections.firstOrNull()?.id
        var classicMode = if (sameNote) previous.classicMode else SectionMode.READ
        if (!startDestinationApplied && date == LocalDate.now()) {
            val requested = openSectionHeading
                ?.removePrefix("##")
                ?.trim()
                ?.let(::normalizeHeading)
            val requestedSection = sections.firstOrNull {
                it.id.normalizedTitle == requested
            }
            if (requestedSection != null) {
                selected = requestedSection.id
                modes[selected] = SectionMode.WRITE
            }
            if (startInEditMode) classicMode = SectionMode.WRITE
            startDestinationApplied = true
        }
        mutableState.update {
            it.copy(
                document = document,
                viewingDate = date,
                sections = sections,
                selectedSectionId = selected,
                sectionModes = modes,
                classicMode = classicMode,
                isLoading = false,
            )
        }
    }

    private fun updateDocument(document: NoteDocument, immediate: Boolean) {
        revision++
        val sections = parseH2Sections(document.text)
        mutableState.update { current ->
            val selected = current.selectedSectionId
                ?.takeIf { id -> sections.any { it.id == id } }
                ?: sections.firstOrNull()?.id
            current.copy(document = document, sections = sections, selectedSectionId = selected)
        }
        saveCoordinator.submit(SaveRevision(document.uri, document.text, revision))
        if (immediate) saveNow()
    }

    class Factory(
        private val application: Application,
        private val startInEditMode: Boolean,
        private val openSectionHeading: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DailyObsiViewModel(application, startInEditMode, openSectionHeading) as T
    }
}
