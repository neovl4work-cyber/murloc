package com.badmanners.murglar.lib.local

import com.badmanners.murglar.lib.core.localization.DefaultMessages.Companion.DEFAULT
import com.badmanners.murglar.lib.core.localization.RussianMessages.Companion.RUSSIAN
import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
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
import com.badmanners.murglar.lib.local.fs.AndroidScanner
import com.badmanners.murglar.lib.local.fs.DesktopScanner
import com.badmanners.murglar.lib.local.fs.FileScanner
import com.badmanners.murglar.lib.local.fs.PlaylistLibrary
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
 * Several music folders are supported at once ([PREF_MUSIC_DIR] value is a `;` separated list),
 * playlists are read from `.m3u`/`.m3u8` files in [PREF_PLAYLISTS_DIR]
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
        if (isAndroidPlatform) AndroidScanner(logger) else DesktopScanner(logger, ::musicDirs)
    }

    val playlistLibrary = PlaylistLibrary(logger, ::playlistsDir)

    private val streamServer = StreamServer(logger, scanner)

    override val loginResolver = LocalLoginResolver(messages)

    override val nodeResolver = LocalNodeResolver(this, messages)

    override val murglarPreferences: List<Preference>
        get() = buildList {
            add(
                EditPreference(
                    id = "music_dir",
                    title = messages.musicFolder,
                    message = messages.musicFolderMessage,
                    getter = { preferences.getString(PREF_MUSIC_DIR, defaultMusicDir()) },
                    setter = { preferences.setString(PREF_MUSIC_DIR, it) }
                )
            )

            if (!isAndroidPlatform) {
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
                SwitchPreference(
                    id = "stream_server",
                    title = messages.useStreamServer,
                    summary = messages.useStreamServerSummary,
                    getter = { preferences.getBoolean(PREF_STREAM_SERVER, true) },
                    setter = { preferences.setBoolean(PREF_STREAM_SERVER, it) }
                )
            )
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
     * Music roots, parsed from the `;` separated [PREF_MUSIC_DIR] preference.
     * Blank entries are skipped, non-existing directories are logged and skipped.
     */
    fun musicDirs(): List<Path> = preferences.getString(PREF_MUSIC_DIR, defaultMusicDir())
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { raw ->
            try {
                Path.of(raw).takeIf { Files.isDirectory(it) }
            } catch (e: Exception) {
                logger.w(TAG, "Invalid music folder '$raw', skipped!", e)
                null
            }
        }

    /**
     * Playlists dir from [PREF_PLAYLISTS_DIR], if it exists.
     */
    fun playlistsDir(): Path? {
        val raw = preferences.getString(PREF_PLAYLISTS_DIR, defaultPlaylistsDir())
        if (raw.isBlank())
            return null

        return try {
            Path.of(raw).takeIf { Files.isDirectory(it) }
        } catch (e: Exception) {
            logger.w(TAG, "Invalid playlists folder '$raw'!", e)
            null
        }
    }

    fun listPlaylists(): List<LocalPlaylist> =
        if (isAndroidPlatform) emptyList() else playlistLibrary.listPlaylists() ?: emptyList()

    private fun defaultMusicDir(): String =
        if (isAndroidPlatform) "" else "${System.getProperty("user.home")}/Music"

    private fun defaultPlaylistsDir(): String = "${System.getProperty("user.home")}/MurglarPlaylists"

    /**
     * Shows a Swing directory picker and merges the selected folders into [PREF_MUSIC_DIR]
     * (Desktop only; silently does nothing in headless environments).
     */
    private fun pickMusicDir() {
        if (isAndroidPlatform)
            return

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

            val existing = preferences.getString(PREF_MUSIC_DIR, defaultMusicDir())
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val merged = (existing + chooser.selectedFiles.map { it.absolutePath })
                .distinct()

            preferences.setString(PREF_MUSIC_DIR, merged.joinToString(";"))
        } catch (e: Exception) {
            logger.w(TAG, "Can't show directory picker!", e)
        }
    }

    /**
     * Opens the playlists folder in the system file manager (Desktop only).
     */
    private fun openPlaylistsFolder() {
        if (isAndroidPlatform)
            return

        try {
            val desktop = java.awt.Desktop.getDesktop()
            if (!java.awt.Desktop.isDesktopSupported() || !desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                logger.w(TAG, "Opening folders in a file manager is not supported on this host!")
                return
            }

            val raw = preferences.getString(PREF_PLAYLISTS_DIR, defaultPlaylistsDir())
            val dir = try {
                Path.of(raw).takeIf { Files.isDirectory(it) } ?: Files.createDirectories(Path.of(raw))
            } catch (e: Exception) {
                logger.w(TAG, "Can't create playlists folder '$raw'!", e)
                return
            }

            desktop.open(dir.toFile())
        } catch (e: Exception) {
            logger.w(TAG, "Can't open playlists folder!", e)
        }
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
