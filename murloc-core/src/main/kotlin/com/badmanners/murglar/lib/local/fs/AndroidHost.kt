package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import java.io.File


/**
 * Access to the host application context on Android through reflection
 * (`ActivityThread.currentApplication()`): the plugins API doesn't pass a `Context`,
 * but the plugin code is executed inside the host app process.
 *
 * All Android classes are accessed through reflection, so this object can be used on Desktop too
 * (it just returns null there).
 */
object AndroidHost {

    private const val TAG = "AndroidHost"

    /**
     * Host application context (android.content.Context), or null if not on Android
     * (or if the host blocks the `ActivityThread` reflection trick).
     */
    fun context(logger: LoggerMiddleware): Any? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null)
    } catch (e: Exception) {
        logger.e(TAG, "Can't obtain application context through ActivityThread!", e)
        null
    }

    /**
     * Content resolver of the host app (android.content.ContentResolver), or null.
     */
    fun contentResolver(logger: LoggerMiddleware): Any? =
        context(logger)?.let { ctx ->
            try {
                ctx.javaClass.getMethod("getContentResolver").invoke(ctx)
            } catch (e: Exception) {
                logger.e(TAG, "Can't obtain content resolver!", e)
                null
            }
        }

    /**
     * A writable app-internal dir of the host app (`<filesDir>/murloc-playlists`), created on demand.
     * Used for playlists storage on Android (there is no user-accessible playlists folder there).
     */
    fun playlistsDir(logger: LoggerMiddleware): File? = try {
        context(logger)
            ?.let { ctx -> ctx.javaClass.getMethod("getFilesDir").invoke(ctx) as? File }
            ?.let { dir -> File(dir, "murloc-playlists") }
            ?.takeIf { it.exists() || it.mkdirs() }
    } catch (e: Exception) {
        logger.e(TAG, "Can't create app-internal playlists dir!", e)
        null
    }
}
