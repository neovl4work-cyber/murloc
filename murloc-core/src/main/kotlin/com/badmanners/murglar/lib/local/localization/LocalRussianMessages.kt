package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.RussianMessages


object LocalRussianMessages : RussianMessages(), LocalMessages {
    override val serviceName = "Локальные файлы"
    override val myMusic = "Моя музыка"
    override val playlistRoot = "Плейлисты"
    override val musicFolder = "Папки с музыкой"
    override val musicFolderMessage = "Абсолютные пути к папкам с музыкой, разделённые точкой с запятой"
    override val pickMusicFolder = "Выбрать папку с музыкой…"
    override val playlistsFolder = "Папка плейлистов"
    override val playlistsFolderMessage = "Абсолютный путь к папке с плейлистами .m3u/.m3u8"
    override val openPlaylistsFolder = "Открыть папку плейлистов"
    override val saveDirAsPlaylist = "Сохранить как плейлист"
    override val addTrackToPlaylist = "Добавить в плейлист"
    override val deletePlaylist = "Удалить плейлист"
    override val targetPlaylist = "Целевой плейлист (для «Добавить в плейлист»)"
    override val createPlaylist = "Создать плейлист"
    override val newPlaylist = "Новый плейлист"
    override val playlistSaved = "Плейлист сохранён:"
    override val playlistCreated = "Плейлист создан:"
    override val useStreamServer = "Использовать встроенный сервер"
    override val useStreamServerSummary =
        "Всегда отдавать файлы через встроенный http-сервер (включите, если прямое воспроизведение файлов не работает)"
    override val loginNotRequired = "Вход не требуется"
    override val folderNotFound = "Папка с музыкой не найдена"
}
