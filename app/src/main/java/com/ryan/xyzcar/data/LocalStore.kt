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

/** 显示大小每一档的整体缩放 */
val SIZE_SCALES = listOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f)
val SIZE_LABELS = listOf("较小", "小", "标准", "大", "特大")

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
     * 显示大小 5 档（0 较小 … 2 标准 … 4 特大），对应整体缩放 [SIZE_SCALES]。
     * 0.0.1 存的是缩放比例 ui_scale（0.7–1.3），第一次读取时换算成最接近的一档。
     */
    var sizeLevel: Int
        get() {
            if (prefs.contains("size_level")) return prefs.getInt("size_level", 2).coerceIn(0, SIZE_SCALES.lastIndex)
            val old = prefs.getFloat("ui_scale", 1f)
            return SIZE_SCALES.indices.minBy { kotlin.math.abs(SIZE_SCALES[it] - old) }
        }
        set(v) = prefs.edit().putInt("size_level", v.coerceIn(0, SIZE_SCALES.lastIndex)).remove("ui_scale").apply()

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
