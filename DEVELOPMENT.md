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
#   → murloc-core/build/libs/murglar-plugin-local-9.7.jar

# Android (apk) — нужен SDK (sdk.dir в local.properties) и keystore
./gradlew :murloc-android:clean :murloc-android:build
#   → murloc-android/build/outputs/apk/release/murglar-plugin-local-9.7.apk
```

Полезное:

- `local.template.properties` — образец `local.properties`. Рабочий формат `sdk.dir`
  (двоеточие экранировать, прямые слеши, UTF-8):
  `sdk.dir=C\:/Users/<user>/AppData/Local/Android/Sdk`
- ⚠️ lint кэширует результат и не инвалидирует его при изменении `local.properties` —
  после правок этого файла обязателен `:murloc-android:clean`, иначе lint падает по старому отчёту.
- keystore (если нет): `keytool -genkeypair -v -keystore keystore.jks -alias release
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <пароль> -keypass <пароль> -dname "CN=MURLOC, ..."`.
  **Ключ нельзя терять**: Android примет апдейт apk только с тем же ключом.

## Установка для проверки вручную

- **Desktop**: jar → папка плагинов Murglar (подкаталог `plugins` рядом с murglar.exe,
  например `D:\murglar\plugins`), перезапуск плеера.
- **Android**: `adb install murglar-plugin-local-9.7.apk` (или передать файл на устройство
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
| `LocalMurglar.kt` | Точка входа: преференсы (platform-зависимые), пикер папок (Swing), `resolveSourceForUrl`, `getTracksByMediaIds` |
| `login/LocalLoginResolver.kt` | `isLogged = true`, пустые варианты логина → UI логина нет, ноды доступны |
| `node/LocalNodeResolver.kt` | BaseNodeResolver: Root `myMusic` + Root `playlists` (Desktop-only) + MappedEntity `*/playlist-*` + Directory `*/dir-*` + Track `*/track-*`; при нескольких корнях `myMusic` показывает ноды-корни (дизамбигуация имён родителем) |
| `model/track/LocalTrack.kt` | BaseTrack: id = путь/URI файла, «Artist - Title» из имени, папка = альбом; `LocalTrackFactory` — сборка из `AudioFile` |
| `model/playlist/LocalPlaylist.kt` | BasePlaylist для `.m3u` (id = путь файла плейлиста) |
| `model/event/LocalEvents.kt` | Кастомные действия нод (`CustomAction`-события): SaveDirectoryAsPlaylist, AddTrackToPlaylist(track+файл плейлиста), RemoveTrackFromPlaylist, DeletePlaylist, CreateEmptyPlaylist, PickMusicFolder, OpenPlaylistsFolder |
| `fs/DesktopScanner.kt` | java.nio: сканирование по списку корней (`musicDirs()`), фильтр расширений (mp3/flac/ogg/m4a/wav/aac/opus/wma); payload путей нод = абсолютные пути |
| `fs/AndroidScanner.kt` | MediaStore через рефлексию (`ActivityThread.currentApplication()` → ContentResolver); `hasMultipleRoots = false` |
| `fs/PlaylistLibrary.kt` | Чтение `.m3u`/`.m3u8` (не рекурсивно): `#EXTINF`, относительные пути (`/` и `\`), пропуск несуществующих, без кэша |
| `http/StreamServer.kt` | NanoHTTPD на 127.0.0.1, `/file?id=…`, Range (206) для перемотки |
| `localization/` | Messages EN/RU |

Ключевые решения:

- **Поддержка локального аудио в MURGLAR-плагине** — `resolveSourceForUrl` возвращает URL,
  по которому хост будет качать трек. Иерархия: `file://` (desktop) → `content://media/...`
  (android) → встроенный HTTP-сервер (гарантированный fallback, тоггл `stream_server`
  в настройках сервиса).
- **Несколько папок с музыкой**: `music_dir` хранит список через `;`. Сканер принимает список
  корней; при >1 корне корень «Моя музыка» показывает по Directory-ноде на каждый корень.
- **Плейлисты через `.m3u`**: read API `PlaylistsManager` в API 9.0 — пустой stub
  (`interface PlaylistsManager {}` + TODO), создание плейлистов из UI хоста невозможно,
  поэтому плейлисты — файлы в `playlists_dir`, а в дереве это отдельный Root `playlists`
  рядом с `myMusic` (на Android Root не добавляется).
- **Кастомные действия нод (events-механика)**: свои `@Model CustomAction`-события
  (`LocalEvents.kt`) объявляются через `EventConfig` в конфигурациях резолвера
  (для Directory-нод — через `UnmappedEntity(type = NODE)`, т.к. у `Directory`/`Root`
  конфигураций нет поля `events`); хост показывает их из `getNodeCustomActions(node)`
  и вызывает `handleEvent(action, node, parent)`. Меню трека формируется динамически:
  по NamedAction на каждый `.m3u` (целевой плейлист через настройки не используется —
  выбор прямо в меню), плюс «Удалить из плейлиста» для трека, открытого изнутри плейлиста
  (родительский сегмент пути `playlist-<id>`). Действия desktop-only: не показываются
  на Android (`getNodeCustomActions` возвращает пустой список) и не выполняются
  (guard внутри обработчиков). Ограничение: механика не умеет запрашивать текст у
  пользователя — нет переименования плейлистов из UI, только создание/добавление/удаление.
- **AWT/Swing на Android**: классы `java.awt`/`javax.swing` используются только в
  platform-guarded ветках (`if (isAndroidPlatform) return`), R8 обрабатывается через
  `-dontwarn java.awt.**` / `-dontwarn javax.swing.**` в `murloc-android/proguard-rules.pro`.
- Плагин не рисует UI: дерево рендерит хост, настройки описываются через `murglarPreferences`
  (Edit/Switch/Action Preference), хранятся через `PreferenceMiddleware`.

## Известные риски (проверяются только на реальном хосте)

1. **Приём `file://`/`content://`** — в API `Source.url` заявлены только `http(s)`/`data:`.
   Если хост не играет такие URL — включить «Use embedded stream server».
2. **Android Context через рефлексию** — `ActivityThread.currentApplication()` может не
   сработать в процессе хоста; тогда библиотека пустая (ошибка в логе хоста).
3. **MediaStore** — список пуст, если у хоста нет разрешения на чтение медиатеки.
4. **Длительность треков на Desktop** не определяется (нет парсера тегов) — в UI `-1`.
5. **Плейлисты** — Desktop-only; создание через UI хоста недоступно (`PlaylistsManager`
   в API 9.0 — пустой stub), крафтинг — кастомными действиями нод (см. выше);
   переименование — только через файловый менеджер.

## Публикация

- Релизы прикрепляются к git-тегу (`v1.0` → GitHub Release с jar + apk).
- Версия артефактов: `murglar-plugin-local-<API-major>.<minor>.jar/apk`
  (major берётся из API, minor — `version` в `murglarPlugin {}`).
