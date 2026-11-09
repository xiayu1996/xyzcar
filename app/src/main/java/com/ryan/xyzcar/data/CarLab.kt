package com.ryan.xyzcar.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build

/** 启动时的车机快照和实验开关的执行；所有结果写进 [DebugLog] */
object CarLab {
    /** 每次进程启动跑一次（后台线程） */
    fun onAppStart(ctx: Context, store: LocalStore, version: String) {
        val tx = BydBridge.transactions()
        DebugLog.log(
            "start",
            "$version Android ${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} ${Build.DISPLAY}；" +
                "分屏标记 ${BydBridge.splitMetaData(ctx)}；系统事务可见 ${tx.visible} 个，分屏相关 ${tx.split}${tx.error?.let { "（$it）" }.orEmpty()}；" +
                "本应用 ${BydBridge.isSupportSplit(ctx.packageName, ctx)}；" +
                "实验开关 注册=${store.labVoiceRegister} 默认音乐=${store.labMusicRole} 分屏常驻=${store.labSplitPersist}",
        )
        DebugLog.log("start", BydBridge.unionInfo(ctx).joinToString("；") { "${it.first}=${it.second}" })
        DebugLog.log("start", BydBridge.splitSettings(ctx).joinToString("；") { "${it.first}=${it.second}" })
        DebugLog.log("start", "小迪：" + AppProbe.voiceRoles(ctx).joinToString("；") { "${it.first}=${it.second}" })
        if (store.labSplitPersist) BydBridge.addPersistentSplitApp(ctx)
        if (store.labVoiceRegister) BydBridge.registerMusicApp(ctx)
        if (store.labMusicRole && BydBridge.readSwitch(ctx, BydBridge.MUSIC_SWITCH).let { it != null && it != ctx.packageName }) {
            DebugLog.log("voice", "默认音乐被系统改回，重新设置：" + BydBridge.takeMusicRole(ctx, store))
        }
    }

    /** 启动 intent 的摘要：不记 extras 的值，只记键名 */
    fun describe(activity: Activity, intent: Intent?, how: String): String {
        val i = intent ?: return "$how：无 intent"
        val from = runCatching { activity.referrer?.toString() }.getOrNull()
        val launchedFrom = if (Build.VERSION.SDK_INT >= 34) runCatching { activity.launchedFromPackage }.getOrNull() else null
        return "$how：action=${i.action} cat=${i.categories} flags=0x${Integer.toHexString(i.flags)} cmp=${i.component?.shortClassName} " +
            "extras=${i.extras?.keySet()} referrer=$from launchedFrom=$launchedFrom；${window(activity)}"
    }

    /** 当前窗口状态：是否分屏、窗口大小、显示屏、系统的窗口配置（含窗口模式和边界） */
    fun window(activity: Activity): String {
        val c = activity.resources.configuration
        val winCfg = Regex("winConfig=\\{[^}]*\\}").find(c.toString())?.value ?: "winConfig=?"
        val display = runCatching { activity.display?.displayId }.getOrNull()
        return "multiWindow=${activity.isInMultiWindowMode} ${c.screenWidthDp}x${c.screenHeightDp}dp display=$display $winCfg"
    }
}
