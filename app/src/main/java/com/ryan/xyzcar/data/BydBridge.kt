package com.ryan.xyzcar.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import java.io.File

/**
 * 比亚迪车机接口（分屏、小迪语音）。思路来自 denza-lab 对 DiLink 5.1 固件的记录，代码自写。
 * - 系统事务编号一律按方法名从 IActivityTaskManager$Stub 解析，解析不到就不调用，绝不写死编号；
 * - UnionActivityManager 只主动调用 get / is 开头的只读方法，写入类只有实验开关触发的 setSplitScreenPersistentApp；
 * - 每次调用和结果都用 [DebugLog.kv] 记成 agent 可读的 k=v。
 *
 * 实车（Di150 / Android 14）已确认：TRANSACTION_* 可见；meta-data 让 7 分屏可用；
 * setSplitScreenPersistentApp 之后 3 分屏可用（固件内存，重启失效）。
 */
object BydBridge {
    const val VOICE_PKG = "com.byd.autovoice"
    const val MUSIC_SWITCH = "MUSIC_SWITCH"
    const val APPLE_MUSIC = "com.apple.android.music"
    private val PERSON_BEAN: Uri = Uri.parse("content://$VOICE_PKG/PersonBean")
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val PKG_IN_TEXT = Regex("\\b(?:com|cn|tv|net|org|io)\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private const val UNION_AM = "android.app.UnionActivityManager"

    private fun err(t: Throwable) = (t as? java.lang.reflect.InvocationTargetException)?.targetException?.let { "${it.javaClass.simpleName}:${it.message.orEmpty()}" }
        ?: "${t.javaClass.simpleName}:${t.message.orEmpty()}"

    // ---------------- 分屏 ----------------

    /** 全部 TRANSACTION_*（名字 → 编号）；[split] 是名字像分屏 / 多窗口的子集 */
    data class Transactions(val all: Map<String, Int>, val error: String?) {
        val split: Map<String, Int> get() = all.filterKeys { SPLIT_NAME.containsMatchIn(it) }
    }

    private val SPLIT_NAME = Regex("(?i)split|multi|ivi|area|union|byd")

    fun transactions(): Transactions = runCatching {
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        val fields = stub.declaredFields.filter { it.name.startsWith("TRANSACTION_") }
        if (fields.isEmpty()) return@runCatching Transactions(emptyMap(), "fields_hidden")
        Transactions(fields.associate { f -> f.isAccessible = true; f.name.removePrefix("TRANSACTION_") to f.getInt(null) }.toSortedMap(), null)
    }.getOrElse { Transactions(emptyMap(), err(it)) }

    private fun activityTask(): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, "activity_task") as? IBinder

    /** 按方法名调用一个参数为包名的事务；名字解析不到返回 null（不调用） */
    private fun callWithPackage(method: String, pkg: String, replyInt: Boolean): Result<Int?> = runCatching {
        val code = transactions().all[method] ?: return Result.success(null)
        val binder = activityTask() ?: error("no activity_task binder")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(binder.interfaceDescriptor ?: error("no descriptor"))
            data.writeString(pkg)
            check(binder.transact(code, data, reply, 0)) { "transact $method#$code rejected" }
            reply.readException()
            if (replyInt) reply.readInt() else 0
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun unionClass(): Class<*>? = runCatching { Class.forName(UNION_AM) }.getOrNull()

    private fun unionInstance(cls: Class<*>, ctx: Context): Any? =
        runCatching { cls.getMethod("getInstance", Context::class.java).invoke(null, ctx) }.getOrNull()

    /**
     * 调 UnionActivityManager 上所有叫 [name] 的重载：参数只能是 int / String / Context，
     * 依次填 [area] / [pkg] / ctx。返回「签名 → 结果」；类不存在返回空。
     */
    private fun unionCallAll(ctx: Context, name: String, pkg: String = ctx.packageName, area: Int = 0): List<Pair<String, String>> {
        val cls = unionClass() ?: return emptyList()
        val inst by lazy { unionInstance(cls, ctx) }
        return cls.methods.filter { it.name == name }.map { m ->
            val sig = "$name(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
            val args = m.parameterTypes.map { t ->
                when (t) {
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> area
                    String::class.java -> pkg
                    Context::class.java -> ctx
                    else -> return@map sig to "skip:param_${t.simpleName}"
                }
            }
            sig to runCatching {
                val target = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else inst ?: error("no instance")
                describe(m.invoke(target, *args.toTypedArray()))
            }.getOrElse { "error:" + err(it) }
        }
    }

    /** 反射结果转成一行：集合逐项、ComponentName 用短格式 */
    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is Collection<*> -> "[" + v.joinToString(";") { describe(it) } + "]"
        is Array<*> -> "[" + v.joinToString(";") { describe(it) } + "]"
        is IntArray -> v.contentToString()
        is android.content.ComponentName -> v.flattenToShortString()
        else -> v.toString()
    }.replace('\n', ' ').take(800)

    /** UnionActivityManager / UnionWindowManager 的方法签名（name(参数)返回类型） */
    fun unionSignatures(): List<Pair<String, String>> = listOf(UNION_AM, "android.view.UnionWindowManager").map { name ->
        val cls = runCatching { Class.forName(name) }.getOrNull()
        "sig.${name.substringAfterLast('.')}" to (cls?.methods?.filter { it.declaringClass == cls }
            ?.map { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" + m.returnType.simpleName }
            ?.distinct()?.sorted()?.joinToString(" ") ?: "absent")
    }

    /** 「是否支持分屏」查询：只在恰好一个名字像 isSupport…Split… 时走事务，否则走 Union */
    fun isSupportSplit(pkg: String, ctx: Context? = null): String {
        val names = transactions().all.keys.filter { Regex("(?i)^is\\w*support\\w*split").containsMatchIn(it) }
        if (names.size != 1) {
            if (ctx != null) unionCallAll(ctx, "isSupportSplit", pkg).firstOrNull()?.let { return "union:${it.second}" }
            return "unresolved(candidates=${names.size})"
        }
        return callWithPackage(names[0], pkg, replyInt = true).fold({ "${it ?: "not_called"}" }, { "error:" + err(it) })
    }

    /**
     * 分屏探针（只读）：「选择分屏应用」列表大概率来自 getSplitScreenListByAreaId，
     * 逐个区域读出来，对照本应用和 Apple Music（它在列表里、本应用不在）。
     */
    fun splitProbe(ctx: Context): List<Pair<String, String>> {
        val tx = transactions()
        val out = mutableListOf(
            "split.meta" to splitMetaData(ctx),
            "split.tx.count" to (tx.error ?: tx.all.size.toString()),
            "split.tx.split" to tx.split.entries.joinToString(",") { "${it.key}#${it.value}" }.ifEmpty { "none" },
            "split.isSupportSplit.self" to isSupportSplit(ctx.packageName, ctx),
            "split.isSupportSplit.apple" to isSupportSplit(APPLE_MUSIC, ctx),
        )
        if (unionClass() == null) return out + ("split.union" to "absent") + splitSettings(ctx)
        out += unionCallAll(ctx, "getScreenAreaInfoForMulti").map { "split.union.${it.first}" to it.second }
        for (area in 0..5) out += unionCallAll(ctx, "getSplitScreenListByAreaId", area = area).map { "split.union.${it.first}[$area]" to it.second }
        for (pkg in listOf(ctx.packageName, APPLE_MUSIC)) {
            val who = if (pkg == ctx.packageName) "self" else "apple"
            for (name in listOf("isCanSplit", "getAppAreaIdForMulti")) out += unionCallAll(ctx, name, pkg).map { "split.union.${it.first}.$who" to it.second }
        }
        out += unionCallAll(ctx, "getLockActivityForMulti").map { "split.union.${it.first}" to it.second }
        return out + splitSettings(ctx)
    }

    /** 把包加入系统的分屏常驻列表（固件内存里，重启车机后清空）。实验开关打开时每次启动调用 */
    fun addPersistentSplitApp(ctx: Context): String {
        var via = "tx"
        var r = callWithPackage("setSplitScreenPersistentApp", ctx.packageName, replyInt = false).fold(
            { if (it == null) "unresolved" else "ok" }, { "error:" + err(it) },
        )
        if (r == "unresolved") unionCallAll(ctx, "setSplitScreenPersistentApp").firstOrNull()?.let { via = "union"; r = it.second }
        DebugLog.kv("split", "persist", "via" to via, "result" to r, "isSupportSplit" to isSupportSplit(ctx.packageName, ctx))
        val ok = r == "ok" || (via == "union" && !r.startsWith("error") && !r.startsWith("skip"))
        return if (ok) "已请求（3 分屏可用；车机重启后需再打开本应用一次）" else "失败：$r"
    }

    /** 分屏相关的系统设置（可能因系统限制读不到，记录原因） */
    fun splitSettings(ctx: Context): List<Pair<String, String>> {
        val r = ctx.contentResolver
        fun sys(k: String) = runCatching { Settings.System.getString(r, k) ?: "null" }.getOrElse { "error:${it.javaClass.simpleName}" }
        fun glob(k: String) = runCatching { Settings.Global.getString(r, k) ?: "null" }.getOrElse { "error:${it.javaClass.simpleName}" }
        return listOf("byd_smart_multi_primary_activity", "byd_smart_multi_second_activity", "byd_smart_multi_primary_position", "byd_smart_multi_split_window_mode")
            .map { "settings.system.$it" to sys(it) } +
            listOf("force_resizable_activities", "enable_freeform_support").map { "settings.global.$it" to glob(it) }
    }

    /** 本应用清单里的比亚迪分屏标记是否生效（系统读到的 meta-data） */
    fun splitMetaData(ctx: Context): String = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(ctx.packageName, android.content.pm.PackageManager.GET_META_DATA)
        ai.metaData?.get("BYD_SUPPORT_SPLIT_ACTIVITY")?.let { "$it(${it.javaClass.simpleName})" } ?: "absent"
    }.getOrElse { "error:${it.javaClass.simpleName}" }

    // ---------------- 小迪语音 ----------------

    /** 读小迪的一个默认应用设置（值是包名）；读不到返回 null */
    fun readSwitch(ctx: Context, key: String): String? = runCatching {
        ctx.contentResolver.query(PERSON_BEAN, arrayOf("VALUE"), "SETTING=?", arrayOf(key), null)?.use { c ->
            if (c.count == 1 && c.moveToFirst()) c.getString(0).orEmpty() else null
        }
    }.getOrNull()

    /**
     * 小迪设置表（只读）：所有键名；值是包名、短数字、布尔的照写，其他值只记长度和里面出现的包名
     * （个人设置可能有地址、昵称等，不记原文）。
     */
    fun voiceProbe(ctx: Context): List<Pair<String, String>> {
        val ver = runCatching { ctx.packageManager.getPackageInfo(VOICE_PKG, 0).versionName ?: "?" }.getOrNull()
            ?: return listOf("voice.version" to "absent")
        val out = mutableListOf("voice.version" to ver)
        out += "voice.provider" to (runCatching { ctx.packageManager.resolveContentProvider(VOICE_PKG, 0) }.getOrNull()?.let { "exported=${it.exported}" } ?: "absent")
        runCatching {
            ctx.contentResolver.query(PERSON_BEAN, arrayOf("SETTING", "VALUE"), null, null, null)?.use { c ->
                out += "voice.rows" to c.count.toString()
                while (c.moveToNext()) {
                    val k = c.getString(0) ?: continue
                    val v = c.getString(1).orEmpty()
                    out += "voice.row.$k" to when {
                        PKG.matches(v) -> v
                        v.length <= 5 && v.all { it.isDigit() || it == '-' } -> v
                        v == "true" || v == "false" -> v
                        else -> "len=${v.length}" + PKG_IN_TEXT.findAll(v).map { it.value }.distinct().joinToString(",").let { if (it.isEmpty()) "" else " pkgs=$it" }
                    }
                }
            } ?: run { out += "voice.rows" to "null_cursor" }
        }.onFailure { out += "voice.rows" to "error:" + err(it) }
        return out
    }

    /** 条件写入：只有当前值等于 [expected] 时才改成 [value]，返回是否成功（以读回为准） */
    private fun writeSwitch(ctx: Context, key: String, expected: String, value: String): Boolean {
        require(PKG.matches(value))
        var error: String? = null
        val n = runCatching {
            ctx.contentResolver.update(PERSON_BEAN, ContentValues().apply { put("VALUE", value) }, "SETTING=? AND VALUE=?", arrayOf(key, expected))
        }.getOrElse { error = err(it); -1 }
        val back = readSwitch(ctx, key)
        DebugLog.kv("voice", "write", "key" to key, "from" to expected, "to" to value, "matched" to n, "readback" to back, "error" to error)
        return back == value
    }

    /** 小迪默认音乐设为本应用；原值存到 [LocalStore.musicOriginal]，返回给界面的说明 */
    fun takeMusicRole(ctx: Context, store: LocalStore): String {
        val cur = readSwitch(ctx, MUSIC_SWITCH) ?: return "读不到小迪的默认音乐设置".also { DebugLog.kv("voice", "take_music", "result" to "read_failed") }
        if (cur == ctx.packageName) return "已经是本应用".also { DebugLog.kv("voice", "take_music", "result" to "already") }
        if (PKG.matches(cur)) store.musicOriginal = cur
        return if (writeSwitch(ctx, MUSIC_SWITCH, cur, ctx.packageName)) "已设为本应用（原来是 $cur）" else "设置失败（系统没接受写入）"
    }

    /** 恢复原来的默认音乐；只有当前仍是本应用时才改，避免覆盖用户在系统里另选的应用 */
    fun restoreMusicRole(ctx: Context, store: LocalStore): String {
        val cur = readSwitch(ctx, MUSIC_SWITCH) ?: return "读不到小迪的默认音乐设置".also { DebugLog.kv("voice", "restore_music", "result" to "read_failed") }
        if (cur != ctx.packageName) return "当前是 $cur，不需要恢复".also { DebugLog.kv("voice", "restore_music", "result" to "skip", "current" to cur) }
        val orig = store.musicOriginal.ifEmpty { "com.byd.mediacenter" }
        return if (writeSwitch(ctx, MUSIC_SWITCH, cur, orig)) "已恢复为 $orig" else "恢复失败（系统没接受写入）"
    }

    // ---------------- 媒体中心（音乐源列表 / 底部卡片） ----------------

    /** 系统媒体相关应用：国内媒体中心、海外固件的本地音乐（合作应用名单在它里面）、小迪 */
    val MEDIA_PKGS = listOf("com.byd.mediacenter", "com.byd.localmusic", VOICE_PKG)
    private val MEDIA_ACTIONS = listOf(
        "byd.intent.action.START_MEDIA", "byd.intent.action.SWITCH_MEDIA_MODE",
        "byd.intent.action.HANDLE_MEDIA_KEY", "byd.intent.action.KILL_MEDIA_CENTER",
    )
    private const val MAX_APK_EXPORT = 300L * 1024 * 1024

    /**
     * 只读：媒体中心的版本、对外开放的组件和所需权限、清单里的自定义 action，
     * 车机上所有提供 MediaBrowserService 的应用，以及有没有海外固件那种 com.fce.* 助手包。
     */
    fun mediaProbe(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val out = mutableListOf<Pair<String, String>>()
        for (pkg in MEDIA_PKGS) {
            val short = pkg.substringAfterLast('.')
            val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
            val pi = runCatching { pm.getPackageInfo(pkg, flags) }.getOrNull()
            if (pi == null) { out += "media.$short" to "absent"; continue }
            val ai = pi.applicationInfo
            out += "media.$short.version" to "${pi.versionName}/${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)}"
            out += "media.$short.apk" to "${ai?.sourceDir} size=${ai?.sourceDir?.let { File(it).length() }} splits=${ai?.splitSourceDirs?.size ?: 0}"
            out += "media.$short.services" to pi.services.orEmpty().filter { it.exported }.joinToString(",") { "${it.name.removePrefix(pkg)}(${it.permission ?: "open"})" }.ifEmpty { "none" }
            out += "media.$short.receivers" to pi.receivers.orEmpty().filter { it.exported }.joinToString(",") { "${it.name.removePrefix(pkg)}(${it.permission ?: "open"})" }.ifEmpty { "none" }
            out += "media.$short.providers" to pi.providers.orEmpty().filter { it.exported }.joinToString(",") { "${it.authority}(r=${it.readPermission ?: "open"})" }.ifEmpty { "none" }
            val intents = AppProbe.readManifest(ctx, pkg)?.first?.joinToString(",") ?: "unreadable"
            intents.chunked(500).forEachIndexed { i, part -> out += "media.$short.intents.$i" to part }
        }
        for (a in MEDIA_ACTIONS) {
            out += "media.action.${a.substringAfterLast('.')}" to runCatching {
                pm.queryIntentServices(Intent(a), 0).joinToString(",") { "${it.serviceInfo.packageName}/${it.serviceInfo.name}(${it.serviceInfo.permission ?: "open"})" }
            }.getOrElse { "error:" + err(it) }.ifEmpty { "none" }
        }
        out += "media.browser_services" to runCatching {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).map { it.serviceInfo.packageName }.distinct().sorted().joinToString(",")
        }.getOrElse { "error:" + err(it) }.ifEmpty { "none" }
        out += "media.fce_helpers" to runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.filter { it.startsWith("com.fce.") }.distinct().joinToString(",")
        }.getOrElse { "error:" + err(it) }.ifEmpty { "none" }
        return out
    }

    /**
     * 把系统媒体应用的安装包复制到「下载/xyzcar/」和 U 盘，交给开发者离线分析音乐源名单是怎么定的。
     * 只读系统目录里本来就公开可读的文件，不改任何东西；超过 300 MB 的跳过。
     */
    fun exportMediaApks(ctx: Context): List<String> {
        val pm = ctx.packageManager
        return MEDIA_PKGS.flatMap { pkg ->
            val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@flatMap emptyList()
            val ai = pi.applicationInfo ?: return@flatMap emptyList()
            (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).flatMapIndexed { i, path ->
                val f = File(path)
                val name = "$pkg-${pi.versionName}" + (if (i == 0) "" else "-split$i") + ".apk"
                if (f.length() > MAX_APK_EXPORT) {
                    DebugLog.kv("media", "apk_export_skip", "pkg" to pkg, "size" to f.length())
                    listOf("$name 太大，跳过（${f.length() / 1024 / 1024} MB）")
                } else runCatching {
                    PublicFiles.save(ctx, name, "application/octet-stream") { out -> f.inputStream().use { it.copyTo(out) } }
                }.getOrElse { listOf("$name 复制失败：${it.javaClass.simpleName}") }
                    .also { DebugLog.kv("media", "apk_export", "pkg" to pkg, "file" to name, "size" to f.length(), "places" to it.size) }
            }
        }
    }
}
