package com.ryan.xyzcar.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 应用内整包更新：Gitee 发行版优先（国内稳定），失败再用 GitHub Releases。
 * 安装包和同名 .sha256 由 CI 同时发布到两边，下载后必须校验一致才允许安装。
 */
class Updater(
    private val http: OkHttpClient,
    private val dir: File,
    private val sources: List<Source> = DEFAULT_SOURCES,
) {
    data class Source(val name: String, val latestApi: String, val downloadBase: String)

    data class Release(val tag: String, val notes: String) {
        val version get() = tag.removePrefix("v")
        val apkName get() = "xyzcar-$version.apk"
    }

    /** 依次询问各个源，第一个成功的为准；全部失败时抛出合并后的原因 */
    fun latest(): Release {
        val errors = mutableListOf<String>()
        for (s in sources) {
            try {
                return parseLatest(get(s.latestApi)) ?: throw IOException("返回内容不对")
            } catch (e: Exception) {
                errors += "${s.name}：${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("；"))
    }

    /** 下载并校验安装包，返回可安装的文件；某个源失败或校验不一致就换下一个 */
    fun download(r: Release, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        val out = File(dir, r.apkName)
        dir.listFiles()?.filter { it.name != out.name }?.forEach { it.delete() }
        val errors = mutableListOf<String>()
        for (s in sources) {
            try {
                val url = "${s.downloadBase}/${r.tag}/${r.apkName}"
                val expected = parseSha(get("$url.sha256")) ?: throw IOException("校验文件格式不对")
                if (out.exists() && sha256(out) == expected) return out.also { onProgress(1f) }
                val part = File(dir, r.apkName + ".part")
                val actual = fetchTo(url, part, onProgress)
                if (actual != expected) {
                    part.delete()
                    throw IOException("文件校验不一致")
                }
                out.delete()
                if (!part.renameTo(out)) throw IOException("保存失败")
                return out
            } catch (e: Exception) {
                errors += "${s.name}：${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("；"))
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("空响应")
        }

    private fun fetchTo(url: String, file: File, onProgress: (Float) -> Unit): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应")
            val total = body.contentLength()
            val md = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                        }
                    }
                }
            }
            md.digest().toHex()
        }

    companion object {
        val DEFAULT_SOURCES = listOf(
            Source(
                "Gitee",
                "https://gitee.com/api/v5/repos/xiayuryan/xyzcar/releases/latest",
                "https://gitee.com/xiayuryan/xyzcar/releases/download",
            ),
            Source(
                "GitHub",
                "https://api.github.com/repos/xiayu1996/xyzcar/releases/latest",
                "https://github.com/xiayu1996/xyzcar/releases/download",
            ),
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun parseLatest(text: String): Release? = runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content?.takeIf { it.startsWith("v") } ?: return null
            Release(tag, cleanNotes(o["body"]?.jsonPrimitive?.content.orEmpty()))
        }.getOrNull()

        /** 版本号按数字逐段比较，如 0.0.10 > 0.0.9 */
        fun isNewer(remote: String, current: String): Boolean {
            val a = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            val b = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        fun parseSha(text: String): String? = Regex("^[0-9a-fA-F]{64}").find(text.trim())?.value?.lowercase()

        /** 发布说明去掉 Markdown 标题、作者链接和 Full Changelog 行，列表改成圆点 */
        fun cleanNotes(body: String): String = body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("**Full Changelog") }
            .map { it.replace(Regex(" by @\\S+ in \\S+$"), "").replace(Regex("^[*-] "), "• ") }
            .joinToString("\n")

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

        /** 发起安装本应用的应用（侧载时通常是车机的文件管理）；读不到返回 null */
        fun selfInstaller(ctx: Context): String? = runCatching {
            val pm = ctx.packageManager
            if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName).let { it.initiatingPackageName ?: it.installingPackageName }
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(ctx.packageName)
        }.getOrNull()

        private val SYSTEM_INSTALLERS = Regex("packageinstaller|^android$|^com\\.android\\.shell$")
        private val FILE_MANAGER_PKG = Regex("file(manager|explorer|browser)|documentsui", RegexOption.IGNORE_CASE)

        /**
         * 车机的文件管理：优先用当初装本应用的那个应用，其次找启动器里名字带「文件」的。
         * 比亚迪车机只给自带的文件管理开了安装权限，本应用自己装不了（设置里也没有开关）。
         */
        fun fileManagerIntent(ctx: Context): Intent? = runCatching {
            val pm = ctx.packageManager
            selfInstaller(ctx)?.takeUnless { SYSTEM_INSTALLERS.containsMatchIn(it) }?.let { pm.getLaunchIntentForPackage(it) }
                ?.let { return@runCatching it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .firstOrNull { it.loadLabel(pm).contains("文件") || FILE_MANAGER_PKG.containsMatchIn(it.activityInfo.packageName) }
                ?.let { Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(it.activityInfo.packageName, it.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }.getOrNull()

        fun installIntent(ctx: Context, apk: File): Intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
