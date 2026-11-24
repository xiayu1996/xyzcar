package com.ryan.xyzcar.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** 车机不让本应用直接装更新包、也没法用数据线拷文件，靠「文件管理」看得到的下载目录和 U 盘中转 */
object PublicFiles {
    const val DIR = "xyzcar"

    fun save(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): List<String> {
        val out = mutableListOf<String>()
        runCatching { saveToDownloads(ctx, name, mime, write) }
            .onSuccess { if (it) out += "下载/$DIR/$name" }
            .onFailure { DebugLog.kv("files", "downloads_error", "name" to name, "error" to "${it.javaClass.simpleName}:${it.message}") }
        // 第一个是机身私有目录，文件管理看不到
        ctx.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { dir ->
            runCatching {
                dir.mkdirs()
                File(dir, name).outputStream().use(write)
                out += "U 盘或 SD 卡/Android/data/${ctx.packageName}/files/$name"
            }.onFailure { DebugLog.kv("files", "volume_error", "error" to "${it.javaClass.simpleName}:${it.message}") }
        }
        DebugLog.kv("files", "saved", "name" to name, "places" to out.size)
        return out
    }

    /** [pattern] 是 SQL LIKE；只能删本应用自己存的 */
    fun prune(ctx: Context, pattern: String) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(pattern, "${Environment.DIRECTORY_DOWNLOADS}/$DIR/"),
            )
        }
    }

    private fun saveToDownloads(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val r = ctx.contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        r.delete(
            coll, "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/$DIR/"),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(coll, values) ?: return false
        try {
            (r.openOutputStream(uri) ?: error("open failed")).use(write)
        } catch (e: Exception) {
            r.delete(uri, null, null)
            throw e
        }
        r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return true
    }
}
