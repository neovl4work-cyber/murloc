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
 * only needs the path to filter the MediaStore library; the raw SAF tree uri is stored as well
 * (and the read permission is persisted, like the host does for its download folder).
 * Unicode folder names and spaces work - `getTreeDocumentId` returns the decoded document id.
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
        val path: String
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && uri != null) {
            // Keep the grant (the same way the host stores its download folder) - the core
            // only needs the real path for the MediaStore filter, but the uri is kept as well.
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            path = treeUriToPath(uri)
        } else {
            path = ""
        }

        PickResultStore.save(this, path, uri?.toString().orEmpty())
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
