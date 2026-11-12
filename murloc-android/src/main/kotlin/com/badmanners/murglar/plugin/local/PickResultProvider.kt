package com.badmanners.murglar.plugin.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri


/**
 * Serves the last folder picker result ([PickResultStore]) to the plugin core,
 * which is executed in the Murglar host process and polls this provider after launching
 * [FolderPickActivity] (see `AndroidFolderPicker` in murloc-core).
 *
 * Only `query` is supported: returns a single row with the `path` and `timestamp` columns.
 */
class PickResultProvider : ContentProvider() {

    companion object {
        const val COLUMN_PATH = "path"
        const val COLUMN_TIMESTAMP = "timestamp"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(COLUMN_PATH, COLUMN_TIMESTAMP))

        val context = context ?: return cursor
        val (path, timestamp) = PickResultStore.read(context)
        cursor.addRow(arrayOf<Any>(path, timestamp))

        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
