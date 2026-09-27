package dev.ayaya.dailyobsi

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownEditingTest {
    @Test
    fun `enter on an ordered item continues with the next number`() {
        assertEquals(ListEnter.Continue("2. "), listEnterFor("1. first"))
        assertEquals(ListEnter.Continue("\t10. "), listEnterFor("\t9. nine"))
    }

    @Test
    fun `enter on a checkbox always starts an unchecked one`() {
        assertEquals(ListEnter.Continue("- [ ] "), listEnterFor("- [ ] a"))
        assertEquals(ListEnter.Continue("- [ ] "), listEnterFor("- [x] done"))
        assertEquals(ListEnter.Continue("\t- [ ] "), listEnterFor("\t- [ ] sub"))
    }

    @Test
    fun `enter on an empty item exits the list`() {
        assertEquals(ListEnter.Exit, listEnterFor("3. "))
        assertEquals(ListEnter.Exit, listEnterFor("- [ ] "))
    }

    @Test
    fun `enter on plain text does nothing special`() {
        assertNull(listEnterFor("just text"))
        assertNull(listEnterFor("## header"))
    }

    @Test
    fun `checkbox overlays sit after indentation and hide the marker`() {
        val specs = checkboxOverlaySpecs("x\n\t- [x] done\n- [ ] a")
        assertEquals(2, specs.size)
        assertEquals(CheckboxOverlaySpec(lineIndex = 1, hideStart = 2, hideEnd = 9, anchorOffset = 3, checked = true), specs[0])
        assertEquals(CheckboxOverlaySpec(lineIndex = 2, hideStart = 14, hideEnd = 20, anchorOffset = 14, checked = false), specs[1])
    }

    @Test
    fun `horizontal rule ranges cover whole rule lines only`() {
        assertEquals(listOf(2..4, 13..15), horizontalRuleRanges("a\n---\n- [ ] \n***"))
    }

    @Test
    fun `cursor stays put (clamped) with no pending bubble action`() {
        assertEquals(3, resolveCursorFollow("abcdef", 3, "abcdefgh", null))
        assertEquals(2, resolveCursorFollow("abcdef", 5, "ab", null))
    }

    @Test
    fun `cursor follows its line when the line moves`() {
        // cursor at "o" of "one" (offset 1), line 0 moves down to line 1
        val new = resolveCursorFollow("one\ntwo", 1, "two\none", PendingCursorFollow.MovedLine(0, 1))
        assertEquals(5, new)
    }

    @Test
    fun `cursor keeps its place in the text when the line is indented`() {
        // cursor before "b" (offset 2 in "- b"), a tab gets added at the line start
        val new = resolveCursorFollow("- b", 2, "\t- b", PendingCursorFollow.ReindentedLine(0))
        assertEquals(3, new)
    }

    @Test
    fun `edit-mode highlighting never changes text length`() {
        val lines = listOf(
            "## tasks", "- [ ] **bold** ==hi==", "- [x] done", "---", "#daily #tag",
            "1. [[note|alias]] and [link](http://x)", "<mark class=\"hltr-flashy-yellow\">=</mark> x",
            "```progressbar", "value: 1", "```", "> quote ~~strike~~ `code`",
        )
        val raw = lines.joinToString("\n")
        // Styles are replayed onto the raw text by offset, so lengths must match
        // exactly (checkbox marks are swapped for a same-length glyph, fine).
        assertEquals(raw.length, highlightMarkdownForEdit(raw, Color.Blue).length)
    }
}
