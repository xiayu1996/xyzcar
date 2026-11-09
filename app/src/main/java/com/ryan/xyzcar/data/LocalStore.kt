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

/** 本地状态：上次播放、播放队列、待补发的进度、最近搜索、列表缓存 */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var lastPlayed: LastPlayed?
        get() = prefs.getString("last_played", null)?.let { runCatching { json.decodeFromString(LastPlayed.serializer(), it) }.getOrNull() }
        set(v) = prefs.edit().putString("last_played", v?.let { json.encodeToString(LastPlayed.serializer(), it) }).apply()

    var queue: List<Episode>
        get() = prefs.getString("queue", null)?.let { runCatching { json.decodeFromString(ListSerializer(Episode.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("queue", json.encodeToString(ListSerializer(Episode.serializer()), v)).apply()

    var pendingProgress: List<PendingProgress>
        get() = prefs.getString("pending_progress", null)?.let { runCatching { json.decodeFromString(ListSerializer(PendingProgress.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("pending_progress", json.encodeToString(ListSerializer(PendingProgress.serializer()), v)).apply()

    var recentSearches: List<String>
        get() = prefs.getString("recent_searches", null)?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("recent_searches", json.encodeToString(ListSerializer(String.serializer()), v.take(10))).apply()

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
    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(v) = prefs.edit().putLong("last_update_check", v).apply()

    /** 已经提示过的新版本，同一版本只自动提示一次 */
    var notifiedUpdate: String
        get() = prefs.getString("notified_update", "") ?: ""
        set(v) = prefs.edit().putString("notified_update", v).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v).apply()

    /** 磁盘缓存：弱网时可以浏览上次加载的列表 */
    fun putCache(key: String, value: String) = prefs.edit().putString("cache_$key", value).putLong("cache_ts_$key", System.currentTimeMillis()).apply()
    fun getCache(key: String): Pair<String, Long>? {
        val v = prefs.getString("cache_$key", null) ?: return null
        return v to prefs.getLong("cache_ts_$key", 0)
    }
}
