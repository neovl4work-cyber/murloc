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
    ): Cursor = try {
        val context = context ?: throw IllegalStateException("Provider context is unavailable")
        if (uri.lastPathSegment == "crashlog") {
            // диагностика: отдаём содержимое murloc-crash.log БЕЗ проверки вызывающего
            // (Android/data недоступен shell-у из-за FUSE; callingPackage у shell иной)
            val cursor = MatrixCursor(arrayOf(COLUMN_ID)) // column 'id' reused as line
            runCatching {
                val dir = context.getExternalFilesDir(null)
                val file = java.io.File(dir, "murloc-crash.log")
                if (file.exists()) file.readLines().forEach { cursor.addRow(arrayOf<Any>(it)) }
                else cursor.addRow(arrayOf<Any>("(журнал пуст)"))
            }.onFailure { cursor.addRow(arrayOf<Any>("crashlog error: ${it.message}")) }
            return cursor
        }
        requireHost()
        when (uri.lastPathSegment) {
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
    } catch (t: Throwable) {
        CrashLog.log(context, "SafProvider.query $uri", t)
        // пустой курсор вместо падения процесса плагина; хост получит пустую библиотеку
        MatrixCursor(arrayOf(COLUMN_ID, COLUMN_NAME, COLUMN_MIME, COLUMN_SIZE))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        try {
            requireHost()
            if (mode != "r")
                throw FileNotFoundException("Read-only provider")
            val raw = uri.getQueryParameter(PARAM_DOCUMENT)
                ?: throw FileNotFoundException("Missing document URI")
            // raw = content://authority/tree/<treeDocId>/document/<docId>
            // прямое открытие такого составного URI отклоняется провайдером
            // хранилища — строим канонический document URI через buildDocumentUriUsingTree
            val treePart = raw.substringBefore("/document/")
            val docId = raw.substringAfter("/document/", "")
            if (docId.isEmpty() || treePart == raw)
                throw FileNotFoundException("Bad document URI: $raw")
            val document = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treePart), docId)
            val resolver = context?.contentResolver ?: throw FileNotFoundException("Provider context is unavailable")
            return resolver.openFileDescriptor(document, "r")
                ?: throw FileNotFoundException("Can't open $document")
        } catch (t: FileNotFoundException) {
            CrashLog.log(context, "SafProvider.openFile $uri", t)
            throw t
        } catch (t: Throwable) {
            CrashLog.log(context, "SafProvider.openFile $uri", t)
            throw FileNotFoundException(t.message)
        }
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
        // Murglar встречается под разными пакетами: com.badmanners.murglar, ...murglar2 (клоны);
        // com.android.shell — для диагностики через `adb shell content query`.
        // callingPackage на Android 11 может сам бросать NPE (AppOps.checkPackage
        // для shell-uid) — обращаемся через runCatching.
        val caller = runCatching { callingPackage }.getOrNull().orEmpty()
        if (caller.isNotEmpty() && !caller.startsWith(HOST_PACKAGE_PREFIX) && caller != "com.android.shell")
            throw SecurityException("Only Murglar can use the local music provider (caller: $caller)")
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
        const val HOST_PACKAGE_PREFIX = "com.badmanners.murglar"
    }
}
