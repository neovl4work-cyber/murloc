package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import java.io.File
import java.io.FileInputStream
import java.io.InputStream


/**
 * [FileScanner] that walks the device storage directly through `java.io.File`.
 *
 * Used on Android when the all-files access (`MANAGE_EXTERNAL_STORAGE`) is granted to the host
 * app - no MediaStore, no content resolver, no cross-process calls: the music folder structure
 * is mirrored as is, track ids are real file paths. Also usable on Desktop (via the `scanner`
 * preference override) for testing.
 *
 * Supports several roots at once (internal memory + SD cards): the root level lists the roots
 * themselves (see [hasMultipleRoots]). Hidden directories are skipped.
 */
class DirectAndroidScanner(
    private val logger: LoggerMiddleware,
    private val roots: () -> List<DirEntry>
) : FileScanner {

    private val audioExtensions = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")

    override val isAvailable: Boolean
        get() = File("/storage/emulated/0").let { it.exists() && it.canRead() }

    override val hasMultipleRoots: Boolean
        get() = roots().size > 1

    override fun listSubdirs(relPath: String?): List<DirEntry> = try {
        // Root level with several roots lists the roots themselves.
        if (relPath.isNullOrEmpty() && hasMultipleRoots) {
            roots()
        } else {
            val bases = when {
                relPath.isNullOrEmpty() -> roots().take(1).map { File(it.relPath) }
                else -> listOfNotNull(File(relPath).takeIf { it.isDirectory })
            }

            bases.flatMap { dir ->
                dir.listFiles { file -> file.isDirectory && !file.isHidden }
                    ?.map { file -> DirEntry(file.absolutePath, file.name) }
                    .orEmpty()
            }.sortedBy { it.name.lowercase() }
        }
    } catch (e: Exception) {
        logger.e(TAG, "Can't list subdirs of '$relPath'!", e)
        emptyList()
    }

    override fun listAudio(relPath: String?): List<AudioFile> = try {
        val bases = when {
            relPath.isNullOrEmpty() -> roots().map { File(it.relPath) }
            else -> listOfNotNull(File(relPath).takeIf { it.isDirectory })
        }

        bases.flatMap { dir ->
            dir.listFiles { file -> file.isFile && file.extension.lowercase() in audioExtensions }
                ?.map(::toAudioFile)
                .orEmpty()
        }.sortedBy { it.title.lowercase() }
    } catch (e: Exception) {
        logger.e(TAG, "Can't list audio of '$relPath'!", e)
        emptyList()
    }

    override fun audioFile(id: String): AudioFile? = try {
        val file = File(id)
        if (file.isFile && file.extension.lowercase() in audioExtensions)
            toAudioFile(file)
        else
            null
    } catch (e: Exception) {
        logger.e(TAG, "Can't get audio file '$id'!", e)
        null
    }

    override fun open(id: String): Pair<InputStream, Long>? = try {
        val file = File(id)
        FileInputStream(file) to file.length()
    } catch (e: Exception) {
        logger.e(TAG, "Can't open '$id'!", e)
        null
    }

    private fun toAudioFile(file: File): AudioFile {
        val fileName = file.name
        val name = fileName.substringBeforeLast('.', fileName)

        val (artists, title) = when {
            name.contains(" - ") -> {
                val artist = name.substringBefore(" - ").trim()
                val title = name.substringAfter(" - ").trim()
                listOf(artist) to title
            }

            else -> emptyList<String>() to name
        }

        return AudioFile(
            id = file.absolutePath,
            title = title,
            artists = artists,
            album = file.parentFile?.name?.takeIf { it.isNotEmpty() },
            durationMs = UNKNOWN_DURATION,
            extension = file.extension.lowercase(),
            size = file.length()
        )
    }

    private companion object {
        const val TAG = "DirectAndroidScanner"
        const val UNKNOWN_DURATION = -1L
    }
}
