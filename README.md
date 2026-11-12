# MURLOC — Local Files plugin for Murglar

Плагин типа `MURGLAR` (`id = "local"`) для музыкального плеера [Murglar](https://github.com/badmannersteam/murglar-plugins),
который отдаёт плееру музыку из локальной папки. Дерево папок на диске отображается в UI Murglar
как «Моя музыка → папки → треки» — свой UI у плагина нет, дерево рендерит хост.

## Структура проекта

Полные заметки по разработке (окружение, сборка SDK, тестирование, устройство кода, риски): [DEVELOPMENT.md](DEVELOPMENT.md).

| Модуль | Назначение |
|---|---|
| `murloc-core` | Реализация плагина; собирается в jar для Murglar Desktop |
| `murloc-android` | Обёртка в apk для Murglar Android |
| `murloc-cli` | Запуск плагина без хоста: построение дерева, resolveSourceForUrl, скачивание первых байт |
| `test-music/` | Тестовая папка с фейковыми файлами для прогона CLI |

API плагинов: `murglar-gradle-plugin-core/android` **9.0** (jitpack), Kotlin/JDK 17.

## Сборка

```bash
./gradlew :murloc-core:build      # jar: murloc-core/build/libs/murglar-plugin-local-9.2.jar
./gradlew :murloc-android:build   # apk: murloc-android/build/outputs/apk/release/murglar-plugin-local-9.2.apk
./gradlew :murloc-cli:build       # тестовый CLI-модуль
```

Для сборки нужен JDK 17+ (в `PATH` или `JAVA_HOME`). Для сборки apk дополнительно нужны
Android SDK и keystore (см. ниже).

## Установка

**Desktop**: положить `murglar-plugin-local-9.2.jar` в папку плагинов Murglar Desktop
и перезапустить приложение. Папка плагинов — подкаталог `plugins` рядом с исполняемым файлом
Murglar (в зависимости от того, куда установлен плеер; например, при установке в `D:\murglar`
путь будет `D:\murglar\plugins`).

**Android**: установить `murglar-plugin-local-9.2.apk` на устройство — Murglar подхватит её
как плагин (как в документации murglar-plugins).

## Настройки плагина

В настройках сервиса внутри Murglar:

- **Music folder / Папка с музыкой** (`music_dir`) — абсолютный путь к папке с музыкой.
  На Desktop по умолчанию `~/Music`. На Android настройка игнорируется — показывается
  вся аудиотека MediaStore.
- **Use embedded stream server / Использовать встроенный сервер** (`stream_server`) —
  если включено, файлы всегда отдаются через встроенный HTTP-сервер (`http://127.0.0.1:<порт>`).
  Если выключено, используется прямой URL (`file://` на Desktop, `content://media/...` на Android),
  а сервер включается автоматически, только если прямой URL недоступен.

## Как это работает

- Логин не требуется: свой `LoginResolver` с `isLogged = true` и пустыми списками вариантов
  логина (в UI кнопки логина не появляются, а ноды остаются доступными).
- Дерево: `BaseNodeResolver` с конфигурациями Root (`myMusic`) + Directory (`*/dir-<dir>`)
  + Track (`*/track-<trackId>`). Пути файлов URL-кодируются внутри сегментов путей нод.
- Метаданные: id трека = канонический путь файла (desktop) или `content://media/...` URI (android);
  «Artist - Title» парсится из имени файла, вложенная папка = название альбома;
  длительность — из MediaStore на Android, на Desktop не определяется (`-1`).
- Поддерживаемые расширения: mp3, flac, ogg, m4a, wav, aac, opus, wma.
- Запасной канал воспроизведения — встроенный сервер на NanoHTTPD на `127.0.0.1` со случайным
  портом, стримит файл с поддержкой `Range` (для перемотки). Код плагина исполняется в процессе
  хоста, поэтому INTERNET-permission у хоста уже есть.

## Проверка без хоста (CLI)

CLI инстанцирует `LocalMurglar` с middleware из JSON-файла/preferences, прогоняет:
`getRootNodes` → обход папок `getNodeContent` → список треков → `getTracksByMediaIds` →
`resolveSourceForUrl` → скачивание первых байт по прямому `file://` URL и через StreamServer,
плюс Range-запрос (код 206). Перед запуском в `preferences.json` задайте `"music_dir": "<папка>"`
(по умолчанию используется `~/Music`). В репозитории для этого лежит `test-music/`.

Запуск (fat-jar плагина не содержит kotlin-stdlib — она «provided» со стороны хоста,
поэтому для CLI её нужно добавить в classpath из Gradle-кэша):

```bash
./gradlew :murloc-cli:build
KOTLIN_STDLIB=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib -name "*.jar" ! -name "*sources*" | head -1)
java -cp "murloc-cli/build/libs/murloc-cli-all.jar;$KOTLIN_STDLIB" com.badmanners.murglar.client.cli.MurglarCliKt
```

(на Windows разделитель classpath — `;`, пути — в формате Windows).

## Сборка Android-модуля

Нужны Android SDK и подпись:

1. Установить Android Studio / cmdline-tools → Android SDK.
2. Скопировать `local.template.properties` в `local.properties`, прописать `sdk.dir`
   (модуль `murloc-android` автоматически подключается к сборке, как только `sdk.dir`
   появляется в `local.properties` или в `ANDROID_HOME`/`ANDROID_SDK_ROOT`).
3. Положить keystore в корень проекта (или прописать путь в `local.properties`):
   ```
   storeFile=keystore.jks
   storePassword=...
   keyAlias=release
   keyPassword=...
   ```
   НЕ коммитьте `local.properties` и keystore в репозиторий.
   Если keystore нет — сгенерировать (`keytool -genkeypair ...`) или собрать debug-вариант
   с debug-подписью Android Gradle Plugin.

## Известные ограничения и риски (проверить только на реальном хосте)

- **Приём `file://`/`content://` хостом**: в API `Source.url` заявлены только `http(s)` и `data:`.
  Если Murglar Desktop/Android не умеет играть такие URL напрямую — включите
  «Use embedded stream server», тогда всё пойдёт через `http://127.0.0.1`.
- **Android Context через рефлексию**: `ActivityThread.currentApplication()` — стандартный трюк,
  но на конкретном хосте может не сработать. Тогда плагин покажет пустую библиотеку
  (ошибка будет в логе через LoggerMiddleware). Проверено только компиляцией, не на устройстве.
- **MediaStore на Android**: треки фильтруются по расширению файла; без разрешения хоста
  на чтение медиатеки список будет пуст.
- **Длительность треков на Desktop** не определяется (нет парсера тегов) — в UI может
  отображаться неизвестная длительность.
- Desktop-сканер читает только «плоские» файлы в каждой папке (без рекурсивного дублирования
  треков вложенных папок в родительской) — дерево повторяет структуру папок.
