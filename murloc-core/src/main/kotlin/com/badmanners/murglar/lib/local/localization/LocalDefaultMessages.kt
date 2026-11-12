package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.DefaultMessages


object LocalDefaultMessages : DefaultMessages(), LocalMessages {
    override val serviceName = "Local Files"
    override val myMusic = "My Music"
    override val musicFolder = "Music folders"
    override val musicFolderMessage = "Absolute paths to music folders, separated by semicolons"
    override val pickMusicFolder = "Choose music folder…"
    override val playlistsFolder = "Playlists folder"
    override val playlistsFolderMessage = "Absolute path to the folder with .m3u/.m3u8 playlists"
    override val openPlaylistsFolder = "Open playlists folder"
    override val useStreamServer = "Use embedded stream server"
    override val useStreamServerSummary =
        "Always stream files through the built-in http server (enable if direct file playback doesn't work)"
    override val loginNotRequired = "Login is not required"
    override val folderNotFound = "Music folder not found"
}
