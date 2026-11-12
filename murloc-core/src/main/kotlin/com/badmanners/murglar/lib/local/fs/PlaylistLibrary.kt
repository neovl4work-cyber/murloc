package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.local.LocalMurglar
import com.badmanners.murglar.lib.local.model.playlist.LocalPlaylist
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import com.badmanners.murglar.lib.local.model.track.LocalTrackFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator


/**
 * Library of `.m3u`/`.m3u8` playlists (Desktop only, non-recursive).
 *
 * Playlist files are re-read on every request (they are small) - no caching.
 */
class PlaylistLibrary(
    private val logger: LoggerMiddleware,
    private val dir: () -> Path?
) {

    private val audioExtensions = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")

    /**
     * Lists all playlists directly in the playlists dir (null, if the dir is not configured/created yet).
     */
    fun listPlaylists(): List<LocalPlaylist>? = playlistsDir()?.let { dir ->
        try {
            Files.list(dir).use { stream ->
                stream.filter { path -> Files.isRegularFile(path) && isPlaylist(path) }
                    .map { path ->
                        val file = path.toAbsolutePath().normalize()
                        LocalPlaylist(
                            id = file.toString(),
                            title = file.fileName.toString().substringBeforeLast('.', ""),
                            tracksCount = parse(file).size
                        )
                    }
                    .sorted(Comparator.comparing(LocalPlaylist::title, String.CASE_INSENSITIVE_ORDER))
                    .toList()
            }
        } catch (e: Exception) {
            logger.e(TAG, "Can't list playlists in '$dir'!", e)
            emptyList()
        }
    }

    /**
     * Parses the playlist file with passed absolute path into tracks, preserving the file order.
     */
    fun playlistTracks(playlistId: String): List<LocalTrack> {
        val file = runCatching { Path.of(playlistId) }.getOrNull()
            ?.takeIf { Files.isRegularFile(it) && isPlaylist(it) }
            ?: return emptyList()

        return parse(file.toAbsolutePath().normalize())
            .map { LocalTrackFactory.create(it, LocalMurglar.SERVICE_ID) }
    }

    private fun playlistsDir(): Path? =
        dir()?.takeIf { Files.isDirectory(it) }

    private fun isPlaylist(path: Path): Boolean =
        fileExtension(path) in setOf("m3u", "m3u8")

    private fun parse(playlist: Path): List<AudioFile> {
        val lines = try {
            Files.readAllLines(playlist)
        } catch (e: Exception) {
            logger.e(TAG, "Can't read playlist '$playlist'!", e)
            return emptyList()
        }

        val audio = mutableListOf<AudioFile>()

        var pendingDurationSec = -1L
        var pendingTitle: String? = null

        fun flushEntry(rawPath: String) {
            val track = resolveEntry(playlist, rawPath, pendingDurationSec, pendingTitle) ?: return
            audio += track
            pendingDurationSec = -1
            pendingTitle = null
        }

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Unit

                trimmed.startsWith("#EXTINF") -> {
                    val params = trimmed.removePrefix("#EXTINF").removePrefix(":")
                    val duration = params.substringBefore(',').toLongOrNull() ?: -1
                    pendingDurationSec = duration
                    pendingTitle = params.substringAfter(',', "").takeIf { it.isNotEmpty() }
                }

                trimmed.startsWith("#") -> Unit

                else -> flushEntry(trimmed)
            }
        }

        return audio
    }

    private fun resolveEntry(
        playlist: Path,
        rawPath: String,
        durationSec: Long,
        extinfTitle: String?
    ): AudioFile? {
        // Only local files are supported.
        if (rawPath.contains("://"))
            return null

        val path = runCatching { Path.of(rawPath) }.getOrNull() ?: return null
        val full = when {
            path.isAbsolute -> path
            else -> playlist.parent.resolve(path)
        }.normalize()

        if (!Files.isRegularFile(full))
            return null

        val extension = fileExtension(full)
        if (extension !in audioExtensions)
            return null

        val fileName = full.fileName.toString()
        val name = fileName.substringBeforeLast('.', fileName)

        val (artists, title) = when {
            // #EXTINF title wins over the file name, same "Artist - Title" parsing applies.
            !extinfTitle.isNullOrEmpty() && extinfTitle.contains(" - ") ->
                listOf(extinfTitle.substringBefore(" - ").trim()) to extinfTitle.substringAfter(" - ").trim()

            !extinfTitle.isNullOrEmpty() -> emptyList<String>() to extinfTitle
            name.contains(" - ") ->
                listOf(name.substringBefore(" - ").trim()) to name.substringAfter(" - ").trim()

            else -> emptyList<String>() to name
        }

        return AudioFile(
            id = full.toAbsolutePath().normalize().toString(),
            title = title,
            artists = artists,
            album = full.parent?.fileName?.toString()?.takeIf { it.isNotEmpty() },
            durationMs = durationSec * 1000,
            extension = extension,
            size = runCatching { Files.size(full) }.getOrElse { Source.UNKNOWN_SIZE }
        )
    }

    private fun fileExtension(path: Path): String {
        val fileName = path.fileName.toString()
        return fileName.substringAfterLast('.', "").lowercase()
    }

    private companion object {
        const val TAG = "PlaylistLibrary"
    }
}
