package dev.ayaya.dailyobsi.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceInlineTest {
    private fun visible(raw: String) = parseInlineSegmentsForGlance(raw).joinToString("") { it.text }

    @Test
    fun `plain text is one run`() {
        assertEquals(listOf(InlineRun("hello")), parseInlineSegmentsForGlance("hello"))
    }

    @Test
    fun `markdown delimiters are hidden, styles applied`() {
        val runs = parseInlineSegmentsForGlance("a **b** *c* ~~d~~ `e`")
        assertEquals("a b c d e", runs.joinToString("") { it.text })
        assertTrue(runs.first { it.text == "b" }.bold)
        assertTrue(runs.first { it.text == "c" }.italic)
        assertTrue(runs.first { it.text == "d" }.strikethrough)
        assertTrue(runs.first { it.text == "e" }.code)
    }

    @Test
    fun `wikilinks show their alias as a link`() {
        val runs = parseInlineSegmentsForGlance("see [[grade fix|be 1st]]")
        assertEquals("see be 1st", visible("see [[grade fix|be 1st]]"))
        assertTrue(runs.last().link)
    }

    @Test
    fun `highlights keep their text and get a color`() {
        val runs = parseInlineSegmentsForGlance("<mark class=\"hltr-flashy-green\">=</mark> x ==y==")
        assertEquals("= x y", runs.joinToString("") { it.text })
        assertTrue(runs.first().highlightColor != null)
        assertTrue(runs.last().highlightColor != null)
    }

    @Test
    fun `unclosed delimiters stay as literal text`() {
        assertEquals("**not bold", visible("**not bold"))
    }
}
