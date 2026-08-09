package dev.ayaya.dailyobsi.widget

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.GlanceId
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.R
import dev.ayaya.dailyobsi.BLOCKQUOTE
import dev.ayaya.dailyobsi.Block
import dev.ayaya.dailyobsi.CHECKBOX_LINE
import dev.ayaya.dailyobsi.CODE_BG
import dev.ayaya.dailyobsi.DailyNote
import dev.ayaya.dailyobsi.EMBED_LINE
import dev.ayaya.dailyobsi.HEADER
import dev.ayaya.dailyobsi.HR
import dev.ayaya.dailyobsi.HIGHLIGHT_YELLOW
import dev.ayaya.dailyobsi.MUTED
import dev.ayaya.dailyobsi.ORDERED
import dev.ayaya.dailyobsi.TAGS_LINE
import dev.ayaya.dailyobsi.UNORDERED
import dev.ayaya.dailyobsi.VaultPrefs
import dev.ayaya.dailyobsi.headerColorFor
import dev.ayaya.dailyobsi.highlightColorFor
import dev.ayaya.dailyobsi.indentLevel
import dev.ayaya.dailyobsi.leadingWhitespaceOf
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Widget 2/3's Glance-native renderer -- a deliberate second, parallel
 * implementation of MarkdownReading.kt's MarkdownView/MarkdownLine, not a
 * reuse of it (RemoteViews/AppWidgetHost sandboxing means none of the real
 * Compose Material3/Foundation composables MarkdownView uses are usable
 * inside a widget -- see CLAUDE.md's "Planned: three-widget system" for the
 * full platform-ceiling writeup). Covers the same block types as reading
 * mode (checkboxes, headers, lists, blockquotes, HR, embeds, progress bars)
 * with real tap targets on checkboxes and progress +/-, at the cost of
 * pixel fidelity -- that trade (Option B) was the explicit, confirmed
 * decision over a pixel-perfect but non-interactive bitmap render.
 */

private val LINE_INDEX_KEY = ActionParameters.Key<Int>("glance_md_line_index")
private val DELTA_KEY = ActionParameters.Key<Int>("glance_md_delta")
private val MAX_KEY = ActionParameters.Key<Int>("glance_md_max")

/** One styled run of inline text -- the flat-list counterpart to
 *  MarkdownReading.kt's AnnotatedString-building appendMarkdownInline.
 *  Glance's RemoteViews-backed Text has no span support, so rich inline
 *  text (bold+highlight+link mixed in one line) is laid out as a Row of
 *  adjacent Text composables instead, one per run -- see [GlanceInlineText].
 *  Genuinely lost versus the in-app renderer: a style spanning a mid-word
 *  line-wrap boundary (edge case, not the normal shape of a checklist note).
 */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val highlightColor: Color? = null,
    val link: Boolean = false,
)

/** Mirrors appendMarkdownInline's scanning logic line-for-line (deliberate
 *  near-duplicate, not a shared refactor -- zero risk to the proven in-app
 *  renderer) but emits a flat list of runs instead of AnnotatedString spans. */
fun parseInlineSegmentsForGlance(raw: String): List<InlineRun> {
    val out = mutableListOf<InlineRun>()
    scanInlineForGlance(raw, bold = false, italic = false, strike = false, out = out)
    return out
}

private fun scanInlineForGlance(s: String, bold: Boolean, italic: Boolean, strike: Boolean, out: MutableList<InlineRun>) {
    var i = 0
    val n = s.length
    var runStart = 0
    fun flushPlain(end: Int) {
        if (end > runStart) out.add(InlineRun(s.substring(runStart, end), bold, italic, strike))
    }
    while (i < n) {
        if (s.startsWith("<mark", i)) {
            val closeTag = s.indexOf('>', i)
            val endTag = s.indexOf("</mark>", i)
            if (closeTag != -1 && endTag != -1 && closeTag < endTag) {
                flushPlain(i)
                out.add(InlineRun(s.substring(closeTag + 1, endTag), bold, italic, strike, highlightColor = highlightColorFor(s.substring(i, closeTag))))
                i = endTag + "</mark>".length; runStart = i
                continue
            }
        }
        if (s.startsWith("==", i)) {
            val end = s.indexOf("==", i + 2)
            if (end > i + 2) {
                flushPlain(i)
                out.add(InlineRun(s.substring(i + 2, end), bold, italic, strike, highlightColor = HIGHLIGHT_YELLOW))
                i = end + 2; runStart = i
                continue
            }
        }
        if (s.startsWith("***", i)) {
            val end = s.indexOf("***", i + 3)
            if (end > i + 3) {
                flushPlain(i)
                scanInlineForGlance(s.substring(i + 3, end), true, true, strike, out)
                i = end + 3; runStart = i
                continue
            }
        }
        if (s.startsWith("**", i) || s.startsWith("__", i)) {
            val delim = s.substring(i, i + 2)
            val end = s.indexOf(delim, i + 2)
            if (end > i + 2) {
                flushPlain(i)
                scanInlineForGlance(s.substring(i + 2, end), true, italic, strike, out)
                i = end + 2; runStart = i
                continue
            }
        }
        if (s.startsWith("~~", i)) {
            val end = s.indexOf("~~", i + 2)
            if (end > i + 2) {
                flushPlain(i)
                scanInlineForGlance(s.substring(i + 2, end), bold, italic, true, out)
                i = end + 2; runStart = i
                continue
            }
        }
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                flushPlain(i)
                out.add(InlineRun(s.substring(i + 1, end), bold, italic, strike, code = true))
                i = end + 1; runStart = i
                continue
            }
        }
        if (s[i] == '*' || s[i] == '_') {
            val delim = s[i]
            val end = s.indexOf(delim, i + 1)
            if (end > i + 1) {
                flushPlain(i)
                scanInlineForGlance(s.substring(i + 1, end), bold, true, strike, out)
                i = end + 1; runStart = i
                continue
            }
        }
        if (s.startsWith("![[", i)) {
            val end = s.indexOf("]]", i + 3)
            if (end != -1) {
                flushPlain(i)
                out.add(InlineRun("🖼 " + s.substring(i + 3, end), bold, italic, strike))
                i = end + 2; runStart = i
                continue
            }
        }
        if (s.startsWith("[[", i)) {
            val end = s.indexOf("]]", i + 2)
            if (end != -1) {
                val display = s.substring(i + 2, end).substringAfterLast('|')
                flushPlain(i)
                out.add(InlineRun(display, bold, italic, strike, link = true))
                i = end + 2; runStart = i
                continue
            }
        }
        if (s[i] == '[') {
            val textEnd = s.indexOf(']', i + 1)
            if (textEnd != -1 && textEnd + 1 < n && s[textEnd + 1] == '(') {
                val urlEnd = s.indexOf(')', textEnd + 2)
                if (urlEnd != -1) {
                    flushPlain(i)
                    out.add(InlineRun(s.substring(i + 1, textEnd), bold, italic, strike, link = true))
                    i = urlEnd + 1; runStart = i
                    continue
                }
            }
        }
        i++
    }
    flushPlain(n)
}

/** Segmented-Row-of-Texts: one Text per styled run, laid out adjacently.
 *  [forceItalic]/[forceStrike] apply on top of each run's own style (used by
 *  blockquotes and checked-checkbox lines, which italicize/strike the whole
 *  line regardless of its own inline styling). */
@Composable
fun GlanceInlineText(
    runs: List<InlineRun>,
    modifier: GlanceModifier = GlanceModifier,
    forceItalic: Boolean = false,
    forceStrike: Boolean = false,
) {
    // RemoteViews hard-caps a flat container at 10 direct children --
    // confirmed by a real crash from TodoWidget's old plain-Column checkbox
    // list ("Column container cannot have more than 10 elements") on an
    // actual daily note. A single markdown line with many inline style
    // transitions could hit the same cap here; past it, the remaining runs
    // collapse into one final plain-text run (styling lost for that tail,
    // but nothing crashes) rather than risking it -- more than 9 style
    // switches within one line isn't a shape any real note in this vault
    // actually takes.
    val visibleRuns = if (runs.size <= 10) runs else
        runs.take(9) + InlineRun(runs.drop(9).joinToString("") { it.text })
    Row(modifier = modifier) {
        for (run in visibleRuns) {
            if (run.text.isEmpty()) continue
            val decoration = if (run.strikethrough || forceStrike) TextDecoration.LineThrough
                else if (run.link) TextDecoration.Underline
                else TextDecoration.None
            Text(
                run.text,
                style = TextStyle(
                    // Dynamic theme accent, not the old hardcoded LINK_COLOR
                    // blue -- same fix as MarkdownReading.kt's appendMarkdownInline,
                    // here trivial since GlanceTheme.colors is already
                    // dynamic-color-backed (see EditShortcutWidget's
                    // ColorProviders(light, dark) doc comment).
                    color = if (run.link) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface,
                    fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (run.italic || forceItalic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = decoration,
                ),
                modifier = when {
                    run.highlightColor != null -> GlanceModifier.background(ColorProvider(run.highlightColor))
                    run.code -> GlanceModifier.background(ColorProvider(CODE_BG))
                    else -> GlanceModifier
                }
            )
        }
    }
}

/** Renders a pre-parsed block list (see MarkdownParsing.kt's parseBlocks) as
 *  a plain (non-scrolling -- widgets have no LazyColumn-in-a-widget for
 *  arbitrary content the way the in-app LazyColumn does) Column. Callers
 *  (HeadingWidget, ReadingViewWidget) are responsible for slicing [blocks]
 *  down to whatever range they want shown (a header's body, or the whole
 *  file) and for resolving [embedImages] ahead of time via
 *  [resolveEmbedImagesForGlance] -- Glance composables aren't suspend, so
 *  the SAF attachment search/bitmap decode has to happen in provideGlance,
 *  before provideContent, not reactively like EmbedImage's Coil AsyncImage. */
@Composable
fun GlanceMarkdownBlocks(blocks: List<Block>, embedImages: Map<String, ImageProvider>) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is Block.Code -> if (block.lang == "progressbar") GlanceProgressBar(block) else GlanceCodeBlock(block.body)
                is Block.Line -> GlanceMarkdownLine(block.raw, block.lineIndex, embedImages)
            }
        }
    }
}

@Composable
private fun GlanceMarkdownLine(line: String, lineIndex: Int, embedImages: Map<String, ImageProvider>) {
    val checkboxMatch = CHECKBOX_LINE.matchEntire(line)
    val embedMatch = EMBED_LINE.matchEntire(line.trim())
    val headerMatch = HEADER.matchEntire(line)
    val blockquoteMatch = BLOCKQUOTE.matchEntire(line)
    val orderedMatch = ORDERED.matchEntire(line)
    val unorderedMatch = UNORDERED.matchEntire(line)

    when {
        line.isBlank() -> Spacer(GlanceModifier.height(6.dp))

        checkboxMatch != null -> {
            val checked = checkboxMatch.groupValues[2].equals("x", ignoreCase = true)
            val indent = indentLevel(leadingWhitespaceOf(line))
            android.util.Log.d("DailyObsiWidget", "GlanceMarkdownLine render: checkbox lineIndex=$lineIndex checked=$checked text=\"${checkboxMatch.groupValues[4]}\"")
            val toggleAction = actionRunCallback<GlanceCheckboxToggleAction>(actionParametersOf(LINE_INDEX_KEY to lineIndex))
            Row(
                verticalAlignment = Alignment.Vertical.CenterVertically,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(start = (indent * 14).dp, top = 3.dp, bottom = 3.dp)
                    // Whole-row tap target -- the ONLY click handler for
                    // this row, deliberately. Tried giving the CheckBox its
                    // own onCheckedChange too (matching in-app
                    // MarkdownReading.kt's Checkbox+Row pattern), on the
                    // theory that the native compound button's view bounds
                    // were eating touches before they reached the Row.
                    // Wrong, and worse than the original bug: confirmed via
                    // logcat that a single tap on/near the checkbox fires
                    // BOTH handlers -- one FIRED event with no extras (the
                    // Row's clickable), a second ~700ms later carrying
                    // android.widget.extra.CHECKED (the CheckBox's own
                    // native click semantics) -- toggling the line, then
                    // immediately toggling it back, which is exactly "fires
                    // once in a blue moon" from the user's side. Worse: the
                    // two onAction calls each independently re-read/re-write
                    // the whole file with no coordination between them, a
                    // genuine race that can lose one of the two writes --
                    // the likely cause of a separately reported "tick it and
                    // the line disappears" symptom. Single handler only.
                    .clickable(toggleAction)
            ) {
                // NOT androidx.glance.appwidget.CheckBox anymore -- reverted
                // after a real, confirmed bug found by directly tapping the
                // checkbox glyph via adb and watching logcat: NOTHING fired
                // at all, ever, no matter how it was tapped. Root cause,
                // confirmed by reading Glance's translator source
                // (CheckBoxTranslator.kt) and the platform's own
                // CompoundButton: with onCheckedChange = null, Glance
                // attaches no click pending intent to the underlying native
                // view at all -- but that view is still a real
                // android.widget.CompoundButton, which is clickable=true by
                // platform default regardless of whether anything is
                // listening, and CompoundButton.performClick() unconditionally
                // calls toggle() on itself before anything else runs. So a
                // tap on the glyph flips the view's own ephemeral visual
                // state (immediately overwritten on the next recompose) and
                // consumes the touch, silently swallowing it before it can
                // reach the Row's clickable underneath/around it -- exactly
                // "clicking the checkbox doesn't update anything" while
                // "clicking the line" (outside the glyph's bounds, where
                // there's no competing clickable view) works. A plain
                // Image has no such built-in click-to-toggle behavior and
                // adds no click listener of its own (colorFilter/tint only),
                // so it's purely decorative and lets every tap -- glyph
                // included -- fall through to the Row's single handler.
                // Vector assets: ic_checkbox_checked/unchecked.xml (standard
                // Material check_box / check_box_outline_blank glyphs,
                // single-color so ColorFilter.tint recolors them correctly).
                Image(
                    provider = ImageProvider(if (checked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (checked) GlanceTheme.colors.primary else GlanceTheme.colors.outline),
                    modifier = GlanceModifier.size(20.dp).padding(end = 4.dp)
                )
                GlanceInlineText(parseInlineSegmentsForGlance(checkboxMatch.groupValues[4]), forceStrike = checked)
            }
        }

        embedMatch != null -> {
            val provider = embedImages[embedMatch.groupValues[1]]
            if (provider != null) {
                // Manual aspect-ratio-matched box sizing (LocalSize.current
                // / aspect ratio math) was tried and didn't actually work --
                // still rendered small and centered. Root cause found by
                // reading Glance's real translation code
                // (ImageTranslator.kt): `adjustViewBounds` -- the real
                // native ImageView mechanism for "auto-size to the image's
                // own aspect ratio" -- is only enabled by Glance when
                // contentScale == Fit AND at least one dimension's modifier
                // is Wrap. A fixed `.height(x)` (Fixed, not Wrap) meant it
                // was never enabled at all, so the ImageView just did plain
                // fit-inside-a-fixed-box regardless of how carefully that
                // box was computed. `.wrapContentHeight()` here (Wrap, not
                // Fixed) is what actually turns adjustViewBounds on, letting
                // the real native view size itself from the real decoded
                // image -- no manual math needed.
                Image(
                    provider = provider,
                    contentDescription = embedMatch.groupValues[1],
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.fillMaxWidth().wrapContentHeight().padding(vertical = 4.dp)
                )
            } else {
                Text(
                    "🖼 ${embedMatch.groupValues[1]} (not found)",
                    style = TextStyle(color = ColorProvider(MUTED), fontStyle = FontStyle.Italic, fontSize = 12.sp)
                )
            }
        }

        headerMatch != null -> {
            val level = headerMatch.groupValues[1].length
            val color = headerColorFor(level)
            Text(
                headerMatch.groupValues[3],
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = when (level) { 1 -> 17.sp; 2 -> 16.sp; 3 -> 15.sp; else -> 14.sp },
                    color = if (color != Color.Unspecified) ColorProvider(color) else GlanceTheme.colors.onSurface
                ),
                modifier = GlanceModifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        blockquoteMatch != null -> GlanceInlineText(
            parseInlineSegmentsForGlance(blockquoteMatch.groupValues[2]),
            forceItalic = true,
            modifier = GlanceModifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp)
        )

        HR.matches(line) -> Box_(GlanceModifier.fillMaxWidth().height(1.dp).padding(vertical = 6.dp).background(ColorProvider(MUTED)))

        orderedMatch != null -> {
            val indent = indentLevel(orderedMatch.groupValues[1])
            Row(modifier = GlanceModifier.fillMaxWidth().padding(start = (indent * 14).dp, top = 2.dp, bottom = 2.dp)) {
                Text("${orderedMatch.groupValues[2]}.", modifier = GlanceModifier.padding(end = 6.dp))
                GlanceInlineText(parseInlineSegmentsForGlance(orderedMatch.groupValues[3]))
            }
        }

        unorderedMatch != null -> {
            val indent = indentLevel(unorderedMatch.groupValues[1])
            Row(modifier = GlanceModifier.fillMaxWidth().padding(start = (indent * 14).dp, top = 2.dp, bottom = 2.dp)) {
                Text("•", modifier = GlanceModifier.padding(end = 6.dp))
                GlanceInlineText(parseInlineSegmentsForGlance(unorderedMatch.groupValues[2]))
            }
        }

        TAGS_LINE.matches(line.trim()) -> Text(
            line.trim(),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp)
        )

        else -> GlanceInlineText(parseInlineSegmentsForGlance(line), modifier = GlanceModifier.padding(top = 1.dp, bottom = 1.dp))
    }
}

/** Glance has no bare Box-with-just-a-background primitive that isn't also
 *  a layout container -- a zero-content Column with a background modifier
 *  is the standard way to draw a plain rule/divider. */
@Composable
private fun Box_(modifier: GlanceModifier) {
    Column(modifier = modifier) {}
}

@Composable
private fun GlanceCodeBlock(body: List<String>) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .padding(8.dp)
    ) {
        Text(body.joinToString("\n"), style = TextStyle(fontSize = 12.sp))
    }
}

@Composable
private fun GlanceProgressBar(block: Block.Code) {
    val body = block.body
    val fields = body.mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx == -1) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()

    val name = fields["name"] ?: fields["id"] ?: "progress"
    val kind = fields["kind"] ?: "manual"
    val value = fields["value"]?.toIntOrNull()
    val max = fields["max"]?.toIntOrNull()
    val valueLineIdx = body.indexOfFirst { it.trim().startsWith("value:") }
        .takeIf { it != -1 }?.let { block.firstBodyLine + it }
    val interactive = kind == "manual" && fields["button"] == "true" && value != null && max != null && valueLineIdx != null

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
            if (total == 0L) null else (ChronoUnit.DAYS.between(min, LocalDate.now()).toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }.getOrNull()
        else -> null
    }

    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text(name, style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp), modifier = GlanceModifier.defaultWeight())
            if (kind == "manual") {
                if (interactive) {
                    Text(
                        "−",
                        style = TextStyle(fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier
                            .clickable(actionRunCallback<GlanceProgressDeltaAction>(actionParametersOf(LINE_INDEX_KEY to valueLineIdx!!, DELTA_KEY to -1, MAX_KEY to max!!)))
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                    Text("$value / $max", style = TextStyle(fontSize = 12.sp))
                    Text(
                        "+",
                        style = TextStyle(fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier
                            .clickable(actionRunCallback<GlanceProgressDeltaAction>(actionParametersOf(LINE_INDEX_KEY to valueLineIdx!!, DELTA_KEY to 1, MAX_KEY to max!!)))
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                } else {
                    Text("${value ?: "?"} / ${max ?: "?"}", style = TextStyle(fontSize = 12.sp))
                }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = fraction,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
        } else {
            Text("(progress unavailable)", style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
    }
}

/** Bitmap-decodes every `![[embed]]` referenced in [blocks], on IO, ahead of
 *  render -- Glance composables aren't suspend, so this can't happen lazily
 *  inside GlanceMarkdownBlocks the way EmbedImage's Coil AsyncImage does in
 *  the in-app renderer. Same "usually not found" caveat as findAttachment:
 *  attachments typically live next to the vault root, outside the picked
 *  daily folder, so a miss here is expected and handled (a text fallback),
 *  not an error. */
suspend fun resolveEmbedImagesForGlance(context: Context, dailyUri: Uri, blocks: List<Block>): Map<String, ImageProvider> {
    val names = blocks
        .filterIsInstance<Block.Line>()
        .mapNotNull { EMBED_LINE.matchEntire(it.raw.trim())?.groupValues?.get(1) }
        .distinct()
    if (names.isEmpty()) return emptyMap()
    return withContext(Dispatchers.IO) {
        buildMap {
            for (name in names) {
                val file = DailyNote.findAttachment(context, dailyUri, name) ?: continue
                val bitmap = runCatching {
                    decodeSampledBitmap(context, file.uri, maxDimensionPx = 600)
                }.getOrNull() ?: continue
                put(name, ImageProvider(bitmap))
            }
        }
    }
}

/** Real, currently-reproducing crash fixed here: `BitmapFactory.decodeStream`
 *  with no options decodes at the file's native resolution -- a normal phone
 *  photo (e.g. 4000x3000) comes out to ~48MB as ARGB_8888, and RemoteViews
 *  has a hard per-update bitmap memory budget (~15MB total, confirmed via a
 *  real crash: "RemoteViews for widget update exceeds maximum bitmap memory
 *  usage", used ~46MB / max ~15.5MB). It only actually threw after a few
 *  resizes because `SizeMode.Exact` recomposes (and thus re-decodes) on
 *  every size change, and each attempt added to the same update's budget.
 *  Standard two-pass downsample: decode bounds only first (`inJustDecodeBounds`,
 *  no pixel allocation) to compute a power-of-two `inSampleSize`, then decode
 *  for real at that reduced size -- these widgets never display an embed
 *  larger than ~140dp tall, so anything past a few hundred px is wasted
 *  memory the RemoteViews budget can't afford regardless. */
private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimensionPx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val (width, height) = bounds.outWidth to bounds.outHeight
    if (width <= 0 || height <= 0) return null

    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxDimensionPx || height / (sampleSize * 2) >= maxDimensionPx) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
}

/** Toggles a checkbox line in today's note straight from a widget tap --
 *  shared by HeadingWidget and ReadingViewWidget (both render editable
 *  checkbox lines via GlanceMarkdownBlocks). Re-reads/writes the file fresh
 *  rather than trusting whatever was on screen at render time, same
 *  reasoning as ToggleTodoAction in TodoWidget.kt. */
class GlanceCheckboxToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tag = "DailyObsiWidget"
        android.util.Log.d(tag, "GlanceCheckboxToggleAction.onAction: FIRED glanceId=$glanceId parameters=$parameters")
        val lineIndex = parameters[LINE_INDEX_KEY]
        if (lineIndex == null) {
            android.util.Log.w(tag, "GlanceCheckboxToggleAction: no LINE_INDEX_KEY in parameters, bailing")
            return
        }
        val treeUri = VaultPrefs.getTreeUri(context)
        if (treeUri == null) {
            android.util.Log.w(tag, "GlanceCheckboxToggleAction: no treeUri (daily folder not picked), bailing")
            return
        }
        val file = DailyNote.findTodayFile(context, treeUri)
        if (file == null) {
            android.util.Log.w(tag, "GlanceCheckboxToggleAction: no today's file found, bailing")
            return
        }
        // See WidgetKeys.kt's noteWriteMutex doc comment -- write only,
        // stays fast, no artificial delay (that was tried and made rapid
        // tapping feel completely dead, a real regression). The refresh
        // side is debounced separately -- see requestWidgetRefresh's doc
        // comment for why a raw refreshAllWidgets() call per tap was the
        // actual bug.
        noteWriteMutex.withLock {
            val text = DailyNote.readText(context, file.uri)
            val line = text.lines().getOrNull(lineIndex)
            android.util.Log.d(tag, "GlanceCheckboxToggleAction: lineIndex=$lineIndex before=\"$line\"")
            val newText = DailyNote.toggleCheckbox(text, lineIndex)
            val newLine = newText.lines().getOrNull(lineIndex)
            android.util.Log.d(tag, "GlanceCheckboxToggleAction: lineIndex=$lineIndex after=\"$newLine\" (unchanged=${line == newLine})")
            DailyNote.writeText(context, file.uri, newText)
        }
        requestWidgetRefresh(context)
        android.util.Log.d(tag, "GlanceCheckboxToggleAction: write done, refresh requested")
    }
}

/** Applies a +/-1 delta to a progress-bar block's `value:` line. [MAX_KEY]
 *  is threaded through from the render that produced the tapped button
 *  (rather than re-parsed here from the block's fence boundaries) since the
 *  value line index alone is enough to locate and rewrite the right line,
 *  and the max at render time is still correct -- this file has exactly one
 *  writer, so nothing else can have changed the block's shape between the
 *  render and the tap. */
class GlanceProgressDeltaAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tag = "DailyObsiWidget"
        android.util.Log.d(tag, "GlanceProgressDeltaAction.onAction: FIRED glanceId=$glanceId parameters=$parameters")
        val lineIndex = parameters[LINE_INDEX_KEY]
        val delta = parameters[DELTA_KEY]
        val max = parameters[MAX_KEY]
        if (lineIndex == null || delta == null || max == null) {
            android.util.Log.w(tag, "GlanceProgressDeltaAction: missing parameter(s), bailing (lineIndex=$lineIndex delta=$delta max=$max)")
            return
        }
        val treeUri = VaultPrefs.getTreeUri(context)
        if (treeUri == null) {
            android.util.Log.w(tag, "GlanceProgressDeltaAction: no treeUri, bailing")
            return
        }
        val file = DailyNote.findTodayFile(context, treeUri)
        if (file == null) {
            android.util.Log.w(tag, "GlanceProgressDeltaAction: no today's file found, bailing")
            return
        }
        // See WidgetKeys.kt's noteWriteMutex doc comment.
        noteWriteMutex.withLock {
            val text = DailyNote.readText(context, file.uri)
            val line = text.lines().getOrNull(lineIndex)
            android.util.Log.d(tag, "GlanceProgressDeltaAction: lineIndex=$lineIndex before=\"$line\"")
            if (line == null) {
                android.util.Log.w(tag, "GlanceProgressDeltaAction: lineIndex $lineIndex out of range (file has ${text.lines().size} lines), bailing")
                return@withLock
            }
            val value = line.substringAfter("value:", "").trim().toIntOrNull()
            if (value == null) {
                android.util.Log.w(tag, "GlanceProgressDeltaAction: couldn't parse an int value out of \"$line\", bailing")
                return@withLock
            }
            val newValue = (value + delta).coerceIn(0, max)
            val leading = leadingWhitespaceOf(line)
            val newLine = "${leading}value: $newValue"
            android.util.Log.d(tag, "GlanceProgressDeltaAction: lineIndex=$lineIndex after=\"$newLine\"")
            DailyNote.writeText(context, file.uri, DailyNote.replaceLine(text, lineIndex, newLine))
        }
        // Debounced, not a raw refreshAllWidgets() call -- see
        // requestWidgetRefresh's doc comment. This is exactly the action the
        // "pressing plus 200 times and it still shows 0/4" report came from:
        // a fixed per-tap delay made rapid +/- tapping feel completely dead;
        // debouncing keeps each tap's write instant and catches the display
        // up once, shortly after tapping actually stops.
        requestWidgetRefresh(context)
        android.util.Log.d(tag, "GlanceProgressDeltaAction: write done, refresh requested")
    }
}
