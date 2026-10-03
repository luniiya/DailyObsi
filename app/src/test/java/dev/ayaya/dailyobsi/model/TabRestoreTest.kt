package dev.ayaya.dailyobsi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabRestoreTest {
    private val parsed = parseH2Sections("## time until\na\n## meds\nb\n## tasks\nc")
    private val sections = tabIds(parsed, todoConnected = false)
    private val first = sections[0]
    private val tasks = sections[2]

    @Test
    fun `reloading the same note keeps the open tab`() {
        assertEquals(tasks, restoredSection(sections, current = tasks, remembered = null))
    }

    @Test
    fun `reopening the app restores the remembered tab`() {
        assertEquals(tasks, restoredSection(sections, current = null, remembered = tasks))
    }

    @Test
    fun `the open tab wins over the remembered one`() {
        assertEquals(first, restoredSection(sections, current = first, remembered = tasks))
    }

    @Test
    fun `a fresh note, or a tab that no longer exists, falls back to the first`() {
        assertEquals(first, restoredSection(sections, current = null, remembered = null))
        assertEquals(first, restoredSection(sections, current = SectionId("gone", 0), remembered = SectionId("gone", 0)))
    }

    @Test
    fun `no sections means no tab`() {
        assertNull(restoredSection(emptyList(), current = tasks, remembered = tasks))
    }

    @Test
    fun `the todo tab comes last, only when connected and the note has sections`() {
        assertEquals(sections + TODO_TAB_ID, tabIds(parsed, todoConnected = true))
        assertEquals(sections, tabIds(parsed, todoConnected = false))
        assertEquals(emptyList<SectionId>(), tabIds(emptyList(), todoConnected = true))
    }

    @Test
    fun `the todo tab is remembered like any other, and dropped once disconnected`() {
        val withTodo = tabIds(parsed, todoConnected = true)
        assertEquals(TODO_TAB_ID, restoredSection(withTodo, current = null, remembered = TODO_TAB_ID))
        assertEquals(first, restoredSection(sections, current = TODO_TAB_ID, remembered = TODO_TAB_ID))
    }

    @Test
    fun `no heading can produce the todo tab id`() {
        val sneaky = parseH2Sections("## nextcloud daily todo\nx\n## Nextcloud Daily Todo\ny")
        assertEquals(false, sneaky.any { it.id == TODO_TAB_ID })
    }

    @Test
    fun `history is read-only, today follows the configured mode`() {
        assertEquals(SectionMode.READ, effectiveSectionMode(isHistorical = true, SectionMode.WRITE))
        assertEquals(SectionMode.WRITE, effectiveSectionMode(isHistorical = false, SectionMode.WRITE))
    }
}
