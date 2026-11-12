package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.Messages


interface LocalMessages : Messages {
    val myMusic: String
    val musicFolder: String
    val musicFolderMessage: String
    val useStreamServer: String
    val useStreamServerSummary: String
    val loginNotRequired: String
    val folderNotFound: String
}
