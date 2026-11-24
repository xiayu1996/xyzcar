package com.ryan.xyzcar.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser

/** 比亚迪原生分屏按 `byd.intent.category.START_IVI_*` 类别启动应用 */
object AppProbe {
    const val CAT_ONE_THIRD = "byd.intent.category.START_IVI_PRIMARY"
    const val CAT_TWO_THIRDS = "byd.intent.category.START_IVI_SECOND"

    data class AppInfo(
        val label: String,
        val pkg: String,
        val self: Boolean,
        val summary: String,
        val customIntents: List<String>,
        val metaData: List<String>,
        /** 分屏选择列表可能按安装来源过滤 */
        val installer: String = "?",
        val split: String = "?",
    ) {
        fun agentLine() = "app pkg=$pkg label=${DebugLog.fmtValue(label)} self=$self installer=$installer split=$split " +
            "summary=${DebugLog.fmtValue(summary)} intents=${customIntents.joinToString(",")} meta=${metaData.joinToString(",")}"
    }

    fun bydSplitPackages(ctx: Context): List<String> = listOf(CAT_ONE_THIRD, CAT_TWO_THIRDS).flatMap { cat ->
        runCatching {
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
    }.distinct().sorted()

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
                val split = BydBridge.isSupportSplit(ai.packageName, ctx)
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ai.packageName,
                    self = ai.packageName == ctx.packageName,
                    summary = summary(ai) + "，可分屏 " + split,
                    customIntents = manifest?.first ?: listOf("（清单读不到）"),
                    metaData = manifest?.second.orEmpty(),
                    installer = installer(ctx, ai.packageName),
                    split = split,
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

    private fun installer(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).let { "${it.installingPackageName}/${it.initiatingPackageName}" }
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg).toString()
    }.getOrElse { "error:${it.javaClass.simpleName}" }

    private val noiseMeta = Regex("^(androidx\\.|com\\.google\\.|android\\.support\\.|com\\.android\\.vending|firebase|com\\.facebook\\.)")

    fun readManifest(ctx: Context, pkg: String): Pair<List<String>, List<String>>? = runCatching {
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
