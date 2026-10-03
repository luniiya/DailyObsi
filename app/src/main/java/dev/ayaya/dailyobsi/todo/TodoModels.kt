package dev.ayaya.dailyobsi.todo

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Where a Daily Todo item comes from (`daily_todo_items.source_type`). */
enum class TodoSource { ROUTINE, EVENT, TASK, QUICK }

data class TodoItem(
    val id: Long,
    val source: TodoSource,
    val title: String,
    val completed: Boolean,
    val parentId: Long?,
    val startsAt: Long? = null,
    val dueAt: Long? = null,
    val allDay: Boolean = false,
    val error: String? = null,
)

/** One day of the Nextcloud Daily Todo list, as `GET /api/day/{date}` returns it. */
data class TodoDay(
    val day: String,
    val logicalToday: String,
    val editable: Boolean,
    val future: Boolean,
    val items: List<TodoItem>,
    /** Fingerprint of everything the day shows; `GET /api/day/{date}/version` returns the same. */
    val version: String? = null,
    /** Board setting: ticking a parent also ticks its subtasks. Off (independent) by default. */
    val completeSubtasks: Boolean = false,
)

enum class SeparatorKind { RULE, SPACE }

/** Rows titled `---` are divider lines and `<br>` rows blank gaps; mirrors the plugin's `separatorKind`. */
fun separatorKind(title: String): SeparatorKind? = when (title.trim().lowercase()) {
    "---", "___" -> SeparatorKind.RULE
    "<br>", "<br/>", "<br />" -> SeparatorKind.SPACE
    else -> null
}

fun parseTodoDay(json: String): TodoDay {
    val root = JsonParser.parseString(json).asJsonObject
    return TodoDay(
        day = root.string("day").orEmpty(),
        logicalToday = root.string("logicalToday").orEmpty(),
        editable = root.bool("editable"),
        future = root.bool("future"),
        items = root.getAsJsonArray("items")?.map { parseItem(it.asJsonObject) }.orEmpty(),
        version = root.string("version"),
        completeSubtasks = root.bool("completeSubtasks"),
    )
}

/** The `{ "version": "…" }` answer of the cheap "has this day changed?" check. */
fun parseDayVersion(json: String): String? = runCatching {
    JsonParser.parseString(json).asJsonObject.string("version")
}.getOrNull()

/** Whether a poll has to fetch the whole day: only when the server's
 *  fingerprint moved, or when there's nothing to compare against. */
fun todoNeedsReload(shown: String?, server: String?): Boolean =
    shown == null || server == null || shown != server

private fun parseItem(o: JsonObject) = TodoItem(
    id = o.get("id").asLong,
    source = when (o.string("source_type")) {
        "routine" -> TodoSource.ROUTINE
        "event" -> TodoSource.EVENT
        "task" -> TodoSource.TASK
        else -> TodoSource.QUICK
    },
    title = o.string("title").orEmpty(),
    completed = o.bool("completed"),
    parentId = o.long("parent_id"),
    startsAt = o.long("starts_at"),
    dueAt = o.long("due_at"),
    allDay = o.bool("all_day"),
    error = o.string("error"),
)

private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
private fun JsonObject.string(key: String): String? = present(key)?.asString
private fun JsonObject.long(key: String): Long? = present(key)?.asString?.toLongOrNull()
private fun JsonObject.bool(key: String): Boolean = present(key)?.let {
    // Postgres booleans can arrive as true/false, 0/1 or "t"/"f" depending on the column.
    val raw = it.asString.lowercase()
    raw == "true" || raw == "1" || raw == "t"
} ?: false

/** The plugin answers failures with `{ "error": "…" }`; pull that message out of a body. */
fun serverErrorMessage(body: String?): String? = runCatching {
    JsonParser.parseString(body ?: return null).asJsonObject.string("error")
}.getOrNull()?.takeIf { it.isNotBlank() }

/** The `{ "id": 42 }` answer to creating an item. */
fun createdId(body: String): Long? = runCatching {
    JsonParser.parseString(body).asJsonObject.long("id")
}.getOrNull()

/** A parent is only honoured if it's on the same day (the web UI does the same). */
private fun parentKey(item: TodoItem, known: Set<Long>): Long? =
    item.parentId?.takeIf { it in known }

/** Nesting depth of each item, 0 for top level; capped against parent cycles. */
fun todoDepths(items: List<TodoItem>): Map<Long, Int> {
    val byId = items.associateBy { it.id }
    return items.associate { item ->
        var depth = 0
        var current = item
        while (depth < 20) {
            current = current.parentId?.let(byId::get) ?: break
            depth++
        }
        item.id to depth
    }
}

/** Every item below [id], nearest first, like the plugin's `descendants`. */
fun todoDescendants(items: List<TodoItem>, id: Long): List<TodoItem> {
    val known = items.mapTo(HashSet()) { it.id }
    val byParent = items.groupBy { parentKey(it, known) }
    val out = mutableListOf<TodoItem>()
    val seen = mutableSetOf(id)
    val queue = ArrayDeque(listOf(id))
    while (queue.isNotEmpty()) {
        for (child in byParent[queue.removeFirst()].orEmpty()) {
            if (seen.add(child.id)) {
                out += child
                queue += child.id
            }
        }
    }
    return out
}

/** Optimistic tick, matching the server: subtasks are independent unless the
 *  board's [completeSubtasks] setting is on, and then only ticking cascades,
 *  never unticking. */
fun withCompleted(items: List<TodoItem>, id: Long, completed: Boolean, completeSubtasks: Boolean): List<TodoItem> {
    val targets = buildSet {
        add(id)
        if (completed && completeSubtasks) todoDescendants(items, id).forEach { add(it.id) }
    }
    return items.map { if (it.id in targets) it.copy(completed = completed) else it }
}

/** The full day order after swapping [id] with its previous (-1) or next (+1)
 *  sibling, a parent carrying its subtasks along -- the web UI's `moveItem`.
 *  Null when there's no sibling in that direction. */
fun reorderedIds(items: List<TodoItem>, id: Long, delta: Int): List<Long>? {
    val item = items.firstOrNull { it.id == id } ?: return null
    val known = items.mapTo(HashSet()) { it.id }
    val childrenOf = LinkedHashMap<Long?, MutableList<Long>>()
    for (entry in items) childrenOf.getOrPut(parentKey(entry, known)) { mutableListOf() } += entry.id
    val siblings = childrenOf[parentKey(item, known)] ?: return null
    val from = siblings.indexOf(id)
    val to = from + delta
    if (from < 0 || to !in siblings.indices) return null
    siblings[from] = siblings[to].also { siblings[to] = siblings[from] }
    val order = mutableListOf<Long>()
    val seen = mutableSetOf<Long>()
    fun walk(key: Long?) {
        for (child in childrenOf[key].orEmpty()) {
            if (!seen.add(child)) continue
            order += child
            walk(child)
        }
    }
    walk(null)
    return order
}

/** The sibling [delta] steps away from [id] (same parent), or null at either end. */
fun todoNeighbor(items: List<TodoItem>, id: Long, delta: Int): Long? {
    val item = items.firstOrNull { it.id == id } ?: return null
    val known = items.mapTo(HashSet()) { it.id }
    val siblings = items.filter { parentKey(it, known) == parentKey(item, known) }.map { it.id }
    val index = siblings.indexOf(id)
    return siblings.getOrNull(index + delta)
}

/** [id] and everything below it: what moves together when it's dragged. */
fun todoBlock(items: List<TodoItem>, id: Long): Set<Long> =
    setOf(id) + todoDescendants(items, id).map { it.id }

/** [items] rearranged into [order] (ids from [reorderedIds]); unknown ids are dropped. */
fun inOrder(items: List<TodoItem>, order: List<Long>): List<TodoItem> {
    val byId = items.associateBy { it.id }
    return order.mapNotNull(byId::get)
}

fun canRename(item: TodoItem): Boolean =
    item.source != TodoSource.EVENT && separatorKind(item.title) == null

fun canDelete(item: TodoItem): Boolean = item.source == TodoSource.QUICK

/** Checklists are one level deep (the server re-parents deeper ones anyway). */
fun canAddSubtask(item: TodoItem): Boolean =
    item.parentId == null && separatorKind(item.title) == null

/** The source shown under an item's title ("Task · 14:00"). */
fun todoSourceLabel(source: TodoSource): String = when (source) {
    TodoSource.ROUTINE -> "Routine"
    TodoSource.EVENT -> "Event"
    TodoSource.TASK -> "Task"
    TodoSource.QUICK -> "Quick"
}

/** Which date to ask the server for. Viewing today's note asks for the
 *  phone's date; the board's rollover hour can make that a day the server
 *  hasn't started yet (e.g. 01:00 with a 04:00 rollover), in which case
 *  the server's own logical today is the real "today". */
fun todoRetryDate(requested: String, response: TodoDay, viewingToday: Boolean): String? =
    if (viewingToday && response.future && response.logicalToday.isNotEmpty() &&
        response.logicalToday != requested
    ) response.logicalToday else null
