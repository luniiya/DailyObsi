package dev.ayaya.dailyobsi

import android.content.Context
import android.net.Uri

/**
 * Persists the SAF tree URI the user picked on first run (their vault's
 * "02 - daily" folder, or a parent of it). Nothing here is hardcoded --
 * the picker in MainActivity is the only place this value is ever set.
 */
object VaultPrefs {
    private const val PREFS = "vault_prefs"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_TEMPLATE_URI = "template_uri"

    fun getTreeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)?.let { Uri.parse(it) }

    fun setTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    /** Explicitly-picked daily-note template file, set via MainActivity's
     *  "Pick template" button. Takes priority over [DailyNote.findDailyTemplate]'s
     *  auto-detection, which only works when the vault root (not just the
     *  daily folder) was picked as the tree. */
    fun getTemplateUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEMPLATE_URI, null)?.let { Uri.parse(it) }

    fun setTemplateUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEMPLATE_URI, uri.toString())
            .apply()
    }
}
