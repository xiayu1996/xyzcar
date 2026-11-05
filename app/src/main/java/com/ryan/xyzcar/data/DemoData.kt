package com.ryan.xyzcar.data

/**
 * debug 包演示模式用的数据快照：只含公开内容（发现页、榜单、编辑精选、分类、节目和单集），不含账号数据和 token。
 * 由 `./scripts/login-check.sh dump` 生成到 app/src/debug/assets/demo_snapshot.json。
 */
@kotlinx.serialization.Serializable
data class DemoSnapshot(
    val podcasts: List<Podcast>,
    val episodes: List<Episode>,
    val sections: List<DiscoverySection>,
    val topLists: Map<String, List<Episode>> = emptyMap(),
    val editorPicks: List<Episode> = emptyList(),
    val categories: List<Category> = emptyList(),
    val searchPresets: List<String> = emptyList(),
    val podcastEpisodes: Map<String, List<Episode>> = emptyMap(),
)

/** 仅 debug 包的样例数据（不发任何请求）。有快照时用真实公开内容，没有时用下面的合成数据 */
object DemoData {
    fun install(s: DemoSnapshot) {
        podcasts = s.podcasts; episodes = s.episodes; sections = s.sections
        topLists = s.topLists; editorPicks = s.editorPicks.ifEmpty { s.episodes }
        if (s.categories.isNotEmpty()) categories = s.categories
        if (s.searchPresets.isNotEmpty()) searchPresets = s.searchPresets
        podcastEpisodes = s.podcastEpisodes
    }

    fun allEpisodes(): List<Episode> =
        (episodes + editorPicks + topLists.values.flatten() + podcastEpisodes.values.flatten() + sections.flatMap { it.episodes }).distinctBy { it.eid }

    var topLists: Map<String, List<Episode>> = emptyMap(); private set
    var podcastEpisodes: Map<String, List<Episode>> = emptyMap(); private set
    var searchPresets = listOf("随机波动", "历史", "商业", "科技"); private set

    private const val COVER = ""
    var podcasts = listOf(
        Podcast("demo-p1", "随机波动", "随机波动", "关于文化与社会的谈话节目", COVER, starred = true, subscribed = true, latestEpisodePubDate = "2026-11-04T08:00:00.000Z", episodeCount = 120, subscriptionCount = 86000, color = ThemeColor("#E4572E", "#F4A28C", "#8C2A12")),
        Podcast("demo-p2", "忽左忽右", "JustPod", "历史与人文", COVER, subscribed = true, latestEpisodePubDate = "2026-11-03T08:00:00.000Z", episodeCount = 120, subscriptionCount = 86000, color = ThemeColor("#2E86AB", "#8CC7E0", "#174A60")),
        Podcast("demo-p3", "声东击西", "声东击西", "科技、文化与生活", COVER, subscribed = true, latestEpisodePubDate = "2026-10-30T08:00:00.000Z", episodeCount = 120, subscriptionCount = 86000, color = ThemeColor("#F2C14E", "#F8DE9C", "#7A5A10")),
        Podcast("demo-p4", "商业就是这样", "第一财经", "商业故事", COVER, subscribed = true, latestEpisodePubDate = "2026-10-28T08:00:00.000Z", episodeCount = 120, subscriptionCount = 86000, color = ThemeColor("#1B998B", "#7FD3C8", "#0C5249")),
        Podcast("demo-p5", "日谈公园", "日谈公园", "聊天节目", COVER, subscribed = true, latestEpisodePubDate = "2026-10-20T08:00:00.000Z", episodeCount = 120, subscriptionCount = 86000, color = ThemeColor("#8E7DBE", "#C9BFE6", "#4A3D78")),
    ); private set
    var episodes = (1..8).map {
        Episode(
            eid = "demo-e$it", pid = "demo-p1", title = "第 ${100 - it} 期：一个比较长的单集标题，用来测试两行截断的效果",
            podcastTitle = "随机波动", durationSec = 3600 + it * 300, pubDate = "2026-11-0${(it % 5) + 1}T08:00:00.000Z",
            progressSec = if (it % 3 == 0) 1200 else 0,
            description = "00:30 开场\n12:05 正题\n1:02:03 结尾", shownotes = "<p>00:30 开场</p><p>12:05 正题</p><p>1:02:03 结尾</p>",
            audioUrl = "https://example.invalid/demo.mp3",
            color = ThemeColor("#E4572E", "#F4A28C", "#8C2A12"),
        )
    }; private set
    var editorPicks: List<Episode> = episodes; private set
    var sections = listOf(
        DiscoverySection("编辑推荐", episodes = episodes.take(4)),
        DiscoverySection("大家都在听", podcasts = podcasts),
        DiscoverySection("新鲜单集", episodes = episodes.drop(4)),
    ); private set
    var categories = listOf("🎭 人文", "💼 商业", "🔬 科技", "🎬 影视", "📚 知识", "🎵 音乐", "🏃 运动", "🍳 生活").mapIndexed { i, n -> Category("c$i", n) }; private set
}
