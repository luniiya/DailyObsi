package dev.ayaya.dailyobsi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParsingTest {
    @Test
    fun `code fences become one block that remembers its first body line`() {
        val blocks = parseBlocks("a\n```progressbar\nkind: manual\nvalue: 1\n```\nb")
        assertEquals(Block.Line("a", 0), blocks[0])
        assertEquals(Block.Code("progressbar", listOf("kind: manual", "value: 1"), 2), blocks[1])
        assertEquals(Block.Line("b", 5), blocks[2])
    }

    @Test
    fun `an unclosed fence swallows the rest of the note`() {
        val blocks = parseBlocks("```\nx\ny")
        assertEquals(listOf(Block.Code("", listOf("x", "y"), 1)), blocks)
    }

    @Test
    fun `header body stops at the next header of same or higher level`() {
        val text = "## a\none\n### sub\ntwo\n## b\nthree"
        assertEquals(1..3, headerBodyLineRange(text, 0))
        assertEquals(3..3, headerBodyLineRange(text, 2))
        assertEquals(5..5, headerBodyLineRange(text, 4))
    }

    @Test
    fun `header body ignores hashes inside code blocks`() {
        val text = "## a\n```\n## not a header\n```\nend"
        assertEquals(1..4, headerBodyLineRange(text, 0))
    }

    @Test
    fun `header with no body gives an empty range`() {
        assertTrue(headerBodyLineRange("## a\n## b", 0).isEmpty())
    }

    @Test
    fun `headers need a space after the hashes, tags do not match`() {
        assertTrue(HEADER.matches("## tasks"))
        assertFalse(HEADER.matches("#daily"))
        assertTrue(TAGS_LINE.matches("#daily #year2026"))
    }

    @Test
    fun `horizontal rules`() {
        listOf("---", "***", "___", "- - -", "----------").forEach { assertTrue(it, HR.matches(it)) }
        listOf("--", "-- x", "- [ ] a").forEach { assertFalse(it, HR.matches(it)) }
    }

    @Test
    fun `indent level counts tabs and pairs of spaces`() {
        assertEquals(0, indentLevel(""))
        assertEquals(2, indentLevel("\t\t"))
        assertEquals(2, indentLevel("    "))
        assertEquals("\t  ", leadingWhitespaceOf("\t  - x"))
    }

    @Test
    fun `highlightr class names pick a color, unknown falls back to yellow`() {
        assertEquals(HIGHLIGHT_YELLOW, highlightColorFor("hltr-flashy-yellow"))
        assertFalse(highlightColorFor("hltr-flashy-bluedarker") == HIGHLIGHT_YELLOW)
    }
}
