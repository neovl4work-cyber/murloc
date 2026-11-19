package com.badmanners.murglar.plugin.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract

class FolderPickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (savedInstanceState != null)
                return
            launchPicker(useSystemUi = true)
        } catch (t: Throwable) {
            CrashLog.log(this, "FolderPickActivity.onCreate", t)
            PickResultStore.save(this, "")
            finish()
        }
    }

    private fun launchPicker(useSystemUi: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        // системный SAF-проводник предпочтителен (BlueStacks и др. перехватывают интент
        // сторонними менеджерами), но на многих телефонах documentsui нет — тогда
        // пробуем общий резолв, а при полном провале закрываемся без ошибки
        if (useSystemUi)
            runCatching { intent.setPackage("com.android.documentsui") }
        runCatching {
            intent.putExtra(
                "android.provider.extra.INITIAL_URI",
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents", "primary:Music"
                )
            )
        }
        runCatching { startActivityForResult(intent, REQUEST_CODE) }
            .onFailure {
                if (useSystemUi) {
                    CrashLog.log(this, "launchPicker: documentsui не найден, пробуем общий резолв", it)
                    launchPicker(useSystemUi = false)
                } else {
                    CrashLog.log(this, "launchPicker: нет activity для ACTION_OPEN_DOCUMENT_TREE", it)
                    PickResultStore.save(this, "")
                    finish()
                }
            }
    }

    @Deprecated("Plain trampoline activity uses the platform activity-result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            CrashLog.log(this, "onActivityResult", IllegalStateException("requestCode=$requestCode resultCode=$resultCode data=$data flags=${data?.flags}"))
            if (requestCode != REQUEST_CODE)
                return
            val treeUri = data?.data
            if (resultCode == RESULT_OK && treeUri != null) {
                // флаги берём из результата (некоторые прошивки не ставят READ отдельно);
                // при неудаче всё равно сохраняем URI — транзитный грант плагину уже доступен
                val flags = (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                    .or(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val granted = runCatching {
                    contentResolver.takePersistableUriPermission(treeUri, flags)
                }.onFailure {
                    CrashLog.log(this, "takePersistableUriPermission failed (uri=$treeUri flags=$flags)", it)
                }.isSuccess
                PickResultStore.save(this, treeUri.toString())
                if (!granted)
                    CrashLog.log(this, "picker result", IllegalStateException("persistable grant НЕ получен, сохранён транзитный: $treeUri"))
            } else {
                PickResultStore.save(this, "")
            }
        } catch (t: Throwable) {
            CrashLog.log(this, "FolderPickActivity.onActivityResult", t)
        } finally {
            finish()
        }
    }

    private companion object {
        const val REQUEST_CODE = 42
    }
}
