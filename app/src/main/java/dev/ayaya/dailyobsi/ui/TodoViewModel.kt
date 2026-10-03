package dev.ayaya.dailyobsi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.android.sso.model.SingleSignOnAccount
import dev.ayaya.dailyobsi.todo.NextcloudTodoClient
import dev.ayaya.dailyobsi.todo.TodoCache
import dev.ayaya.dailyobsi.todo.TodoDay
import dev.ayaya.dailyobsi.todo.TodoException
import dev.ayaya.dailyobsi.todo.TodoItem
import dev.ayaya.dailyobsi.todo.inOrder
import dev.ayaya.dailyobsi.todo.parseTodoDay
import dev.ayaya.dailyobsi.todo.reorderedIds
import dev.ayaya.dailyobsi.todo.todoNeedsReload
import dev.ayaya.dailyobsi.todo.todoRetryDate
import dev.ayaya.dailyobsi.todo.withCompleted
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TodoUiState(
    val accountName: String? = null,
    /** The date the open note asked for (the cache key); the server's day may differ, see [todoRetryDate]. */
    val requestedDate: String? = null,
    val day: TodoDay? = null,
    /** [day] came from the server in this session, not from the cache. */
    val fresh: Boolean = false,
    val loading: Boolean = false,
    /** A pull-to-refresh is running; the indicator only springs back when this goes true -> false. */
    val refreshing: Boolean = false,
    /** Nextcloud couldn't be reached; [day] (if any) is the cached copy fetched at [cachedAt]. */
    val offline: Boolean = false,
    val cachedAt: Long? = null,
    val message: String? = null,
) {
    val connected: Boolean get() = accountName != null
    val canEdit: Boolean get() = day?.editable == true && fresh && !offline
}

/**
 * The Nextcloud Daily Todo tab. Independent of the note: it follows the
 * note's date, but its data lives on the server (docs/nextcloud-daily-todo.md).
 * Edits show up at once (optimistic), go out one at a time, and the list is
 * re-read from the server once the queue is empty.
 */
class TodoViewModel(application: Application) : AndroidViewModel(application) {
    private val client = NextcloudTodoClient(application)
    private val cache = TodoCache(File(application.filesDir, "todo-cache"))
    private val writeLock = Mutex()
    private var pendingWrites = 0
    private var dragging = false
    private var viewingToday = true
    private var loadJob: Job? = null

    private val mutableState = MutableStateFlow(TodoUiState(accountName = client.accountName))
    val state: StateFlow<TodoUiState> = mutableState.asStateFlow()

    /** Show [date]'s list; a no-op if it's already the one shown, unless [force]. */
    fun show(date: LocalDate, isToday: Boolean, force: Boolean = false) {
        val key = date.toString()
        val current = mutableState.value
        if (!current.connected) return
        if (!force && key == current.requestedDate && current.day != null) return
        viewingToday = isToday
        if (key != current.requestedDate) {
            // Show the cached copy right away while the real one loads.
            val cached = cache.load(key)?.let { entry -> runCatching { parseTodoDay(entry.json) }.getOrNull() }
            mutableState.update {
                it.copy(requestedDate = key, day = cached, fresh = false, offline = false, message = null)
            }
        }
        reload(silent = false)
    }

    /** Re-read from the server, keeping what's on screen meanwhile. */
    fun refresh() = reload(silent = mutableState.value.day != null)

    /** Pull-to-refresh: like [refresh], but drives the indicator. */
    fun pullToRefresh() {
        mutableState.update { it.copy(refreshing = true) }
        reload(silent = true)
        // However the load ends (done, failed, cancelled by a newer one), let the indicator go.
        loadJob?.invokeOnCompletion { mutableState.update { it.copy(refreshing = false) } }
            ?: mutableState.update { it.copy(refreshing = false) }
    }

    /** Picks up changes made elsewhere (web UI, the agent) while the tab is
     *  on screen. Called every [POLL_INTERVAL_MS]: asks the server for the
     *  day's version and fetches the whole day only when it moved. Skips a
     *  beat while edits are going out or a load is already running. Doubles
     *  as the automatic retry when offline. */
    fun poll() {
        if (dragging || pendingWrites > 0 || loadJob?.isActive == true) return
        val current = mutableState.value
        val day = current.day
        if (day?.version == null || !current.fresh || current.offline) {
            reload(silent = true)
            return
        }
        loadJob = viewModelScope.launch {
            val server = try {
                client.dayVersion(day.day)
            } catch (error: TodoException) {
                null // fall through to a full reload, which handles offline/errors
            }
            if (pendingWrites > 0 || mutableState.value.day !== day) return@launch
            if (todoNeedsReload(day.version, server)) reload(silent = true)
        }
    }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    fun showMessage(message: String) = mutableState.update { it.copy(message = message) }

    fun onConnected(account: SingleSignOnAccount) {
        client.connect(account)
        mutableState.update { TodoUiState(accountName = account.name) }
    }

    fun disconnect() {
        client.disconnect()
        loadJob?.cancel()
        mutableState.update { TodoUiState() }
    }

    fun toggle(item: TodoItem) = write(
        optimistic = { items ->
            val cascade = mutableState.value.day?.completeSubtasks == true
            withCompleted(items, item.id, !item.completed, cascade)
        },
    ) { client.complete(item.id, !item.completed) }

    /** The "Add a task" box: the server slots it in with the day's tasks. */
    fun add(title: String) = write { date -> client.createQuick(date, title) }

    /** A new line right below [item] (after its subtasks), at the same level. */
    fun addAfter(item: TodoItem, title: String, onCreated: (Long) -> Unit = {}) =
        write { date -> onCreated(client.createQuick(date, title, afterId = item.id)) }

    fun addSubtask(item: TodoItem, title: String, onCreated: (Long) -> Unit = {}) =
        write { date -> onCreated(client.createQuick(date, title, parentId = item.id)) }

    fun rename(item: TodoItem, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == item.title) return
        write(optimistic = { items -> items.map { if (it.id == item.id) it.copy(title = trimmed) else it } }) {
            client.rename(item.id, trimmed)
        }
    }

    fun delete(item: TodoItem) = write(
        optimistic = { items -> items.filter { it.id != item.id && it.parentId != item.id } },
    ) { client.delete(item.id) }

    fun move(item: TodoItem, delta: Int) {
        val items = mutableState.value.day?.items ?: return
        reorderedIds(items, item.id, delta)?.let(::reorder)
    }

    /** Save a whole new order at once (the end of a drag); one request however far it moved. */
    fun reorder(order: List<Long>) {
        val items = mutableState.value.day?.items ?: return
        if (order == items.map { it.id }) return
        write(optimistic = { inOrder(it, order) }) { date -> client.reorder(date, order) }
    }

    /** While a row is being dragged, polling would reshuffle the list under the finger. */
    fun setDragging(dragging: Boolean) {
        this.dragging = dragging
    }

    private fun write(
        optimistic: ((List<TodoItem>) -> List<TodoItem>)? = null,
        call: suspend (date: String) -> Unit,
    ) {
        val current = mutableState.value
        val day = current.day ?: return
        if (!current.canEdit) return
        if (optimistic != null) {
            mutableState.update { it.copy(day = day.copy(items = optimistic(day.items))) }
        }
        pendingWrites++
        viewModelScope.launch {
            val failed = writeLock.withLock {
                try {
                    call(day.day)
                    null
                } catch (error: TodoException) {
                    error
                }
            }
            pendingWrites--
            failed?.let { error -> mutableState.update { it.copy(message = error.message) } }
            // Once the burst is done, the server's version wins (ordering, rollover, task write-back).
            if (pendingWrites == 0) reload(silent = true)
        }
    }

    private fun reload(silent: Boolean) {
        val key = mutableState.value.requestedDate ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!silent) mutableState.update { it.copy(loading = true) }
            try {
                var json = client.day(key)
                var day = parseTodoDay(json)
                todoRetryDate(key, day, viewingToday)?.let { logical ->
                    json = client.day(logical)
                    day = parseTodoDay(json)
                }
                withContext(Dispatchers.IO) { cache.store(key, json) }
                // A write started while this was in flight; its own reload will follow.
                if (pendingWrites > 0) {
                    mutableState.update { it.copy(loading = false) }
                    return@launch
                }
                mutableState.update {
                    it.copy(day = day, fresh = true, offline = false, cachedAt = null, loading = false)
                }
            } catch (error: TodoException) {
                if (error.reachedServer) {
                    mutableState.update { it.copy(loading = false, message = error.message) }
                } else {
                    val cached = withContext(Dispatchers.IO) { cache.load(key) }
                    mutableState.update {
                        it.copy(
                            day = cached?.let { entry -> runCatching { parseTodoDay(entry.json) }.getOrNull() },
                            fresh = false,
                            offline = true,
                            cachedAt = cached?.fetchedAtMillis,
                            loading = false,
                            message = if (cached == null) error.message else null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                // Malformed JSON (e.g. a login page instead of the API) must not crash the app.
                mutableState.update {
                    it.copy(loading = false, message = "Unexpected answer from Nextcloud: ${error.message}")
                }
            }
        }
    }

    override fun onCleared() {
        client.close()
    }

    companion object {
        const val POLL_INTERVAL_MS = 10_000L
    }
}
