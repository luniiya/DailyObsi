package dev.ayaya.dailyobsi.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyArchiveTest {
    @Test
    fun `accepts only exact valid daily filenames`() {
        assertEquals(LocalDate.of(2026, 9, 14), dateFromDailyFileName("2026-09-14.md"))
        assertNull(dateFromDailyFileName("2026-09-14 (1).md"))
        assertNull(dateFromDailyFileName("2026-09-14.sync-conflict.md"))
        assertNull(dateFromDailyFileName("2026-02-30.md"))
    }

    @Test
    fun `memories include same date in earlier years newest first`() {
        val active = LocalDate.of(2026, 9, 14)
        val available = listOf(
            LocalDate.of(2025, 9, 14),
            LocalDate.of(2024, 9, 14),
            LocalDate.of(2027, 9, 14),
            LocalDate.of(2025, 9, 13),
        )

        assertEquals(
            listOf(LocalDate.of(2025, 9, 14), LocalDate.of(2024, 9, 14)),
            memoryDates(active, available),
        )
    }
}
