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
import com.badmanners.murglar.lib.local.http.StreamServer
import com.badmanners.murglar.lib.local.localization.LocalDefaultMessages
import com.badmanners.murglar.lib.local.localization.LocalMessages
import com.badmanners.murglar.lib.local.localization.LocalRussianMessages
import com.badmanners.murglar.lib.local.login.LocalLoginResolver
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import com.badmanners.murglar.lib.local.model.track.LocalTrackFactory
import com.badmanners.murglar.lib.local.node.LocalNodeResolver
import java.nio.file.Path


/**
 * Murglar plugin that exposes local audio files as a music service.
 *
 * On Desktop files are served through `file://` urls, on Android - through `content://media/...` urls.
 * If the host player can't play such urls (or if forced by the corresponding preference),
 * files are streamed through the embedded [StreamServer] on `http://127.0.0.1:<random port>`.
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
        const val PREF_STREAM_SERVER = "stream_server"

        private val MESSAGES = mapOf(
            DEFAULT to LocalDefaultMessages,
            RUSSIAN to LocalRussianMessages
        )

        private const val TAG = "LocalMurglar"
    }

    private val android = isAndroid()

    val scanner: FileScanner by lazy {
        if (android) AndroidScanner(logger) else DesktopScanner(logger, ::musicDir)
    }

    private val streamServer = StreamServer(logger, scanner)

    override val loginResolver = LocalLoginResolver(messages)

    override val nodeResolver = LocalNodeResolver(this, messages)

    override val murglarPreferences: List<Preference>
        get() = listOf(
            EditPreference(
                id = "music_dir",
                title = messages.musicFolder,
                message = messages.musicFolderMessage,
                getter = { preferences.getString(PREF_MUSIC_DIR, defaultMusicDir()) },
                setter = { preferences.setString(PREF_MUSIC_DIR, it) }
            ),
            SwitchPreference(
                id = "stream_server",
                title = messages.useStreamServer,
                summary = messages.useStreamServerSummary,
                getter = { preferences.getBoolean(PREF_STREAM_SERVER, true) },
                setter = { preferences.setBoolean(PREF_STREAM_SERVER, it) }
            )
        )

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
        if (musicDir() == null && !android)
            logger.w(TAG, "'${preferences.getString(PREF_MUSIC_DIR, defaultMusicDir())}' is not an existing directory!")
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


    fun musicDir(): Path? {
        val raw = preferences.getString(PREF_MUSIC_DIR, defaultMusicDir())
        if (raw.isBlank())
            return null

        return try {
            Path.of(raw).takeIf { java.nio.file.Files.isDirectory(it) }
        } catch (e: Exception) {
            logger.e(TAG, "Invalid music dir '$raw'!", e)
            null
        }
    }

    private fun defaultMusicDir(): String =
        if (android) "" else "${System.getProperty("user.home")}/Music"

    /**
     * Url that the host player can play directly (without the embedded server), if any.
     */
    private fun directUrlFor(id: String): String? = when {
        android -> id.takeIf { it.startsWith("content://") }

        else -> try {
            val path = Path.of(id)
            when {
                !java.nio.file.Files.isRegularFile(path) -> null
                else -> path.toAbsolutePath().normalize().toUri().toString()
            }
        } catch (e: Exception) {
            logger.e(TAG, "Can't build file url for '$id'!", e)
            null
        }
    }
}
