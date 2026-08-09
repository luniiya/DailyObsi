package dev.ayaya.dailyobsi

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Not a full CommonMark renderer -- a pragmatic subset covering what
 * actually shows up in this vault's daily notes (see "00 - todo/daily
 * template.md"): headers, Obsidian #tags, bold/italic/strikethrough/inline
 * code, ==highlights== and <mark> HTML highlights (Highlightr plugin),
 * [[wikilinks]] and ![[embeds]] (rendered as real images when findable),
 * ordered/unordered/checkbox lists with indentation (shiftable left/right),
 * blockquotes, horizontal rules, and ```progressbar``` blocks rendered as an
 * actual (tappable, for manual counters) progress bar instead of raw YAML.
 */

private val HEADER = Regex("""^(#{1,6})(\s+)(.*)$""")
private val TAGS_LINE = Regex("""^(#\S+\s*)+$""")
private val HR = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
private val ORDERED = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
private val UNORDERED = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val BLOCKQUOTE = Regex("""^(\s*)>\s?(.*)$""")
private val EMBED_LINE = Regex("""^!\[\[([^\]]+)]]$""")

/** Process-lifetime cache of resolved `![[embed]]` URIs, keyed on "dailyUri|name".
 *  See [EmbedImage] -- avoids re-running the SAF attachment search every time
 *  a LazyColumn item scrolls out of view and back in. */
private val attachmentUriCache = mutableMapOf<String, Uri?>()

private val HIGHLIGHT_YELLOW = Color(0x66FFEB3B)
private val CODE_BG = Color(0x33808080)
private val LINK_COLOR = Color(0xFF4A90D9)
private val MUTED = Color(0xFF9E9E9E)

// Header color-by-level, matching the vault's leftover AnuPpuccin
// style-settings scheme (h2 pink / h3 flamingo / h4 rosewater / h6 blue,
// h1/h5 left default) -- Catppuccin *Latte* hex values specifically, since
// the Mocha pastel originals are too washed out to read against a light
// background and this app doesn't split header color by theme.
private val HEADER_H2_COLOR = Color(0xFFEA76CB) // pink
private val HEADER_H3_COLOR = Color(0xFFDD7878) // flamingo
private val HEADER_H4_COLOR = Color(0xFFDC8A78) // rosewater
private val HEADER_H6_COLOR = Color(0xFF1E66F5) // blue

private fun headerColorFor(level: Int): Color = when (level) {
    2 -> HEADER_H2_COLOR
    3 -> HEADER_H3_COLOR
    4 -> HEADER_H4_COLOR
    6 -> HEADER_H6_COLOR
    else -> Color.Unspecified
}

private fun highlightColorFor(classAttr: String): Color = when {
    classAttr.contains("blue", ignoreCase = true) -> Color(0x662196F3)
    classAttr.contains("green", ignoreCase = true) -> Color(0x664CAF50)
    classAttr.contains("pink", ignoreCase = true) -> Color(0x66E91E63)
    classAttr.contains("red", ignoreCase = true) -> Color(0x66F44336)
    classAttr.contains("orange", ignoreCase = true) -> Color(0x66FF9800)
    classAttr.contains("purple", ignoreCase = true) -> Color(0x669C27B0)
    else -> HIGHLIGHT_YELLOW
}

/** Each tab, or run of 2 leading spaces, is one nesting level. */
private fun indentLevel(leading: String): Int {
    var level = 0
    var i = 0
    while (i < leading.length) {
        if (leading[i] == '\t') { level++; i++ } else { level++; i += 2 }
    }
    return level
}

private fun leadingWhitespaceOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }

private sealed class Block {
    data class Line(val raw: String, val lineIndex: Int) : Block()
    /** [firstBodyLine] is the file line index of body[0], for writing back to a specific field line. */
    data class Code(val lang: String, val body: List<String>, val firstBodyLine: Int) : Block()
}

private fun parseBlocks(text: String): List<Block> {
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

/** Recursive inline scanner: appends styled spans to [this], recursing into
 *  matched content so nesting (e.g. bold wrapping a highlight) composes.
 *  `![[embeds]]` are handled as a fallback text placeholder here -- a
 *  standalone embed line is rendered as a real image at the block level
 *  instead (see [MarkdownLine] / [EmbedImage]). */
private fun AnnotatedString.Builder.appendMarkdownInline(raw: String) {
    var i = 0
    val n = raw.length
    while (i < n) {
        if (raw.startsWith("<mark", i)) {
            val closeTag = raw.indexOf('>', i)
            val endTag = raw.indexOf("</mark>", i)
            if (closeTag != -1 && endTag != -1 && closeTag < endTag) {
                val color = highlightColorFor(raw.substring(i, closeTag))
                withStyle(SpanStyle(background = color)) { appendMarkdownInline(raw.substring(closeTag + 1, endTag)) }
                i = endTag + "</mark>".length
                continue
            }
        }
        if (raw.startsWith("==", i)) {
            val end = raw.indexOf("==", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(background = HIGHLIGHT_YELLOW)) { appendMarkdownInline(raw.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("***", i)) {
            val end = raw.indexOf("***", i + 3)
            if (end > i + 3) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    appendMarkdownInline(raw.substring(i + 3, end))
                }
                i = end + 3
                continue
            }
        }
        if (raw.startsWith("**", i) || raw.startsWith("__", i)) {
            val delim = raw.substring(i, i + 2)
            val end = raw.indexOf(delim, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendMarkdownInline(raw.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("~~", i)) {
            val end = raw.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendMarkdownInline(raw.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (raw[i] == '`') {
            val end = raw.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CODE_BG)) {
                    append(raw.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (raw[i] == '*' || raw[i] == '_') {
            val delim = raw[i]
            val end = raw.indexOf(delim, i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendMarkdownInline(raw.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (raw.startsWith("![[", i)) {
            val end = raw.indexOf("]]", i + 3)
            if (end != -1) {
                withStyle(SpanStyle(color = MUTED, fontStyle = FontStyle.Italic)) {
                    append("🖼 " + raw.substring(i + 3, end))
                }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("[[", i)) {
            val end = raw.indexOf("]]", i + 2)
            if (end != -1) {
                val display = raw.substring(i + 2, end).substringAfterLast('|')
                withStyle(SpanStyle(color = LINK_COLOR)) { append(display) }
                i = end + 2
                continue
            }
        }
        if (raw[i] == '[') {
            val textEnd = raw.indexOf(']', i + 1)
            if (textEnd != -1 && textEnd + 1 < n && raw[textEnd + 1] == '(') {
                val urlEnd = raw.indexOf(')', textEnd + 2)
                if (urlEnd != -1) {
                    val url = raw.substring(textEnd + 2, urlEnd)
                    withLink(
                        LinkAnnotation.Url(
                            url,
                            TextLinkStyles(style = SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline))
                        )
                    ) { append(raw.substring(i + 1, textEnd)) }
                    i = urlEnd + 1
                    continue
                }
            }
        }
        append(raw[i])
        i++
    }
}

private fun parseInline(raw: String): AnnotatedString = buildAnnotatedString { appendMarkdownInline(raw) }

@Composable
fun MarkdownView(
    modifier: Modifier = Modifier,
    text: String,
    dailyUri: Uri,
    viewingDate: LocalDate,
    onToggleCheckbox: (lineIndex: Int) -> Unit,
    onShiftIndent: (lineIndex: Int, delta: Int) -> Unit,
    onMoveLine: (lineIndex: Int, delta: Int) -> Unit,
    onSetLine: (lineIndex: Int, newLine: String) -> Unit,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val totalLines = remember(text) { text.lines().size }
    // Collapsed state per header line index. Keyed on the note being viewed
    // (not on `text`) so toggling a checkbox/indent elsewhere doesn't reset
    // it -- those edits never change line count/order for existing headers.
    val collapsedHeaders = remember(viewingDate) { mutableStateMapOf<Int, Boolean>() }

    if (blocks.all { it is Block.Line && it.raw.isBlank() }) {
        Text("Nothing here yet.", modifier = modifier)
        return
    }

    // Hide every block between a collapsed header and the next header whose
    // level is <= its own (a sibling or an ancestor closing the section).
    val visibleBlocks = buildList {
        var hideLevel: Int? = null
        for (block in blocks) {
            val headerLevel = (block as? Block.Line)?.let { HEADER.matchEntire(it.raw)?.groupValues?.get(1)?.length }
            if (headerLevel != null && hideLevel != null && headerLevel <= hideLevel) hideLevel = null
            if (hideLevel == null) add(block)
            if (headerLevel != null && hideLevel == null && collapsedHeaders[(block as Block.Line).lineIndex] == true) {
                hideLevel = headerLevel
            }
        }
    }

    LazyColumn(modifier = modifier) {
        items(visibleBlocks) { block ->
            when (block) {
                is Block.Code ->
                    if (block.lang == "progressbar") ProgressBarBlock(block, onSetLine)
                    else CodeBlock(block.body)
                is Block.Line -> MarkdownLine(
                    line = block.raw,
                    lineIndex = block.lineIndex,
                    totalLines = totalLines,
                    dailyUri = dailyUri,
                    onToggleCheckbox = onToggleCheckbox,
                    onShiftIndent = onShiftIndent,
                    onMoveLine = onMoveLine,
                    isHeaderCollapsed = collapsedHeaders[block.lineIndex] == true,
                    onToggleHeaderCollapse = { idx -> collapsedHeaders[idx] = !(collapsedHeaders[idx] ?: false) }
                )
            }
        }
    }
}

@Composable
private fun MarkdownLine(
    line: String,
    lineIndex: Int,
    totalLines: Int,
    dailyUri: Uri,
    onToggleCheckbox: (Int) -> Unit,
    onShiftIndent: (Int, Int) -> Unit,
    onMoveLine: (Int, Int) -> Unit,
    isHeaderCollapsed: Boolean,
    onToggleHeaderCollapse: (Int) -> Unit,
) {
    val checkboxMatch = CHECKBOX_LINE.matchEntire(line)
    val embedMatch = EMBED_LINE.matchEntire(line.trim())
    when {
        line.isBlank() -> Spacer(Modifier.height(8.dp))

        checkboxMatch != null -> {
            val checked = checkboxMatch.groupValues[2].equals("x", ignoreCase = true)
            val indent = indentLevel(leadingWhitespaceOf(line))
            val density = LocalDensity.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (indent * 20).dp)
                    .clickable { onToggleCheckbox(lineIndex) }
                    // Swipe right/left does what the old ⇤/⇥ buttons did --
                    // indent/outdent this line. Doesn't just watch horizontal
                    // movement (that stole ordinary vertical scrolls the
                    // instant they wobbled sideways past touch-slop): once
                    // slop is crossed we check which axis actually dominates,
                    // and only commit to the swipe if horizontal clearly
                    // wins -- otherwise we bail immediately so the enclosing
                    // LazyColumn's scroll takes the gesture instead.
                    .pointerInput(lineIndex) {
                        val thresholdPx = with(density) { 56.dp.toPx() }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var accumX = 0f
                            var accumY = 0f
                            var horizontalLocked = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    if (horizontalLocked) {
                                        when {
                                            accumX > thresholdPx -> onShiftIndent(lineIndex, 1)
                                            accumX < -thresholdPx -> onShiftIndent(lineIndex, -1)
                                        }
                                    }
                                    break
                                }
                                val delta = change.positionChange()
                                accumX += delta.x
                                accumY += delta.y
                                if (!horizontalLocked) {
                                    val slop = viewConfiguration.touchSlop
                                    if (abs(accumX) > slop || abs(accumY) > slop) {
                                        if (abs(accumX) > abs(accumY) * 1.5f) {
                                            horizontalLocked = true
                                        } else {
                                            break // predominantly vertical -- let the list scroll
                                        }
                                    }
                                }
                                if (horizontalLocked) change.consume()
                            }
                        }
                    }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(checked = checked, onCheckedChange = { onToggleCheckbox(lineIndex) })
                Text(
                    parseInline(checkboxMatch.groupValues[4]),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                // Reorders this line up/down in the raw file (swap with the
                // adjacent line). Indent shifting moved to the swipe above.
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move up",
                    tint = if (lineIndex > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .clickable(enabled = lineIndex > 0) { onMoveLine(lineIndex, -1) }
                        .padding(horizontal = 4.dp)
                        .size(22.dp)
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move down",
                    tint = if (lineIndex < totalLines - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .clickable(enabled = lineIndex < totalLines - 1) { onMoveLine(lineIndex, 1) }
                        .padding(horizontal = 4.dp)
                        .size(22.dp)
                )
            }
        }

        embedMatch != null -> EmbedImage(name = embedMatch.groupValues[1], dailyUri = dailyUri)

        TAGS_LINE.matches(line.trim()) -> Text(
            line.trim(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        HR.matches(line) -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        HEADER.matchEntire(line) != null -> {
            val m = HEADER.matchEntire(line)!!
            val level = m.groupValues[1].length
            val style = when (level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            // Tapping a header folds everything under it until the next
            // header of the same-or-shallower level (see MarkdownView).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleHeaderCollapse(lineIndex) }
                    .padding(top = 12.dp, bottom = 4.dp)
            ) {
                Icon(
                    if (isHeaderCollapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isHeaderCollapsed) "Expand" else "Collapse",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp).size(20.dp)
                )
                Text(
                    parseInline(m.groupValues[3]),
                    style = style,
                    fontWeight = FontWeight.Bold,
                    color = headerColorFor(level),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        BLOCKQUOTE.matchEntire(line) != null -> {
            val m = BLOCKQUOTE.matchEntire(line)!!
            Text(
                parseInline(m.groupValues[2]),
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
            )
        }

        ORDERED.matchEntire(line) != null -> {
            val m = ORDERED.matchEntire(line)!!
            val indent = indentLevel(m.groupValues[1])
            Row(modifier = Modifier.fillMaxWidth().padding(start = (indent * 20).dp, top = 2.dp, bottom = 2.dp)) {
                Text("${m.groupValues[2]}.", modifier = Modifier.padding(end = 6.dp))
                Text(parseInline(m.groupValues[3]), style = MaterialTheme.typography.bodyLarge)
            }
        }

        UNORDERED.matchEntire(line) != null -> {
            val m = UNORDERED.matchEntire(line)!!
            val indent = indentLevel(m.groupValues[1])
            Row(modifier = Modifier.fillMaxWidth().padding(start = (indent * 20).dp, top = 2.dp, bottom = 2.dp)) {
                Text("•", modifier = Modifier.padding(end = 6.dp))
                Text(parseInline(m.groupValues[2]), style = MaterialTheme.typography.bodyLarge)
            }
        }

        else -> Text(
            parseInline(line),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

/** Attachments usually live next to the vault, not inside the picked daily
 *  folder, so this may not find anything -- falls back to a text placeholder
 *  rather than failing silently, so it's obvious the image just isn't reachable. */
@Composable
private fun EmbedImage(name: String, dailyUri: Uri) {
    val context = LocalContext.current
    // LazyColumn disposes/recomposes items that scroll out of the viewport
    // and back in, which would otherwise re-run the (slow, bounded-depth SAF
    // DFS) attachment search -- and re-fetch/redecode the image -- every
    // single time, showing a "reload" flash. Cache resolved URIs across
    // recompositions (process-lifetime, not tied to any one composition).
    val cacheKey = remember(dailyUri, name) { "$dailyUri|$name" }
    val uri by produceState<Uri?>(initialValue = attachmentUriCache[cacheKey], key1 = cacheKey) {
        if (!attachmentUriCache.containsKey(cacheKey)) {
            value = withContext(Dispatchers.IO) {
                DailyNote.findAttachment(context, dailyUri, name)?.uri
            }
            attachmentUriCache[cacheKey] = value
        }
    }
    if (uri != null) {
        AsyncImage(
            model = uri,
            contentDescription = name,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(vertical = 4.dp)
        )
    } else {
        Text(
            "🖼 $name (not found in daily folder)",
            color = MUTED,
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun CodeBlock(body: List<String>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Text(body.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Obsidian's "progressbar" plugin blocks are YAML-lite key: value pairs.
 *  Rendered as an actual progress bar; `kind: manual` with `button: true`
 *  gets +/- controls that rewrite the block's `value:` line and autosave
 *  through [onSetLine] (same path the checkbox toggle uses). */
@Composable
private fun ProgressBarBlock(block: Block.Code, onSetLine: (Int, String) -> Unit) {
    val body = block.body
    val fields = body.mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx == -1) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()

    val name = fields["name"] ?: fields["id"] ?: "progress"
    val kind = fields["kind"] ?: "manual"
    val value = fields["value"]?.toIntOrNull()
    val max = fields["max"]?.toIntOrNull()
    val interactive = kind == "manual" && fields["button"] == "true" && value != null && max != null

    val fraction: Float? = when (kind) {
        "manual" -> if (value != null && max != null && max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else null
        "day-year" -> {
            val today = LocalDate.now()
            val len = if (today.isLeapYear) 366f else 365f
            (today.dayOfYear / len).coerceIn(0f, 1f)
        }
        "day-custom" -> runCatching {
            val min = LocalDate.parse(fields["min"])
            val maxDate = LocalDate.parse(fields["max"])
            val total = ChronoUnit.DAYS.between(min, maxDate)
            if (total == 0L) null
            else (ChronoUnit.DAYS.between(min, LocalDate.now()).toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }.getOrNull()
        else -> null
    }

    fun applyDelta(delta: Int) {
        if (value == null || max == null) return
        val newValue = (value + delta).coerceIn(0, max)
        val bodyLineIdx = body.indexOfFirst { it.trim().startsWith("value:") }
        if (bodyLineIdx == -1) return
        val leading = leadingWhitespaceOf(body[bodyLineIdx])
        onSetLine(block.firstBodyLine + bodyLineIdx, "${leading}value: $newValue")
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge)
            if (kind == "manual") {
                if (interactive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "−",
                            modifier = Modifier.clickable { applyDelta(-1) }.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("$value / $max", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "+",
                            modifier = Modifier.clickable { applyDelta(1) }.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                } else {
                    Text("${value ?: "?"} / ${max ?: "?"}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                "(progress unavailable)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------
// Edit-mode syntax highlighting. A TextField can't embed real composables
// mid-text (no interactive checkboxes/progress bars here -- that's what
// reading mode is for), but it *can* be styled via VisualTransformation as
// long as the transform never changes the character count, so the cursor/
// selection still line up 1:1 with the underlying raw text. So unlike
// appendMarkdownInline (which hides delimiters like "**"), this variant
// keeps every character and just dims the markdown syntax around it.
// ---------------------------------------------------------------------

private fun AnnotatedString.Builder.appendEditableInline(raw: String) {
    var i = 0
    val n = raw.length
    while (i < n) {
        if (raw.startsWith("<mark", i)) {
            val closeTag = raw.indexOf('>', i)
            val endTag = raw.indexOf("</mark>", i)
            if (closeTag != -1 && endTag != -1 && closeTag < endTag) {
                val color = highlightColorFor(raw.substring(i, closeTag))
                withStyle(SpanStyle(color = MUTED)) { append(raw.substring(i, closeTag + 1)) }
                withStyle(SpanStyle(background = color)) { appendEditableInline(raw.substring(closeTag + 1, endTag)) }
                withStyle(SpanStyle(color = MUTED)) { append("</mark>") }
                i = endTag + "</mark>".length
                continue
            }
        }
        if (raw.startsWith("==", i)) {
            val end = raw.indexOf("==", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(color = MUTED)) { append("==") }
                withStyle(SpanStyle(background = HIGHLIGHT_YELLOW)) { appendEditableInline(raw.substring(i + 2, end)) }
                withStyle(SpanStyle(color = MUTED)) { append("==") }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("***", i)) {
            val end = raw.indexOf("***", i + 3)
            if (end > i + 3) {
                withStyle(SpanStyle(color = MUTED)) { append("***") }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    appendEditableInline(raw.substring(i + 3, end))
                }
                withStyle(SpanStyle(color = MUTED)) { append("***") }
                i = end + 3
                continue
            }
        }
        if (raw.startsWith("**", i) || raw.startsWith("__", i)) {
            val delim = raw.substring(i, i + 2)
            val end = raw.indexOf(delim, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(color = MUTED)) { append(delim) }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendEditableInline(raw.substring(i + 2, end)) }
                withStyle(SpanStyle(color = MUTED)) { append(delim) }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("~~", i)) {
            val end = raw.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(color = MUTED)) { append("~~") }
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendEditableInline(raw.substring(i + 2, end))
                }
                withStyle(SpanStyle(color = MUTED)) { append("~~") }
                i = end + 2
                continue
            }
        }
        if (raw[i] == '`') {
            val end = raw.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CODE_BG)) {
                    append(raw.substring(i, end + 1))
                }
                i = end + 1
                continue
            }
        }
        if (raw[i] == '*' || raw[i] == '_') {
            val delim = raw[i].toString()
            val end = raw.indexOf(raw[i], i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(color = MUTED)) { append(delim) }
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendEditableInline(raw.substring(i + 1, end)) }
                withStyle(SpanStyle(color = MUTED)) { append(delim) }
                i = end + 1
                continue
            }
        }
        if (raw.startsWith("![[", i)) {
            val end = raw.indexOf("]]", i + 3)
            if (end != -1) {
                withStyle(SpanStyle(color = MUTED, fontStyle = FontStyle.Italic)) { append(raw.substring(i, end + 2)) }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("[[", i)) {
            val end = raw.indexOf("]]", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(color = LINK_COLOR)) { append(raw.substring(i, end + 2)) }
                i = end + 2
                continue
            }
        }
        if (raw[i] == '[') {
            val textEnd = raw.indexOf(']', i + 1)
            if (textEnd != -1 && textEnd + 1 < n && raw[textEnd + 1] == '(') {
                val urlEnd = raw.indexOf(')', textEnd + 2)
                if (urlEnd != -1) {
                    withStyle(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)) {
                        append(raw.substring(i, textEnd + 1))
                    }
                    withStyle(SpanStyle(color = MUTED)) { append(raw.substring(textEnd + 1, urlEnd + 1)) }
                    i = urlEnd + 1
                    continue
                }
            }
        }
        append(raw[i])
        i++
    }
}

private val CHECKED_COLOR = Color(0xFF4CAF50)

/** Length-preserving styling of the whole raw note text, line by line, for
 *  [MarkdownVisualTransformation]. Every character in [raw] is re-appended
 *  exactly once (styled, never hidden/substituted) so offsets stay 1:1. */
fun highlightMarkdownForEdit(raw: String): AnnotatedString = buildAnnotatedString {
    val lines = raw.split("\n")
    var inFence = false
    lines.forEachIndexed { idx, line ->
        if (idx > 0) append("\n")
        val checkboxMatch = CHECKBOX_LINE.matchEntire(line)
        val headerMatch = HEADER.matchEntire(line)
        when {
            line.trimStart().startsWith("```") -> {
                inFence = !inFence
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = MUTED)) { append(line) }
            }
            inFence -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CODE_BG)) { append(line) }
            line.isBlank() -> append(line)
            checkboxMatch != null -> {
                // Swap just the mark character for a checkbox-shaped glyph
                // (same length: 1 char in, 1 char out) and drop the monospace
                // font so this reads like the real Checkbox in reading mode
                // instead of raw "- [x]" syntax, at the same size as the rest
                // of the line.
                val checked = checkboxMatch.groupValues[2].equals("x", ignoreCase = true)
                val markGlyph = if (checked) "☑" else "☐"
                withStyle(SpanStyle(color = if (checked) CHECKED_COLOR else MUTED, fontWeight = FontWeight.Bold)) {
                    append(checkboxMatch.groupValues[1])
                    append(markGlyph)
                    append(checkboxMatch.groupValues[3])
                }
                appendEditableInline(checkboxMatch.groupValues[4])
            }
            TAGS_LINE.matches(line.trim()) -> withStyle(SpanStyle(color = LINK_COLOR)) { append(line) }
            HR.matches(line) -> withStyle(SpanStyle(color = MUTED, letterSpacing = 2.sp)) { append(line) }
            headerMatch != null -> {
                val level = headerMatch.groupValues[1].length
                val size = when (level) {
                    1 -> 22.sp; 2 -> 20.sp; 3 -> 18.sp; else -> 17.sp
                }
                withStyle(SpanStyle(color = MUTED)) { append(headerMatch.groupValues[1] + headerMatch.groupValues[2]) }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size, color = headerColorFor(level))) {
                    appendEditableInline(headerMatch.groupValues[3])
                }
            }
            BLOCKQUOTE.matchEntire(line) != null ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = MUTED)) { appendEditableInline(line) }
            else -> appendEditableInline(line)
        }
    }
}

/** Wraps [highlightMarkdownForEdit] for use as a TextField's visualTransformation.
 *  Safe to use [OffsetMapping.Identity] because the highlighter never adds or
 *  removes characters -- only applies [SpanStyle]s. */
class MarkdownVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightMarkdownForEdit(text.text), OffsetMapping.Identity)
}
