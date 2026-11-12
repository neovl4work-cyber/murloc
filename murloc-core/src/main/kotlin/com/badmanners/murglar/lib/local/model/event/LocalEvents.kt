package com.badmanners.murglar.lib.local.model.event

import com.badmanners.murglar.lib.core.model.event.CustomAction
import com.badmanners.murglar.lib.core.utils.contract.Model


/**
 * Custom node actions of the plugin (see [com.badmanners.murglar.lib.core.node.NodeResolver.getNodeCustomActions]
 * and `handleEvent`).
 *
 * All actions are Desktop-only - they are neither shown nor executed on Android.
 */

/**
 * Saves all audio files directly in the directory [dir] (absolute path) as an `.m3u` playlist.
 */
@Model
data class SaveDirectoryAsPlaylist(
    val dir: String
) : CustomAction

/**
 * Appends the track [trackId] (absolute file path) to the target playlist from plugin preferences.
 */
@Model
data class AddTrackToPlaylist(
    val trackId: String
) : CustomAction

/**
 * Deletes the playlist file [playlistId] (file name in the playlists dir).
 */
@Model
data class DeletePlaylist(
    val playlistId: String
) : CustomAction

/**
 * Opens the playlists folder in the system file manager.
 */
object OpenPlaylistsFolder : CustomAction {
    private fun readResolve(): Any = OpenPlaylistsFolder
}
