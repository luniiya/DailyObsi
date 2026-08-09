package dev.ayaya.dailyobsi

import android.net.Uri
import androidx.compose.ui.graphics.Color

/**
 * Not a full CommonMark renderer -- a pragmatic subset covering what
 * actually shows up in this vault's daily notes (see "00 - todo/daily
 * template.md"): headers, Obsidian #tags, bold/italic/strikethrough/inline
 * code, ==highlights== and <mark> HTML highlights (Highlightr plugin),
 * [[wikilinks]] and ![[embeds]] (rendered as real images when findable),
 * ordered/unordered/checkbox lists with indentation (shiftable left/right),
 * blockquotes, horizontal rules, and ```progressbar``` blocks rendered as an
 * actual (tappable, for manual counters) progress bar instead of raw YAML.
 *
 * Split across three files: this one (shared regexes/parsing/colors, no
 * Compose UI), MarkdownReading.kt (the LazyColumn-based reading-mode
 * renderer), and MarkdownEditing.kt (the TextFieldState-based raw-markdown
 * editor shared by main edit mode and the section editor).
 */

val HEADER = Regex("""^(#{1,6})(\s+)(.*)$""")
val TAGS_LINE = Regex("""^(#\S+\s*)+$""")
val HR = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
val ORDERED = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
val UNORDERED = Regex("""^(\s*)[-*+]\s+(.*)$""")
val BLOCKQUOTE = Regex("""^(\s*)>\s?(.*)$""")
val EMBED_LINE = Regex("""^!\[\[([^\]]+)]]$""")

/** Process-lifetime cache of resolved `![[embed]]` URIs, keyed on "dailyUri|name".
 *  See EmbedImage in MarkdownReading.kt -- avoids re-running the SAF attachment
 *  search every time a LazyColumn item scrolls out of view and back in. */
val attachmentUriCache = mutableMapOf<String, Uri?>()

val HIGHLIGHT_YELLOW = Color(0x66FFEB3B)
val CODE_BG = Color(0x33808080)
// No hardcoded LINK_COLOR anymore -- link color follows the current Material
// You dynamic theme accent instead (MaterialTheme.colorScheme.primary in-app,
// GlanceTheme.colors.primary in the widgets), resolved at each composable
// call site and threaded down into the non-composable inline-parsing
// functions. A real reported bug (links rendering a fixed blue regardless of
// system theme) is what prompted removing this rather than just not using it.
val MUTED = Color(0xFF9E9E9E)
val CHECKED_COLOR = Color(0xFF4CAF50)

// Header color-by-level, matching the vault's leftover AnuPpuccin
// style-settings scheme (h2 pink / h3 flamingo / h4 rosewater / h6 blue,
// h1/h5 left default) -- Catppuccin *Latte* hex values specifically, since
// the Mocha pastel originals are too washed out to read against a light
// background and this app doesn't split header color by theme.
private val HEADER_H2_COLOR = Color(0xFFEA76CB) // pink
private val HEADER_H3_COLOR = Color(0xFFDD7878) // flamingo
private val HEADER_H4_COLOR = Color(0xFFDC8A78) // rosewater
private val HEADER_H6_COLOR = Color(0xFF1E66F5) // blue

fun headerColorFor(level: Int): Color = when (level) {
    2 -> HEADER_H2_COLOR
    3 -> HEADER_H3_COLOR
    4 -> HEADER_H4_COLOR
    6 -> HEADER_H6_COLOR
    else -> Color.Unspecified
}

fun highlightColorFor(classAttr: String): Color = when {
    classAttr.contains("blue", ignoreCase = true) -> Color(0x662196F3)
    classAttr.contains("green", ignoreCase = true) -> Color(0x664CAF50)
    classAttr.contains("pink", ignoreCase = true) -> Color(0x66E91E63)
    classAttr.contains("red", ignoreCase = true) -> Color(0x66F44336)
    classAttr.contains("orange", ignoreCase = true) -> Color(0x66FF9800)
    classAttr.contains("purple", ignoreCase = true) -> Color(0x669C27B0)
    else -> HIGHLIGHT_YELLOW
}

/** Each tab, or run of 2 leading spaces, is one nesting level. */
fun indentLevel(leading: String): Int {
    var level = 0
    var i = 0
    while (i < leading.length) {
        if (leading[i] == '\t') { level++; i++ } else { level++; i += 2 }
    }
    return level
}

fun leadingWhitespaceOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }

sealed class Block {
    data class Line(val raw: String, val lineIndex: Int) : Block()
    /** [firstBodyLine] is the file line index of body[0], for writing back to a specific field line. */
    data class Code(val lang: String, val body: List<String>, val firstBodyLine: Int) : Block()
}

fun parseBlocks(text: String): List<Block> {
    val lines = text.lines()
    val blocks = mutableListOf<Block>()
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trimStart()
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val body = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && !lines[j].trimStart().startsWith("```")) {
                body.add(lines[j]); j++
            }
            blocks.add(Block.Code(lang, body, i + 1))
            i = j + 1 // skip the closing fence too
        } else {
            blocks.add(Block.Line(lines[i], i))
            i++
        }
    }
    return blocks
}

/** Inclusive raw-file line range of the *body* under the header at
 *  [headerLineIndex] -- the header line itself excluded, everything up to
 *  (not including) the next header whose level is <= this one, or end of
 *  file. Same boundary rule as the reading-mode fold in MarkdownView, and
 *  what the section editor (pencil icon) reads/writes. Returns an empty
 *  range (nothing to show) if the header has no body before that boundary.
 *  Walks [parseBlocks]'s block list rather than raw lines so a `#` inside a
 *  fenced code block is never mistaken for a heading, matching the fold. */
fun headerBodyLineRange(text: String, headerLineIndex: Int): IntRange {
    val blocks = parseBlocks(text)
    val headerBlockIdx = blocks.indexOfFirst { it is Block.Line && it.lineIndex == headerLineIndex }
    val totalLines = text.lines().size
    if (headerBlockIdx == -1) return headerLineIndex + 1..headerLineIndex
    val level = HEADER.matchEntire((blocks[headerBlockIdx] as Block.Line).raw)?.groupValues?.get(1)?.length
        ?: return headerLineIndex + 1..headerLineIndex
    var endLine = totalLines - 1
    for (i in headerBlockIdx + 1 until blocks.size) {
        val b = blocks[i]
        if (b is Block.Line) {
            val hLevel = HEADER.matchEntire(b.raw)?.groupValues?.get(1)?.length
            if (hLevel != null && hLevel <= level) {
                endLine = b.lineIndex - 1
                break
            }
        }
    }
    return (headerLineIndex + 1)..endLine
}
