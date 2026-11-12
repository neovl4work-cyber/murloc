package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path


/**
 * [FileScanner] for Desktop - walks the music folder through `java.nio.file`.
 */
class DesktopScanner(
    private val logger: LoggerMiddleware,
    private val musicDir: () -> Path?
) : FileScanner {

    private val audioExtensions = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")

    override val isAvailable = true

    private fun base(relPath: String?): Path? {
        val root = musicDir() ?: return null
        val base = when {
            relPath.isNullOrEmpty() -> root
            else -> root.resolve(relPath).normalize()
        }
        return base.takeIf { Files.isDirectory(base) }
    }

    override fun listSubdirs(relPath: String?): List<DirEntry> = try {
        base(relPath)?.let { base ->
            Files.list(base).use { stream ->
                stream.filter { path -> Files.isDirectory(path) }
                    .map { path -> DirEntry(path.toRelativeString(), path.fileName.toString()) }
                    .sorted(compareBy { it.name.lowercase() })
                    .toList()
            }
        } ?: emptyList()
    } catch (e: Exception) {
        logger.e(TAG, "Can't list subdirs of '$relPath'!", e)
        emptyList()
    }

    override fun listAudio(relPath: String?): List<AudioFile> = try {
        base(relPath)?.let { base ->
            Files.list(base).use { stream ->
                stream.filter { path -> Files.isRegularFile(path) && isAudio(path) }
                    .map { path -> toAudioFile(path, relPath) }
                    .sorted(compareBy { it.title.lowercase() })
                    .toList()
            }
        } ?: emptyList()
    } catch (e: Exception) {
        logger.e(TAG, "Can't list audio of '$relPath'!", e)
        emptyList()
    }

    override fun audioFile(id: String): AudioFile? = try {
        val path = Path.of(id)
        if (Files.isRegularFile(path) && isAudio(path))
            toAudioFile(path.toAbsolutePath().normalize(), path.parent?.toRelativeString())
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

    private fun isAudio(path: Path): Boolean =
        fileExtension(path) in audioExtensions

    private fun toAudioFile(path: Path, parentRel: String?): AudioFile {
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
            id = path.toAbsolutePath().normalize().toString(),
            title = title,
            artists = artists,
            album = parentRel?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')?.takeIf { it.isNotEmpty() },
            durationMs = UNKNOWN_DURATION,
            extension = fileExtension(path),
            size = Files.size(path)
        )
    }

    private fun fileExtension(path: Path): String {
        val fileName = path.fileName.toString()
        return fileName.substringAfterLast('.', "").lowercase()
    }

    private fun Path.toRelativeString(): String {
        val root = musicDir()?.toAbsolutePath()?.normalize() ?: return toString()
        val target = toAbsolutePath().normalize()
        return root.relativize(target).toString().replace('\\', '/')
    }

    private companion object {
        const val TAG = "DesktopScanner"
        const val UNKNOWN_DURATION = -1L
    }
}
