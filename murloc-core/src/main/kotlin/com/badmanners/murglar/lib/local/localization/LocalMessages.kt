package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.Messages


interface LocalMessages : Messages {
    val myMusic: String
    val playlistRoot: String
    val musicFolder: String
    val musicFolderMessage: String
    val pickMusicFolder: String
    val playlistsFolder: String
    val playlistsFolderMessage: String
    val openPlaylistsFolder: String
    val useStreamServer: String
    val useStreamServerSummary: String
    val loginNotRequired: String
    val folderNotFound: String
}
