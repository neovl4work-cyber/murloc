package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import java.io.InputStream


/**
 * [FileScanner] for Android - reads the device media library through `MediaStore.Audio`.
 *
 * The plugin has no access to a `Context` through the plugins API, so the application context
 * is obtained through reflection (`ActivityThread.currentApplication()`). If that fails
 * on the host app, [isAvailable] becomes false and the plugin shows an empty library.
 *
 * All Android classes are accessed through reflection, so this class can be loaded on Desktop too.
 */
class AndroidScanner(
    private val logger: LoggerMiddleware
) : FileScanner {

    companion object {
        private const val TAG = "AndroidScanner"

        private const val CONTENT_URI = "content://media/external/audio/media/"

        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")

        private const val UNKNOWN_DURATION = -1L
    }

    private var cached: List<AudioFile>? = null

    override val isAvailable: Boolean
        get() = context() != null

    override fun listSubdirs(relPath: String?): List<DirEntry> {
        val prefix = when {
            relPath.isNullOrEmpty() -> ""
            else -> "$relPath/"
        }

        return allAudio()
            .mapNotNull { it.album }
            .map { it.removeSuffix("/") }
            .filter { it.startsWith(prefix) && it.substringAfter(prefix).isNotEmpty() }
            .map { DirEntry(it, it.substringAfterLast('/')) }
            .distinctBy { it.relPath }
            .sortedBy { it.name.lowercase() }
    }

    override fun listAudio(relPath: String?): List<AudioFile> {
        val parent = relPath.orEmpty().removeSuffix("/")
        return allAudio().filter { (it.album ?: "") == parent }
    }

    override fun audioFile(id: String): AudioFile? =
        allAudio().firstOrNull { it.id == id }

    override fun open(id: String): Pair<InputStream, Long>? = try {
        val resolver = resolver() ?: return null
        val uri = parseUri(id) ?: return null

        val afdClass = Class.forName("android.content.res.AssetFileDescriptor")
        val afd = resolver.javaClass
            .getMethod("openAssetFileDescriptor", androidNetUri(), String::class.java)
            .invoke(resolver, uri, "r")

        if (afd != null) {
            val length = afdClass.getMethod("getLength").invoke(afd) as Long
            val stream = afdClass.getMethod("createInputStream").invoke(afd) as InputStream
            stream to length
        } else {
            openStreamUnknownLength(resolver, uri)
        }
    } catch (e: Exception) {
        logger.e(TAG, "Can't open '$id'!", e)
        null
    }

    private fun openStreamUnknownLength(resolver: Any, uri: Any): Pair<InputStream, Long>? = try {
        val stream = resolver.javaClass
            .getMethod("openInputStream", androidNetUri())
            .invoke(resolver, uri) as InputStream
        stream to Source.UNKNOWN_SIZE
    } catch (e: Exception) {
        logger.e(TAG, "Can't open stream for '$uri'!", e)
        null
    }


    private fun allAudio(): List<AudioFile> {
        cached?.let { return it }

        val audio = try {
            queryMediaStore()
        } catch (e: Exception) {
            logger.e(TAG, "MediaStore query failed - plugin will show an empty library!", e)
            emptyList()
        }

        cached = audio
        return audio
    }

    private fun queryMediaStore(): List<AudioFile> {
        val resolver = resolver() ?: return emptyList()

        val mediaClass = Class.forName("android.provider.MediaStore\$Audio\$Media")
        val uri = mediaClass.getField("EXTERNAL_CONTENT_URI").get(null)

        val projection = arrayOf("_id", "_display_name", "relative_path", "duration", "artist")
        val cursor = resolver.javaClass
            .getMethod("query", androidNetUri(), Array<String>::class.java, String::class.java,
                Array<String>::class.java, String::class.java)
            .invoke(resolver, uri, projection, null, null, null) ?: return emptyList()

        val audio = mutableListOf<AudioFile>()
        try {
            val idColumn = columnIndex(cursor, "_id")
            val nameColumn = columnIndex(cursor, "_display_name")
            val pathColumn = columnIndex(cursor, "relative_path")
            val durationColumn = columnIndex(cursor, "duration")
            val artistColumn = columnIndex(cursor, "artist")

            while (cursor.javaClass.getMethod("moveToNext").invoke(cursor) as Boolean) {
                val name = cursorString(cursor, nameColumn) ?: continue
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in AUDIO_EXTENSIONS)
                    continue

                val id = cursorString(cursor, idColumn) ?: continue
                val relativePath = cursorString(cursor, pathColumn)
                val artist = cursorString(cursor, artistColumn)?.takeIf { it.isNotEmpty() && it != "<unknown>" }
                val duration = cursorLong(cursor, durationColumn)

                audio += AudioFile(
                    id = "$CONTENT_URI$id",
                    title = name.substringBeforeLast('.', name),
                    artists = listOfNotNull(artist),
                    album = relativePath?.removeSuffix("/")?.takeIf { it.isNotEmpty() },
                    durationMs = duration,
                    extension = extension
                )
            }
        } finally {
            cursor.javaClass.getMethod("close").invoke(cursor)
        }

        return audio.sortedBy { it.title.lowercase() }
    }

    private fun context(): Any? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null)
    } catch (e: Exception) {
        logger.e(TAG, "Can't obtain application context through ActivityThread!", e)
        null
    }

    private fun resolver(): Any? = context()?.let {
        it.javaClass.getMethod("getContentResolver").invoke(it)
    }

    private fun parseUri(id: String): Any? = try {
        Class.forName("android.net.Uri")
            .getMethod("parse", String::class.java)
            .invoke(null, id)
    } catch (e: Exception) {
        logger.e(TAG, "Can't parse uri '$id'!", e)
        null
    }

    private fun androidNetUri() = Class.forName("android.net.Uri")

    private fun columnIndex(cursor: Any, name: String): Int =
        cursor.javaClass.getMethod("getColumnIndex", String::class.java).invoke(cursor, name) as Int

    private fun cursorString(cursor: Any, column: Int): String? =
        (column >= 0).takeIf { it }?.let {
            cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, column) as String?
        }

    private fun cursorLong(cursor: Any, column: Int): Long =
        if (column >= 0)
            cursor.javaClass.getMethod("getLong", Int::class.java).invoke(cursor, column) as Long
        else
            UNKNOWN_DURATION
}
