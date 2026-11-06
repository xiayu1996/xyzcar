package com.ryan.xyzcar.data

import kotlinx.serialization.Serializable

/** 节目主题色（接口 color 字段，取自封面）：original 原色，light / dark 是深浅两种变体 */
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
    /** 服务端记录的播放进度（秒），列表展示用 */
    val progressSec: Int = 0,
    /** 所属节目的主题色 */
    val color: ThemeColor? = null,
    /** 付费单集（payType 不是 FREE），第三方客户端拿不到音频 */
    val paid: Boolean = false,
    /** 服务端标记已听完 */
    val finished: Boolean = false,
)

/** 当前账号：只取昵称和头像（接口还返回手机号等，一律不解析、不保存） */
@Serializable
data class User(
    val uid: String,
    val nickname: String = "",
    val avatarUrl: String = "",
)

/** 分页结果：items + 下一页的游标（原样保存 loadMoreKey 的 JSON） */
data class Page<T>(val items: List<T>, val loadMoreKey: kotlinx.serialization.json.JsonElement?)

/** 发现页的一个区块 */
@Serializable
data class DiscoverySection(
    val title: String,
    val podcasts: List<Podcast> = emptyList(),
    val episodes: List<Episode> = emptyList(),
)

/** iconUrl：接口给的分类图标（运行时加载，不打包进 App） */
@Serializable
data class Category(val id: String, val name: String, val iconUrl: String = "")

data class TopList(val title: String, val category: String, val episodes: List<Episode>, val podcasts: List<Podcast>)

/** highlight：服务端给的高亮词 */
data class SearchResult(val podcasts: List<Podcast>, val episodes: List<Episode>, val highlight: List<String> = emptyList())
