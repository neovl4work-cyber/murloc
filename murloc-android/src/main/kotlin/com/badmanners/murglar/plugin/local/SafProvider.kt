package com.badmanners.murglar.plugin.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileNotFoundException

class SafProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        requireHost()
        val context = context ?: throw IllegalStateException("Provider context is unavailable")
        return when (uri.lastPathSegment) {
            "last" -> {
                val result = PickResultStore.read(context)
                MatrixCursor(arrayOf(COLUMN_URI, COLUMN_TIMESTAMP)).apply {
                    addRow(arrayOf<Any>(result.treeUri, result.timestamp))
                }
            }
            "list" -> queryDocuments(uri, children = true)
            "info" -> queryDocuments(uri, children = false)
            else -> throw IllegalArgumentException("Unknown path: $uri")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireHost()
        if (mode != "r")
            throw FileNotFoundException("Read-only provider")
        val document = uri.getQueryParameter(PARAM_DOCUMENT)
            ?: throw FileNotFoundException("Missing document URI")
        val resolver = context?.contentResolver ?: throw FileNotFoundException("Provider context is unavailable")
        return resolver.openFileDescriptor(Uri.parse(document), "r")
            ?: throw FileNotFoundException("Can't open $document")
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun queryDocuments(uri: Uri, children: Boolean): Cursor {
        val context = context ?: throw IllegalStateException("Provider context is unavailable")
        val tree = Uri.parse(uri.getQueryParameter(PARAM_TREE)
            ?: throw IllegalArgumentException("Missing tree URI"))
        val documentId = uri.getQueryParameter(PARAM_DOCUMENT)
            ?: throw IllegalArgumentException("Missing document ID")
        val target = if (children)
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        else
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val source = context.contentResolver.query(target, projection, null, null, null)
            ?: throw FileNotFoundException("Can't query $target")
        source.use { cursor ->
            val result = MatrixCursor(arrayOf(COLUMN_ID, COLUMN_NAME, COLUMN_MIME, COLUMN_SIZE))
            while (cursor.moveToNext()) {
                result.addRow(arrayOf<Any>(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    if (cursor.isNull(3)) 0L else cursor.getLong(3)
                ))
            }
            return result
        }
    }

    private fun requireHost() {
        if (callingPackage != HOST_PACKAGE)
            throw SecurityException("Only $HOST_PACKAGE can use the local music provider")
    }

    companion object {
        const val COLUMN_URI = "uri"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_MIME = "mime"
        const val COLUMN_SIZE = "size"
        const val PARAM_TREE = "tree"
        const val PARAM_DOCUMENT = "document"
        const val HOST_PACKAGE = "com.badmanners.murglar"
    }
}
