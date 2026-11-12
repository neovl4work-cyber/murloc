package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path


/**
 * [FileScanner] for Desktop - walks the music folders through `java.nio.file`.
 *
 * Supports several music roots at once: the root level lists the roots themselves
 * (see [hasMultipleRoots]), all deeper levels are plain directories by absolute path.
 */
class DesktopScanner(
    private val logger: LoggerMiddleware,
    private val roots: () -> List<Path>
) : FileScanner {

    private val audioExtensions = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")

    override val isAvailable = true

    override val hasMultipleRoots: Boolean
        get() = roots().size > 1

    override fun listSubdirs(relPath: String?): List<DirEntry> = try {
        // Root level with several music roots lists the roots themselves.
        if (relPath.isNullOrEmpty() && hasMultipleRoots)
            return roots().map { root -> DirEntry(root.toString(), dirName(root)) }

        val bases = when {
            relPath.isNullOrEmpty() -> roots().take(1)
            else -> listOfNotNull(base(relPath))
        }

        bases.flatMap { root ->
            Files.list(root).use { stream ->
                stream.filter { path -> Files.isDirectory(path) }
                    .map { path -> DirEntry(path.toAbsolutePath().normalize().toString(), dirName(path)) }
                    .toList()
            }
        }.sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        logger.e(TAG, "Can't list subdirs of '$relPath'!", e)
        emptyList()
    }

    override fun listAudio(relPath: String?): List<AudioFile> = try {
        val bases = when {
            relPath.isNullOrEmpty() -> roots()
            else -> listOfNotNull(base(relPath))
        }

        bases.flatMap { root ->
            Files.list(root).use { stream ->
                stream.filter { path -> Files.isRegularFile(path) && isAudio(path) }
                    .map { path -> toAudioFile(path.toAbsolutePath().normalize()) }
                    .toList()
            }
        }.sortedBy { it.title.lowercase() }
    } catch (e: Exception) {
        logger.e(TAG, "Can't list audio of '$relPath'!", e)
        emptyList()
    }

    override fun audioFile(id: String): AudioFile? = try {
        val path = Path.of(id)
        if (Files.isRegularFile(path) && isAudio(path))
            toAudioFile(path.toAbsolutePath().normalize())
        else
            null
    } catch (e: Exception) {
        logger.e(TAG, "Can't get audio file '$id'!", e)
        null
    }

    override fun open(id: String): Pair<InputStream, Long>? = try {
        val path = Path.of(id)
        Files.newInputStream(path) to Files.size(path)
    } catch (e: Exception) {
        logger.e(TAG, "Can't open '$id'!", e)
        null
    }

    private fun base(relPath: String): Path? =
        runCatching { Path.of(relPath) }.getOrNull()?.takeIf { Files.isDirectory(it) }

    private fun isAudio(path: Path): Boolean =
        fileExtension(path) in audioExtensions

    private fun toAudioFile(path: Path): AudioFile {
        val fileName = path.fileName.toString()
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
            id = path.toString(),
            title = title,
            artists = artists,
            album = path.parent?.let { dirName(it) }?.takeIf { name -> name.isNotEmpty() },
            durationMs = UNKNOWN_DURATION,
            extension = fileExtension(path),
            size = Files.size(path)
        )
    }

    private fun dirName(path: Path): String =
        path.fileName?.toString() ?: path.toString()

    private fun fileExtension(path: Path): String {
        val fileName = path.fileName.toString()
        return fileName.substringAfterLast('.', "").lowercase()
    }

    private companion object {
        const val TAG = "DesktopScanner"
        const val UNKNOWN_DURATION = -1L
    }
}
