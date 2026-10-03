package dev.ayaya.dailyobsi.todo

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
            val tmp = File(dir, "$date.json.tmp")
            tmp.writeText(json)
            tmp.setLastModified(nowMillis)
            tmp.renameTo(fileFor(date))
            prune()
        }
    }

    /** Keeps the [KEEP] most recent dates; older days are rarely reopened offline. */
    private fun prune() {
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    private fun fileFor(date: String) = File(dir, "$date.json")

    private companion object {
        const val KEEP = 31
    }
}
