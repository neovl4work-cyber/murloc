package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.RussianMessages


object LocalRussianMessages : RussianMessages(), LocalMessages {
    override val serviceName = "Локальные файлы"
    override val myMusic = "Моя музыка"
    override val musicFolder = "Папка с музыкой"
    override val musicFolderMessage = "Абсолютный путь к папке с музыкальными файлами"
    override val useStreamServer = "Использовать встроенный сервер"
    override val useStreamServerSummary =
        "Всегда отдавать файлы через встроенный http-сервер (включите, если прямое воспроизведение файлов не работает)"
    override val loginNotRequired = "Вход не требуется"
    override val folderNotFound = "Папка с музыкой не найдена"
}
