package com.badmanners.murglar.plugin.local

import android.content.Context
import android.content.SharedPreferences


/**
 * Last folder picker result of this app (empty path means 'cancelled').
 * Written by [FolderPickActivity], served by [PickResultProvider] to the plugin core
 * running in the Murglar host process.
 */
object PickResultStore {

    private const val PREFS = "folder_picker"
    private const val KEY_PATH = "path"
    private const val KEY_TIMESTAMP = "timestamp"

    fun save(context: Context, path: String) {
        prefs(context).edit()
            .putString(KEY_PATH, path)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun read(context: Context): Pair<String, Long> {
        val prefs = prefs(context)
        return prefs.getString(KEY_PATH, "").orEmpty() to prefs.getLong(KEY_TIMESTAMP, 0L)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
