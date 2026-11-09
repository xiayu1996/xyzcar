package com.ryan.xyzcar.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser

/**
 * 「车机信息」页的应用对照（只读）：把能分屏 / 能被小迪打开的第三方应用和本应用的启动配置并排列出来，
 * 拍照就能看出差在哪。比亚迪原生分屏按 `byd.intent.category.START_IVI_*` 类别启动应用。
 */
object AppProbe {
    const val CAT_ONE_THIRD = "byd.intent.category.START_IVI_PRIMARY"
    const val CAT_TWO_THIRDS = "byd.intent.category.START_IVI_SECOND"

    data class AppInfo(
        val label: String,
        val pkg: String,
        val self: Boolean,
        /** 一行摘要：targetSdk、可调整大小、启动模式、方向、最小尺寸 */
        val summary: String,
        /** 清单里非 android 标准的 action / category（含 byd.*） */
        val customIntents: List<String>,
        /** 清单里的 meta-data 名（去掉 androidx / Google 等通用库） */
        val metaData: List<String>,
    )

    /** 声明了比亚迪分屏类别的应用包名 */
    fun bydSplitPackages(ctx: Context): List<String> = listOf(CAT_ONE_THIRD, CAT_TWO_THIRDS).flatMap { cat ->
        runCatching {
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
    }.distinct().sorted()

    /** 启动器里的非系统应用（含本应用），本应用排第一 */
    fun thirdPartyApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val launchers = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        return launchers
            .filter { it.activityInfo.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val manifest = readManifest(ctx, ai.packageName)
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ai.packageName,
                    self = ai.packageName == ctx.packageName,
                    summary = summary(ai) + "，可分屏 " + BydBridge.isSupportSplit(ai.packageName, ctx).substringAfter("→ ", "?"),
                    customIntents = manifest?.first ?: listOf("（清单读不到）"),
                    metaData = manifest?.second.orEmpty(),
                )
            }
            .sortedWith(compareByDescending<AppInfo> { it.self }.thenBy { it.label })
            .also { list -> list.forEach { android.util.Log.i("AppProbe", "${it.pkg} | ${it.summary} | ${it.customIntents} | ${it.metaData}") } }
    }

    private fun summary(ai: ActivityInfo): String {
        val resize = runCatching { ActivityInfo::class.java.getField("resizeMode").getInt(ai).toString() }.getOrDefault("?")
        val launch = when (ai.launchMode) {
            ActivityInfo.LAUNCH_MULTIPLE -> "standard"
            ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop"
            ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask"
            ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance"
            else -> ai.launchMode.toString()
        }
        val min = ai.windowLayout?.let { "，最小 ${it.minWidth}×${it.minHeight}" }.orEmpty()
        return "target ${ai.applicationInfo.targetSdkVersion}，resize $resize，$launch，方向 ${ai.screenOrientation}$min"
    }

    /**
     * 小迪的默认应用设置（只读）。小迪把「默认音乐 / 导航 / 视频」等存在 com.byd.autovoice 导出的
     * PersonBean 表里（SETTING / VALUE），其中 *_SWITCH 行的值是包名。只显示这些行，其他行可能是
     * 个人语音设置，只计数不显示。读不到时说明原因。
     */
    fun voiceRoles(ctx: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val ver = runCatching { ctx.packageManager.getPackageInfo(VOICE_PKG, 0).versionName ?: "?" }.getOrNull()
        out += "小迪语音（$VOICE_PKG）" to (ver ?: "未找到")
        if (ver == null) return out
        val provider = runCatching { ctx.packageManager.resolveContentProvider(VOICE_PKG, 0) }.getOrNull()
        out += "语音设置接口" to if (provider == null) "未开放" else "已开放（exported=${provider.exported}）"
        runCatching {
            ctx.contentResolver.query(android.net.Uri.parse("content://$VOICE_PKG/PersonBean"), arrayOf("SETTING", "VALUE"), null, null, null)
        }.fold(
            onSuccess = { c ->
                if (c == null) { out += "默认应用设置" to "读不到（空）"; return out }
                c.use {
                    out += "设置行数" to it.count.toString()
                    while (it.moveToNext()) {
                        val k = it.getString(0) ?: continue
                        if (!k.endsWith("_SWITCH")) continue
                        val v = it.getString(1).orEmpty()
                        out += k to if (PKG.matches(v)) v else if (v.isEmpty()) "（空）" else "（非包名）"
                    }
                }
            },
            onFailure = { out += "默认应用设置" to "读不到：${it.javaClass.simpleName}" },
        )
        return out
    }

    private const val VOICE_PKG = "com.byd.autovoice"
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val noiseMeta =Regex("^(androidx\\.|com\\.google\\.|android\\.support\\.|com\\.android\\.vending|firebase|com\\.facebook\\.)")

    /** 读另一个应用的 AndroidManifest（二进制 XML），取非标准 action / category 和 meta-data 名 */
    private fun readManifest(ctx: Context, pkg: String): Pair<List<String>, List<String>>? = runCatching {
        val parser = ctx.createPackageContext(pkg, 0).assets.openXmlResourceParser("AndroidManifest.xml")
        val intents = linkedSetOf<String>()
        val meta = linkedSetOf<String>()
        parser.use { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                val name = p.getAttributeValue(ANDROID_NS, "name") ?: continue
                when (p.name) {
                    "action", "category" -> if (!name.startsWith("android.") && !name.startsWith("androidx.")) intents += name
                    "meta-data" -> if (!noiseMeta.containsMatchIn(name)) meta += name
                }
            }
        }
        intents.toList() to meta.toList()
    }.getOrNull()

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}
