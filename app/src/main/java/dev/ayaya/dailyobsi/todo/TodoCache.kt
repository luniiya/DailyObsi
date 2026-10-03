package dev.ayaya.dailyobsi.todo

import android.content.Context
import java.io.File

/** The last list fetched for each date, raw JSON on disk, so the tab still
 *  shows something when Nextcloud can't be reached. */
class TodoCache(private val dir: File) {
    data class Entry(val json: String, val fetchedAtMillis: Long)

    fun load(date: String): Entry? = runCatching {
        val file = fileFor(date)
        if (!file.isFile) null else Entry(file.readText(), file.lastModified())
    }.getOrNull()

    fun store(date: String, json: String, nowMillis: Long = System.currentTimeMillis()) {
        runCatching {
            dir.mkdirs()
            // A unique temp name: the app and the widget's worker can store the same date at once.
            val tmp = File.createTempFile(date, ".tmp", dir)
            tmp.writeText(json)
            tmp.setLastModified(nowMillis)
            if (!tmp.renameTo(fileFor(date))) tmp.delete()
            prune()
        }
    }

    companion object {
        /** The one cache the tab and the widget share. */
        fun forApp(context: Context) = TodoCache(File(context.filesDir, "todo-cache"))

        private const val KEEP = 31
    }

    /** Keeps the [KEEP] most recent dates; older days are rarely reopened offline. */
    private fun prune() {
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    private fun fileFor(date: String) = File(dir, "$date.json")
}
