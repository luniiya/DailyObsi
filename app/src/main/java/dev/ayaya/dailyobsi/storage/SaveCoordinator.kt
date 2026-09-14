package dev.ayaya.dailyobsi.storage

import dev.ayaya.dailyobsi.model.SaveStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SaveRevision<T>(val target: T, val text: String, val revision: Long)

class SaveCoordinator<T>(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 750,
    private val writer: suspend (SaveRevision<T>) -> Unit,
) {
    private val mutex = Mutex()
    private var debounceJob: Job? = null
    private var latest: SaveRevision<T>? = null
    private var savedRevision = 0L
    private val mutableStatus = MutableStateFlow<SaveStatus>(SaveStatus.Clean)

    val status: StateFlow<SaveStatus> = mutableStatus.asStateFlow()

    fun submit(value: SaveRevision<T>) {
        latest = value
        mutableStatus.value = SaveStatus.Unsaved
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMillis)
            savePending()
        }
    }

    suspend fun flush() {
        debounceJob?.cancel()
        debounceJob = null
        savePending()
    }

    suspend fun retry() = savePending()

    fun markClean(revision: Long = 0L) {
        latest = null
        savedRevision = revision
        mutableStatus.value = SaveStatus.Clean
    }

    private suspend fun savePending() {
        mutex.withLock {
            while (true) {
                val value = latest ?: return
                if (value.revision <= savedRevision) return
                mutableStatus.value = SaveStatus.Saving
                try {
                    writer(value)
                    savedRevision = value.revision
                    mutableStatus.value = if (latest?.revision == savedRevision) {
                        SaveStatus.Saved
                    } else {
                        SaveStatus.Unsaved
                    }
                } catch (error: Exception) {
                    mutableStatus.value = SaveStatus.Error(
                        error.message ?: "Could not save the note",
                    )
                    return
                }
                if (latest?.revision == savedRevision) return
            }
        }
    }
}
