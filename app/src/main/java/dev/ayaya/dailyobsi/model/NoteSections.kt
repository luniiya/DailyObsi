package dev.ayaya.dailyobsi.model

import dev.ayaya.dailyobsi.Block
import dev.ayaya.dailyobsi.HEADER
import dev.ayaya.dailyobsi.parseBlocks

fun normalizeHeading(title: String): String =
    title.trim().lowercase().replace(Regex("\\s+"), " ")

fun parseH2Sections(text: String): List<NoteSection> {
    val lines = text.lines()
    val headings = parseBlocks(text).mapNotNull { block ->
        val line = block as? Block.Line ?: return@mapNotNull null
        val match = HEADER.matchEntire(line.raw) ?: return@mapNotNull null
        if (match.groupValues[1].length != 2) return@mapNotNull null
        line.lineIndex to match.groupValues[3].trim()
    }
    val occurrences = mutableMapOf<String, Int>()
    return headings.mapIndexed { index, (lineIndex, title) ->
        val normalized = normalizeHeading(title)
        val occurrence = occurrences.getOrDefault(normalized, 0)
        occurrences[normalized] = occurrence + 1
        val nextHeader = headings.getOrNull(index + 1)?.first ?: lines.size
        val range = (lineIndex + 1)..(nextHeader - 1)
        val body = if (range.first > range.last) {
            ""
        } else {
            lines.subList(range.first, range.last + 1).joinToString("\n")
        }
        NoteSection(
            id = SectionId(normalized, occurrence),
            title = title,
            headerLineIndex = lineIndex,
            bodyRange = range,
            body = body,
        )
    }
}

fun replaceSectionBody(text: String, id: SectionId, body: String): String {
    val section = parseH2Sections(text).firstOrNull { it.id == id } ?: return text
    val lines = text.lines()
    val start = section.bodyRange.first.coerceIn(0, lines.size)
    val endExclusive = if (section.bodyRange.first > section.bodyRange.last) {
        start
    } else {
        (section.bodyRange.last + 1).coerceIn(start, lines.size)
    }
    val replacement = if (body.isEmpty()) emptyList() else body.split("\n")
    return (lines.take(start) + replacement + lines.drop(endExclusive)).joinToString("\n")
}
