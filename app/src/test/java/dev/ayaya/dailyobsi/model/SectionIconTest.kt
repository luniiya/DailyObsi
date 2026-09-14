package dev.ayaya.dailyobsi.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionIconTest {
    @Test
    fun `common heading words receive useful icons`() {
        assertEquals(SectionIcon.TASKS, defaultSectionIcon("Today's tasks"))
        assertEquals(SectionIcon.JOURNAL, defaultSectionIcon("Daily report"))
        assertEquals(SectionIcon.GRATITUDE, defaultSectionIcon("Gratitude"))
        assertEquals(SectionIcon.HEALTH, defaultSectionIcon("Sleep and health"))
        assertEquals(SectionIcon.WORK, defaultSectionIcon("Work projects"))
        assertEquals(SectionIcon.IDEAS, defaultSectionIcon("Ideas"))
    }

    @Test
    fun `long navigation labels use a compact first word`() {
        assertEquals("Morning", sectionNavLabel("Morning reflection"))
        assertEquals("Short", sectionNavLabel("Short"))
    }
}
