package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import java.io.InputStream


/**
 * [FileScanner] for Android - reads the device media library through `MediaStore.Audio`.
 *
 * The library can be limited to specific folders through the `music_dir` preference:
 * entries are folder paths relative to the internal storage root (e.g. `Music` or
 * `Music/Discounted`), absolute paths like `/storage/emulated/0/Music` are also accepted
 * (see [normalizeRoots]). An empty preference means the whole media library.
 *
 * The plugin has no access to a `Context` through the plugins API, so the application context
 * is obtained through reflection (see [AndroidHost]). If that fails on the host app,
 * [isAvailable] becomes false and the plugin shows an empty library.
 *
 * All Android classes are accessed through reflection, so this class can be loaded on Desktop too.
 */
class AndroidScanner(
    private val logger: LoggerMiddleware,
    private val musicFolders: () -> List<String> = { emptyList() }
) : FileScanner {

    companion object {
        private const val TAG = "AndroidScanner"

        private const val CONTENT_URI = "content://media/external/audio/media/"

        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")

        private const val UNKNOWN_DURATION = -1L
    }

    private var cached: List<AudioFile>? = null

    override val isAvailable: Boolean
        get() = AndroidHost.context(logger) != null

    override val hasMultipleRoots = false

    override fun listSubdirs(relPath: String?): List<DirEntry> {
        val prefix = when {
            relPath.isNullOrEmpty() -> ""
            else -> "$relPath/"
        }

        return allAudio()
            .mapNotNull { it.album }
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
        val resolver = AndroidHost.contentResolver(logger) ?: return null
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
        val resolver = AndroidHost.contentResolver(logger) ?: return emptyList()

        val mediaClass = Class.forName("android.provider.MediaStore\$Audio\$Media")
        val uri = mediaClass.getField("EXTERNAL_CONTENT_URI").get(null)

        val projection = arrayOf("_id", "_display_name", "relative_path", "duration", "artist", "volume_name")
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
            val volumeColumn = columnIndex(cursor, "volume_name")

            while (cursor.javaClass.getMethod("moveToNext").invoke(cursor) as Boolean) {
                val name = cursorString(cursor, nameColumn) ?: continue
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in AUDIO_EXTENSIONS)
                    continue

                val id = cursorString(cursor, idColumn) ?: continue
                val relativePath = cursorString(cursor, pathColumn)
                val artist = cursorString(cursor, artistColumn)?.takeIf { it.isNotEmpty() && it != "<unknown>" }
                val duration = cursorLong(cursor, durationColumn)
                // volume_name exists since API 29; before that there is only the primary volume.
                val volume = cursorString(cursor, volumeColumn) ?: "external"

                val strippedPath = stripRoot(relativePath?.removeSuffix("/")?.takeIf { it.isNotEmpty() }, volume)
                    ?: continue

                audio += AudioFile(
                    id = "$CONTENT_URI$id",
                    title = name.substringBeforeLast('.', name),
                    artists = listOfNotNull(artist),
                    album = strippedPath,
                    durationMs = duration,
                    extension = extension,
                    volume = volume
                )
            }
        } finally {
            cursor.javaClass.getMethod("close").invoke(cursor)
        }

        return audio.sortedBy { it.title.lowercase() }
    }

    /**
     * Music folder roots from the `music_dir` preference (see [MusicRoots] for supported forms).
     * Empty entries are dropped - an empty result means the whole library.
     */
    private fun normalizeRoots(): List<MusicRoots.Root> = musicFolders()
        .mapNotNull { MusicRoots.parse(it) }
        .distinct()

    /**
     * The part of the MediaStore `relative_path` [path] of the track from the volume [volume]
     * relative to the longest configured music root, or null if the track is outside all
     * configured roots (all tracks pass when no roots are configured).
     */
    private fun stripRoot(path: String?, volume: String): String? {
        val trackPath = path ?: ""
        val roots = normalizeRoots()
        if (roots.isEmpty())
            return trackPath

        val base = roots
            .filter { it.volume == null || it.volume == volume }
            .filter { it.path.isEmpty() || trackPath == it.path || trackPath.startsWith(it.path + "/") }
            .maxByOrNull { it.path.length }
            ?: return null

        return trackPath.removePrefix(base.path).removePrefix("/")
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
