package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.local.LocalMurglar
import com.badmanners.murglar.lib.local.model.playlist.LocalPlaylist
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import com.badmanners.murglar.lib.local.model.track.LocalTrackFactory
import java.io.File


/**
 * Library of `.m3u`/`.m3u8` playlists (non-recursive).
 *
 * Playlist files are re-read on every request (they are small) - no caching.
 *
 * Implemented on `java.io.File` (not `java.nio.file`), because `java.nio.file` is not
 * available on Android before API 26, while the plugin works since API 21.
 * On Desktop the playlists dir comes from the `playlists_dir` preference,
 * on Android - from the app-internal storage (see [com.badmanners.murglar.lib.local.LocalMurglar]).
 *
 * Entries are stored as paths relative to the playlist file (Desktop) or as SAF document
 * URIs (Android); [uriResolver] resolves them back to tracks through the platform [FileScanner].
 */
class PlaylistLibrary(
    private val logger: LoggerMiddleware,
    private val dir: () -> File?,
    private val uriResolver: (String) -> AudioFile? = { null }
) {

    private val audioExtensions = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")

    private val illegalFileNameChars = Regex("[\\\\/:*?\"<>|]")

    /**
     * Lists all playlists directly in the playlists dir (null, if the dir is not configured/created yet).
     */
    fun listPlaylists(): List<LocalPlaylist>? = playlistsDir()?.let { dir ->
        try {
            dir.listFiles { file -> file.isFile && isPlaylist(file) }
                ?.map { file ->
                    LocalPlaylist(
                        id = file.absolutePath,
                        title = file.name.substringBeforeLast('.', ""),
                        tracksCount = parse(file).size
                    )
                }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LocalPlaylist::title))
                .orEmpty()
        } catch (e: Exception) {
            logger.e(TAG, "Can't list playlists in '$dir'!", e)
            emptyList()
        }
    }

    /**
     * Parses the playlist file with passed absolute path into tracks, preserving the file order.
     */
    fun playlistTracks(playlistId: String): List<LocalTrack> {
        val file = runCatching { File(playlistId) }.getOrNull()
            ?.takeIf { it.isFile && isPlaylist(it) }
            ?: return emptyList()

        return parse(file)
            .map { LocalTrackFactory.create(it, LocalMurglar.SERVICE_ID) }
    }


    //region Playlist file editing (used by custom node actions and the 'Rename' preferences)

    /**
     * (title, fileName) pairs of all playlists, or null if the playlists dir doesn't exist.
     */
    fun playlistFileNames(): List<Pair<String, String>>? =
        listPlaylists()?.map { it.title to fileNameOf(it.id) }

    fun exists(fileName: String): Boolean =
        playlistFile(fileName)?.let { it.isFile } ?: false

    /**
     * Overwrites/creates `<playlists_dir>/<sanitized name>.m3u` with the passed entries.
     *
     * @return created file, or null on failure
     */
    fun writePlaylist(name: String, entries: List<AudioFile>): File? = try {
        val dir = playlistsDir() ?: return null
        val target = File(dir, sanitizeFileName(name) + ".m3u")
        val separator = System.lineSeparator()
        target.writeText(toLines(entries).joinToString(separator, postfix = separator), Charsets.UTF_8)
        target
    } catch (e: Exception) {
        logger.e(TAG, "Can't write playlist '$name'!", e)
        null
    }

    /**
     * Renames the playlist [fileName] to [newTitle] (the file extension is preserved).
     *
     * @return true on success, false if the playlist is missing or a playlist with
     * the target name already exists
     */
    fun rename(fileName: String, newTitle: String): Boolean = try {
        val source = playlistFile(fileName) ?: return false
        if (!source.isFile)
            return false

        val target = File(source.parentFile, sanitizeFileName(newTitle) + ".m3u")
        when {
            source.name == target.name -> true
            target.exists() -> false
            else -> source.renameTo(target)
        }
    } catch (e: Exception) {
        logger.e(TAG, "Can't rename playlist '$fileName' to '$newTitle'!", e)
        false
    }

    /**
     * Appends tracks to the playlist [fileName], skipping ones that are already there.
     *
     * @return true, if the file was changed
     */
    fun appendTracks(fileName: String, tracks: List<AudioFile>): Boolean {
        val target = playlistFile(fileName) ?: return false
        if (!target.isFile)
            return false

        val existing = parse(target)
        val knownIds = existing.map { it.id }.toHashSet()
        val newTracks = tracks.filter { it.id !in knownIds }
        if (newTracks.isEmpty())
            return false

        val stem = target.name.substringBeforeLast('.', "")
        return writePlaylist(stem, existing + newTracks) != null
    }

    fun deletePlaylist(fileName: String): Boolean = try {
        playlistFile(fileName)?.delete() ?: false
    } catch (e: Exception) {
        logger.e(TAG, "Can't delete playlist '$fileName'!", e)
        false
    }

    /**
     * Removes the track with passed id (absolute file path or `content://` uri)
     * from the playlist [fileName].
     *
     * @return true, if the file was changed
     */
    fun removeTrack(fileName: String, trackId: String): Boolean {
        val target = playlistFile(fileName) ?: return false
        if (!target.isFile)
            return false

        val existing = parse(target)
        val remaining = existing.filterNot { it.id == trackId }
        if (remaining.size == existing.size)
            return false

        val stem = target.name.substringBeforeLast('.', "")
        return writePlaylist(stem, remaining) != null
    }

    private fun playlistFile(fileName: String): File? {
        val dir = playlistsDir() ?: return null
        return File(dir, sanitizeFileName(fileNameOf(fileName)))
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
            add(relativeOrAbsolute(audio.id))
        }
    }

    private fun relativeOrAbsolute(id: String): String {
        // Android track ids are content uris - they can't be relativized, stored as is.
        if (id.contains("://"))
            return id

        val dir = playlistsDir() ?: return id
        val absolute = File(id).absoluteFile.normalize().path
        val base = dir.absoluteFile.normalize().path + File.separator
        return when {
            // '/' separators on all platforms - our parser accepts both.
            absolute.startsWith(base) -> absolute.removePrefix(base).replace('\\', '/')
            absolute.startsWith(base.replace('\\', '/')) ->
                absolute.removePrefix(base.replace('\\', '/')).replace('\\', '/')
            else -> absolute
        }
    }

    //endregion


    private fun playlistsDir(): File? =
        dir()?.takeIf { it.isDirectory }

    private fun isPlaylist(file: File): Boolean =
        file.extension.lowercase() in setOf("m3u", "m3u8")

    private fun parse(playlist: File): List<AudioFile> {
        val lines = try {
            playlist.readLines(Charsets.UTF_8)
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
        playlist: File,
        rawPath: String,
        durationSec: Long,
        extinfTitle: String?
    ): AudioFile? {
        // Android entries are SAF document uris owned by the plugin's persisted grant.
        if (rawPath.contains("://"))
            return uriResolver(rawPath)?.takeIf { it.extension in audioExtensions }

        val file = runCatching { File(rawPath) }.getOrNull() ?: return null
        val full = when {
            file.isAbsolute -> file
            else -> File(playlist.parentFile, rawPath)
        }.normalize()

        if (!full.isFile)
            return null

        val extension = full.extension.lowercase()
        if (extension !in audioExtensions)
            return null

        val fileName = full.name
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
            id = full.absolutePath,
            title = title,
            artists = artists,
            album = full.parentFile?.name?.takeIf { it.isNotEmpty() },
            durationMs = durationSec * 1000,
            extension = extension,
            size = runCatching { full.length() }.getOrElse { Source.UNKNOWN_SIZE }
        )
    }

    private companion object {
        const val TAG = "PlaylistLibrary"
    }
}
