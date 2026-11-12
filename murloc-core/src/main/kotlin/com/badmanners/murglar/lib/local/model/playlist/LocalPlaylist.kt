package com.badmanners.murglar.lib.local.model.playlist

import com.badmanners.murglar.lib.core.model.playlist.BasePlaylist
import com.badmanners.murglar.lib.core.utils.contract.Model


/**
 * Playlist of local audio files, backed by a `.m3u`/`.m3u8` file.
 *
 * [id] is the absolute path of the playlist file.
 */
@Model
class LocalPlaylist(
    id: String,
    title: String,
    tracksCount: Int
) : BasePlaylist(
    id = id,
    title = title,
    description = null,
    tracksCount = tracksCount
)
