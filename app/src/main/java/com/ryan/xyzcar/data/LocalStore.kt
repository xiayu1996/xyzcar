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

    var uiScale: Float
        get() = prefs.getFloat("ui_scale", 1f)
        set(v) = prefs.edit().putFloat("ui_scale", v).apply()

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
