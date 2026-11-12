package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware


/**
 * Client of the Android folder picker, implemented in the `murloc-android` module
 * ([com.badmanners.murglar.plugin.local.FolderPickActivity] + `PickResultProvider`).
 *
 * The plugin core is executed in the host app process and has no `Context` through the plugins API,
 * so the host context is obtained through reflection (see [AndroidHost]) and used to:
 * 1. start the picker activity of the plugin apk (it is a separately installed app with its own
 *    manifest, so it can show the system `ACTION_OPEN_DOCUMENT_TREE` dialog and receive the result);
 * 2. read the picked folder from the plugin's exported `PickResultProvider` content provider.
 *
 * Everything is non-blocking: [launchPicker] only starts the system dialog (so it is safe to call
 * from any thread of the host, including the UI thread), and [pollPickedFolder] is a quick one-shot
 * read of the last result - the plugin calls it when the library tree is built and merges a fresh
 * result into the `music_dir` preference (see [com.badmanners.murglar.lib.local.LocalMurglar]).
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

    private const val COLUMN_PATH = "path"
    private const val COLUMN_TIMESTAMP = "timestamp"

    /**
     * Starts the system folder picker activity of the plugin apk.
     *
     * @return true, if the activity was started (the result can be read later with
     * [pollPickedFolder]); false on any failure (logged)
     */
    fun launchPicker(logger: LoggerMiddleware): Boolean = try {
        val context = AndroidHost.context(logger) ?: return false

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

        logger.i(TAG, "Folder picker activity launched, waiting for the user to pick a folder.")
        true
    } catch (e: Throwable) {
        logger.e(TAG, "Can't launch the folder picker activity of '$PLUGIN_PACKAGE'!", e)
        false
    }

    /**
     * One-shot read of the last folder picker result.
     *
     * @param sinceTimestamp only results newer than this timestamp are returned
     *                       (0 - any result, e.g. at the plugin start)
     * @return the picked folder (real path, e.g. `/storage/emulated/0/Music`), or null if there is
     * no result yet, it is not newer than [sinceTimestamp], or reading failed (logged).
     * A result with an empty path means 'the dialog was cancelled'.
     */
    fun pollPickedFolder(logger: LoggerMiddleware, sinceTimestamp: Long): PickedFolder? = try {
        val resolver = AndroidHost.contentResolver(logger) ?: return null

        val cursor = try {
            val uri = Class.forName("android.net.Uri")
                .getMethod("parse", String::class.java)
                .invoke(null, PROVIDER_URI)

            resolver.javaClass
                .getMethod("query", Class.forName("android.net.Uri"), Array<String>::class.java,
                    String::class.java, Array<String>::class.java, String::class.java)
                .invoke(resolver, uri, null, null, null, null)
        } catch (e: Throwable) {
            logger.e(TAG, "Can't query the picker result provider!", e)
            return null
        } ?: return null

        try {
            if (!(cursor.javaClass.getMethod("moveToFirst").invoke(cursor) as Boolean))
                return null

            fun columnIndex(column: String): Int =
                cursor.javaClass.getMethod("getColumnIndex", String::class.java)
                    .invoke(cursor, column) as Int

            val timestampIndex = columnIndex(COLUMN_TIMESTAMP)
            if (timestampIndex < 0)
                return null

            val timestamp = cursor.javaClass.getMethod("getLong", Int::class.java)
                .invoke(cursor, timestampIndex) as Long
            if (timestamp <= sinceTimestamp)
                return null

            val pathIndex = columnIndex(COLUMN_PATH)
            val path = if (pathIndex >= 0)
                cursor.javaClass.getMethod("getString", Int::class.java)
                    .invoke(cursor, pathIndex) as String?
            else
                null

            PickedFolder(path.orEmpty(), timestamp)
        } finally {
            try {
                cursor.javaClass.getMethod("close").invoke(cursor)
            } catch (_: Throwable) {
            }
        }
    } catch (e: Throwable) {
        logger.e(TAG, "Can't read the picker result!", e)
        null
    }

    /**
     * Result of the folder picker: the picked folder as a real file path and the time of picking.
     */
    class PickedFolder(val path: String, val timestamp: Long)
}
