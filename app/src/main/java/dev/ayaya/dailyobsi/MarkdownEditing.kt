@file:OptIn(ExperimentalFoundationApi::class)

package dev.ayaya.dailyobsi

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Edit mode's raw-markdown editor -- shared by main edit mode and the
 * section editor. See MarkdownParsing.kt for shared regexes/colors/data
 * model and MarkdownReading.kt for the reading-mode LazyColumn renderer
 * this mirrors the look of.
 *
 * A TextField can't embed real composables mid-text, but [MarkdownTextField]
 * gets close for checkboxes specifically: [markdownOutputTransformation]
 * hides each checkbox line's raw syntax and a real overlaid [Checkbox] is
 * drawn on top of the blank space it leaves (see [MarkdownTextField]).
 * Progress-bar +/- still can't exist inline in raw text -- that's what
 * reading mode is for.
 */

private const val LOG_TAG = "DailyObsiEdit"

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

/** Length-preserving styling of the whole raw note text, line by line. Every
 *  character in [raw] is re-appended exactly once (styled, never hidden/
 *  substituted) so offsets stay 1:1 -- its span styles get replayed onto a
 *  TextFieldBuffer by [markdownOutputTransformation]. */
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
                // of the line. (The whole span gets hidden by
                // markdownOutputTransformation anyway, for the real overlaid
                // Checkbox -- this glyph is what's underneath it either way.)
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

private val ORDERED_LIST_PREFIX = Regex("""^(\s*)(\d+)\.(\s+)""")

/** On a plain Enter keystroke (exactly one "\n" inserted, nothing replaced)
 *  whose line is a list item, continues the list on the new line -- ordered
 *  ("1. text" -> "{n+1}. ") or checkbox ("- [ ] text"/"- [x] text" -> a fresh
 *  unchecked "- [ ] ", regardless of whether the item that was split off was
 *  checked). Either way, if that item's own text was empty, strips the
 *  marker instead (Enter on a blank item exits the list rather than
 *  continuing forever). Any other edit (typing, paste, deletion, IME
 *  composition, multi-change edits) passes through untouched. Built on
 *  InputTransformation rather than diffing values by hand: `changes` gives
 *  this directly (a single collapsed original range, length-1 new range). */
private val listContinuationInputTransformation = InputTransformation {
    if (changes.changeCount != 1) return@InputTransformation
    val newRange = changes.getRange(0)
    val oldRange = changes.getOriginalRange(0)
    if (!oldRange.collapsed || newRange.length != 1) return@InputTransformation
    val full = asCharSequence()
    if (full[newRange.start] != '\n') return@InputTransformation

    val insertPos = newRange.start
    val lineStart = full.lastIndexOf('\n', insertPos - 1) + 1
    val currentLine = full.substring(lineStart, insertPos)

    val orderedMatch = ORDERED_LIST_PREFIX.find(currentLine)
    val checkboxMatch = if (orderedMatch == null) CHECKBOX_LINE.matchEntire(currentLine) else null

    val marker: String
    val contentAfterMarker: String
    when {
        orderedMatch != null -> {
            val num = orderedMatch.groupValues[2].toIntOrNull() ?: return@InputTransformation
            marker = "${orderedMatch.groupValues[1]}${num + 1}. "
            contentAfterMarker = currentLine.substring(orderedMatch.range.last + 1)
        }
        checkboxMatch != null -> {
            marker = "${checkboxMatch.groupValues[1]} ${checkboxMatch.groupValues[3]}"
            contentAfterMarker = checkboxMatch.groupValues[4]
        }
        else -> return@InputTransformation
    }

    if (contentAfterMarker.isBlank()) {
        delete(lineStart, insertPos)
        placeCursorAfterCharAt(lineStart)
    } else {
        insert(insertPos + 1, marker)
        placeCursorAfterCharAt(insertPos + marker.length)
    }
}

/** One checkbox line's position, for [MarkdownTextField]'s overlay: [hideStart]/[hideEnd]
 *  is the raw-text-offset span of its "- [ ]"/"- [x]" syntax (hidden, not deleted --
 *  see [markdownOutputTransformation]), [anchorOffset] is where that span starts
 *  *after* leading indentation, i.e. where the overlaid Checkbox actually gets
 *  placed (matching reading mode's checkbox position after the indent padding). */
private data class CheckboxOverlaySpec(
    val lineIndex: Int,
    val hideStart: Int,
    val hideEnd: Int,
    val anchorOffset: Int,
    val checked: Boolean,
)

private fun checkboxOverlaySpecs(text: String): List<CheckboxOverlaySpec> {
    val specs = mutableListOf<CheckboxOverlaySpec>()
    var pos = 0
    text.split("\n").forEachIndexed { idx, line ->
        val m = CHECKBOX_LINE.matchEntire(line)
        if (m != null) {
            specs.add(
                CheckboxOverlaySpec(
                    lineIndex = idx,
                    hideStart = pos,
                    hideEnd = pos + m.groupValues[1].length + 1 + m.groupValues[3].length,
                    anchorOffset = pos + leadingWhitespaceOf(line).length,
                    checked = m.groupValues[2].equals("x", ignoreCase = true)
                )
            )
        }
        pos += line.length + 1
    }
    return specs
}

/** What's actually displayed for the raw text: replays [highlightMarkdownForEdit]'s
 *  span styles (still valid 1:1 since it never changes length), then hides every
 *  checkbox line's "- [ ]"/"- [x]" syntax (transparent, not deleted, so it still
 *  reserves layout space) -- [MarkdownTextField] overlays a real Checkbox exactly
 *  there instead, for a pixel-accurate match with reading mode. */
private val markdownOutputTransformation = OutputTransformation {
    val raw = asCharSequence().toString()
    highlightMarkdownForEdit(raw).spanStyles.forEach { addStyle(it.item, it.start, it.end) }
    for (spec in checkboxOverlaySpecs(raw)) {
        addStyle(SpanStyle(color = Color.Transparent), spec.hideStart, spec.hideEnd)
    }
}

/** Shared raw-markdown editor for both main edit mode and the section editor.
 *  Built on the newer TextFieldState-based BasicTextField
 *  (androidx.compose.foundation.text.input) specifically for two things a
 *  classic VisualTransformation can't do: hide checkbox syntax behind a real
 *  overlaid [Checkbox] (matching reading mode pixel-for-pixel, via
 *  [markdownOutputTransformation]) and keep that overlay correctly positioned
 *  while scrolling, via a ScrollState hoisted here and shared between the
 *  text field and the overlay -- the same `scrollState.value` that moves the
 *  text also shifts the overlay, instead of guessing at an internal scroll
 *  offset the classic API never exposed.
 *
 *  [onShiftIndent]/[onMoveLine] are optional: pass them to also get a
 *  floating ⇤⇥▲▼ bubble that acts on whichever line the cursor is currently
 *  on (see the bottom of this function for why it's one fixed bubble and not
 *  per-row controls). This composable has no idea what [value] represents in
 *  the wider app, so it always reports line indices local to [value] itself
 *  -- main edit mode passes callbacks operating on the full file (its
 *  `value` *is* the full file), the section editor passes callbacks
 *  operating on its own draft string instead (see `SectionEditorScreen`),
 *  not the file directly, since the file won't reflect the draft until Save. */
@Composable
fun MarkdownTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle,
    cursorBrush: Brush,
    onShiftIndent: ((lineIndex: Int, delta: Int) -> Unit)? = null,
    onMoveLine: ((lineIndex: Int, delta: Int) -> Unit)? = null,
) {
    val state = rememberTextFieldState(initialText = value)

    // Resync when `value` changes for a reason other than this field's own
    // edit (switching notes/sections, or -- importantly -- the ⇤⇥▲▼ bubble
    // below, which mutates `value` via callbacks that go around this field's
    // own onValueChange entirely). Our own typing already round-trips back
    // to an equal `value` on the next recomposition, so this only actually
    // fires for those external changes.
    //
    // BUG FIXED HERE: this used to call setTextAndPlaceCursorAtEnd(value)
    // unconditionally, which -- since every ⇤⇥▲▼ tap changes `value` this
    // way -- slammed the cursor (and the view, which follows it) to the very
    // end of the document after every single bubble tap. Preserving the
    // prior cursor *offset* (clamped to the new length) instead keeps the
    // view roughly where the user was looking.
    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            val preservedOffset = state.selection.start.coerceIn(0, value.length)
            Log.d(LOG_TAG, "external value change (len ${state.text.length} -> ${value.length}), preserving cursor offset $preservedOffset")
            state.edit {
                replace(0, length, value)
                placeCursorBeforeCharAt(preservedOffset)
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { onValueChange(it) }
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val checkboxSizePx = with(density) { 18.dp.roundToPx() }

    Box(modifier = modifier) {
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxSize(),
            keyboardOptions = keyboardOptions,
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            scrollState = scrollState,
            onTextLayout = { layoutResult = it() },
            inputTransformation = listContinuationInputTransformation,
            outputTransformation = markdownOutputTransformation,
        )

        val lr = layoutResult
        val textLen = lr?.layoutInput?.text?.length ?: 0
        if (lr != null && textLen > 0) {
            for (spec in checkboxOverlaySpecs(state.text.toString())) {
                val anchor = spec.anchorOffset.coerceIn(0, textLen - 1)
                val box = lr.getBoundingBox(anchor)
                val centerY = (box.top.roundToInt() + box.bottom.roundToInt()) / 2

                Box(
                    modifier = Modifier.offset {
                        IntOffset(box.left.roundToInt(), centerY - checkboxSizePx / 2 - scrollState.value)
                    }
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Checkbox(checked = spec.checked, onCheckedChange = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // One floating bubble, not one per line -- acts on whichever line
        // the cursor currently sits on rather than needing to be positioned
        // next to a specific row. Per-row buttons and a swipe gesture were
        // both tried and both ate taps meant for placing a cursor in the
        // todo text right next to them (unlike reading mode's row, plain
        // static Text with nothing underneath to conflict with, this row's
        // text *is* the real editable field). One fixed bubble sidesteps
        // that entirely. Kept small/compact deliberately -- 16dp icons in a
        // tight cluster, not full 48dp Material touch targets, so it reads
        // as a minor utility, not a dominant floating action button.
        if (onShiftIndent != null || onMoveLine != null) {
            val cursorLineIndex = remember(state.text, state.selection) {
                var count = 0
                val pos = state.selection.start.coerceIn(0, state.text.length)
                for (i in 0 until pos) if (state.text[i] == '\n') count++
                count
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 4.dp,
                modifier = Modifier.align(Alignment.BottomEnd).imePadding().padding(8.dp)
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onShiftIndent != null) {
                            IconButton(onClick = {
                                Log.d(LOG_TAG, "bubble: outdent line $cursorLineIndex")
                                onShiftIndent(cursorLineIndex, -1)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Outdent", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                Log.d(LOG_TAG, "bubble: indent line $cursorLineIndex")
                                onShiftIndent(cursorLineIndex, 1)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Indent", modifier = Modifier.size(18.dp))
                            }
                        }
                        if (onMoveLine != null) {
                            IconButton(onClick = {
                                Log.d(LOG_TAG, "bubble: move up line $cursorLineIndex")
                                onMoveLine(cursorLineIndex, -1)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                Log.d(LOG_TAG, "bubble: move down line $cursorLineIndex")
                                onMoveLine(cursorLineIndex, 1)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
