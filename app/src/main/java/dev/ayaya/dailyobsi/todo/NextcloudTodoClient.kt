package dev.ayaya.dailyobsi.todo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.aidl.NextcloudRequest
import com.nextcloud.android.sso.api.NextcloudAPI
import com.nextcloud.android.sso.exceptions.NextcloudHttpRequestFailedException
import com.nextcloud.android.sso.model.SingleSignOnAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A failed call. [reachedServer] separates "Nextcloud said no" (show the
 *  message, keep the list live) from "couldn't get there" (fall back to the cache). */
class TodoException(message: String, val reachedServer: Boolean) : Exception(message)

/**
 * Talks to the Daily Todo app on the user's Nextcloud through Nextcloud's
 * Single Sign-On: requests are proxied by the Nextcloud Files app, which
 * holds the login and adds the auth and the `OCS-APIRequest` header itself
 * (it refuses requests that set that header). See docs/nextcloud-daily-todo.md.
 */
class NextcloudTodoClient(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()
    private var api: NextcloudAPI? = null
    private var apiAccount: String? = null

    /** The Files-app account picked in Settings, e.g. "ayaya@nc.luniiya.me". */
    val accountName: String? get() = prefs.getString(KEY_ACCOUNT, null)

    fun connect(account: SingleSignOnAccount) {
        prefs.edit().putString(KEY_ACCOUNT, account.name).apply()
        close()
    }

    fun disconnect() {
        prefs.edit().remove(KEY_ACCOUNT).apply()
        close()
    }

    @Synchronized
    fun close() {
        runCatching { api?.close() }
        api = null
        apiAccount = null
    }

    suspend fun day(date: String): String = request("GET", "/api/day/$date")

    /** ~3 ms on the server vs ~21 ms for the whole day; poll this. */
    suspend fun dayVersion(date: String): String? = parseDayVersion(request("GET", "/api/day/$date/version"))

    suspend fun complete(id: Long, completed: Boolean) {
        request("PUT", "/api/items/$id/complete", mapOf("completed" to completed))
    }

    suspend fun reorder(date: String, ids: List<Long>) {
        request("PUT", "/api/day/$date/order", mapOf("ids" to ids))
    }

    /** [afterId] inserts right after that item (its sibling); [parentId] adds a subtask. Returns the new id. */
    suspend fun createQuick(date: String, title: String, parentId: Long? = null, afterId: Long? = null): Long {
        val body = buildMap<String, Any> {
            put("title", title)
            parentId?.let { put("parentId", it) }
            afterId?.let { put("afterId", it) }
        }
        val answer = request("POST", "/api/day/$date/quick", body)
        return createdId(answer) ?: throw TodoException("Nextcloud didn't return the new item", reachedServer = true)
    }

    suspend fun rename(id: Long, title: String) {
        request("PUT", "/api/items/$id", mapOf("title" to title))
    }

    suspend fun delete(id: Long) {
        request("DELETE", "/api/items/$id")
    }

    private suspend fun request(method: String, path: String, body: Any? = null): String =
        withContext(Dispatchers.IO) {
            val api = api() ?: throw TodoException("Nextcloud isn't connected", reachedServer = false)
            val request = NextcloudRequest.Builder()
                .setMethod(method)
                .setUrl("$BASE$path")
                .apply { if (body != null) setRequestBody(gson.toJson(body)) }
                .build()
            try {
                api.performNetworkRequestV2(request).body.use { it.readBytes().decodeToString() }
            } catch (error: NextcloudHttpRequestFailedException) {
                // The Files app passes the response body along as the cause's message.
                val message = serverErrorMessage(error.cause?.message)
                    ?: "Nextcloud answered HTTP ${error.statusCode}"
                throw TodoException(message, reachedServer = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw TodoException(
                    error.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach Nextcloud",
                    reachedServer = false,
                )
            }
        }

    @Synchronized
    private fun api(): NextcloudAPI? {
        val name = accountName ?: return null
        if (api != null && apiAccount == name) return api
        close()
        val account = runCatching { AccountImporter.getSingleSignOnAccount(context, name) }
            .getOrNull() ?: return null
        return NextcloudAPI(context, account, gson).also {
            api = it
            apiAccount = name
        }
    }

    companion object {
        /** Whether an account is picked, without building a client (the tab row needs just this). */
        fun isConnected(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_ACCOUNT)

        private const val PREFS = "dailyobsi_nextcloud"
        private const val KEY_ACCOUNT = "sso_account_name"
        private const val BASE = "/index.php/apps/daily_todo"
    }
}
