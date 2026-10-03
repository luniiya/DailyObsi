package dev.ayaya.dailyobsi.todo

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoModelsTest {
    private fun item(id: Long, parent: Long? = null, source: TodoSource = TodoSource.QUICK, done: Boolean = false, title: String = "item $id") =
        TodoItem(id = id, source = source, title = title, completed = done, parentId = parent)

    // 1, 2 (with 3, 4 under it), 5
    private val items = listOf(item(1), item(2), item(3, parent = 2), item(4, parent = 2), item(5))

    @Test
    fun `parses the day the plugin returns, including Postgres-style booleans`() {
        val day = parseTodoDay(
            """{"day":"2026-10-03","logicalToday":"2026-10-03","editable":true,"future":false,
            "owner":"ayaya","isOwner":true,"items":[
              {"id":7,"source_type":"task","title":"Call","starts_at":"1759500000","due_at":null,
               "all_day":false,"completed":true,"href":null,"error":null,"parent_id":null,"position":"0"},
              {"id":"8","source_type":"routine","title":"---","starts_at":null,"due_at":null,
               "all_day":"0","completed":"f","parent_id":7}]}""",
        )
        assertEquals("2026-10-03", day.day)
        assertTrue(day.editable)
        assertEquals(2, day.items.size)
        assertEquals(TodoItem(7, TodoSource.TASK, "Call", true, null, startsAt = 1759500000), day.items[0])
        assertEquals(7L, day.items[1].parentId)
        assertFalse(day.items[1].completed)
    }

    @Test
    fun `polling fetches the day only when its version moved`() {
        assertEquals("v1", parseTodoDay("""{"day":"d","items":[],"version":"v1"}""").version)
        assertNull(parseTodoDay("""{"day":"d","items":[]}""").version)
        assertEquals("v2", parseDayVersion("""{"version":"v2"}"""))
        assertFalse(todoNeedsReload("v1", "v1"))
        assertTrue(todoNeedsReload("v1", "v2"))
        assertTrue(todoNeedsReload(null, "v1"))
        assertTrue(todoNeedsReload("v1", null))
    }

    @Test
    fun `server error messages and created ids are read from the body`() {
        assertEquals("Historical days are read-only", serverErrorMessage("""{"error":"Historical days are read-only"}"""))
        assertNull(serverErrorMessage("<html>login</html>"))
        assertNull(serverErrorMessage(null))
        assertEquals(42L, createdId("""{"id":42}"""))
        assertNull(createdId("{}"))
    }

    @Test
    fun `separators match the plugin`() {
        assertEquals(SeparatorKind.RULE, separatorKind(" --- "))
        assertEquals(SeparatorKind.RULE, separatorKind("___"))
        assertEquals(SeparatorKind.SPACE, separatorKind("<BR />"))
        assertNull(separatorKind("-- not a rule"))
    }

    @Test
    fun `depth follows parents and ignores missing ones`() {
        val depths = todoDepths(items + item(6, parent = 99))
        assertEquals(0, depths[2])
        assertEquals(1, depths[3])
        assertEquals(0, depths[6])
    }

    @Test
    fun `subtasks are independent by default`() {
        val done = withCompleted(items, 2, true, completeSubtasks = false)
        assertEquals(listOf(false, true, false, false, false), done.map { it.completed })
        assertFalse(parseTodoDay("""{"day":"d","items":[]}""").completeSubtasks)
    }

    @Test
    fun `with the board setting on, ticking a parent ticks its subtasks, unticking leaves them`() {
        assertTrue(parseTodoDay("""{"day":"d","items":[],"completeSubtasks":true}""").completeSubtasks)
        val done = withCompleted(items, 2, true, completeSubtasks = true)
        assertEquals(listOf(false, true, true, true, false), done.map { it.completed })
        val reopened = withCompleted(done, 2, false, completeSubtasks = true)
        assertEquals(listOf(false, false, true, true, false), reopened.map { it.completed })
    }

    @Test
    fun `moving a parent carries its subtasks`() {
        assertEquals(listOf(2L, 3, 4, 1, 5), reorderedIds(items, 2, -1))
        assertEquals(listOf(1L, 5, 2, 3, 4), reorderedIds(items, 2, 1))
    }

    @Test
    fun `subtasks move among their siblings only`() {
        assertEquals(listOf(1L, 2, 4, 3, 5), reorderedIds(items, 3, 1))
        assertNull(reorderedIds(items, 3, -1))
        assertNull(reorderedIds(items, 4, 1))
        assertNull(reorderedIds(items, 1, -1))
        assertNull(reorderedIds(items, 5, 1))
    }

    @Test
    fun `dragging moves a block past whole sibling blocks`() {
        assertEquals(5L, todoNeighbor(items, 2, 1))
        assertEquals(1L, todoNeighbor(items, 2, -1))
        assertEquals(4L, todoNeighbor(items, 3, 1))
        assertNull(todoNeighbor(items, 4, 1))
        assertEquals(setOf(2L, 3, 4), todoBlock(items, 2))
        assertEquals(setOf(5L), todoBlock(items, 5))
        // Two drag steps down from the top = one reorder call with the final order.
        val once = inOrder(items, reorderedIds(items, 1, 1)!!)
        val twice = inOrder(once, reorderedIds(once, 1, 1)!!)
        assertEquals(listOf(2L, 3, 4, 5, 1), twice.map { it.id })
    }

    @Test
    fun `what each kind of item allows`() {
        assertFalse(canRename(item(1, source = TodoSource.EVENT)))
        assertFalse(canRename(item(1, title = "---")))
        assertTrue(canRename(item(1, source = TodoSource.TASK)))
        assertTrue(canDelete(item(1)))
        assertFalse(canDelete(item(1, source = TodoSource.ROUTINE)))
        assertTrue(canAddSubtask(item(1)))
        assertFalse(canAddSubtask(item(3, parent = 2)))
    }

    @Test
    fun `before the board's rollover, today falls back to the server's logical today`() {
        val notStarted = TodoDay("2026-10-04", "2026-10-03", editable = false, future = true, items = emptyList())
        assertEquals("2026-10-03", todoRetryDate("2026-10-04", notStarted, viewingToday = true))
        assertNull(todoRetryDate("2026-10-04", notStarted, viewingToday = false))
        val started = notStarted.copy(future = false, logicalToday = "2026-10-04")
        assertNull(todoRetryDate("2026-10-04", started, viewingToday = true))
    }

    @Test
    fun `the cache returns the last list per date`() {
        val cache = TodoCache(Files.createTempDirectory("todo").toFile())
        assertNull(cache.load("2026-10-03"))
        cache.store("2026-10-03", "{\"a\":1}", nowMillis = 1_000_000L)
        cache.store("2026-10-03", "{\"a\":2}", nowMillis = 2_000_000L)
        val entry = cache.load("2026-10-03")!!
        assertEquals("{\"a\":2}", entry.json)
        assertEquals(2_000_000L, entry.fetchedAtMillis)
    }

    @Test
    fun `the widget shows ticks the server hasn't confirmed yet, the server's way`() {
        val ticked = withPendingTicks(items, mapOf(2L to true, 5L to true), completeSubtasks = true)
        assertEquals(listOf(false, true, true, true, true), ticked.map { it.completed })
        assertEquals(items, withPendingTicks(items, emptyMap(), completeSubtasks = true))
    }

    @Test
    fun `separators don't count towards done`() {
        val list = items + item(9, title = "---") + item(10, title = "<br>")
        assertEquals(items.map { it.id }, todoCheckable(list).map { it.id })
    }

    @Test
    fun `cache writes for the same date don't trip over each other`() {
        val dir = Files.createTempDirectory("todo").toFile()
        val threads = (1..8).map { n -> Thread { repeat(20) { TodoCache(dir).store("2026-10-03", "{\"n\":$n}") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(TodoCache(dir).load("2026-10-03")!!.json.startsWith("{\"n\":"))
        assertEquals(listOf("2026-10-03.json"), dir.list()!!.toList())
    }
}
