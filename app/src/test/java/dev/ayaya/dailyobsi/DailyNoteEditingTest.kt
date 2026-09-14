package dev.ayaya.dailyobsi

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyNoteEditingTest {
    @Test
    fun `checkbox mutations preserve surrounding text`() {
        val original = "before\n- [ ] task\nafter"
        assertEquals("before\n- [x] task\nafter", DailyNote.toggleCheckbox(original, 1))
    }

    @Test
    fun `moving a line swaps only its neighbor`() {
        assertEquals("two\none\nthree", DailyNote.moveLine("one\ntwo\nthree", 0, 1))
    }
}
