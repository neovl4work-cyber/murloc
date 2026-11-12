package com.badmanners.murglar.plugin.local

import android.content.Context

object PickResultStore {

    data class Result(val treeUri: String, val timestamp: Long)

    private const val PREFS = "folder_picker"
    private const val KEY_URI = "uri"
    private const val KEY_TIMESTAMP = "timestamp"

    fun save(context: Context, treeUri: String) {
        prefs(context).edit()
            .putString(KEY_URI, treeUri)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun read(context: Context): Result {
        val prefs = prefs(context)
        return Result(
            prefs.getString(KEY_URI, "").orEmpty(),
            prefs.getLong(KEY_TIMESTAMP, 0L)
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
