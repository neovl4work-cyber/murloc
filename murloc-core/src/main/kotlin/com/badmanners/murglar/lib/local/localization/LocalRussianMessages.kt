package com.badmanners.murglar.lib.local.localization

import com.badmanners.murglar.lib.core.localization.RussianMessages


object LocalRussianMessages : RussianMessages(), LocalMessages {
    override val serviceName = "Локальные файлы"
    override val myMusic = "Моя музыка"
    override val playlistRoot = "Плейлисты"
    override val musicFolder = "Папки с музыкой"
    override val musicFolderMessage = "Абсолютные пути к папкам с музыкой, разделённые точкой с запятой"
    override val musicFolderMessageAndroid =
        "SAF-ссылка на папку из системного проводника; можно вставить вручную. Несколько папок через ;. Кириллица и пробелы поддерживаются"
    override val pickMusicFolder = "Выбрать папку с музыкой…"
    override val useAsMusicFolder = "Использовать как папку с музыкой"
    override val musicFolderSet = "Папка с музыкой:"
    override val internalStorage = "Внутренняя память"
    override val playlistsFolder = "Папка плейлистов"
    override val playlistsFolderMessage = "Абсолютный путь к папке с плейлистами .m3u/.m3u8"
    override val openPlaylistsFolder = "Открыть папку плейлистов"
    override val saveDirAsPlaylist = "Сохранить как плейлист"
    override val removeFromPlaylist = "Удалить из плейлиста"
    override val deletePlaylist = "Удалить плейлист"
    override val createPlaylist = "Создать плейлист"
    override val newPlaylist = "Новый плейлист"
    override val playlistSaved = "Плейлист сохранён:"
    override val playlistCreated = "Плейлист создан:"

    override fun addToPlaylist(playlistName: String) = "Добавить в «$playlistName»"
    override fun renamePlaylist(playlistName: String) = "Переименовать «$playlistName»"
    override val renamePlaylistMessage = "Введите новое имя плейлиста"
    override val playlistRenamed = "Плейлист переименован:"
    override val playlistRenameFailed = "Не удалось переименовать — плейлист с таким именем уже существует"
    override val useStreamServer = "Использовать встроенный сервер"
    override val useStreamServerSummary =
        "Всегда отдавать файлы через встроенный http-сервер (включите, если прямое воспроизведение файлов не работает)"
    override val loginNotRequired = "Вход не требуется"
    override val folderNotFound = "Папка с музыкой не найдена"
}
