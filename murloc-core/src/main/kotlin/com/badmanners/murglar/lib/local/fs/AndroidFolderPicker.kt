package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware


/**
 * Client of the Android folder picker, implemented in the `murloc-android` module
 * ([com.badmanners.murglar.plugin.local.FolderPickActivity] + `ResultProvider`).
 *
 * The plugin core is executed in the host app process and has no `Context` through the plugins API,
 * so the host context is obtained through reflection (see [AndroidHost]) and used to:
 * 1. start the picker activity of the plugin apk (it is a separately installed app with its own
 *    manifest, so it can show the system `ACTION_OPEN_DOCUMENT_TREE` dialog and receive the result);
 * 2. poll the plugin's exported `ResultProvider` content provider for the picked folder path.
 *
 * All Android classes are accessed through reflection, so this object can be loaded on Desktop too
 * (picker-related methods just fail there with a log record).
 */
object AndroidFolderPicker {

    private const val TAG = "AndroidFolderPicker"

    /** Package of the plugin apk (see the murglar android gradle plugin: `com.badmanners.murglar.plugin.<id>`). */
    private const val PLUGIN_PACKAGE = "com.badmanners.murglar.plugin.local"

    private const val PICKER_ACTIVITY = "$PLUGIN_PACKAGE.FolderPickActivity"
    private const val PROVIDER_URI = "content://$PLUGIN_PACKAGE.provider/last"

    private const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

    private const val POLL_INTERVAL_MS = 300L
    private const val POLL_TIMEOUT_MS = 90_000L

    private const val COLUMN_PATH = "path"
    private const val COLUMN_TIMESTAMP = "timestamp"

    /**
     * Launches the system folder picker and waits (up to [POLL_TIMEOUT_MS]) for the user
     * to pick a folder, then passes the picked absolute path to [onPicked]
     * (not called if the dialog was cancelled or timed out).
     */
    fun pick(logger: LoggerMiddleware, onPicked: (String) -> Unit, onCancelled: () -> Unit = {}) {
        val context = AndroidHost.context(logger) ?: return
        val startTimestamp = System.currentTimeMillis()

        if (!launchPicker(context, logger)) {
            onCancelled()
            return
        }

        val deadline = startTimestamp + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)

            val result = pollResult(logger)
            if (result != null && result.timestamp > startTimestamp) {
                if (result.path.isNotEmpty())
                    onPicked(result.path)
                else
                    onCancelled()
                return
            }
        }

        logger.w(TAG, "Folder picker result wasn't received in ${POLL_TIMEOUT_MS / 1000} seconds!")
        onCancelled()
    }

    private fun launchPicker(context: Any, logger: LoggerMiddleware): Boolean = try {
        val intentClass = Class.forName("android.content.Intent")
        val componentNameClass = Class.forName("android.content.ComponentName")

        val componentName = componentNameClass
            .getConstructor(String::class.java, String::class.java)
            .newInstance(PLUGIN_PACKAGE, PICKER_ACTIVITY)

        val intent = intentClass.getConstructor().newInstance().also { intent ->
            intent.javaClass
                .getMethod("setComponent", componentNameClass)
                .invoke(intent, componentName)
            intent.javaClass
                .getMethod("addFlags", Int::class.java)
                .invoke(intent, FLAG_ACTIVITY_NEW_TASK)
        }

        context.javaClass
            .getMethod("startActivity", intentClass)
            .invoke(context, intent)

        true
    } catch (e: Exception) {
        logger.e(TAG, "Can't launch the folder picker activity of '$PLUGIN_PACKAGE'!", e)
        false
    }

    private fun pollResult(logger: LoggerMiddleware): PickResult? {
        val resolver = AndroidHost.contentResolver(logger) ?: return null

        val cursor = try {
            val uri = Class.forName("android.net.Uri")
                .getMethod("parse", String::class.java)
                .invoke(null, PROVIDER_URI)

            resolver.javaClass
                .getMethod("query", Class.forName("android.net.Uri"), Array<String>::class.java,
                    String::class.java, Array<String>::class.java, String::class.java)
                .invoke(resolver, uri, null, null, null, null)
        } catch (e: Exception) {
            logger.e(TAG, "Can't query the picker result provider!", e)
            return null
        } ?: return null

        try {
            if (!(cursor.javaClass.getMethod("moveToFirst").invoke(cursor) as Boolean))
                return null

            fun string(column: String): String? {
                val index = cursor.javaClass.getMethod("getColumnIndex", String::class.java)
                    .invoke(cursor, column) as Int
                if (index < 0)
                    return null
                return cursor.javaClass.getMethod("getString", Int::class.java)
                    .invoke(cursor, index) as String?
            }

            fun long(column: String): Long {
                val index = cursor.javaClass.getMethod("getColumnIndex", String::class.java)
                    .invoke(cursor, column) as Int
                if (index < 0)
                    return -1L
                return cursor.javaClass.getMethod("getLong", Int::class.java)
                    .invoke(cursor, index) as Long
            }

            return PickResult(
                path = string(COLUMN_PATH).orEmpty(),
                timestamp = long(COLUMN_TIMESTAMP)
            )
        } catch (e: Exception) {
            logger.e(TAG, "Can't read the picker result!", e)
            return null
        } finally {
            try {
                cursor.javaClass.getMethod("close").invoke(cursor)
            } catch (_: Exception) {
            }
        }
    }

    private data class PickResult(val path: String, val timestamp: Long)
}
