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
    )
}

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

/** Optimistic tick, matching the server: completing a parent completes its
 *  subtasks, reopening it leaves them alone. */
fun withCompleted(items: List<TodoItem>, id: Long, completed: Boolean): List<TodoItem> {
    val targets = buildSet {
        add(id)
        if (completed) todoDescendants(items, id).forEach { add(it.id) }
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
