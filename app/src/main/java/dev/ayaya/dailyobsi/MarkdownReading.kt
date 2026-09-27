package dev.ayaya.dailyobsi

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.ayaya.dailyobsi.storage.AttachmentResolver
import dev.ayaya.dailyobsi.storage.AttachmentState
import java.time.LocalDate
import kotlin.math.abs

/**
 * Reading mode: renders [parseBlocks] (MarkdownParsing.kt) as an interactive
 * LazyColumn -- real Checkbox/Icon/AsyncImage composables, not styled text.
 * See MarkdownEditing.kt for the raw-text editor counterpart this mirrors
 * the look of.
 */

/** Axis-dominant horizontal swipe-to-indent for a checkbox row. Doesn't just
 *  watch horizontal movement (that steals ordinary vertical scrolls the
 *  instant they wobble sideways past touch-slop): once slop is crossed on
 *  either axis, only commits to the swipe if horizontal clearly dominates --
 *  otherwise bails immediately without consuming, so the enclosing
 *  scrollable still gets the gesture. PointerInputScope already implements
 *  Density, so `.dp.toPx()` works directly, no LocalDensity needed. */
private suspend fun PointerInputScope.detectSwipeToIndent(lineIndex: Int, onShiftIndent: (Int, Int) -> Unit) {
    val thresholdPx = 56.dp.toPx()
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

/** Recursive inline scanner: appends styled spans to [this], recursing into
 *  matched content so nesting (e.g. bold wrapping a highlight) composes.
 *  `![[embeds]]` are handled as a fallback text placeholder here -- a
 *  standalone embed line is rendered as a real image at the block level
 *  instead (see [MarkdownLine] / [EmbedImage]). [linkColor] is threaded
 *  through (not the old hardcoded LINK_COLOR constant) so both `[[wikilinks]]`
 *  and `[text](url)` links follow the current Material You dynamic theme
 *  color instead of a fixed blue -- a real reported bug (links showing a
 *  hardcoded blue when the system/theme accent was a different hue
 *  entirely) fixed by resolving MaterialTheme.colorScheme.primary once at
 *  the composable call site (see MarkdownLine's parseInline calls) and
 *  passing it down, since this function itself isn't composable. */
private fun AnnotatedString.Builder.appendMarkdownInline(raw: String, linkColor: Color) {
    var i = 0
    val n = raw.length
    while (i < n) {
        if (raw.startsWith("<mark", i)) {
            val closeTag = raw.indexOf('>', i)
            val endTag = raw.indexOf("</mark>", i)
            if (closeTag != -1 && endTag != -1 && closeTag < endTag) {
                val color = highlightColorFor(raw.substring(i, closeTag))
                withStyle(SpanStyle(background = color)) { appendMarkdownInline(raw.substring(closeTag + 1, endTag), linkColor) }
                i = endTag + "</mark>".length
                continue
            }
        }
        if (raw.startsWith("==", i)) {
            val end = raw.indexOf("==", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(background = HIGHLIGHT_YELLOW)) { appendMarkdownInline(raw.substring(i + 2, end), linkColor) }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("***", i)) {
            val end = raw.indexOf("***", i + 3)
            if (end > i + 3) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    appendMarkdownInline(raw.substring(i + 3, end), linkColor)
                }
                i = end + 3
                continue
            }
        }
        if (raw.startsWith("**", i) || raw.startsWith("__", i)) {
            val delim = raw.substring(i, i + 2)
            val end = raw.indexOf(delim, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendMarkdownInline(raw.substring(i + 2, end), linkColor) }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("~~", i)) {
            val end = raw.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendMarkdownInline(raw.substring(i + 2, end), linkColor)
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
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendMarkdownInline(raw.substring(i + 1, end), linkColor) }
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
                withStyle(SpanStyle(color = linkColor)) { append(display) }
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
                            TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
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

private fun parseInline(raw: String, linkColor: Color): AnnotatedString = buildAnnotatedString { appendMarkdownInline(raw, linkColor) }

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
    readOnly: Boolean = false,
    onAtTopChanged: (Boolean) -> Unit = {},
) {
    val blocks = remember(text) { parseBlocks(text) }
    val embedNames = remember(blocks) {
        blocks.mapNotNull { block ->
            (block as? Block.Line)?.raw?.let(EMBED_LINE::matchEntire)
                ?.groupValues?.get(1)?.substringBefore('|')?.trim()
        }.toSet()
    }
    val context = LocalContext.current
    val attachments by produceState(
        initialValue = AttachmentResolver.snapshot(dailyUri, embedNames),
        key1 = dailyUri,
        key2 = embedNames,
    ) {
        value = AttachmentResolver.resolve(context, dailyUri, embedNames)
    }
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

    // Content deliberately draws behind the (transparent) nav bar while
    // scrolling -- but without this, scrolling all the way to the end has
    // nowhere further to go, leaving the last item sitting *underneath* the
    // nav bar rather than clear of it. contentPadding at the bottom equal to
    // the nav bar's actual height gives the list that extra room to scroll
    // into, so the true end of the note always ends up visible above the bar.
    val listState = rememberLazyListState()
    val latestOnAtTopChanged by rememberUpdatedState(onAtTopChanged)
    androidx.compose.runtime.LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }.collect(latestOnAtTopChanged)
    }
    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = navBarBottomPadding),
    ) {
        items(visibleBlocks) { block ->
            when (block) {
                is Block.Code ->
                    if (block.lang == "progressbar") {
                        ProgressBarBlock(block, if (readOnly) null else onSetLine)
                    }
                    else CodeBlock(block.body)
                is Block.Line -> MarkdownLine(
                    line = block.raw,
                    lineIndex = block.lineIndex,
                    totalLines = totalLines,
                    dailyUri = dailyUri,
                    onToggleCheckbox = onToggleCheckbox,
                    onShiftIndent = onShiftIndent,
                    onMoveLine = onMoveLine,
                    onToggleHeaderCollapse = { idx -> collapsedHeaders[idx] = !(collapsedHeaders[idx] ?: false) },
                    readOnly = readOnly,
                    attachments = attachments,
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
    onToggleHeaderCollapse: (Int) -> Unit,
    readOnly: Boolean,
    attachments: Map<String, AttachmentState>,
) {
    // Resolved once here (Material You dynamic accent, not the old hardcoded
    // LINK_COLOR blue) and threaded into every parseInline call below --
    // appendMarkdownInline itself isn't composable, so it can't read
    // MaterialTheme directly.
    val linkColor = MaterialTheme.colorScheme.primary
    val checkboxMatch = CHECKBOX_LINE.matchEntire(line)
    val embedMatch = EMBED_LINE.matchEntire(line.trim())
    when {
        line.isBlank() -> Spacer(Modifier.height(8.dp))

        checkboxMatch != null -> {
            val checked = checkboxMatch.groupValues[2].equals("x", ignoreCase = true)
            val indent = indentLevel(leadingWhitespaceOf(line))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (indent * 20).dp)
                    .clickable(enabled = !readOnly) { onToggleCheckbox(lineIndex) }
                    // Swipe right/left does what the old ⇤/⇥ buttons did --
                    // indent/outdent this line (also reused by the edit-mode
                    // checkbox overlay, see detectSwipeToIndent).
                    .then(
                        if (readOnly) Modifier
                        else Modifier.pointerInput(lineIndex) {
                            detectSwipeToIndent(lineIndex, onShiftIndent)
                        },
                    )
                    .padding(vertical = 3.dp)
            ) {
                // It's not just the checkbox glyph that made rows feel huge
                // -- it's Material3's default 48dp minimum touch target,
                // which reserves that much row height regardless of the
                // visual checkbox size. Shrinking the layout (not just
                // scaling the drawn pixels) is what let more todos fit; the
                // explicit size on top shrinks the glyph itself further.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = if (readOnly) null else {
                            { onToggleCheckbox(lineIndex) }
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    parseInline(checkboxMatch.groupValues[4], linkColor),
                    style = MaterialTheme.typography.bodyLarge,
                    // Zeroing the checkbox's touch target above also zeroed
                    // its built-in padding, so text was sitting flush against
                    // it -- add that gap back explicitly.
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                // Reorders this line up/down in the raw file (swap with the
                // adjacent line). Indent shifting moved to the swipe above.
                if (!readOnly) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Move up",
                        tint = if (lineIndex > 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier
                            .clickable(enabled = lineIndex > 0) {
                                onMoveLine(lineIndex, -1)
                            }
                            .padding(horizontal = 4.dp)
                            .size(22.dp),
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Move down",
                        tint = if (lineIndex < totalLines - 1) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        modifier = Modifier
                            .clickable(enabled = lineIndex < totalLines - 1) {
                                onMoveLine(lineIndex, 1)
                            }
                            .padding(horizontal = 4.dp)
                            .size(22.dp),
                    )
                }
            }
        }

        embedMatch != null -> {
            val name = embedMatch.groupValues[1].substringBefore('|').trim()
            EmbedImage(name, attachments[name] ?: AttachmentState.Loading)
        }

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
                Text(
                    parseInline(m.groupValues[3], linkColor),
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
                parseInline(m.groupValues[2], linkColor),
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
                Text(parseInline(m.groupValues[3], linkColor), style = MaterialTheme.typography.bodyLarge)
            }
        }

        UNORDERED.matchEntire(line) != null -> {
            val m = UNORDERED.matchEntire(line)!!
            val indent = indentLevel(m.groupValues[1])
            Row(modifier = Modifier.fillMaxWidth().padding(start = (indent * 20).dp, top = 2.dp, bottom = 2.dp)) {
                Text("•", modifier = Modifier.padding(end = 6.dp))
                Text(parseInline(m.groupValues[2], linkColor), style = MaterialTheme.typography.bodyLarge)
            }
        }

        else -> Text(
            parseInline(line, linkColor),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

/** Attachments usually live next to the vault, not inside the picked daily
 *  folder, so this may not find anything -- falls back to a text placeholder
 *  rather than failing silently, so it's obvious the image just isn't reachable. */
@Composable
private fun EmbedImage(name: String, state: AttachmentState) {
    val context = LocalContext.current
    if (state is AttachmentState.Found) {
        val request = remember(state.uri) {
            ImageRequest.Builder(context)
                .data(state.uri)
                .memoryCacheKey(state.uri.toString())
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else if (state is AttachmentState.Missing) {
        Text(
            "🖼 $name (not found in daily folder)",
            color = MUTED,
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    } else {
        Text(
            "Loading image…",
            color = MUTED,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
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
private fun ProgressBarBlock(block: Block.Code, onSetLine: ((Int, String) -> Unit)?) {
    val spec = parseProgressBar(block)
    val name = spec.name
    val kind = spec.kind
    val value = spec.value
    val max = spec.max
    val interactive = onSetLine != null && spec.interactive
    val fraction = spec.fraction()

    fun applyDelta(delta: Int) {
        val lineIndex = spec.valueLineIndex ?: return
        val line = block.body[lineIndex - block.firstBodyLine]
        val newLine = progressValueLine(line, delta, max ?: return) ?: return
        onSetLine?.invoke(lineIndex, newLine)
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
