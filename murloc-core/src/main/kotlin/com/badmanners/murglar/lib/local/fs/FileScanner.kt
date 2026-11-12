package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.model.track.source.Source
import java.io.InputStream


/**
 * Subdirectory of the music root.
 *
 * @property relPath path of the directory relative to the music root, '/' separated
 * @property name    last path component, displayed to user
 */
data class DirEntry(
    val relPath: String,
    val name: String
)


/**
 * Audio file found by [FileScanner], before conversion to a plugin track model.
 *
 * @property id canonical file path on Desktop or `content://media/...` uri on Android
 */
data class AudioFile(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val extension: String,
    val size: Long = Source.UNKNOWN_SIZE
)


/**
 * Source of local audio files for the plugin.
 */
interface FileScanner {

    /**
     * True, if the scanner can serve requests on the current platform.
     */
    val isAvailable: Boolean

    /**
     * Lists direct subdirectories of the directory with passed relative path
     * (null or empty - of the music root).
     */
    fun listSubdirs(relPath: String?): List<DirEntry>

    /**
     * Lists audio files directly in the directory with passed relative path
     * (null or empty - the music root itself).
     */
    fun listAudio(relPath: String?): List<AudioFile>

    /**
     * Returns [AudioFile] for the file with passed [AudioFile.id], if it still exists.
     */
    fun audioFile(id: String): AudioFile?

    /**
     * Opens an audio stream for the file with passed [AudioFile.id] (for the embedded stream server).
     *
     * @return input stream with total content length in bytes (or [Source.UNKNOWN_SIZE] if unknown)
     */
    fun open(id: String): Pair<InputStream, Long>?
}
