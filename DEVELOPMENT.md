# Разработка MURLOC

Полные заметки по разработке плагина: окружение, сборка, тестирование, устройство кода и известные риски.

## Окружение

| Компонент | Версия / место |
|---|---|
| JDK | 17 (Temurin 17.0.21+, `JAVA_HOME`) |
| Gradle | wrapper проекта (9.4.1) — `gradlew.bat` / `./gradlew` |
| Kotlin | 2.3.21 (задаётся murglar-gradle-plugin) |
| API плагинов | `murglar-gradle-plugin-core/android` **9.0** (jitpack) |
| Android SDK | `C:\Users\<user>\AppData\Local\Android\Sdk` (cmdline-tools + `platform-tools`, `platforms;android-36`, `build-tools;36.1.0`) |
| Подпись apk | `keystore.jks` в корне проекта + `local.properties` (оба в `.gitignore`) |

Установка минимального Android SDK с нуля (если машина чистая):

1. Скачать `commandlinetools-win-*_latest.zip` со https://developer.android.com/studio#command-tools,
   распаковать так, чтобы лежало `<Sdk>\cmdline-tools\latest\bin\sdkmanager.bat`.
2. `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.1.0"`, лицензии — `yes | sdkmanager --licenses`.
3. Если sdkmanager.bat падает из Git Bash (пути с пробелами/кириллицей) — запускать класс напрямую:
   `java -cp <Sdk>\cmdline-tools\latest\lib\sdkmanager-classpath.jar com.android.sdklib.tool.sdkmanager.SdkManagerCli ...`

## Сборка

```bash
# Desktop (jar) — JDK 17 в JAVA_HOME
./gradlew :murloc-core:build
#   → murloc-core/build/libs/murglar-plugin-local-9.12.jar

# Android (apk) — нужен SDK (sdk.dir в local.properties) и keystore
./gradlew :murloc-android:clean :murloc-android:build
#   → murloc-android/build/outputs/apk/release/murglar-plugin-local-9.12.apk
```

Полезное:

- `local.template.properties` — образец `local.properties`. Рабочий формат `sdk.dir`
  (двоеточие экранировать, прямые слеши, UTF-8):
  `sdk.dir=C\:/Users/<user>/AppData/Local/Android/Sdk`
- ⚠️ lint кэширует результат и не инвалидирует его при изменении `local.properties` —
  после правок этого файла обязателен `:murloc-android:clean`, иначе lint падает по старому отчёту.
- ⚠️ `java.io.IOException: ...занят другим процессом` в `SdkLocator` (AGP падает при чтении
  `sdk.dir` из `local.properties`, Windows): убрать `sdk.dir` из `local.properties` и задать
  SDK через переменные окружения `ANDROID_HOME`/`ANDROID_SDK_ROOT` (`settings.gradle.kts`
  подхватывает android-модуль и по env, и по sdk.dir).
- keystore (если нет): `keytool -genkeypair -v -keystore keystore.jks -alias release
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <пароль> -keypass <пароль> -dname "CN=MURLOC, ..."`.
  **Ключ нельзя терять**: Android примет апдейт apk только с тем же ключом.

## Установка для проверки вручную

- **Desktop**: jar → папка плагинов Murglar (подкаталог `plugins` рядом с murglar.exe,
  например `D:\murglar\plugins`), перезапуск плеера.
- **Android**: `adb install murglar-plugin-local-9.12.apk` (или передать файл на устройство
  и установить) — Murglar находит apk по `NoOpDiscoverableReceiver`.

## Тестирование без хоста (murloc-cli)

CLI-модуль инстанцирует `LocalMurglar` с файловыми middleware, прогоняет:
`getRootNodes` → обход папок → треки → `getTracksByMediaIds` → `resolveSourceForUrl` →
скачивание первых байт по `file://` и через StreamServer + Range-запрос (ожидается HTTP 206).

```bash
./gradlew :murloc-cli:build
KOTLIN_STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib -name "*.jar" ! -name "*sources*" | head -1)
java -cp "murloc-cli/build/libs/murloc-cli-all.jar;$KOTLIN_STDLIB" com.badmanners.murglar.client.cli.MurglarCliKt
```

Папки с музыкой задаются в `preferences.json` (`"music_dir": "путь1;путь2"` — несколько через
`;`), папка плейлистов — `"playlists_dir"`. По умолчанию `~/Music` и `~/MurglarPlaylists`.
В репозитории лежит тестовый набор `test-music/` (фейковые файлы + `playlists/playlist.m3u`
с относительными/абсолютными/обратно-слэшными путями и одной несуществующей записью).

## Устройство кода (murloc-core)

| Файл | Роль |
|---|---|
| `LocalMurglar.kt` | Точка входа: преференсы (включая per-playlist 'Rename'), выбор сканера (Desktop / SAF), запуск системного picker, 'Use as music folder', `resolveSourceForUrl`, `getTracksByMediaIds` |
| `login/LocalLoginResolver.kt` | `isLogged = true`, пустые варианты логина → UI логина нет, ноды доступны |
| `node/LocalNodeResolver.kt` | BaseNodeResolver: Root `myMusic` + Root `playlists` + MappedEntity `*/playlist-*` + Directory `*/dir-*` + Track `*/track-*`; при нескольких корнях `myMusic` показывает ноды-корни (дизамбигуация имён родителем/томом). Никакого `java.nio.file` — минимальный API Android 21 |
| `model/track/LocalTrack.kt` | BaseTrack: id = путь/URI файла, «Artist - Title» из имени, папка = альбом; `LocalTrackFactory` — сборка из `AudioFile` |
| `model/playlist/LocalPlaylist.kt` | BasePlaylist для `.m3u` (id = путь файла плейлиста) |
| `model/event/LocalEvents.kt` | Кастомные действия нод (`CustomAction`-события): SaveDirectoryAsPlaylist, UseDirectoryAsMusicRoot(папка корнем music_dir), AddTrackToPlaylist(track+файл плейлиста), RemoveTrackFromPlaylist, DeletePlaylist, CreateEmptyPlaylist, PickMusicFolder (Desktop+Android), OpenPlaylistsFolder (Desktop-only) |
| `fs/DesktopScanner.kt` | java.nio (только Desktop): сканирование по списку корней (`musicDirs()`), фильтр расширений (mp3/flac/ogg/m4a/wav/aac/opus/wma); payload путей нод = абсолютные пути |
| `fs/SafScanner.kt` | Android-сканер SAF document URI через рефлексивные вызовы `ContentResolver` к provider apk; список папок/файлов, metadata и read-only потоки; `MANAGE_EXTERNAL_STORAGE` и MediaStore не используются |
| `fs/AndroidFolderPicker.kt` | Запуск `FolderPickActivity` из apk и polling последнего выбора через `SafProvider`; без блокировки UI-потока |
| `fs/DirectAndroidScanner.kt` | CLI-only на Desktop (`scanner=direct`): обход тестовых путей через `java.io.File`; на Android не выбирается |
| `fs/MusicRoots.kt` | Общий разбор SAF tree URI и legacy-путей; из SAF tree получает volume/path для построения document URI |
| `fs/AndroidHost.kt` | Рефлексивный доступ к контексту хоста (`ActivityThread.currentApplication()`): контекст, ContentResolver, app-internal папка плейлистов |
| `fs/PlaylistLibrary.kt` | Чтение/запись `.m3u`/`.m3u8` на `java.io.File` (не рекурсивно): `#EXTINF`, относительные пути (`/` и `\`), SAF-записи через resolver сканера, rename/delete/append, без кэша |
| `http/StreamServer.kt` | NanoHTTPD на 127.0.0.1, `/file?id=…`, Range (206) для перемотки |
| `localization/` | Messages EN/RU |

В `murloc-android` дополнительно находятся `FolderPickActivity` (`ACTION_OPEN_DOCUMENT_TREE`,
`takePersistableUriPermission`) и `SafProvider`. Provider выполняется в процессе apk, где живёт
SAF-грант, и обслуживает `last`/`list`/`info`/`openFile`; доступ разрешён только calling package
`com.badmanners.murglar`.

Ключевые решения:

- **Поддержка локального аудио в MURGLAR-плагине** — `resolveSourceForUrl` возвращает URL,
  по которому хост будет качать трек. На Desktop: встроенный HTTP-сервер по умолчанию либо
  `file://` при выключенном тоггле. На Android SAF-треки всегда идут через HTTP-сервер:
  внешний document URI нельзя отдать хосту без его собственного гранта.
- **Несколько папок с музыкой**: `music_dir` хранит список через `;`. Сканер принимает список
  корней; при >1 корне корень «Моя музыка» показывает по Directory-ноде на каждый корень.
- **Плейлисты через `.m3u`**: read API `PlaylistsManager` в API 9.0 — пустой stub
  (`interface PlaylistsManager {}` + TODO), создание плейлистов из UI хоста невозможно,
  поэтому плейлисты — файлы (Desktop — `playlists_dir`, Android — `<filesDir>/murloc-playlists`
  хоста), а в дереве это отдельный Root `playlists` рядом с `myMusic` на обеих платформах.
- **`java.io.File` везде в общем коде**: `java.nio.file` недоступен на Android до API 26,
  а minSdk плагина — 21. Desktop-only участки (DesktopScanner, musicDirs) могут использовать
  `java.nio`, всё остальное — только `java.io`.
- **SAF на Android**: выбор папки выполняет отдельная activity apk, поэтому постоянный грант
  принадлежит плагину. Core в процессе Murglar не открывает внешние URI напрямую, а запрашивает
  `DocumentsContract`-операции у собственного provider. Ручной `music_dir` принимает SAF
  tree/document URI; кириллица, пробелы и `+` декодируются как UTF-8. Старые `/storage/...`
  и относительные пути на Android не сканируются. Пустое значение не означает всё хранилище.
- **Кастомные действия нод (events-механика)**: свои `@Model CustomAction`-события
  (`LocalEvents.kt`) объявляются через `EventConfig` в конфигурациях резолвера
  (для Directory-нод — через `UnmappedEntity(type = NODE)`, т.к. у `Directory`/`Root`
  конфигураций нет поля `events`); хост показывает их из `getNodeCustomActions(node)`
  и вызывает `handleEvent(action, node, parent)`. Меню трека формируется динамически:
  по NamedAction на каждый `.m3u` (целевой плейлист через настройки не используется —
  выбор прямо в меню), плюс «Удалить из плейлиста» для трека, открытого изнутри плейлиста
  (родительский сегмент пути `playlist-<id>`). Единственное Desktop-only действие —
  «Открыть папку плейлистов» (нет пользовательской папки на Android).
- **Переименование плейлистов**: механика кастомных действий не умеет запрашивать текст,
  но текстовый ввод умеет `EditPreference` — поэтому в `murglarPreferences` генерируется
  поле «Переименовать "<имя>"» на каждый плейлист (обе платформы); `PlaylistLibrary.rename`
  отказывается при занятом имени.
- **AWT/Swing на Android**: классы `java.awt`/`javax.swing` используются только в
  platform-guarded ветках (`if (isAndroidPlatform) return`), R8 обрабатывается через
  `-dontwarn java.awt.**` / `-dontwarn javax.swing.**` в `murloc-android/proguard-rules.pro`.
- Плагин не рисует UI: дерево рендерит хост, настройки описываются через `murglarPreferences`
  (Edit/Switch/Action Preference), хранятся через `PreferenceMiddleware`.

## Известные риски (проверяются только на реальном хосте)

1. **Приём `file://` на Desktop** — в API `Source.url` заявлены только `http(s)`/`data:`.
   Если хост не играет такие URL — включить «Use embedded stream server».
2. **Android Context через рефлексию** — `ActivityThread.currentApplication()` может не
   сработать в процессе хоста; тогда библиотека пустая (ошибка в логе хоста).
3. **SAF provider** — доступ ограничен calling package `com.badmanners.murglar`; другое имя
   пакета хоста приведёт к `SecurityException`. Грант может быть отозван, SD-карта извлечена,
   а некоторые системные папки запрещены SAF. Сценарии picker/list/open на устройстве ещё
   требуют ручной проверки.
4. **Длительность треков** не определяется (нет парсера тегов и SAF metadata длительности) — в UI `-1`.
5. **HTTP на Android обязателен** — грант принадлежит apk, поэтому выключение `stream_server`
   не переключает SAF-треки на прямой `content://`. Кэшированием таких потоков управляет хост,
   API плагина не имеет флага «не кэшировать».
6. **Rename-преференсы** строятся при открытии настроек: новый плейлист появится в списке
   полей переименования после перезахода в настройки (если хост кэширует список
   преференсов — после перезапуска).

## Публикация

- Релизы прикрепляются к git-тегу (`v1.0` → GitHub Release с jar + apk).
- Версия артефактов: `murglar-plugin-local-<API-major>.<minor>.jar/apk`
  (major берётся из API, minor — `version` в `murglarPlugin {}`).
