package com.badmanners.murglar.lib.local.model.event

import com.badmanners.murglar.lib.core.model.event.CustomAction
import com.badmanners.murglar.lib.core.utils.contract.Model


/**
 * Custom node actions of the plugin (see [com.badmanners.murglar.lib.core.node.NodeResolver.getNodeCustomActions]
 * and `handleEvent`).
 *
 * Rename is not here on purpose: the custom action mechanics can't ask the user for a text,
 * renaming is done through the per-playlist 'Rename' EditPreferences in the plugin settings
 * (see [com.badmanners.murglar.lib.local.LocalMurglar]).
 */

/**
 * Saves all audio files directly in the directory [dir] (absolute path) as an `.m3u` playlist.
 */
@Model
data class SaveDirectoryAsPlaylist(
    val dir: String
) : CustomAction

/**
 * Appends the track [trackId] (absolute file path) to the playlist [playlistFileName].
 */
@Model
data class AddTrackToPlaylist(
    val trackId: String,
    val playlistFileName: String
) : CustomAction

/**
 * Removes the track [trackId] (absolute file path) from the playlist [playlistFileName].
 */
@Model
data class RemoveTrackFromPlaylist(
    val trackId: String,
    val playlistFileName: String
) : CustomAction

/**
 * Deletes the playlist file [playlistId] (file name in the playlists dir).
 */
@Model
data class DeletePlaylist(
    val playlistId: String
) : CustomAction

/**
 * Creates an empty `Новый плейлист (yyyy-MM-dd HH-mm).m3u` in the playlists dir.
 */
object CreateEmptyPlaylist : CustomAction {
    private fun readResolve(): Any = CreateEmptyPlaylist
}

/**
 * Opens the system folder picker (Swing chooser on Desktop, `ACTION_OPEN_DOCUMENT_TREE`
 * on Android) and merges the selected folders into the `music_dir` preference.
 */
object PickMusicFolder : CustomAction {
    private fun readResolve(): Any = PickMusicFolder
}

/**
 * Opens the playlists folder in the system file manager (Desktop only).
 */
object OpenPlaylistsFolder : CustomAction {
    private fun readResolve(): Any = OpenPlaylistsFolder
}
