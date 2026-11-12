package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.DefaultMessages


object LocalDefaultMessages : DefaultMessages(), LocalMessages {
    override val serviceName = "Local Files"
    override val myMusic = "My Music"
    override val playlistRoot = "Playlists"
    override val musicFolder = "Music folders"
    override val musicFolderMessage = "Absolute paths to music folders, separated by semicolons"
    override val musicFolderMessageAndroid =
        "Absolute paths, e.g. /storage/emulated/0/Music (empty = the whole storage; with all-files access folders are also picked from the menu of any folder)"
    override val pickMusicFolder = "Choose music folder…"
    override val allFilesAccess = "All-files access for the player (opens system settings)"
    override val useAsMusicFolder = "Use as music folder"
    override val musicFolderSet = "Music folder:"
    override val internalStorage = "Internal storage"
    override val playlistsFolder = "Playlists folder"
    override val playlistsFolderMessage = "Absolute path to the folder with .m3u/.m3u8 playlists"
    override val openPlaylistsFolder = "Open playlists folder"
    override val saveDirAsPlaylist = "Save as playlist"
    override val removeFromPlaylist = "Remove from playlist"
    override val deletePlaylist = "Delete playlist"
    override val createPlaylist = "Create playlist"
    override val newPlaylist = "New playlist"
    override val playlistSaved = "Playlist saved:"
    override val playlistCreated = "Playlist created:"

    override fun addToPlaylist(playlistName: String) = "Add to $playlistName"
    override fun renamePlaylist(playlistName: String) = "Rename \"$playlistName\""
    override val renamePlaylistMessage = "Enter a new playlist name"
    override val playlistRenamed = "Playlist renamed:"
    override val playlistRenameFailed = "Can't rename - a playlist with this name already exists"
    override val useStreamServer = "Use embedded stream server"
    override val useStreamServerSummary =
        "Always stream files through the built-in http server (enable if direct file playback doesn't work)"
    override val loginNotRequired = "Login is not required"
    override val folderNotFound = "Music folder not found"
}
