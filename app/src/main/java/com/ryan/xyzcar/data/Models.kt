package com.ryan.xyzcar.data

import kotlinx.serialization.Serializable

/** 接口 color 字段，取自封面 */
@Serializable
data class ThemeColor(val original: String = "", val light: String = "", val dark: String = "")

@Serializable
data class Podcast(
    val pid: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val subscriptionCount: Int = 0,
    val episodeCount: Int = 0,
    val latestEpisodePubDate: String = "",
    val subscribed: Boolean = false,
    val starred: Boolean = false,
    val hasUpdate: Boolean = false,
    val color: ThemeColor? = null,
)

@Serializable
data class Episode(
    val eid: String,
    val pid: String,
    val title: String,
    val podcastTitle: String = "",
    val description: String = "",
    val shownotes: String = "",
    val durationSec: Int = 0,
    val pubDate: String = "",
    val audioUrl: String = "",
    val coverUrl: String = "",
    val playCount: Int = 0,
    /** 服务端记录的进度（秒） */
    val progressSec: Int = 0,
    val color: ThemeColor? = null,
    /** payType 不是 FREE，第三方客户端拿不到音频 */
    val paid: Boolean = false,
    val finished: Boolean = false,
    /** 空表示没有文字稿 */
    val transcriptMediaId: String = "",
)

data class TranscriptLine(val startMs: Long, val text: String)

/** 列表要缓存很多集，去掉常有几 KB 的 shownotes、截短简介，完整内容打开详情时再取 */
fun Episode.lite(): Episode = if (shownotes.isEmpty() && description.length <= LITE_DESC) this
    else copy(shownotes = "", description = description.take(LITE_DESC))

private const val LITE_DESC = 300

fun Episode.isTodo(): Boolean = !finished && !(durationSec > 0 && progressSec >= durationSec - 30)

/** 接口还返回手机号等，一律不解析、不保存 */
@Serializable
data class User(
    val uid: String,
    val nickname: String = "",
    val avatarUrl: String = "",
)

/** loadMoreKey 原样保存接口返回的 JSON */
data class Page<T>(val items: List<T>, val loadMoreKey: kotlinx.serialization.json.JsonElement?)

/** [topCategory] 非空时是榜单 */
@Serializable
data class DiscoverySection(
    val title: String,
    val podcasts: List<Podcast> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val topCategory: String = "",
)

val TOP_LIST_TITLES = linkedMapOf(
    "HOT_EPISODES_IN_24_HOURS" to "最热榜",
    "SKYROCKET_EPISODES" to "锋芒榜",
    "NEW_STAR_EPISODES" to "新星榜",
)

@Serializable
data class Category(val id: String, val name: String, val iconUrl: String = "")

data class TopList(val title: String, val category: String, val episodes: List<Episode>, val podcasts: List<Podcast>)

data class SearchResult(val podcasts: List<Podcast>, val episodes: List<Episode>, val highlight: List<String> = emptyList())
