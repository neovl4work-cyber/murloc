package com.badmanners.murglar.plugin.local

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract


/**
 * Trampoline activity that shows the system folder picker (`ACTION_OPEN_DOCUMENT_TREE`).
 *
 * The plugin core is executed inside the Murglar host process and can't receive activity results,
 * so the picking is done here (the plugin apk is a separately installed app with its own manifest).
 * The picked folder is published through [PickResultProvider] and polled by the core
 * (see `AndroidFolderPicker` in murloc-core).
 *
 * The result is stored as a real file path (e.g. `/storage/emulated/0/Music`), because the plugin
 * only needs the path to filter the MediaStore library - no persistable uri permission is required.
 */
class FolderPickActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CODE)
    }

    @Deprecated("ComponentActivity provides ActivityResult APIs, but this activity is a plain trampoline")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val uri = data?.data
        val path = if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && uri != null)
            treeUriToPath(uri)
        else
            ""

        PickResultStore.save(this, path)
        finish()
    }

    /**
     * Converts a tree document uri to the real file path:
     * `content://.../tree/primary:Music/Rock` -> `/storage/emulated/0/Music/Rock`.
     */
    private fun treeUriToPath(uri: Uri): String {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', missingDelimiterValue = "").replace(':', '/')
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
