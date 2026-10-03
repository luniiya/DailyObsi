package dev.ayaya.dailyobsi.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The bedtime countdown, a port of the user's `~/.config/bin/bc-print.sh`
 * with the static bedtime only (the user dropped the server-synced one).
 * A day runs from 03:00 to 03:00. From bedtime until 03:00 is the "night
 * zone" and shows nothing.
 */
const val BEDTIME_DAY_START_HOUR = 3
val DEFAULT_BEDTIME: LocalTime = LocalTime.of(22, 0)

/** How the bedtime widget looks (Settings). [ACCENT] is the one the user asked for. */
enum class BedtimeStyle(val label: String) {
    ACCENT("Accent"),
    THIN("Thin"),
    WHITE("White"),
    CARD("Card"),
}

/** Tonight's bedtime for the day [now] belongs to. A bedtime before 03:00 is
 *  after midnight, so it falls on the next calendar day. The script has no such
 *  case, because its bedtime is always in the evening. */
fun bedtimeFor(now: LocalDateTime, bedtime: LocalTime): LocalDateTime {
    val day = now.minusHours(BEDTIME_DAY_START_HOUR.toLong()).toLocalDate()
    val bed = day.atTime(bedtime)
    return if (bedtime.hour < BEDTIME_DAY_START_HOUR) bed.plusDays(1) else bed
}

/** "3h05m", "3h", "42m", or "" in the night zone, exactly like bc-print.sh (minutes rounded down). */
fun bedtimeCountdown(now: LocalDateTime, bedtime: LocalTime): String {
    val bed = bedtimeFor(now, bedtime)
    if (now.isAfter(bed)) return ""
    val seconds = Duration.between(now, bed).seconds
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h" + "%02dm".format(minutes)
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** When [bedtimeCountdown]'s text next changes. The widget sleeps until then
 *  instead of ticking: just after the next minute boundary, or at 03:00 if it's
 *  in the night zone. */
fun nextBedtimeChange(now: LocalDateTime, bedtime: LocalTime): LocalDateTime {
    val bed = bedtimeFor(now, bedtime)
    if (now.isAfter(bed)) {
        val dayStart = now.toLocalDate().atTime(BEDTIME_DAY_START_HOUR, 0)
        return if (now.isBefore(dayStart)) dayStart else dayStart.plusDays(1)
    }
    // Shown minutes are floor(remaining); they drop just after remaining hits a whole minute.
    val wholeMinutes = Duration.between(now, bed).toMinutes()
    return bed.minusMinutes(wholeMinutes).plusSeconds(1)
}
