package dev.ayaya.dailyobsi.model

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class BedtimeTest {
    private val ten = LocalTime.of(22, 0)
    private fun at(time: String) = LocalDateTime.parse("2026-10-03T$time")

    @Test
    fun `formats like bc-print`() {
        assertEquals("3h05m", bedtimeCountdown(at("18:54:30"), ten))
        assertEquals("3h", bedtimeCountdown(at("19:00"), ten))
        assertEquals("1h01m", bedtimeCountdown(at("20:59"), ten))
        assertEquals("42m", bedtimeCountdown(at("21:18"), ten))
        assertEquals("0m", bedtimeCountdown(at("21:59:30"), ten))
        assertEquals("0m", bedtimeCountdown(at("22:00"), ten))
    }

    @Test
    fun `nothing from bedtime until three`() {
        assertEquals("", bedtimeCountdown(at("22:00:01"), ten))
        assertEquals("", bedtimeCountdown(at("23:30"), ten))
        assertEquals("", bedtimeCountdown(at("02:59"), ten))
        assertEquals("19h", bedtimeCountdown(at("03:00"), ten))
    }

    @Test
    fun `a bedtime after midnight counts down through midnight`() {
        val one = LocalTime.of(1, 0)
        assertEquals("2h", bedtimeCountdown(at("23:00"), one))
        assertEquals("30m", bedtimeCountdown(at("00:30"), one))
        assertEquals("", bedtimeCountdown(at("01:30"), one))
        assertEquals("22h", bedtimeCountdown(at("03:00"), one))
    }

    @Test
    fun `wakes right after the shown minute changes, or at three`() {
        assertEquals(at("21:18:01"), nextBedtimeChange(at("21:17:30"), ten))
        assertEquals(at("21:19:01"), nextBedtimeChange(at("21:18:01"), ten))
        assertEquals(at("22:00:01"), nextBedtimeChange(at("22:00"), ten))
        assertEquals(LocalDateTime.parse("2026-10-04T03:00"), nextBedtimeChange(at("22:00:01"), ten))
        assertEquals(at("03:00"), nextBedtimeChange(at("01:00"), ten))
        // The text really changes there.
        val before = at("21:17:30")
        val next = nextBedtimeChange(before, ten)
        assertEquals("42m", bedtimeCountdown(next.minusSeconds(1), ten))
        assertEquals("41m", bedtimeCountdown(next, ten))
    }
}
