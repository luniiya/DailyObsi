package dev.ayaya.dailyobsi

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressBarTest {
    private fun block(vararg body: String, firstBodyLine: Int = 10) =
        Block.Code("progressbar", body.toList(), firstBodyLine)

    @Test
    fun `parses a manual counter with buttons`() {
        val spec = parseProgressBar(
            block("    id: est", "    kind: manual", "    name: e", "    button: true", "    value: 1", "    max: 3"),
        )
        assertEquals("e", spec.name)
        assertEquals(1, spec.value)
        assertEquals(3, spec.max)
        assertEquals(14, spec.valueLineIndex)
        assertTrue(spec.interactive)
        assertEquals(1f / 3f, spec.fraction()!!, 0.0001f)
    }

    @Test
    fun `name falls back to id, kind falls back to manual`() {
        val spec = parseProgressBar(block("id: meds", "value: 0", "max: 1"))
        assertEquals("meds", spec.name)
        assertEquals("manual", spec.kind)
        assertFalse("no button: true", spec.interactive)
    }

    @Test
    fun `manual bar with zero max has no fraction`() {
        assertNull(parseProgressBar(block("value: 0", "max: 0")).fraction())
    }

    @Test
    fun `day-year and day-custom compute from dates`() {
        val today = LocalDate.of(2026, 7, 2) // day 183 of 365
        assertEquals(183f / 365f, parseProgressBar(block("kind: day-year")).fraction(today)!!, 0.0001f)
        val custom = parseProgressBar(block("kind: day-custom", "min: 2026-07-01", "max: 2026-07-11"))
        assertEquals(0.1f, custom.fraction(today)!!, 0.0001f)
        assertFalse(custom.interactive)
    }

    @Test
    fun `date bars with bad dates have no fraction`() {
        assertNull(parseProgressBar(block("kind: day-custom", "min: nope", "max: 2026-01-01")).fraction())
    }

    @Test
    fun `plus and minus clamp to 0 and max, keeping indentation`() {
        assertEquals("    value: 2", progressValueLine("    value: 1", 1, 3))
        assertEquals("    value: 3", progressValueLine("    value: 3", 1, 3))
        assertEquals("value: 0", progressValueLine("value: 0", -1, 3))
        assertNull(progressValueLine("value: lots", 1, 3))
    }
}
