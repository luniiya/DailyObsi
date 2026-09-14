package dev.ayaya.dailyobsi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoteSectionsTest {
    @Test
    fun `splits h2 sections and hides preamble`() {
        val text = "#tag\n## First\none\n### nested\ntwo\n## Second\nthree"

        val sections = parseH2Sections(text)

        assertEquals(listOf("First", "Second"), sections.map { it.title })
        assertEquals("one\n### nested\ntwo", sections[0].body)
        assertFalse(sections.any { it.body.contains("#tag") })
    }

    @Test
    fun `ignores headings inside fenced blocks`() {
        val text = "## One\n```text\n## fake\n```\nend\n## Two\nbody"

        assertEquals(listOf("One", "Two"), parseH2Sections(text).map { it.title })
    }

    @Test
    fun `duplicate titles receive distinct identities`() {
        val sections = parseH2Sections("## Same\na\n## same \nb")

        assertEquals(SectionId("same", 0), sections[0].id)
        assertEquals(SectionId("same", 1), sections[1].id)
    }

    @Test
    fun `replaces one body without touching preamble or siblings`() {
        val original = "#tag\n## First\none\n## Second\ntwo"
        val id = parseH2Sections(original).first().id

        assertEquals(
            "#tag\n## First\nchanged\nline\n## Second\ntwo",
            replaceSectionBody(original, id, "changed\nline"),
        )
    }
}
