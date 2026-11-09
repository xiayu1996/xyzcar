package com.ryan.xyzcar.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车机排查用的事件日志：启动方式、分屏状态变化、哪个系统组件连了播放服务、实验开关的结果、崩溃。
 * 写在 app 私有目录，超过上限只保留后一半；「车机信息」页显示并可导出二维码。
 * 不记录 token、手机号、IMEI 等私人信息。
 */
object DebugLog {
    private const val MAX_BYTES = 200_000L
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "debug.log")
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        Log.i("XyzDebug", "$tag | $msg")
        val f = file ?: return
        runCatching {
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
            }
        }
    }

    @Synchronized
    fun read(): String = runCatching { file?.readText() }.getOrNull().orEmpty()

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }
}
