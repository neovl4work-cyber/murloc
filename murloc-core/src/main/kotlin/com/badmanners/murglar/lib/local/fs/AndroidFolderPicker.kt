package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware

object AndroidFolderPicker {

    private const val TAG = "AndroidFolderPicker"
    private const val PLUGIN_PACKAGE = "com.badmanners.murglar.plugin.local"
    private const val PICKER_ACTIVITY = "$PLUGIN_PACKAGE.FolderPickActivity"
    private const val PROVIDER_URI = "content://$PLUGIN_PACKAGE.provider/last"
    private const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

    fun launchPicker(logger: LoggerMiddleware): Boolean = try {
        val context = AndroidHost.context(logger) ?: return false
        val intentClass = Class.forName("android.content.Intent")
        val componentNameClass = Class.forName("android.content.ComponentName")
        val componentName = componentNameClass
            .getConstructor(String::class.java, String::class.java)
            .newInstance(PLUGIN_PACKAGE, PICKER_ACTIVITY)
        val intent = intentClass.getConstructor().newInstance().also { intent ->
            intent.javaClass.getMethod("setComponent", componentNameClass).invoke(intent, componentName)
            intent.javaClass.getMethod("addFlags", Int::class.java).invoke(intent, FLAG_ACTIVITY_NEW_TASK)
        }
        context.javaClass.getMethod("startActivity", intentClass).invoke(context, intent)
        true
    } catch (e: Throwable) {
        logger.e(TAG, "Can't launch the folder picker!", e)
        false
    }

    fun pollPickedFolder(logger: LoggerMiddleware, sinceTimestamp: Long): PickedFolder? = try {
        val resolver = AndroidHost.contentResolver(logger) ?: return null
        val uri = Class.forName("android.net.Uri").getMethod("parse", String::class.java)
            .invoke(null, PROVIDER_URI)
        val cursor = resolver.javaClass.getMethod(
            "query", Class.forName("android.net.Uri"), Array<String>::class.java,
            String::class.java, Array<String>::class.java, String::class.java
        ).invoke(resolver, uri, null, null, null, null) ?: return null

        try {
            if (!(cursor.javaClass.getMethod("moveToFirst").invoke(cursor) as Boolean))
                return null
            val timestamp = cursorValue(cursor, "timestamp")?.toLongOrNull() ?: return null
            if (timestamp <= sinceTimestamp)
                return null
            val treeUri = cursorValue(cursor, "uri").orEmpty()
            PickedFolder(treeUri, timestamp)
        } finally {
            runCatching { cursor.javaClass.getMethod("close").invoke(cursor) }
        }
    } catch (e: Throwable) {
        logger.e(TAG, "Can't read the picker result!", e)
        null
    }

    private fun cursorValue(cursor: Any, column: String): String? {
        val index = cursor.javaClass.getMethod("getColumnIndex", String::class.java)
            .invoke(cursor, column) as Int
        if (index < 0)
            return null
        return cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, index) as String?
    }

    class PickedFolder(val treeUri: String, val timestamp: Long)
}
