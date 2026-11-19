package com.badmanners.murglar.plugin.local

import android.content.Context
import java.io.File
import java.util.Date

/**
 * Аварийный журнал процесса плагина: любое необработанное исключение в точках входа
 * (activity, provider) пишется в файл во внешней папке приложения —
 * пользователь может достать его кабелем/USB-накопителем без root:
 * /sdcard/Android/data/com.badmanners.murglar.plugin.local/files/murloc-crash.log
 */
object CrashLog {

    fun log(context: Context?, where: String, t: Throwable) {
        try {
            val dir = context?.getExternalFilesDir(null) ?: return
            val file = File(dir, "murloc-crash.log")
            file.appendText("${Date()}\n== $where ==\n${t.stackTraceToString()}\n\n")
        } catch (_: Throwable) {
        }
    }
}
