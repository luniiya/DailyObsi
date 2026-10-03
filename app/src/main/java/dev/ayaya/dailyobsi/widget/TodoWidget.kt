package dev.ayaya.dailyobsi.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ayaya.dailyobsi.MainActivity
import dev.ayaya.dailyobsi.R
import dev.ayaya.dailyobsi.todo.NextcloudTodoClient
import dev.ayaya.dailyobsi.todo.SeparatorKind
import dev.ayaya.dailyobsi.todo.TodoCache
import dev.ayaya.dailyobsi.todo.TodoDay
import dev.ayaya.dailyobsi.todo.TodoException
import dev.ayaya.dailyobsi.todo.TodoItem
import dev.ayaya.dailyobsi.todo.parseTodoDay
import dev.ayaya.dailyobsi.todo.separatorKind
import dev.ayaya.dailyobsi.todo.todoCheckable
import dev.ayaya.dailyobsi.todo.todoDepths
import dev.ayaya.dailyobsi.todo.withPendingTicks
import dev.ayaya.dailyobsi.ui.appColorScheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Today's Nextcloud Daily Todo list on the home screen, with working ticks.
 *
 * Battery: the widget itself never touches the network. It renders the same
 * on-disk cache the app's Todo tab writes ([TodoCache]). The cache is
 * refreshed by a 30-minute WorkManager job that only runs with a network
 * connection ([scheduleTodoWidgetSync]), by the app when it goes to the
 * background, by a tick on the widget, and by the ↻ button.
 *
 * Main thread: Glance composes on the app's main thread (see
 * [widgetDataVersion]), so all reading happens in [loadTodoWidget] on
 * Dispatchers.IO before a recompose is asked for ([refreshTodoWidgets]), and
 * all network calls happen in the worker or inside an [ActionCallback]'s own
 * suspend call, never in a composable.
 */
class TodoWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    // Same reason as the other widgets: reading currentState() inside
    // provideContent is what lets updateAll() reach a live session.
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val light = appColorScheme(context, dark = false)
        val dark = appColorScheme(context, dark = true)
        // A fresh session (first placement, process restart): load once, off the main thread.
        val initial = latestLoad ?: loadTodoWidget(context).also { latestLoad = it }

        provideContent {
            // The stamp changing is what recomposes a live session; the data is
            // already loaded by then (refreshTodoWidgets), so composing only reads memory.
            currentState<Preferences>()[RENDER_STAMP_KEY]
            val loaded = latestLoad ?: initial
            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) {
                TodoWidgetContent(loaded)
            }
        }
    }
}

class TodoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleTodoWidgetSync(context)
        requestTodoWidgetSync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_SYNC)
    }
}

/**
 * Re-renders every Todo widget from the cache. No network.
 *
 * Loads first (on IO), then pushes a new stamp into each instance's Glance
 * state, so the recomposition that follows draws the new data in one frame.
 * The first version bumped a counter and loaded in `produceState` after the
 * recompose; that frame was pushed with the previous load, so the widget was
 * always one refresh behind (a tick showed up only after the next ↻).
 */
suspend fun refreshTodoWidgets(context: Context) = renderLock.withLock {
    // Under a lock, so an older load can't land after a newer one.
    latestLoad = loadTodoWidget(context)
    val stamp = renderStamp.incrementAndGet()
    val widget = TodoWidget()
    for (id in GlanceAppWidgetManager(context).getGlanceIds(TodoWidget::class.java)) {
        updateAppWidgetState(context, id) { it[RENDER_STAMP_KEY] = stamp }
        widget.update(context, id)
    }
}

/** The 30-minute background refresh; only runs online, and is a no-op when no widget is placed. */
fun scheduleTodoWidgetSync(context: Context) {
    val request = PeriodicWorkRequestBuilder<TodoWidgetSyncWorker>(30, TimeUnit.MINUTES)
        .setConstraints(onlineOnly)
        .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(PERIODIC_SYNC, ExistingPeriodicWorkPolicy.KEEP, request)
}

/** One refresh as soon as there's a network (placement, a new day with nothing cached yet). */
fun requestTodoWidgetSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<TodoWidgetSyncWorker>().setConstraints(onlineOnly).build()
    WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_SYNC, ExistingWorkPolicy.KEEP, request)
}

class TodoWidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val placed = GlanceAppWidgetManager(applicationContext).getGlanceIds(TodoWidget::class.java)
        if (placed.isNotEmpty()) syncTodoWidget(applicationContext)
        return Result.success()
    }
}

/** Tap on a row: tick at once on the widget, send it, then re-read the day. */
class TodoWidgetToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ITEM_ID_KEY] ?: return
        val completed = parameters[COMPLETED_KEY] ?: return
        pendingTicks[id] = completed
        refreshTodoWidgets(context)
        syncTodoWidget(context) { client -> client.complete(id, completed) }
        pendingTicks.remove(id)
        refreshTodoWidgets(context)
    }
}

class TodoWidgetRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        syncTodoWidget(context)
    }
}

/**
 * Runs [write] (if any), then fetches today's list into the cache and
 * re-renders. One at a time, so a tick and the periodic job can't race.
 * Offline keeps the cached list and its "updated" time; a refusal from the
 * server is shown on the widget.
 */
private suspend fun syncTodoWidget(
    context: Context,
    write: (suspend (NextcloudTodoClient) -> Unit)? = null,
) {
    if (!NextcloudTodoClient.isConnected(context)) {
        refreshTodoWidgets(context)
        return
    }
    syncLock.withLock {
        val client = NextcloudTodoClient(context)
        try {
            withTimeout(SYNC_TIMEOUT_MS) {
                write?.invoke(client)
                val today = LocalDate.now().toString()
                val (json, _) = client.dayFollowingRollover(today, viewingToday = true)
                withContext(Dispatchers.IO) { TodoCache.forApp(context).store(today, json) }
            }
            widgetError = null
        } catch (error: TimeoutCancellationException) {
            widgetError = if (write != null) "Nextcloud didn't answer" else null
        } catch (error: CancellationException) {
            throw error
        } catch (error: TodoException) {
            widgetError = if (error.reachedServer || write != null) error.message else null
        } catch (error: RuntimeException) {
            widgetError = "Unexpected answer from Nextcloud"
        } finally {
            client.close()
        }
    }
    refreshTodoWidgets(context)
}

private class TodoWidgetLoad(
    val connected: Boolean,
    val day: TodoDay?,
    /** [day]'s items with unconfirmed widget ticks applied. */
    val items: List<TodoItem>,
    val fetchedAtMillis: Long?,
    val error: String?,
)

/** Cache read + parse, on Dispatchers.IO. Asks for a sync when today isn't cached yet. */
private suspend fun loadTodoWidget(context: Context): TodoWidgetLoad = withContext(Dispatchers.IO) {
    if (!NextcloudTodoClient.isConnected(context)) {
        return@withContext TodoWidgetLoad(connected = false, day = null, items = emptyList(), fetchedAtMillis = null, error = null)
    }
    val entry = TodoCache.forApp(context).load(LocalDate.now().toString())
    val day = entry?.let { runCatching { parseTodoDay(it.json) }.getOrNull() }
    if (day == null) {
        // A new day, or never fetched. Throttled so an unreachable server can't loop sync -> render -> sync.
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncRequest > AUTO_SYNC_THROTTLE_MS) {
            lastAutoSyncRequest = now
            requestTodoWidgetSync(context)
        }
    }
    TodoWidgetLoad(
        connected = true,
        day = day,
        items = day?.let { withPendingTicks(it.items, pendingTicks, it.completeSubtasks) }.orEmpty(),
        fetchedAtMillis = entry?.fetchedAtMillis,
        error = widgetError,
    )
}

@Composable
private fun TodoWidgetContent(loaded: TodoWidgetLoad) {
    val day = loaded.day
    val items = loaded.items
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(16.dp),
    ) {
        TodoWidgetHeader(items, loaded)
        loaded.error?.let {
            Text(
                it,
                maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp),
                modifier = GlanceModifier.padding(horizontal = 12.dp),
            )
        }
        when {
            !loaded.connected -> TodoWidgetMessage("Connect Nextcloud in DailyObsi's settings.")
            day == null -> TodoWidgetMessage(if (loaded.error == null) "Loading…" else "Nothing cached for today yet.")
            items.isEmpty() -> TodoWidgetMessage("Nothing on today's list.")
            else -> {
                val depths = todoDepths(items)
                LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                    // Content-derived ids: a position-only id left ticked rows stale (see ReadingViewWidget).
                    items(items, itemId = { (it.id shl 20) xor (it.hashCode().toLong() and 0xFFFFF) }) { item ->
                        TodoWidgetRow(item, depths[item.id] ?: 0, day.editable)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoWidgetHeader(items: List<TodoItem>, loaded: TodoWidgetLoad) {
    val checkable = todoCheckable(items)
    Row(
        verticalAlignment = Alignment.Vertical.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Vertical.CenterVertically,
            modifier = GlanceModifier
                .defaultWeight()
                .padding(vertical = 8.dp)
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(OPEN_TODO_KEY to true))),
        ) {
            Text("Todo", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
            if (checkable.isNotEmpty()) {
                Text(
                    "  ${checkable.count { it.completed }}/${checkable.size}",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            loaded.fetchedAtMillis?.let {
                Text(
                    timeFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
        }
        if (loaded.connected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier.size(36.dp).clickable(actionRunCallback<TodoWidgetRefreshAction>()),
            ) {
                Text("↻", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 18.sp))
            }
        }
    }
}

@Composable
private fun TodoWidgetRow(item: TodoItem, depth: Int, editable: Boolean) {
    when (separatorKind(item.title)) {
        SeparatorKind.RULE -> Box(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(GlanceTheme.colors.outline)) {}
        }
        SeparatorKind.SPACE -> Spacer(GlanceModifier.height(10.dp))
        null -> {
            val toggle = actionRunCallback<TodoWidgetToggleAction>(
                actionParametersOf(ITEM_ID_KEY to item.id, COMPLETED_KEY to !item.completed),
            )
            var row = GlanceModifier.fillMaxWidth().padding(start = (12 + depth * 18).dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            // The row is the only click handler; the glyph is a plain Image (see GlanceMarkdownLine).
            if (editable) row = row.clickable(toggle)
            Row(verticalAlignment = Alignment.Vertical.CenterVertically, modifier = row) {
                Image(
                    provider = ImageProvider(if (item.completed) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (item.completed) GlanceTheme.colors.primary else GlanceTheme.colors.outline),
                    modifier = GlanceModifier.size(20.dp).padding(end = 4.dp),
                )
                val start = item.startsAt?.takeUnless { item.allDay }?.let {
                    timeFormat.format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) + "  "
                }.orEmpty()
                Text(
                    start + item.title,
                    maxLines = 2,
                    style = TextStyle(
                        color = if (item.completed) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                        textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TodoWidgetMessage(message: String) {
    Text(message, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.padding(12.dp))
}

/** What every Todo widget draws; replaced by [refreshTodoWidgets] before it recomposes them. */
@Volatile private var latestLoad: TodoWidgetLoad? = null
private val renderStamp = AtomicLong(System.currentTimeMillis())
private val RENDER_STAMP_KEY = longPreferencesKey("todo_render_stamp")

/** Ticks sent from the widget that the server hasn't confirmed yet, shown already applied. */
private val pendingTicks = ConcurrentHashMap<Long, Boolean>()

@Volatile private var widgetError: String? = null
@Volatile private var lastAutoSyncRequest = 0L
private val syncLock = Mutex()
private val renderLock = Mutex()

private val onlineOnly = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private val ITEM_ID_KEY = ActionParameters.Key<Long>("todo_item_id")
private val COMPLETED_KEY = ActionParameters.Key<Boolean>("todo_completed")
private val OPEN_TODO_KEY = ActionParameters.Key<Boolean>(MainActivity.EXTRA_OPEN_TODO)

private const val PERIODIC_SYNC = "todo-widget-sync"
private const val ONE_TIME_SYNC = "todo-widget-sync-now"
private const val SYNC_TIMEOUT_MS = 20_000L
private const val AUTO_SYNC_THROTTLE_MS = 5 * 60_000L
