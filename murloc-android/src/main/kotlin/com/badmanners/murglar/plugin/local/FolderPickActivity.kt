package com.badmanners.murglar.plugin.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class FolderPickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null)
            return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_CODE)
    }

    @Deprecated("Plain trampoline activity uses the platform activity-result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE)
            return

        val treeUri = data?.data
        if (resultCode == RESULT_OK && treeUri != null) {
            val granted = runCatching {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            PickResultStore.save(this, if (granted) treeUri.toString() else "")
        } else {
            PickResultStore.save(this, "")
        }
        finish()
    }

    private companion object {
        const val REQUEST_CODE = 42
    }
}
