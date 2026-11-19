package com.badmanners.murglar.plugin.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Трамплин для SAF-выбора папки: живёт в процессе плагина, поэтому постоянный
 * грант принадлежит плагину, а не хосту. Конфигурация проверена на OneUI 4
 * (Samsung A52): НИКАКИХ флагов активности (noHistory/excludeFromRecents ломают
 * доставку результата — активность убивается под пикером и AMS отдаёт
 * RESULT_CANCELD), интент — голый ACTION_OPEN_DOCUMENT_TREE (+грант-флаги):
 * setPackage/EXTRA_INITIAL_URI на OneUI дают потерянный результат.
 */
class FolderPickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (savedInstanceState != null)
                return
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            runCatching { startActivityForResult(intent, REQUEST_CODE) }
                .onFailure {
                    CrashLog.log(this, "launchPicker: нет activity для ACTION_OPEN_DOCUMENT_TREE", it)
                    PickResultStore.save(this, "")
                    finish()
                }
        } catch (t: Throwable) {
            CrashLog.log(this, "FolderPickActivity.onCreate", t)
            PickResultStore.save(this, "")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            CrashLog.log(this, "onActivityResult", IllegalStateException("requestCode=$requestCode resultCode=$resultCode uri=${data?.data} flags=${data?.flags}"))
            if (requestCode != REQUEST_CODE)
                return
            val treeUri = data?.data
            if (resultCode == RESULT_OK && treeUri != null) {
                val granted = runCatching {
                    contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure {
                    CrashLog.log(this, "takePersistableUriPermission failed (uri=$treeUri)", it)
                }.isSuccess
                PickResultStore.save(this, treeUri.toString())
                if (!granted)
                    CrashLog.log(this, "picker result", IllegalStateException("persistable НЕ получен, сохранён транзитный: $treeUri"))
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
