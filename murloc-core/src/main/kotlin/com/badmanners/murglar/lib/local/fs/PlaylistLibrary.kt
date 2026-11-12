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

    private val illegalFileNameChars = Regex("[\\\\/:*?\"<>|]")

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


    //region Playlist file editing (Desktop only, used by custom node actions)

    /**
     * (title, fileName) pairs of all playlists, or null if the playlists dir doesn't exist.
     */
    fun playlistFileNames(): List<Pair<String, String>>? =
        listPlaylists()?.map { it.title to Path.of(it.id).fileName.toString() }

    fun exists(fileName: String): Boolean =
        playlistFile(fileName)?.let { Files.isRegularFile(it) } ?: false

    /**
     * Overwrites/creates `<playlists_dir>/<sanitized name>.m3u` with the passed entries.
     *
     * @return created file, or null on failure
     */
    fun writePlaylist(name: String, entries: List<AudioFile>): Path? = try {
        val dir = playlistsDir() ?: return null
        val target = dir.resolve(sanitizeFileName(name) + ".m3u")
        Files.write(target, toLines(entries), Charsets.UTF_8)
        target
    } catch (e: Exception) {
        logger.e(TAG, "Can't write playlist '$name'!", e)
        null
    }

    /**
     * Appends tracks to the playlist [fileName], skipping ones that are already there.
     *
     * @return true, if the file was changed
     */
    fun appendTracks(fileName: String, tracks: List<AudioFile>): Boolean {
        val target = playlistFile(fileName) ?: return false
        if (!Files.isRegularFile(target))
            return false

        val existing = parse(target.toAbsolutePath().normalize())
        val knownIds = existing.map { it.id }.toHashSet()
        val newTracks = tracks.filter { it.id !in knownIds }
        if (newTracks.isEmpty())
            return false

        val stem = target.fileName.toString().substringBeforeLast('.', "")
        return writePlaylist(stem, existing + newTracks) != null
    }

    fun deletePlaylist(fileName: String): Boolean = try {
        val target = playlistFile(fileName) ?: return false
        Files.deleteIfExists(target)
    } catch (e: Exception) {
        logger.e(TAG, "Can't delete playlist '$fileName'!", e)
        false
    }

    /**
     * Removes the track with passed absolute path from the playlist [fileName].
     *
     * @return true, if the file was changed
     */
    fun removeTrack(fileName: String, trackId: String): Boolean {
        val target = playlistFile(fileName) ?: return false
        if (!Files.isRegularFile(target))
            return false

        val existing = parse(target.toAbsolutePath().normalize())
        val remaining = existing.filterNot { it.id == trackId }
        if (remaining.size == existing.size)
            return false

        val stem = target.fileName.toString().substringBeforeLast('.', "")
        return writePlaylist(stem, remaining) != null
    }

    private fun playlistFile(fileName: String): Path? {
        val dir = playlistsDir() ?: return null
        val plainName = runCatching { Path.of(fileName).fileName?.toString() }.getOrNull() ?: fileName
        return dir.resolve(sanitizeFileName(plainName))
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(illegalFileNameChars, "_").trim().ifEmpty { "playlist" }

    private fun toLines(entries: List<AudioFile>): List<String> = buildList {
        add("#EXTM3U")
        entries.forEach { audio ->
            val durationSec = if (audio.durationMs > 0) audio.durationMs / 1000 else -1
            val fullTitle = when {
                audio.artists.isNotEmpty() -> "${audio.artists.joinToString(", ")} - ${audio.title}"
                else -> audio.title
            }
            add("#EXTINF:$durationSec,$fullTitle")
            add(relativeOrAbsolute(Path.of(audio.id)))
        }
    }

    private fun relativeOrAbsolute(path: Path): String {
        val dir = playlistsDir() ?: return path.toString()
        val absolute = path.toAbsolutePath().normalize()
        return try {
            // '/' separators on all platforms - our parser accepts both.
            dir.relativize(absolute).toString().replace('\\', '/')
        } catch (e: IllegalArgumentException) {
            // Different file system roots (e.g. different drives on Windows).
            absolute.toString()
        }
    }

    //endregion


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
