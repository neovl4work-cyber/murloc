package com.badmanners.murglar.lib.local.fs


/**
 * Last path component of [path] in a platform-independent way (no `java.nio.file` -
 * it is not available on Android before API 26).
 */
internal fun fileNameOf(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    return trimmed.substringAfterLast('/').substringAfterLast('\\')
}
