package dev.ayaya.dailyobsi.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarTest {
    @Test
    fun `few tabs split the full bar width evenly`() {
        // 360 - 2*6 side padding - 2*2 spacing = 344, / 3
        assertEquals(344f / 3f, bottomBarTabWidth(360.dp, 3).value, 0.01f)
    }

    @Test
    fun `many tabs stop shrinking at the minimum and scroll instead`() {
        assertEquals(72f, bottomBarTabWidth(360.dp, 12).value, 0.01f)
    }

    @Test
    fun `zero tabs does not divide by zero`() {
        assertEquals(348f, bottomBarTabWidth(360.dp, 0).value, 0.01f)
    }
}
