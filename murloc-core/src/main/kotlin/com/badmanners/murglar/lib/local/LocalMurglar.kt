package com.badmanners.murglar.lib.local

import com.badmanners.murglar.lib.core.localization.DefaultMessages.Companion.DEFAULT
import com.badmanners.murglar.lib.core.localization.RussianMessages.Companion.RUSSIAN
import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat
import com.badmanners.murglar.lib.core.model.track.source.SourceFormat.SourceFormatAvailability.AVAILABLE
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.ActionPreference
import com.badmanners.murglar.lib.core.preference.EditPreference
import com.badmanners.murglar.lib.core.preference.Preference
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.preference.SwitchPreference
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.isAndroid
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlEncode
import com.badmanners.murglar.lib.local.fs.AndroidFolderPicker
import com.badmanners.murglar.lib.local.fs.AndroidHost
import com.badmanners.murglar.lib.local.fs.AndroidScanner
import com.badmanners.murglar.lib.local.fs.AudioFile
import com.badmanners.murglar.lib.local.fs.DesktopScanner
import com.badmanners.murglar.lib.local.fs.FileScanner
import com.badmanners.murglar.lib.local.fs.PlaylistLibrary
import com.badmanners.murglar.lib.local.fs.fileNameOf
import com.badmanners.murglar.lib.local.http.StreamServer
import com.badmanners.murglar.lib.local.localization.LocalDefaultMessages
import com.badmanners.murglar.lib.local.localization.LocalMessages
import com.badmanners.murglar.lib.local.localization.LocalRussianMessages
import com.badmanners.murglar.lib.local.login.LocalLoginResolver
import com.badmanners.murglar.lib.local.model.playlist.LocalPlaylist
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import com.badmanners.murglar.lib.local.model.track.LocalTrackFactory
import com.badmanners.murglar.lib.local.node.LocalNodeResolver
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser


/**
 * Murglar plugin that exposes local audio files as a music service.
 *
 * On Desktop files are served through `file://` urls, on Android - through `content://media/...` urls.
 * If the host player can't play such urls (or if forced by the corresponding preference),
 * files are streamed through the embedded [StreamServer] on `http://127.0.0.1:<random port>`.
 *
 * Several music folders are supported at once ([PREF_MUSIC_DIR] value is a `;` separated list).
 * On Desktop folders are absolute paths scanned through `java.nio.file`, on Android - paths relative
 * to the internal storage root (or absolute) that limit the `MediaStore` library
 * (an empty value means the whole library).
 *
 * Playlists are read from `.m3u`/`.m3u8` files: on Desktop - from the [PREF_PLAYLISTS_DIR] folder,
 * on Android - from the app-internal storage of the host app
 * (the read part of the `PlaylistsManager` plugins API is a stub in API 9.0,
 * so playlists can't be created from the host UI).
 */
class LocalMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware
) : BaseMurglar<LocalTrack, LocalMessages>(
    id, MESSAGES, preferences, network, notifications, logger
) {

    companion object {
        /**
         * Must be used only for [com.badmanners.murglar.lib.core.utils.MediaId.build],
         * don't pass it to the [BaseMurglar] constructor directly!
         */
        const val SERVICE_ID = "local"

        const val PREF_MUSIC_DIR = "music_dir"
        const val PREF_PLAYLISTS_DIR = "playlists_dir"
        const val PREF_STREAM_SERVER = "stream_server"

        private val MESSAGES = mapOf(
            DEFAULT to LocalDefaultMessages,
            RUSSIAN to LocalRussianMessages
        )

        private const val TAG = "LocalMurglar"
    }

    val isAndroidPlatform = isAndroid()

    val scanner: FileScanner by lazy {
        if (isAndroidPlatform) AndroidScanner(logger, ::rawMusicFolders) else DesktopScanner(logger, ::musicDirs)
    }

    val playlistLibrary = PlaylistLibrary(logger, ::playlistsDir) { id ->
        scanner.audioFile(id)
    }

    private val streamServer = StreamServer(logger, scanner)

    override val loginResolver = LocalLoginResolver(messages)

    override val nodeResolver = LocalNodeResolver(this, messages)

    override val murglarPreferences: List<Preference>
        get() = buildList {
            add(
                EditPreference(
                    id = "music_dir",
                    title = messages.musicFolder,
                    message = if (isAndroidPlatform) messages.musicFolderMessageAndroid else messages.musicFolderMessage,
                    getter = { preferences.getString(PREF_MUSIC_DIR, defaultMusicDir()) },
                    setter = { preferences.setString(PREF_MUSIC_DIR, it) }
                )
            )

            add(
                ActionPreference(
                    id = "pick_music_dir",
                    title = messages.pickMusicFolder,
                    summary = null,
                    action = ::pickMusicDir,
                    needConfirmation = false,
                    refreshAllAfterInvoke = true
                )
            )

            if (!isAndroidPlatform) {
                add(
                    EditPreference(
                        id = "playlists_dir",
                        title = messages.playlistsFolder,
                        message = messages.playlistsFolderMessage,
                        getter = { preferences.getString(PREF_PLAYLISTS_DIR, defaultPlaylistsDir()) },
                        setter = { preferences.setString(PREF_PLAYLISTS_DIR, it) }
                    )
                )
                add(
                    ActionPreference(
                        id = "open_playlists_dir",
                        title = messages.openPlaylistsFolder,
                        summary = null,
                        action = ::openPlaylistsFolder,
                        needConfirmation = false
                    )
                )
            }

            add(
                ActionPreference(
                    id = "create_playlist",
                    title = messages.createPlaylist,
                    summary = null,
                    action = { createPlaylist() },
                    needConfirmation = false,
                    refreshAllAfterInvoke = true
                )
            )

            add(
                SwitchPreference(
                    id = "stream_server",
                    title = messages.useStreamServer,
                    summary = messages.useStreamServerSummary,
                    getter = { preferences.getBoolean(PREF_STREAM_SERVER, true) },
                    setter = { preferences.setBoolean(PREF_STREAM_SERVER, it) }
                )
            )

            // One 'Rename' text field per playlist (the plugins API has no text input in
            // custom node actions, but EditPreferences in the settings screen do have it).
            listPlaylists().forEach { playlist ->
                val fileName = fileNameOf(playlist.id)
                add(
                    EditPreference(
                        id = "rename_playlist_$fileName",
                        title = messages.renamePlaylist(playlist.title),
                        message = messages.renamePlaylistMessage,
                        getter = { playlist.title },
                        setter = { newTitle -> renamePlaylist(fileName, playlist.title, newTitle) },
                        refreshAllAfterChange = true
                    )
                )
            }
        }

    override val possibleFormats: List<SourceFormat>
        get() = listOf(
            SourceFormat(Extension.MP3, Bitrate.B_UNKNOWN, QualityTier.HIGH_QUALITY, AVAILABLE),
            SourceFormat(Extension.FLAC, Bitrate.B_LOSSLESS, QualityTier.LOSSLESS, AVAILABLE),
            SourceFormat(Extension.OGG, Bitrate.B_UNKNOWN, QualityTier.HIGH_QUALITY, AVAILABLE),
            SourceFormat(Extension.M4A, Bitrate.B_UNKNOWN, QualityTier.HIGH_QUALITY, AVAILABLE),
            SourceFormat(Extension.WAV, Bitrate.B_LOSSLESS, QualityTier.LOSSLESS, AVAILABLE),
            SourceFormat(Extension.AAC, Bitrate.B_UNKNOWN, QualityTier.HIGH_QUALITY, AVAILABLE),
            SourceFormat(Extension.OPUS, Bitrate.B_UNKNOWN, QualityTier.HIGH_QUALITY, AVAILABLE),
            SourceFormat(Extension.WMA, Bitrate.B_UNKNOWN, QualityTier.HIGH_QUALITY, AVAILABLE),
            SourceFormat(Extension.UNKNOWN, Bitrate.B_UNKNOWN, QualityTier.HIGH_QUALITY, AVAILABLE)
        )

    override suspend fun onCreate() {
        if (!scanner.isAvailable)
            logger.e(TAG, "File scanner is not available - plugin will show an empty library!")
        if (!isAndroidPlatform && musicDirs().isEmpty())
            logger.w(TAG, "No valid music folders in '${preferences.getString(PREF_MUSIC_DIR, defaultMusicDir())}'!")
        checkPickedMusicFolder()
    }

    override suspend fun resolveSourceForUrl(track: LocalTrack, source: Source): Source {
        val forceServer = preferences.getBoolean(PREF_STREAM_SERVER, true)
        if (!forceServer) {
            val directUrl = directUrlFor(track.id)
            if (directUrl != null)
                return source.copyWithNewUrl(directUrl)
            logger.w(TAG, "No direct url for '${track.id}', falling back to the embedded stream server!")
        }

        val port = streamServer.ensureStarted()
        return source.copyWithNewUrl("http://127.0.0.1:$port/file?id=${track.id.urlEncode()}")
    }

    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<LocalTrack> = mediaIds
        .mapNotNull { mediaId -> scanner.audioFile(mediaId.removePrefix("${SERVICE_ID}_")) }
        .map { LocalTrackFactory.create(it, SERVICE_ID) }


    /**
     * Raw `;` separated entries of [PREF_MUSIC_DIR] (Android scanner normalizes them itself).
     */
    private fun rawMusicFolders(): List<String> = preferences.getString(PREF_MUSIC_DIR, defaultMusicDir())
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /**
     * Music roots, parsed from the `;` separated [PREF_MUSIC_DIR] preference (Desktop only).
     * Blank entries are skipped, non-existing directories are logged and skipped.
     */
    fun musicDirs(): List<Path> = rawMusicFolders()
        .mapNotNull { raw ->
            try {
                Path.of(raw).takeIf { Files.isDirectory(it) }
            } catch (e: Exception) {
                logger.w(TAG, "Invalid music folder '$raw', skipped!", e)
                null
            }
        }

    /**
     * Playlists dir: from [PREF_PLAYLISTS_DIR] on Desktop, app-internal storage of the host
     * app on Android (created on demand, there is no user-visible folder there).
     */
    fun playlistsDir(): File? =
        if (isAndroidPlatform) {
            AndroidHost.playlistsDir(logger)
        } else {
            val raw = preferences.getString(PREF_PLAYLISTS_DIR, defaultPlaylistsDir())
            if (raw.isBlank())
                null
            else
                try {
                    File(raw).takeIf { it.isDirectory }
                } catch (e: Exception) {
                    logger.w(TAG, "Invalid playlists folder '$raw'!", e)
                    null
                }
        }

    fun listPlaylists(): List<LocalPlaylist> =
        playlistLibrary.listPlaylists() ?: emptyList()

    /**
     * Saves all audio files directly in the directory [dir] (absolute path on Desktop,
     * MediaStore relative path on Android) as a new `.m3u` playlist named after the directory
     * (custom node action).
     *
     * @return true on success
     */
    fun saveDirectoryAsPlaylist(dir: String): Boolean {
        val audio = scanner.listAudio(dir)
        if (audio.isEmpty()) {
            logger.w(TAG, "No audio files in '$dir' - playlist not created!")
            return false
        }

        val name = fileNameOf(dir)
        val file = playlistLibrary.writePlaylist(name, audio)
        if (file != null)
            notifications.shortNotify("${messages.playlistSaved} ${file.name}")

        return file != null
    }

    /**
     * Appends the track [trackId] to the playlist [playlistFileName] (custom node action).
     *
     * @return true, if the playlist was changed
     */
    fun addTrackToPlaylist(trackId: String, playlistFileName: String): Boolean {
        if (!playlistLibrary.exists(playlistFileName)) {
            logger.w(TAG, "Playlist '$playlistFileName' doesn't exist - track '$trackId' not added!")
            return false
        }

        val audio = scanner.audioFile(trackId)
        if (audio == null) {
            logger.w(TAG, "Can't find track '$trackId' - not added to '$playlistFileName'!")
            return false
        }

        val changed = playlistLibrary.appendTracks(playlistFileName, listOf(audio))
        if (!changed)
            logger.i(TAG, "Track '$trackId' is already in '$playlistFileName', skipped.")

        return changed
    }

    /**
     * Removes the track [trackId] from the playlist [playlistFileName] (custom node action).
     *
     * @return true, if the playlist was changed
     */
    fun removeTrackFromPlaylist(trackId: String, playlistFileName: String): Boolean {
        if (!playlistLibrary.exists(playlistFileName)) {
            logger.w(TAG, "Playlist '$playlistFileName' doesn't exist - track '$trackId' not removed!")
            return false
        }

        val changed = playlistLibrary.removeTrack(playlistFileName, trackId)
        if (!changed)
            logger.i(TAG, "Track '$trackId' is not in '$playlistFileName', nothing to remove.")

        return changed
    }

    /**
     * Deletes the playlist file [fileName] from the playlists dir (custom node action).
     */
    fun deletePlaylist(fileName: String): Boolean =
        playlistLibrary.deletePlaylist(fileName)

    /**
     * Creates an empty `Новый плейлист (yyyy-MM-dd HH-mm).m3u` (used by the ActionPreference
     * from plugin settings and by the 'Create playlist' custom node actions).
     *
     * @return file name of the created playlist, or null on failure
     */
    fun createPlaylist(): String? {
        val stamp = org.threeten.bp.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")
            .format(org.threeten.bp.LocalDateTime.now())
        val file = playlistLibrary.writePlaylist("${messages.newPlaylist} ($stamp)", emptyList())
            ?: return null

        val fileName = file.name
        notifications.shortNotify("${messages.playlistCreated} $fileName")
        return fileName
    }

    /**
     * Renames the playlist [fileName] from [oldTitle] to [newTitle]
     * (used by the per-playlist 'Rename' EditPreferences in the plugin settings).
     */
    fun renamePlaylist(fileName: String, oldTitle: String, newTitle: String) {
        val target = newTitle.trim()
        if (target.isEmpty() || target == oldTitle)
            return

        if (playlistLibrary.rename(fileName, target)) {
            notifications.shortNotify("${messages.playlistRenamed} $target")
            logger.i(TAG, "Playlist '$oldTitle' renamed to '$target'.")
        } else {
            notifications.shortNotify(messages.playlistRenameFailed)
            logger.w(TAG, "Can't rename playlist '$oldTitle' to '$target'!")
        }
    }

    /**
     * Opens the playlists folder in the system file manager (Desktop only, custom node action).
     */
    fun openPlaylistsDir(): Boolean {
        if (isAndroidPlatform)
            return false

        return try {
            val desktop = java.awt.Desktop.getDesktop()
            if (!java.awt.Desktop.isDesktopSupported() || !desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                logger.w(TAG, "Opening folders in a file manager is not supported on this host!")
                return false
            }

            val raw = preferences.getString(PREF_PLAYLISTS_DIR, defaultPlaylistsDir())
            val dir = try {
                File(raw).takeIf { it.isDirectory } ?: File(raw).apply { mkdirs() }
            } catch (e: Exception) {
                logger.w(TAG, "Can't create playlists folder '$raw'!", e)
                return false
            }

            desktop.open(dir)
            true
        } catch (e: Exception) {
            logger.w(TAG, "Can't open playlists folder!", e)
            false
        }
    }

    private fun defaultMusicDir(): String =
        if (isAndroidPlatform) "" else "${System.getProperty("user.home")}/Music"

    private fun defaultPlaylistsDir(): String = "${System.getProperty("user.home")}/MurglarPlaylists"

    /**
     * Picks music folders and merges them into [PREF_MUSIC_DIR]:
     * - on Desktop - shows a Swing directory picker (with a protection from headless environments);
     * - on Android - launches the system `ACTION_OPEN_DOCUMENT_TREE` picker from the plugin apk
     *   (see [AndroidFolderPicker]) and returns immediately; the picked folder is merged into
     *   [PREF_MUSIC_DIR] on the next [checkPickedMusicFolder] call (library tree refresh).
     *
     * Used by the ActionPreference from plugin settings and by the 'Pick music folder'
     * custom node action on the My Music root.
     */
    fun pickMusicDir() {
        if (isAndroidPlatform) {
            // Never block here: the host may invoke the action on its UI thread.
            AndroidFolderPicker.launchPicker(logger)
            return
        }

        try {
            if (GraphicsEnvironment.isHeadless()) {
                logger.w(TAG, "Headless environment - can't show directory picker!")
                return
            }

            val chooser = JFileChooser()
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.isMultiSelectionEnabled = true

            val current = musicDirs().firstOrNull()
            if (current != null)
                chooser.currentDirectory = current.toFile()

            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
                return

            val merged = (rawMusicFolders() + chooser.selectedFiles.map { it.absolutePath })
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            preferences.setString(PREF_MUSIC_DIR, merged.joinToString(";"))
        } catch (e: Exception) {
            logger.w(TAG, "Can't show directory picker!", e)
        }
    }

    private fun mergeMusicFolder(picked: String) {
        val merged = (rawMusicFolders() + picked)
            .distinct()

        preferences.setString(PREF_MUSIC_DIR, merged.joinToString(";"))
    }

    /**
     * Timestamp of the last picker result seen by the plugin (results from previous
     * sessions are ignored).
     */
    private var pickerResultSeenAt = System.currentTimeMillis()

    /**
     * Merges a freshly picked music folder into [PREF_MUSIC_DIR] (Android only).
     *
     * Non-blocking by design: called when the library tree is built ([onCreate] and the
     * 'My Music' root content), so the folder picked in the system picker appears in
     * the library as soon as the user gets back to it. Never throws into the host.
     */
    fun checkPickedMusicFolder() {
        if (!isAndroidPlatform)
            return

        val result = try {
            AndroidFolderPicker.pollPickedFolder(logger, pickerResultSeenAt)
        } catch (e: Throwable) {
            logger.w(TAG, "Can't poll the folder picker result!", e)
            null
        } ?: return

        pickerResultSeenAt = result.timestamp
        if (result.path.isEmpty()) {
            logger.i(TAG, "Folder picker was cancelled.")
            return
        }

        mergeMusicFolder(result.path)
        logger.i(TAG, "Picked music folder '${result.path}' merged into '$PREF_MUSIC_DIR'.")
        try {
            notifications.shortNotify("${messages.pickMusicFolderPicked} ${result.path}")
        } catch (e: Throwable) {
            logger.w(TAG, "Can't notify about the picked folder!", e)
        }
    }

    /**
     * Opens the playlists folder in the system file manager (Desktop only).
     */
    private fun openPlaylistsFolder() {
        openPlaylistsDir()
    }

    /**
     * Url that the host player can play directly (without the embedded server), if any.
     */
    private fun directUrlFor(id: String): String? = when {
        isAndroidPlatform -> id.takeIf { it.startsWith("content://") }

        else -> try {
            val path = Path.of(id)
            when {
                !Files.isRegularFile(path) -> null
                else -> path.toAbsolutePath().normalize().toUri().toString()
            }
        } catch (e: Exception) {
            logger.e(TAG, "Can't build file url for '$id'!", e)
            null
        }
    }
}
