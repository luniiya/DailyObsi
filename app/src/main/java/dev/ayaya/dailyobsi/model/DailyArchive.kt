package dev.ayaya.dailyobsi.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

private val exactDailyName = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.md$")

fun dateFromDailyFileName(name: String): LocalDate? {
    val value = exactDailyName.matchEntire(name)?.groupValues?.get(1) ?: return null
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

fun memoryDates(activeDate: LocalDate, availableDates: Collection<LocalDate>): List<LocalDate> =
    availableDates.asSequence()
        .filter { date ->
            date.year < activeDate.year &&
                date.monthValue == activeDate.monthValue &&
                date.dayOfMonth == activeDate.dayOfMonth
        }
        .sortedDescending()
        .toList()
