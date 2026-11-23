package com.ryan.xyzcar.data

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车机快照、实验开关、导出报告。全部输出给 agent 读：一行一个 `key=value`。
 * 快照只在内容变化时整份写进事件日志，没变化只记一行哈希，避免日志被重复内容撑满。
 */
object CarLab {
    private val VOLATILE = Regex("getScreenAreaInfoForMulti|getLockActivityForMulti|getAppAreaIdForMulti")

    /** 当前快照（只读）：环境 + 分屏探针 + 小迪设置表 + 媒体中心 + 启动器入口 */
    fun snapshot(ctx: Context, version: String): List<Pair<String, String>> {
        val dm = ctx.resources.displayMetrics
        val env = listOf(
            "env.app" to version,
            "env.android" to "${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT}",
            "env.build" to Build.DISPLAY,
            "env.model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "env.display" to "${dm.widthPixels}x${dm.heightPixels}px density=${dm.density}",
            "env.prop.ro.byd.ui.splitscreen" to sysProp("ro.byd.ui.splitscreen"),
            "env.prop.ro.build.ui_platformized" to sysProp("ro.build.ui_platformized"),
            "env.launcher.label" to launcherLabels(ctx),
            "env.can_install" to runCatching { Updater.canInstall(ctx).toString() }.getOrElse { "error:${it.javaClass.simpleName}" },
            "env.installer.self" to (Updater.selfInstaller(ctx) ?: "null"),
        )
        return env + BydBridge.splitProbe(ctx) + BydBridge.voiceProbe(ctx) + BydBridge.mediaProbe(ctx)
    }

    /** 每次进程启动跑一次（后台线程） */
    fun onAppStart(ctx: Context, store: LocalStore, version: String) {
        DebugLog.kv(
            "start", "process", "app" to version, "pid" to android.os.Process.myPid(),
            "lab.music" to store.labMusicRole,
            "music_switch" to BydBridge.readSwitch(ctx, BydBridge.MUSIC_SWITCH),
        )
        val snap = snapshot(ctx, version)
        // 每次启动都可能变的项（当前屏幕区域、任务 / 锁定状态、小迪设置里的长文本）不参与哈希，单独每次记
        val volatile = snap.filter { (k, v) -> VOLATILE.containsMatchIn(k) || (k.startsWith("voice.row.") && v.startsWith("len=")) }
        val hash = Integer.toHexString((snap - volatile.toSet()).joinToString("\n").hashCode())
        if (hash != store.lastSnapshotHash) {
            DebugLog.kv("snap", "begin", "hash" to hash)
            (snap + BydBridge.unionSignatures() + ("tx.all" to BydBridge.transactions().all.entries.joinToString(",") { "${it.key}#${it.value}" }))
                .forEach { (k, v) -> DebugLog.log("snap", "$k=${DebugLog.fmtValue(v)}") }
            DebugLog.kv("snap", "end", "hash" to hash)
            store.lastSnapshotHash = hash
        } else {
            DebugLog.kv("snap", "unchanged", "hash" to hash)
            volatile.filter { !it.first.startsWith("voice.row.") }.forEach { (k, v) -> DebugLog.log("snap", "$k=${DebugLog.fmtValue(v)}") }
        }
        // 3 分屏：每次进程启动都加入系统分屏常驻列表（固件只存在内存里，重启就丢；开机广播会拉起进程，所以重启后不用先手动打开）
        BydBridge.addPersistentSplitApp(ctx)
        if (store.labMusicRole && BydBridge.readSwitch(ctx, BydBridge.MUSIC_SWITCH).let { it != null && it != ctx.packageName }) {
            DebugLog.kv("voice", "music_switch_reset_by_system", "reapply" to BydBridge.takeMusicRole(ctx, store))
        }
    }

    /** 启用的启动器入口和名字（含主入口） */
    fun launcherLabels(ctx: Context): String = runCatching {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ctx.packageName), 0)
            .joinToString(",") { "${it.activityInfo.name.substringAfterLast('.')}:${it.loadLabel(ctx.packageManager)}" }
    }.getOrElse { "error:${it.javaClass.simpleName}" }

    /**
     * 导出报告（给 agent）：当前快照 + 应用对照 + 精简后的事件日志（连续重复合并，最多 400 行）。
     * 不含系统属性全集（里面有蓝牙名）；日志写入时已打码。应用对照会列出车机上装了哪些第三方应用。
     */
    fun report(ctx: Context, version: String): String = DebugLog.redact(buildString {
        append("# xyzcar-report v=2 app=").append(version).append(" at=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        append("## snapshot\n")
        snapshot(ctx, version).forEach { (k, v) -> append(k).append('=').append(DebugLog.fmtValue(v)).append('\n') }
        append("## apps\n")
        AppProbe.thirdPartyApps(ctx).forEach { append(it.agentLine()).append('\n') }
        append("## log\n").append(DebugLog.compact(DebugLog.read()))
    })

    fun reportFileName(): String = "xyzcar-report-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    /** 启动 intent 的摘要：不记 extras 的值，只记键名 */
    fun describe(activity: Activity, intent: Intent?, how: String): String {
        val i = intent ?: return "$how intent=null"
        val from = runCatching { activity.referrer?.toString() }.getOrNull()
        val launchedFrom = if (Build.VERSION.SDK_INT >= 34) runCatching { activity.launchedFromPackage }.getOrNull() else null
        return "$how action=${i.action} cat=${i.categories?.joinToString(",")} flags=0x${Integer.toHexString(i.flags)} cmp=${i.component?.className} " +
            "extras=${i.extras?.keySet()?.joinToString(",")} referrer=$from launchedFrom=$launchedFrom ${window(activity)}"
    }

    /** 当前窗口状态：是否分屏、窗口大小、显示屏、系统的窗口配置（含窗口模式和边界） */
    fun window(activity: Activity): String {
        val c = activity.resources.configuration
        val winCfg = Regex("winConfig=\\{[^}]*\\}").find(c.toString())?.value?.let { DebugLog.fmtValue(it.removePrefix("winConfig=")) } ?: "?"
        val display = runCatching { activity.display?.displayId }.getOrNull()
        return "multiWindow=${activity.isInMultiWindowMode} sizeDp=${c.screenWidthDp}x${c.screenHeightDp} display=$display winConfig=$winCfg"
    }

    private fun sysProp(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("").ifEmpty { "null" }
}
