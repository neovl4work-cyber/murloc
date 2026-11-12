package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.DefaultMessages


object LocalDefaultMessages : DefaultMessages(), LocalMessages {
    override val serviceName = "Local Files"
    override val myMusic = "My Music"
    override val musicFolder = "Music folder"
    override val musicFolderMessage = "Absolute path to the folder with your music files"
    override val useStreamServer = "Use embedded stream server"
    override val useStreamServerSummary =
        "Always stream files through the built-in http server (enable if direct file playback doesn't work)"
    override val loginNotRequired = "Login is not required"
    override val folderNotFound = "Music folder not found"
}
