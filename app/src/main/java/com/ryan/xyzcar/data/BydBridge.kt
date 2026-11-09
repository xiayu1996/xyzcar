package com.ryan.xyzcar.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings

/**
 * 比亚迪车机接口（分屏、小迪语音）。思路来自 denza-lab 对 DiLink 5.1 固件的记录，代码自写；
 * 本车是 Di150 / Android 14，接口可能不同，所以：
 * - 系统事务编号一律按方法名从 IActivityTaskManager$Stub 解析，解析不到就不调用，绝不写死编号；
 * - 每次调用和结果都写进 [DebugLog]；
 * - 写入类操作都由设置里的实验开关触发，默认关闭。
 */
object BydBridge {
    const val VOICE_PKG = "com.byd.autovoice"
    const val MUSIC_SWITCH = "MUSIC_SWITCH"
    private val PERSON_BEAN: Uri = Uri.parse("content://$VOICE_PKG/PersonBean")
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private const val PLAYBACK_SERVICE = "com.ryan.xyzcar.player.PlaybackService"

    // ---------------- 分屏 ----------------

    /** 可见的 TRANSACTION_* 总数，和其中名字像分屏 / 多窗口的（名字 → 编号） */
    data class Transactions(val visible: Int, val split: Map<String, Int>, val error: String?)

    private val SPLIT_NAME = Regex("(?i)split|multi|ivi|area|union|byd")

    /**
     * 读 Stub 的 TRANSACTION_* 字段（名字 → 编号）。隐藏接口限制下可能一个都读不到
     * （Android 12 模拟器实测 0 个），那就不调用任何事务，改走 UnionActivityManager 的同名方法。
     */
    fun transactions(): Transactions = runCatching {
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        val fields = stub.declaredFields.filter { it.name.startsWith("TRANSACTION_") }
        if (fields.isNotEmpty()) {
            val split = fields.filter { SPLIT_NAME.containsMatchIn(it.name) }
                .associate { f -> f.isAccessible = true; f.name.removePrefix("TRANSACTION_") to f.getInt(null) }
            return@runCatching Transactions(fields.size, split.toSortedMap(), null)
        }
        Transactions(0, emptyMap(), "TRANSACTION_* 字段不可见（系统隐藏接口限制）")
    }.getOrElse { Transactions(0, emptyMap(), it.javaClass.simpleName + ": " + it.message) }

    private fun activityTask(): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, "activity_task") as? IBinder

    /** 按方法名调用一个参数为包名的事务；名字解析不到返回 null（不调用） */
    private fun callWithPackage(method: String, pkg: String, replyInt: Boolean): Result<Int?> = runCatching {
        val code = transactions().split[method] ?: return Result.success(null)
        val binder = activityTask() ?: error("activity_task 服务拿不到")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(binder.interfaceDescriptor ?: error("没有接口描述"))
            data.writeString(pkg)
            check(binder.transact(code, data, reply, 0)) { "事务 $method#$code 未被接受" }
            reply.readException()
            if (replyInt) reply.readInt() else 0
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** UnionActivityManager 上恰好一个、参数只有一个 String 的同名方法；没有返回 null */
    private fun unionCall(ctx: Context?, nameRegex: Regex, pkg: String): Result<Any?>? {
        val cls = runCatching { Class.forName("android.app.UnionActivityManager") }.getOrNull() ?: return null
        val m = cls.methods.filter { nameRegex.matches(it.name) && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }.singleOrNull() ?: return null
        return runCatching {
            val inst = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null
            else cls.getMethod("getInstance", Context::class.java).invoke(null, ctx ?: error("没有 Context"))
            "${m.name} → " + m.invoke(inst, pkg)
        }
    }

    /** 「是否支持分屏」查询：只在恰好一个名字像 isSupport…Split… 时调用 */
    fun isSupportSplit(pkg: String, ctx: Context? = null): String {
        val names = transactions().split.keys.filter { Regex("(?i)^is\\w*support\\w*split").containsMatchIn(it) }
        if (names.size != 1) {
            unionCall(ctx, Regex("(?i)^is\\w*support\\w*split\\w*"), pkg)?.let { r ->
                return r.fold({ "Union.$it" }, { "Union 出错：${it.javaClass.simpleName}" })
            }
            return "未解析（候选 ${names.size} 个）"
        }
        return callWithPackage(names[0], pkg, replyInt = true).fold(
            { "${names[0]} → ${it ?: "未调用"}" },
            { "${names[0]} 出错：${it.javaClass.simpleName} ${it.message.orEmpty()}" },
        )
    }

    /** 把包加入系统的分屏常驻列表（固件内存里，重启车机后清空）。实验开关打开时每次启动调用 */
    fun addPersistentSplitApp(ctx: Context): String {
        var r = callWithPackage("setSplitScreenPersistentApp", ctx.packageName, replyInt = false).fold(
            { if (it == null) "未解析到 setSplitScreenPersistentApp，未调用" else "已调用" },
            { "出错：${it.javaClass.simpleName} ${it.message.orEmpty()}" },
        )
        if (r.startsWith("未解析")) unionCall(ctx, Regex("setSplitScreenPersistentApp"), ctx.packageName)?.let { u ->
            r = u.fold({ "已通过 Union 调用（$it）" }, { "Union 调用出错：${it.javaClass.simpleName} ${it.message.orEmpty()}" })
        }
        DebugLog.log("split", "加入分屏常驻列表：$r；之后 ${isSupportSplit(ctx.packageName, ctx)}")
        return r
    }

    /** UnionActivityManager / UnionWindowManager（比亚迪多窗口接口）的公开方法名和当前屏幕区域 */
    fun unionInfo(ctx: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (name in listOf("android.app.UnionActivityManager", "android.view.UnionWindowManager")) {
            val cls = runCatching { Class.forName(name) }.getOrNull()
            out += name.substringAfterLast('.') to (cls?.methods?.filter { it.declaringClass == cls }?.map { it.name }?.distinct()?.sorted()?.joinToString(", ") ?: "无")
        }
        out += "屏幕区域（0 桌面 1 窄 2 宽 3 分屏 4 全屏）" to runCatching {
            val cls = Class.forName("android.app.UnionActivityManager")
            val inst = cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
            cls.getMethod("getScreenAreaInfoForMulti").invoke(inst).toString()
        }.getOrElse { "读不到：${it.javaClass.simpleName}" }
        return out
    }

    /** 分屏相关的系统设置（可能因系统限制读不到，记录原因） */
    fun splitSettings(ctx: Context): List<Pair<String, String>> {
        val r = ctx.contentResolver
        fun sys(k: String) = runCatching { Settings.System.getString(r, k) ?: "（无）" }.getOrElse { "读不到：${it.javaClass.simpleName}" }
        fun glob(k: String) = runCatching { Settings.Global.getString(r, k) ?: "（无）" }.getOrElse { "读不到：${it.javaClass.simpleName}" }
        return listOf(
            "byd_smart_multi_primary_activity" to sys("byd_smart_multi_primary_activity"),
            "byd_smart_multi_second_activity" to sys("byd_smart_multi_second_activity"),
            "byd_smart_multi_primary_position" to sys("byd_smart_multi_primary_position"),
            "byd_smart_multi_split_window_mode" to sys("byd_smart_multi_split_window_mode"),
            "force_resizable_activities" to glob("force_resizable_activities"),
            "enable_freeform_support" to glob("enable_freeform_support"),
        )
    }

    /** 本应用清单里的比亚迪分屏标记是否生效（系统读到的 meta-data） */
    fun splitMetaData(ctx: Context): String = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(ctx.packageName, android.content.pm.PackageManager.GET_META_DATA)
        ai.metaData?.get("BYD_SUPPORT_SPLIT_ACTIVITY")?.let { "$it（${it.javaClass.simpleName}）" } ?: "无"
    }.getOrElse { "读不到：${it.javaClass.simpleName}" }

    // ---------------- 小迪语音 ----------------

    /** 读小迪的一个默认应用设置（值是包名）；读不到返回 null */
    fun readSwitch(ctx: Context, key: String): String? = runCatching {
        ctx.contentResolver.query(PERSON_BEAN, arrayOf("VALUE"), "SETTING=?", arrayOf(key), null)?.use { c ->
            if (c.count == 1 && c.moveToFirst()) c.getString(0).orEmpty() else null
        }
    }.getOrNull()

    /** 条件写入：只有当前值等于 [expected] 时才改成 [value]，返回是否成功（以读回为准） */
    private fun writeSwitch(ctx: Context, key: String, expected: String, value: String): Boolean {
        require(PKG.matches(value))
        val n = runCatching {
            ctx.contentResolver.update(PERSON_BEAN, ContentValues().apply { put("VALUE", value) }, "SETTING=? AND VALUE=?", arrayOf(key, expected))
        }.getOrElse { DebugLog.log("voice", "写 $key 出错：${it.javaClass.simpleName} ${it.message.orEmpty()}"); -1 }
        val back = readSwitch(ctx, key)
        DebugLog.log("voice", "写 $key：$expected → $value，匹配 $n 行，读回 $back")
        return back == value
    }

    /** 小迪默认音乐设为本应用；原值存到 [LocalStore.musicOriginal]，返回给界面的说明 */
    fun takeMusicRole(ctx: Context, store: LocalStore): String {
        val cur = readSwitch(ctx, MUSIC_SWITCH) ?: return "读不到小迪的默认音乐设置".also { DebugLog.log("voice", it) }
        if (cur == ctx.packageName) return "已经是本应用"
        if (PKG.matches(cur)) store.musicOriginal = cur
        return if (writeSwitch(ctx, MUSIC_SWITCH, cur, ctx.packageName)) "已设为本应用（原来是 $cur）" else "设置失败，见车机信息里的日志"
    }

    /** 恢复原来的默认音乐；只有当前仍是本应用时才改，避免覆盖用户在系统里另选的应用 */
    fun restoreMusicRole(ctx: Context, store: LocalStore): String {
        val cur = readSwitch(ctx, MUSIC_SWITCH) ?: return "读不到小迪的默认音乐设置".also { DebugLog.log("voice", it) }
        if (cur != ctx.packageName) return "当前是 $cur，不需要恢复"
        val orig = store.musicOriginal.ifEmpty { "com.byd.mediacenter" }
        return if (writeSwitch(ctx, MUSIC_SWITCH, cur, orig)) "已恢复为 $orig" else "恢复失败，见车机信息里的日志"
    }

    /**
     * 向小迪注册为音乐应用（官方「默认音乐」列表来源）。参数名照 denza-lab 记录，含义未验证，
     * 发了什么全部写进日志。注册不会改默认音乐，需要用户自己在小迪设置里选。
     */
    fun registerMusicApp(ctx: Context): String {
        val extras = linkedMapOf<String, Any>(
            "EXTRA_PACKAGE_NAME" to ctx.packageName,
            "EXTRA_APPLICATION_TYPE" to "MUSIC",
            "ISCONTROL" to true,
            "EXTRA_SERVICE_NAME" to PLAYBACK_SERVICE,
        )
        val intent = Intent("com.byd.action.FUNCTION_UPDATE").setPackage(VOICE_PKG)
        extras.forEach { (k, v) -> if (v is Boolean) intent.putExtra(k, v) else intent.putExtra(k, v.toString()) }
        val r = runCatching { ctx.sendBroadcast(intent); "已发送" }.getOrElse { "发送出错：${it.javaClass.simpleName}" }
        DebugLog.log("voice", "注册音乐应用广播 ${intent.action} → $VOICE_PKG $extras：$r")
        return r
    }
}
