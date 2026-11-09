package com.ryan.xyzcar

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 崩溃日志写到 app 私有目录 files/crash/，可以用 adb 导出 */
object CrashLog {
    fun install(context: Context) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "crash-$ts.txt").writeText("thread=${t.name}\n" + e.stackTraceToString())
                com.ryan.xyzcar.data.DebugLog.log("crash", "thread=${t.name} " + e.stackTraceToString().lines().take(12).joinToString(" ⏎ "))
                dir.listFiles()?.sortedBy { it.name }?.dropLast(20)?.forEach { it.delete() }
            }
            previous?.uncaughtException(t, e)
        }
    }
}
