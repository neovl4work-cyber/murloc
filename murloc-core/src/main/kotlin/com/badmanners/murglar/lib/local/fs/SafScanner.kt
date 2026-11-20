package com.badmanners.murglar.lib.local.fs

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.track.source.Source
import java.io.InputStream
import java.net.URLEncoder

class SafScanner(
    private val logger: LoggerMiddleware,
    private val musicFolders: () -> List<String>
) : FileScanner {

    private data class Entry(val id: String, val name: String, val mime: String, val size: Long)

    override val isAvailable: Boolean
        get() = AndroidHost.context(logger) != null

    override val hasMultipleRoots: Boolean
        get() = roots().size > 1

    override fun listSubdirs(relPath: String?): List<DirEntry> = try {
        // DEBUG-BISECT: совсем пустое дерево — если краш останется, дело не в наших нодах
        if (BISECT_NO_TRACKS) emptyList()
        else if (relPath.isNullOrEmpty() && hasMultipleRoots) {
            roots()
        } else {
            directories(relPath).flatMap { dir ->
                queryChildren(dir.relPath)
                    .filter { it.mime == DIRECTORY_MIME }
                    .map { entry -> DirEntry(documentUri(dir.relPath, entry.id), entry.name) }
            }.sortedBy { it.name.lowercase() }
        }
    } catch (e: Throwable) {
        logger.e(TAG, "Can't list SAF subdirs of '$relPath'!", e)
        emptyList()
    }

    override fun listAudio(relPath: String?): List<AudioFile> = try {
        // DEBUG-BISECT: треки скрыты — если краш исчезнет, виновато поле трека
        if (BISECT_NO_TRACKS) emptyList()
        else directories(relPath).flatMap { dir ->
            queryChildren(dir.relPath)
                .filter { it.mime != DIRECTORY_MIME && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
                .map { entry -> toAudioFile(dir.relPath, entry) }
        }.sortedBy { it.title.lowercase() }
    } catch (e: Throwable) {
        logger.e(TAG, "Can't list SAF audio of '$relPath'!", e)
        emptyList()
    }

    override fun audioFile(id: String): AudioFile? = try {
        val (tree, documentId) = parseDocument(id) ?: return null
        queryEntries("info", tree, documentId).firstOrNull()
            ?.takeIf { it.mime != DIRECTORY_MIME && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
            ?.let { toAudioFile(id, it) }
    } catch (e: Throwable) {
        logger.e(TAG, "Can't get SAF audio file '$id'!", e)
        null
    }

    override fun open(id: String): Pair<InputStream, Long>? = try {
        if (parseDocument(id) == null)
            return null
        val resolver = AndroidHost.contentResolver(logger) ?: return null
        val uri = providerUri("file", PARAM_DOCUMENT to id)
        val afdClass = Class.forName("android.content.res.AssetFileDescriptor")
        val afd = resolver.javaClass
            .getMethod("openAssetFileDescriptor", androidNetUri(), String::class.java)
            .invoke(resolver, uri, "r") ?: return null
        val length = afdClass.getMethod("getLength").invoke(afd) as Long
        val stream = afdClass.getMethod("createInputStream").invoke(afd) as InputStream
        stream to if (length >= 0) length else Source.UNKNOWN_SIZE
    } catch (e: Throwable) {
        logger.e(TAG, "Can't open SAF file '$id'!", e)
        null
    }

    private fun roots(): List<DirEntry> = musicFolders().mapNotNull { raw ->
        val value = raw.trim().trimEnd('/')
        // принимаем и SAF tree URI, и обычные пути (/sdcard/Music, /storage/<том>/<путь>) —
        // последние конвертируем в tree URI (работают, только если выдан SAF-грант на эту папку)
        val treeValue = when {
            value.startsWith(EXTERNAL_STORAGE_TREE) -> value
            else -> legacyPathToTree(value) ?: return@mapNotNull null
        }

        if ("/document/" in treeValue) {
            val tree = treeValue.substringBefore("/document/")
            val documentId = decodeDocumentId(treeValue.substringAfter("/document/"))
                ?: return@mapNotNull null
            DirEntry("$tree/document/$documentId", documentId.substringAfterLast('/').substringAfterLast(':'))
        } else {
            val root = MusicRoots.parse(treeValue) ?: return@mapNotNull null
            val volume = root.volume ?: return@mapNotNull null
            val documentVolume = if (volume == "external") "primary" else volume
            val documentId = "$documentVolume:${root.path}"
            val name = root.path.substringAfterLast('/').ifEmpty { documentVolume }
            DirEntry("$treeValue/document/$documentId", name)
        }
    }.distinctBy { it.relPath }

    private fun legacyPathToTree(value: String): String? {
        val root = MusicRoots.parse(value) ?: return null
        val volume = root.volume ?: return null
        val documentVolume = if (volume == "external") "primary" else volume
        val documentId = if (root.path.isEmpty()) documentVolume else "$documentVolume:${root.path}"
        val encoded = URLEncoder.encode(documentId, "UTF-8").replace("+", "%20")
        return EXTERNAL_STORAGE_TREE + encoded
    }

    private fun directories(relPath: String?): List<DirEntry> = when {
        relPath.isNullOrEmpty() -> roots()
        parseDocument(relPath) != null -> listOf(DirEntry(relPath, relPath.substringAfterLast('/')))
        else -> emptyList()
    }

    private fun queryChildren(document: String): List<Entry> {
        val (tree, documentId) = parseDocument(document) ?: return emptyList()
        return queryEntries("list", tree, documentId)
    }

    private fun queryEntries(action: String, tree: String, documentId: String): List<Entry> = try {
        queryEntriesRaw(action, tree, documentId)
    } catch (e: Throwable) {
        if (e is SecurityException || (e.cause is SecurityException))
            logger.w(TAG, "SAF: нет доступа к '$documentId' — выдайте его через «Выбрать папку с музыкой…» (SAF), затем укажите папку заново.")
        logger.e(TAG, "SAF provider '$action' query failed for '$documentId'!", e)
        emptyList()
    }

    /** Живой статус каждой папки для диагностики (показывается в настройках плагина). */
    fun status(): List<String> = musicFolders().map { raw ->
        val value = raw.trim().trimEnd('/')
        when {
            value.isEmpty() -> "• (пусто)"
            else -> {
                val treeValue = if (value.startsWith(EXTERNAL_STORAGE_TREE)) value
                    else legacyPathToTree(value)
                if (treeValue == null) "• '$raw' — путь не распознан"
                else {
                    val documentId = if ("/document/" in treeValue) treeValue.substringAfter("/document/")
                    else MusicRoots.parse(treeValue)?.let { r ->
                        val v = r.volume?.let { if (it == "external") "primary" else it }
                        if (v == null) null else if (r.path.isEmpty()) v else "$v:${r.path}"
                    }
                    if (documentId == null) "• '$raw' — documentId не построен"
                    else {
                        val tree = treeValue.substringBefore("/document/")
                        runCatching { queryEntriesRaw("info", tree, documentId) }.fold(
                            onSuccess = { "• '${short(raw)}' — доступ OK (${it.size} элементов)" },
                            onFailure = { "• '${short(raw)}' — НЕТ ДОСТУПА: ${it.javaClass.simpleName}: ${it.message}" }
                        )
                    }
                }
            }
        }
    }

    private fun short(s: String) = if (s.length > 48) s.take(48) + "…" else s

    private fun queryEntriesRaw(action: String, tree: String, documentId: String): List<Entry> {
        val resolver = AndroidHost.contentResolver(logger) ?: throw IllegalStateException("нет ContentResolver хоста")
        val uri = providerUri(action, PARAM_TREE to tree, PARAM_DOCUMENT to documentId)
        val cursor = resolver.javaClass.getMethod(
            "query", androidNetUri(), Array<String>::class.java,
            String::class.java, Array<String>::class.java, String::class.java
        ).invoke(resolver, uri, null, null, null, null) ?: return emptyList()

        try {
            val result = mutableListOf<Entry>()
            while (cursor.javaClass.getMethod("moveToNext").invoke(cursor) as Boolean) {
                val id = cursorString(cursor, "id") ?: continue
                val name = cursorString(cursor, "name") ?: continue
                val mime = cursorString(cursor, "mime").orEmpty()
                val size = cursorLong(cursor, "size")
                result += Entry(id, name, mime, size)
            }
            return result
        } finally {
            runCatching { cursor.javaClass.getMethod("close").invoke(cursor) }
        }
    }

    private fun toAudioFile(parentDocument: String, entry: Entry): AudioFile {
        val id = documentUri(parentDocument, entry.id)
        val name = entry.name.substringBeforeLast('.', entry.name)
        val (artists, title) = if (name.contains(" - ")) {
            listOf(name.substringBefore(" - ").trim()) to name.substringAfter(" - ").trim()
        } else {
            emptyList<String>() to name
        }
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val album = entry.id.substringAfter(':').substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { null }
        return AudioFile(
            id = id,
            title = title,
            artists = artists,
            album = album,
            // реальная длительность обязательна: хост делает coerceIn(0..duration)
            // и падает при -1 (UNKNOWN_DURATION)
            durationMs = durationOf(id, extension, entry.size),
            extension = extension,
            size = entry.size
        )
    }

    private fun durationOf(id: String, extension: String, size: Long): Long {
        val opened = open(id) ?: return UNKNOWN_DURATION
        return try {
            AudioDuration.detect(opened.first, size, extension)
        } catch (e: Throwable) {
            UNKNOWN_DURATION
        } finally {
            runCatching { opened.first.close() }
        }
    }

    private fun documentUri(parentDocument: String, childId: String): String {
        val tree = parentDocument.substringBefore("/document/")
        return "$tree/document/$childId"
    }

    private fun parseDocument(value: String): Pair<String, String>? {
        if (!value.startsWith(EXTERNAL_STORAGE_TREE) || "/document/" !in value)
            return null
        val tree = value.substringBefore("/document/")
        val documentId = value.substringAfter("/document/")
        return if (documentId.isEmpty()) null else tree to documentId
    }

    private fun decodeDocumentId(value: String): String? = runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            .takeIf { it.isNotEmpty() && !it.substringAfter(':').split('/').contains("..") }
    }.getOrNull()

    private fun providerUri(action: String, vararg params: Pair<String, String>): Any {
        val query = params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        val value = "content://$PLUGIN_PACKAGE.provider/$action?$query"
        return androidNetUri().getMethod("parse", String::class.java).invoke(null, value)
    }

    private fun androidNetUri() = Class.forName("android.net.Uri")

    private fun cursorString(cursor: Any, column: String): String? {
        val index = cursor.javaClass.getMethod("getColumnIndex", String::class.java)
            .invoke(cursor, column) as Int
        return if (index >= 0)
            cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, index) as String?
        else
            null
    }

    private fun cursorLong(cursor: Any, column: String): Long {
        val index = cursor.javaClass.getMethod("getColumnIndex", String::class.java)
            .invoke(cursor, column) as Int
        return if (index >= 0)
            cursor.javaClass.getMethod("getLong", Int::class.java).invoke(cursor, index) as Long
        else
            0L
    }

    private companion object {
        const val TAG = "SafScanner"
        const val PLUGIN_PACKAGE = "com.badmanners.murglar.plugin.local"
        const val EXTERNAL_STORAGE_TREE = "content://com.android.externalstorage.documents/tree/"
        const val DIRECTORY_MIME = "vnd.android.document/directory"
        const val PARAM_TREE = "tree"
        const val PARAM_DOCUMENT = "document"
        const val UNKNOWN_DURATION = -1L
        const val BISECT_NO_TRACKS = false
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus", "wma")
    }
}
