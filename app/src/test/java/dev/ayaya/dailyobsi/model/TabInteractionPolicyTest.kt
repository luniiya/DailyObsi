package dev.ayaya.dailyobsi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabInteractionPolicyTest {
    @Test
    fun `reading tabs do not swipe but writing tabs do`() {
        assertFalse(canSwipeBetweenTabs(false, SectionMode.READ))
        assertTrue(canSwipeBetweenTabs(false, SectionMode.WRITE))
    }

    @Test
    fun `historical tabs are always read only and cannot swipe`() {
        assertEquals(SectionMode.READ, effectiveSectionMode(true, SectionMode.WRITE))
        assertFalse(canSwipeBetweenTabs(true, SectionMode.WRITE))
    }
}
