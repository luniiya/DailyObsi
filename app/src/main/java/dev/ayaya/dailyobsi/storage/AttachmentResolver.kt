package dev.ayaya.dailyobsi.storage

import android.content.Context
import android.net.Uri
import dev.ayaya.dailyobsi.DailyNote
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AttachmentState {
    data object Loading : AttachmentState
    data object Missing : AttachmentState
    data class Found(val uri: Uri) : AttachmentState
}

/** Process-wide, batched attachment lookup shared by every section page. */
object AttachmentResolver {
    private val found = ConcurrentHashMap<String, Uri>()
    private val resolved = ConcurrentHashMap.newKeySet<String>()
    private val lookupMutex = Mutex()

    fun snapshot(treeUri: Uri, names: Set<String>): Map<String, AttachmentState> =
        names.associateWith { name -> stateFor(key(treeUri, name)) }

    suspend fun resolve(
        context: Context,
        treeUri: Uri,
        names: Set<String>,
    ): Map<String, AttachmentState> = lookupMutex.withLock {
        val unresolved = names.filterNot { resolved.contains(key(treeUri, it)) }.toSet()
        if (unresolved.isNotEmpty()) {
            val matches = withContext(Dispatchers.IO) {
                DailyNote.findAttachmentUris(context, treeUri, unresolved)
            }
            unresolved.forEach { name ->
                val cacheKey = key(treeUri, name)
                matches[name]?.let { found[cacheKey] = it }
                resolved += cacheKey
            }
        }
        snapshot(treeUri, names)
    }

    private fun stateFor(cacheKey: String): AttachmentState = when {
        found.containsKey(cacheKey) -> AttachmentState.Found(found.getValue(cacheKey))
        cacheKey in resolved -> AttachmentState.Missing
        else -> AttachmentState.Loading
    }

    private fun key(treeUri: Uri, name: String): String = "$treeUri|$name"
}
