package dev.ayaya.dailyobsi

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every reading-mode / widget interaction (tick, indent, reorder, progress,
 *  section save) is one of these pure text rewrites of the note file. */
class DailyNoteEditingTest {
    @Test
    fun `checkbox mutations preserve surrounding text`() {
        val original = "before\n- [ ] task\nafter"
        assertEquals("before\n- [x] task\nafter", DailyNote.toggleCheckbox(original, 1))
    }

    @Test
    fun `toggling a checked box unchecks it, including uppercase X`() {
        assertEquals("- [ ] a", DailyNote.toggleCheckbox("- [x] a", 0))
        assertEquals("- [ ] a", DailyNote.toggleCheckbox("- [X] a", 0))
    }

    @Test
    fun `toggling keeps indentation and emoji text`() {
        assertEquals("\t- [x] 🪥 brush 1", DailyNote.toggleCheckbox("\t- [ ] 🪥 brush 1", 0))
    }

    @Test
    fun `toggling a non-checkbox or missing line changes nothing`() {
        val text = "## tasks\n- plain bullet"
        assertEquals(text, DailyNote.toggleCheckbox(text, 0))
        assertEquals(text, DailyNote.toggleCheckbox(text, 1))
        assertEquals(text, DailyNote.toggleCheckbox(text, 99))
    }

    @Test
    fun `parseCheckboxes finds every item in file order`() {
        val items = DailyNote.parseCheckboxes("## t\n- [ ] one\ntext\n\t- [x] two")
        assertEquals(listOf(TodoItem(1, false, "one"), TodoItem(3, true, "two")), items)
    }

    @Test
    fun `indent adds a tab, outdent removes a tab or two spaces`() {
        assertEquals("\t- [ ] a", DailyNote.shiftIndent("- [ ] a", 0, 1))
        assertEquals("- [ ] a", DailyNote.shiftIndent("\t- [ ] a", 0, -1))
        assertEquals("- [ ] a", DailyNote.shiftIndent("  - [ ] a", 0, -1))
    }

    @Test
    fun `outdenting an unindented line is a no-op`() {
        assertEquals("- [ ] a", DailyNote.shiftIndent("- [ ] a", 0, -1))
    }

    @Test
    fun `moving a line swaps only its neighbor`() {
        assertEquals("two\none\nthree", DailyNote.moveLine("one\ntwo\nthree", 0, 1))
        assertEquals("one\nthree\ntwo", DailyNote.moveLine("one\ntwo\nthree", 2, -1))
    }

    @Test
    fun `moving past either end of the file does nothing`() {
        assertEquals("one\ntwo", DailyNote.moveLine("one\ntwo", 0, -1))
        assertEquals("one\ntwo", DailyNote.moveLine("one\ntwo", 1, 1))
    }

    @Test
    fun `replaceLine rewrites exactly one line, ignores bad indices`() {
        assertEquals("a\nX\nc", DailyNote.replaceLine("a\nb\nc", 1, "X"))
        assertEquals("a\nb", DailyNote.replaceLine("a\nb", 5, "X"))
    }

    @Test
    fun `replaceLines swaps a span, or inserts for an empty span`() {
        assertEquals("h\nnew1\nnew2\nz", DailyNote.replaceLines("h\nold1\nold2\nz", 1..2, "new1\nnew2"))
        assertEquals("h\nbody\nz", DailyNote.replaceLines("h\nz", 1..0, "body"))
    }

    @Test
    fun `daily file names are ISO dates`() {
        assertEquals("2026-09-27.md", DailyNote.fileNameFor(LocalDate.of(2026, 9, 27)))
    }
}
