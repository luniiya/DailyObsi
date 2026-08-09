package dev.ayaya.dailyobsi

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE // yyyy-MM-dd

/** Regex for a markdown checkbox list item: "- [ ] text" or "- [x] text". */
val CHECKBOX_LINE = Regex("""^(\s*-\s\[)([ xX])(]\s*)(.*)$""")

data class TodoItem(val lineIndex: Int, val checked: Boolean, val text: String)

/**
 * The user picks the daily-notes folder itself (e.g. "02 - daily") and,
 * separately, the template file -- not the vault root. So no guessing or
 * SAF parent-walking is needed here: [treeUri] IS the folder today's note
 * lives (or should be created) in.
 */
object DailyNote {

    fun fileNameFor(date: LocalDate): String = date.format(FILE_NAME_FORMAT) + ".md"

    fun findFile(context: Context, treeUri: Uri, date: LocalDate): DocumentFile? {
        val name = fileNameFor(date)
        return findFiles(context, treeUri, setOf(name))[name]
    }

    /** Looks up any number of files by exact name in *one* directory listing
     *  query, instead of calling `DocumentFile.findFile` once per name.
     *  `DocumentFile.findFile` lists the folder (1 query), then calls
     *  `.getName()` on every child to compare -- and `TreeDocumentFile.getName()`
     *  is its own separate Binder round-trip per child, not a cached read off
     *  the listing (confirmed via a real ANR: the stack trace bottomed out in
     *  exactly that method). That's `1 + N` queries per lookup, N = files in
     *  the folder -- doubled at startup (today + yesterday-fallback) when
     *  today's note doesn't exist yet. Reading DISPLAY_NAME directly off the
     *  same cursor row the listing already returns needs exactly one query
     *  total, for any number of names. */
    fun findFiles(context: Context, treeUri: Uri, names: Set<String>): Map<String, DocumentFile> {
        val result = mutableMapOf<String, DocumentFile>()
        if (names.isEmpty()) return result
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (result.size < names.size && cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                if (name in names) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                    DocumentFile.fromSingleUri(context, docUri)?.let { result[name] = it }
                }
            }
        }
        return result
    }

    fun findTodayFile(context: Context, treeUri: Uri): DocumentFile? =
        findFile(context, treeUri, LocalDate.now())

    fun createTodayFile(context: Context, treeUri: Uri, templateUri: Uri?): DocumentFile? {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val name = LocalDate.now().format(FILE_NAME_FORMAT) + ".md"
        val file = folder.createFile("text/markdown", name) ?: return null
        val templateText = templateUri?.let { readText(context, it) }.orEmpty()
        writeText(context, file.uri, templateText)
        return file
    }

    fun readText(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri).use { stream ->
            return stream?.bufferedReader()?.readText().orEmpty()
        }
    }

    fun writeText(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt").use { stream ->
            stream?.bufferedWriter()?.use { it.write(text) }
        }
    }

    /** Extracts checkbox list items from raw markdown, in file order. */
    fun parseCheckboxes(text: String): List<TodoItem> =
        text.lines().mapIndexedNotNull { index, line ->
            CHECKBOX_LINE.matchEntire(line)?.let { m ->
                val checked = m.groupValues[2].equals("x", ignoreCase = true)
                TodoItem(index, checked, m.groupValues[4])
            }
        }

    /** Returns [text] with the checkbox on [lineIndex] flipped. */
    fun toggleCheckbox(text: String, lineIndex: Int): String {
        val lines = text.lines().toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return text
        val match = CHECKBOX_LINE.matchEntire(line) ?: return text
        val newMark = if (match.groupValues[2].equals("x", ignoreCase = true)) " " else "x"
        lines[lineIndex] = "${match.groupValues[1]}$newMark${match.groupValues[3]}${match.groupValues[4]}"
        return lines.joinToString("\n")
    }

    /** Indents/outdents [lineIndex] by one level (a tab). [delta] > 0 adds a
     *  level, < 0 removes one (a leading tab, else a leading 2-space run). */
    fun shiftIndent(text: String, lineIndex: Int, delta: Int): String {
        val lines = text.lines().toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return text
        lines[lineIndex] = when {
            delta > 0 -> "\t$line"
            delta < 0 && line.startsWith("\t") -> line.removePrefix("\t")
            delta < 0 && line.startsWith("  ") -> line.removePrefix("  ")
            else -> line
        }
        return lines.joinToString("\n")
    }

    /** Replaces a single line wholesale (used for e.g. progress-bar `value:` updates). */
    fun replaceLine(text: String, lineIndex: Int, newLine: String): String {
        val lines = text.lines().toMutableList()
        if (lineIndex !in lines.indices) return text
        lines[lineIndex] = newLine
        return lines.joinToString("\n")
    }

    /** Replaces the inclusive line range [range] with [newBody] (split on
     *  "\n"), rewriting only that span -- used by the section editor to save
     *  just one header's body without touching the rest of the file. An
     *  empty range (range.first > range.last, a header with no body yet)
     *  inserts [newBody] right there instead of replacing nothing. */
    fun replaceLines(text: String, range: IntRange, newBody: String): String {
        val lines = text.lines()
        val start = range.first.coerceIn(0, lines.size)
        val endExclusive = if (range.first > range.last) start else (range.last + 1).coerceIn(start, lines.size)
        val result = lines.subList(0, start) + newBody.split("\n") + lines.subList(endExclusive, lines.size)
        return result.joinToString("\n")
    }

    /** Swaps [lineIndex] with its neighbor one position toward [delta]
     *  (-1 up, +1 down) -- how reading mode reorders tasks in the raw file. */
    fun moveLine(text: String, lineIndex: Int, delta: Int): String {
        val lines = text.lines().toMutableList()
        val target = lineIndex + delta
        if (lineIndex !in lines.indices || target !in lines.indices) return text
        val tmp = lines[lineIndex]
        lines[lineIndex] = lines[target]
        lines[target] = tmp
        return lines.joinToString("\n")
    }

    /**
     * Best-effort resolve for an `![[name]]` embed: attachments usually live
     * next to the vault, not inside the picked daily folder, so this may not
     * find anything -- that's expected and handled by the caller (falls back
     * to a placeholder). Bounded-depth DFS so a large tree can't hang this.
     */
    fun findAttachment(context: Context, treeUri: Uri, name: String, maxDepth: Int = 4): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        fun search(dir: DocumentFile, depth: Int): DocumentFile? {
            if (depth > maxDepth) return null
            for (child in dir.listFiles()) {
                if (child.isFile && child.name == name) return child
                if (child.isDirectory) search(child, depth + 1)?.let { return it }
            }
            return null
        }
        return search(root, 0)
    }
}
