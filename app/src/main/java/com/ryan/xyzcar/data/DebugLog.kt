package com.ryan.xyzcar.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车机排查用的事件日志：启动方式、分屏状态变化、哪个系统组件连了播放服务、实验开关的结果、崩溃。
 * 给 agent 读，不给人读：一行一个事件，`时间 [tag] event k=v k=v`，键用英文，值里有空格时加引号。
 * 写在 app 私有目录，超过上限只保留后一半。不记录 token、手机号、IMEI、蓝牙名等私人信息；
 * 万一异常信息里带了，写入前由 [redact] 兜底打码。
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
        @Suppress("NAME_SHADOWING") val msg = redact(msg)
        runCatching { Log.i("XyzDebug", "$tag | $msg") }
        val f = file ?: return
        runCatching {
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
            }
        }
    }

    /** 结构化事件：`event k=v k=v`；值转成单行，含空格或为空时加引号 */
    fun kv(tag: String, event: String, vararg pairs: Pair<String, Any?>) =
        log(tag, (listOf(event) + pairs.map { (k, v) -> "$k=" + fmtValue(v) }).joinToString(" "))

    fun fmtValue(v: Any?): String {
        val s = v.toString().replace('\n', ' ').replace('\r', ' ').take(600)
        return if (s.isEmpty() || s.any { it == ' ' || it == '"' }) "\"" + s.replace("\"", "'") + "\"" else s
    }

    private val SECRET_KV = Regex("(?i)((?:access|refresh)?_?token|authorization|x-jike-[a-z-]+|password|cookie|imei|iccid|serial|mobile|phone)([\"']?\\s*[=:]\\s*[\"']?)[^\\s&,;\"']+")
    private val JWT = Regex("eyJ[\\w-]{8,}\\.[\\w-]{8,}\\.[\\w-]+")
    private val PHONE = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val LONG_DIGITS = Regex("(?<![\\d.])\\d{14,20}(?![\\d.])")

    /** 兜底打码：键名像密钥的值、JWT、手机号、14~20 位数字串（IMEI / ICCID） */
    fun redact(s: String): String = s
        .replace(SECRET_KV) { it.groupValues[1] + it.groupValues[2] + "***" }
        .replace(JWT, "<jwt>")
        .replace(PHONE, "<phone>")
        .replace(LONG_DIGITS, "<digits>")

    /**
     * 给报告用的精简日志：去掉时间戳后连续重复的事件合并成一行（末尾加 `x次数`），只留最后 [maxLines] 行。
     */
    fun compact(text: String, maxLines: Int = 400): String {
        val out = ArrayList<String>()
        var lastKey: String? = null
        var count = 0
        fun flush() { if (count > 1) out[out.lastIndex] = out.last() + " x$count" }
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val key = line.substringAfter(' ').substringAfter(' ')
            if (key == lastKey) { count++; continue }
            flush()
            out += line; lastKey = key; count = 1
        }
        flush()
        return out.takeLast(maxLines).joinToString("\n", postfix = if (out.isEmpty()) "" else "\n")
    }

    @Synchronized
    fun read(): String = runCatching { file?.readText() }.getOrNull().orEmpty()

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }
}
