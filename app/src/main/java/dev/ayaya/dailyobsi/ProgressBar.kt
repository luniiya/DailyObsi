package dev.ayaya.dailyobsi

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A parsed ` ```progressbar ` block, shared by the in-app reader and the
 *  widgets so both agree on names, values and what's tappable. */
data class ProgressBarSpec(
    val name: String,
    val kind: String,
    val value: Int?,
    val max: Int?,
    /** Raw-file line index of the block's `value:` line, if it has one. */
    val valueLineIndex: Int?,
    private val min: String?,
    private val maxRaw: String?,
    private val button: Boolean,
) {
    /** `kind: manual` + `button: true` with a usable value/max gets +/-. */
    val interactive: Boolean
        get() = kind == "manual" && button && value != null && max != null && valueLineIndex != null

    /** 0..1 fill, or null when the block doesn't describe a computable bar. */
    fun fraction(today: LocalDate = LocalDate.now()): Float? = when (kind) {
        "manual" -> if (value != null && max != null && max > 0) {
            (value.toFloat() / max).coerceIn(0f, 1f)
        } else {
            null
        }
        "day-year" -> {
            val len = if (today.isLeapYear) 366f else 365f
            (today.dayOfYear / len).coerceIn(0f, 1f)
        }
        "day-custom" -> runCatching {
            val start = LocalDate.parse(min)
            val end = LocalDate.parse(maxRaw)
            val total = ChronoUnit.DAYS.between(start, end)
            if (total == 0L) null
            else (ChronoUnit.DAYS.between(start, today).toFloat() / total).coerceIn(0f, 1f)
        }.getOrNull()
        else -> null
    }
}

fun parseProgressBar(block: Block.Code): ProgressBarSpec {
    val fields = block.body.mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx == -1) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()
    val valueLine = block.body.indexOfFirst { it.trim().startsWith("value:") }
    return ProgressBarSpec(
        name = fields["name"] ?: fields["id"] ?: "progress",
        kind = fields["kind"] ?: "manual",
        value = fields["value"]?.toIntOrNull(),
        max = fields["max"]?.toIntOrNull(),
        valueLineIndex = valueLine.takeIf { it != -1 }?.let { block.firstBodyLine + it },
        min = fields["min"],
        maxRaw = fields["max"],
        button = fields["button"] == "true",
    )
}

/** The rewritten `value:` line after a +/- tap, clamped to 0..[max] and
 *  keeping the line's indentation; null if [line] has no integer value. */
fun progressValueLine(line: String, delta: Int, max: Int): String? {
    val value = line.substringAfter("value:", "").trim().toIntOrNull() ?: return null
    return "${leadingWhitespaceOf(line)}value: ${(value + delta).coerceIn(0, max)}"
}
