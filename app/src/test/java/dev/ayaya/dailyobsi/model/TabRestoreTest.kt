package dev.ayaya.dailyobsi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabRestoreTest {
    private val sections = parseH2Sections("## time until\na\n## meds\nb\n## tasks\nc")
    private val first = sections[0].id
    private val tasks = sections[2].id

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
    fun `history is read-only, today follows the configured mode`() {
        assertEquals(SectionMode.READ, effectiveSectionMode(isHistorical = true, SectionMode.WRITE))
        assertEquals(SectionMode.WRITE, effectiveSectionMode(isHistorical = false, SectionMode.WRITE))
    }
}
