package com.ryan.xyzcar.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class LastPlayed(val episode: Episode, val positionMs: Long, val speed: Float = 1f, val savedAt: Long = System.currentTimeMillis())

@Serializable
data class PendingProgress(val eid: String, val pid: String, val progressSec: Int, val playedAt: String)

/**
 * 显示大小每一档对内容区的缩放（左侧导航、迷你播放条、全屏播放页不跟着缩放）。
 * 0.0.4 起「标准」= 以前的「较小」：车机实测以前的标准档卡片太大、一屏内容太少。
 */
val SIZE_SCALES = listOf(0.68f, 0.74f, 0.8f, 0.9f, 1.0f)
val SIZE_LABELS = listOf("较小", "小", "标准", "大", "特大")
const val SIZE_DEFAULT = 2

/** 0.0.2–0.0.3 的 5 档整体缩放，只用来迁移旧设置 */
private val OLD_SIZE_SCALES = listOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f)

/**
 * 本地状态：上次播放、播放队列、待补发的进度、最近搜索、列表缓存。
 * 这几项按账号分开存（键名加 @槽位；迁移来的第一个账号 [AccountStore.MAIN] 沿用原键名），
 * 外观、显示大小、倍速、车机测试、更新提示是整台车机共用的。
 */
class LocalStore(context: Context, private val account: () -> String? = { AccountStore.MAIN }) {
    private val prefs = context.getSharedPreferences("local_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 没有当前账号（添加账号的登录页）时写到 @none，不碰任何账号的数据 */
    private fun scoped(key: String, id: String? = account()): String = when (id) {
        AccountStore.MAIN -> key
        null -> "$key@none"
        else -> "$key@$id"
    }

    var lastPlayed: LastPlayed?
        get() = prefs.getString(scoped("last_played"), null)?.let { runCatching { json.decodeFromString(LastPlayed.serializer(), it) }.getOrNull() }
        set(v) = prefs.edit().putString(scoped("last_played"), v?.let { json.encodeToString(LastPlayed.serializer(), it) }).apply()

    var queue: List<Episode>
        get() = prefs.getString(scoped("queue"), null)?.let { runCatching { json.decodeFromString(ListSerializer(Episode.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString(scoped("queue"), json.encodeToString(ListSerializer(Episode.serializer()), v)).apply()

    var pendingProgress: List<PendingProgress>
        get() = pendingProgressOf(account())
        set(v) = setPendingProgressOf(account(), v)

    /** 某个账号待补发的进度（切换账号时，旧账号最后一次上报可能晚于切换，要记在旧账号名下） */
    fun pendingProgressOf(id: String?): List<PendingProgress> =
        prefs.getString(scoped("pending_progress", id), null)?.let { runCatching { json.decodeFromString(ListSerializer(PendingProgress.serializer()), it) }.getOrNull() } ?: emptyList()

    fun setPendingProgressOf(id: String?, v: List<PendingProgress>) =
        prefs.edit().putString(scoped("pending_progress", id), json.encodeToString(ListSerializer(PendingProgress.serializer()), v)).apply()

    var recentSearches: List<String>
        get() = prefs.getString(scoped("recent_searches"), null)?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString(scoped("recent_searches"), json.encodeToString(ListSerializer(String.serializer()), v.take(10))).apply()

    /** 自动续播后被用户换掉的单集（最近 100 个），之后不再自动选它们 */
    var autoRejected: List<String>
        get() = prefs.getString(scoped("auto_rejected"), null)?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString(scoped("auto_rejected"), json.encodeToString(ListSerializer(String.serializer()), v.takeLast(100))).apply()

    /** 播放列表空了、单集听完后自动接着播（默认开） */
    var autoContinue: Boolean
        get() = prefs.getBoolean("auto_continue", true)
        set(v) = prefs.edit().putBoolean("auto_continue", v).apply()

    /** 自动续播也可以选订阅里刚更新、没听过的单集（默认关：最容易播到不想听的） */
    var autoFresh: Boolean
        get() = prefs.getBoolean("auto_fresh", false)
        set(v) = prefs.edit().putBoolean("auto_fresh", v).apply()

    /** 删除账号时清掉它的本地数据 */
    fun dropAccount(id: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { k ->
            if (id == AccountStore.MAIN) k in SCOPED || (k.startsWith("cache_") && '@' !in k) else k.endsWith("@$id")
        }.forEach { e.remove(it) }
        e.apply()
    }

    /** 外观：light / dark / system（默认跟随系统） */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(v) = prefs.edit().putString("theme_mode", v).apply()

    /**
     * 显示大小 5 档（0 较小 … 2 标准 … 4 特大），对应内容区缩放 [SIZE_SCALES]。
     * 旧版本存的 size_level（0.0.2–0.0.3）或 ui_scale（0.0.1）按实际缩放换算成最接近的新档位，
     * 所以升级后内容大小不变、左侧导航恢复原尺寸。
     */
    var sizeLevel: Int
        get() {
            if (prefs.contains("content_size")) return prefs.getInt("content_size", SIZE_DEFAULT).coerceIn(0, SIZE_SCALES.lastIndex)
            val old = when {
                prefs.contains("size_level") -> OLD_SIZE_SCALES[prefs.getInt("size_level", 2).coerceIn(0, OLD_SIZE_SCALES.lastIndex)]
                prefs.contains("ui_scale") -> prefs.getFloat("ui_scale", 1f)
                else -> return SIZE_DEFAULT
            }
            return SIZE_SCALES.indices.minBy { kotlin.math.abs(SIZE_SCALES[it] - old) }
        }
        set(v) = prefs.edit().putInt("content_size", v.coerceIn(0, SIZE_SCALES.lastIndex)).remove("size_level").remove("ui_scale").apply()

    /** 上次成功检查更新的时间；自动检查每天最多一次 */
    var transcriptOn: Boolean
        get() = prefs.getBoolean("transcript_on", false)
        set(v) = prefs.edit().putBoolean("transcript_on", v).apply()

    /** 上次自动检查更新失败的时间：失败后隔一小时再试，不在每次回到前台时都重试 */
    var lastUpdateFailure: Long
        get() = prefs.getLong("last_update_failure", 0L)
        set(v) = prefs.edit().putLong("last_update_failure", v).apply()

    /** 用户选了「跳过这个版本」的版本号（如 v0.0.7）：自动检查不再提示它，手动检查照常显示 */
    var skippedUpdate: String
        get() = prefs.getString("skipped_update", "") ?: ""
        set(v) = prefs.edit().putString("skipped_update", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(v) = prefs.edit().putLong("last_update_check", v).apply()

    /** 已经提示过的新版本，同一版本只自动提示一次 */
    var notifiedUpdate: String
        get() = prefs.getString("notified_update", "") ?: ""
        set(v) = prefs.edit().putString("notified_update", v).apply()

    // 车机测试开关（默认关）
    /** 小迪默认音乐设为本应用 */
    var labMusicRole: Boolean
        get() = prefs.getBoolean("lab_music_role", false)
        set(v) = prefs.edit().putBoolean("lab_music_role", v).apply()

    /** 设为本应用之前小迪的默认音乐包名，关闭时恢复 */
    var musicOriginal: String
        get() = prefs.getString("music_original", "") ?: ""
        set(v) = prefs.edit().putString("music_original", v).apply()

    /** 上次写进事件日志的车机快照哈希；变化时才整份重写 */
    var lastSnapshotHash: String
        get() = prefs.getString("last_snapshot_hash", "") ?: ""
        set(v) = prefs.edit().putString("last_snapshot_hash", v).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v).apply()

    /** 磁盘缓存：弱网时可以浏览上次加载的列表 */
    fun putCache(key: String, value: String) = prefs.edit().putString(scoped("cache_$key"), value).putLong(scoped("cache_ts_$key"), System.currentTimeMillis()).apply()
    fun getCache(key: String): Pair<String, Long>? {
        val v = prefs.getString(scoped("cache_$key"), null) ?: return null
        return v to prefs.getLong(scoped("cache_ts_$key"), 0)
    }

    private companion object {
        val SCOPED = setOf("last_played", "queue", "pending_progress", "recent_searches", "auto_rejected")
    }
}
