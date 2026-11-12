package com.badmanners.murglar.lib.local.fs


/**
 * Music folder root of the `music_dir` preference, parsed for Android scanners.
 *
 * Supported forms (all unicode- and space-safe - matching is done on plain decoded paths):
 * - `Music`, `Music/Rock` - relative to any storage (internal memory, SD card);
 * - `/storage/emulated/0/Music`, `/sdcard/Music` - absolute paths of the primary storage;
 * - `/storage/<volume>/<path>` - absolute path of a specific volume (e.g. an SD card);
 * - SAF tree uris, like the host app stores its download folder
 *   (`content://com.android.externalstorage.documents/tree/AE7C-9F03:Music/`).
 */
internal object MusicRoots {

    /**
     * @property path folder path without leading/trailing `/`, in MediaStore `relative_path`
     * terms (e.g. `Music` or `Music/Rock`)
     * @property volume MediaStore volume name (`external` for the primary storage),
     * null = relative path without an explicit volume (matches any storage)
     */
    data class Root(val path: String, val volume: String?)

    fun parse(raw: String): Root? {
        var path = raw.trim().replace('\\', '/')
        if (path.isEmpty())
            return null

        // SAF tree uri, e.g. content://com.android.externalstorage.documents/tree/AE7C-9F03:Music/
        if (path.startsWith("content://")) {
            val documentId = path.substringAfter("/tree/", "").trimEnd('/')
            if (documentId.isEmpty())
                return null
            val volume = documentId.substringBefore(':').let { if (it == "primary") "external" else it }
            val relative = documentId.substringAfter(':', missingDelimiterValue = "")
                .replace(':', '/').trim('/')
            return Root(relative, volume)
        }

        path = path.trimEnd('/')
        return when {
            path.isEmpty() -> null

            path == "/storage/emulated/0" || path == "/sdcard" ->
                Root("", "external")

            path.startsWith("/storage/emulated/0/") ->
                Root(path.removePrefix("/storage/emulated/0/"), "external")

            path.startsWith("/sdcard/") ->
                Root(path.removePrefix("/sdcard/"), "external")

            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                val volume = rest.substringBefore('/')
                val relative = rest.substringAfter('/', missingDelimiterValue = "").trim('/')
                Root(relative, volume)
            }

            path.startsWith("/") ->
                // Unknown absolute form - match by the path tail on any volume.
                Root(path.trim('/'), null)

            else ->
                // Relative path - any volume (a `Music` folder on the internal memory
                // and on an SD card both match).
                Root(path, null)
        }
    }

    /**
     * Real absolute path of the root (for the direct file scanner).
     */
    fun toAbsolutePath(root: Root): String {
        val base = when (root.volume) {
            null, "external" -> "/storage/emulated/0"
            else -> "/storage/${root.volume}"
        }
        return if (root.path.isEmpty()) base else "$base/${root.path}"
    }
}
