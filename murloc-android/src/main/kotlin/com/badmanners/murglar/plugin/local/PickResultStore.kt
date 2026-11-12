package com.badmanners.murglar.plugin.local

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences


/**
 * Last folder picker result of this app (empty path means 'cancelled').
 * Written by [FolderPickActivity], served by [PickResultProvider] to the plugin core
 * running in the Murglar host process.
 *
 * [path] is the real file path (e.g. `/storage/emulated/0/Music`), [uri] - the raw SAF tree uri
 * (e.g. `content://.../tree/AE7C-9F03:Music/`, the same form the host app uses for its
 * download folder) - kept for troubleshooting and future use.
 */
object PickResultStore {

    private const val PREFS = "folder_picker"
    private const val KEY_PATH = "path"
    private const val KEY_URI = "uri"
    private const val KEY_TIMESTAMP = "timestamp"

    fun save(context: Context, path: String, uri: String) {
        prefs(context).edit()
            .putString(KEY_PATH, path)
            .putString(KEY_URI, uri)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun read(context: Context): Triple<String, String, Long> {
        val prefs = prefs(context)
        return Triple(
            prefs.getString(KEY_PATH, "").orEmpty(),
            prefs.getString(KEY_URI, "").orEmpty(),
            prefs.getLong(KEY_TIMESTAMP, 0L)
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
