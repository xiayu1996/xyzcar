package com.ryan.xyzcar.data

import com.ryan.xyzcar.data.api.XyzApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 数据仓库：缓存、节流、进度同步。
 * - 列表内存缓存 5 分钟，订阅列表另存磁盘，弱网时可以浏览
 * - 进度上报失败先存本地，下次上报时一起补发
 */
class Repository(
    val api: XyzApi,
    private val local: LocalStore,
    private val tokens: TokenStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memCache = HashMap<String, Pair<Long, Any>>()
    private val progressLock = Mutex()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, force: Boolean, load: suspend () -> T): T {
        val hit = memCache[key]
        if (!force && hit != null && System.currentTimeMillis() - hit.first < CACHE_MS) return hit.second as T
        val v = load()
        memCache[key] = System.currentTimeMillis() to v
        return v
    }

    fun invalidate(prefix: String) {
        memCache.keys.filter { it.startsWith(prefix) }.forEach { memCache.remove(it) }
    }

    /** debug 包的演示模式：返回样例数据，用于界面截图 */
    var demo = false

    val isLoggedIn get() = tokens.isLoggedIn

    fun logout() {
        tokens.clear()
        memCache.clear()
    }

    // ---------- 订阅（星标置顶） ----------

    data class SubscriptionPage(val items: List<Podcast>, val loadMoreKey: JsonElement?, val fromDisk: Boolean = false)

    suspend fun subscriptionsFirstPage(force: Boolean): SubscriptionPage = if (demo) SubscriptionPage(DemoData.podcasts, null) else try {
        cached("subs", force) {
            val starred = runCatching { api.starredSubscriptions() }.getOrDefault(emptyList())
            val page = api.subscriptions(null)
            val starredIds = starred.map { it.pid }.toSet()
            val merged = starred + page.items.filter { it.pid !in starredIds }
            local.putCache("subs", json.encodeToString(ListSerializer(Podcast.serializer()), merged))
            SubscriptionPage(merged, page.loadMoreKey)
        }
    } catch (e: Exception) {
        if (e is com.ryan.xyzcar.data.api.AuthExpiredException) throw e
        val disk = local.getCache("subs")?.first?.let { runCatching { json.decodeFromString(ListSerializer(Podcast.serializer()), it) }.getOrNull() }
        if (disk.isNullOrEmpty()) throw e
        SubscriptionPage(disk, null, fromDisk = true)
    }

    suspend fun subscriptionsMore(key: JsonElement, existing: Set<String>): SubscriptionPage {
        val page = api.subscriptions(key)
        return SubscriptionPage(page.items.filter { it.pid !in existing }, page.loadMoreKey)
    }

    suspend fun setSubscribed(pid: String, on: Boolean): Podcast? = if (demo) null else api.updateSubscription(pid, on).also { invalidate("subs") }
    suspend fun setStarred(pid: String, star: Boolean): Podcast? = if (demo) null else api.updateStar(pid, star).also { invalidate("subs") }

    // ---------- 节目 / 单集 ----------

    suspend fun podcast(pid: String, force: Boolean = false): Podcast =
        if (demo) (DemoData.podcasts.firstOrNull { it.pid == pid } ?: DemoData.allEpisodes().firstOrNull { it.pid == pid }?.let { e -> Podcast(e.pid, e.podcastTitle, "", "", e.coverUrl, color = e.color) } ?: DemoData.podcasts.first()) else cached("pod:$pid", force) { api.podcast(pid) }

    /** 单集列表 + 合并服务端已听进度 */
    suspend fun episodes(pid: String, key: JsonElement?): Page<Episode> {
        if (demo) return Page(DemoData.podcastEpisodes[pid] ?: DemoData.allEpisodes().filter { it.pid == pid }.ifEmpty { DemoData.episodes }, null)
        val page = if (key == null) cached("eps:$pid", false) { api.episodes(pid, null) } else api.episodes(pid, key)
        return Page(withProgress(page.items), page.loadMoreKey)
    }

    suspend fun withProgress(eps: List<Episode>): List<Episode> {
        if (eps.isEmpty()) return eps
        val map = runCatching { eps.chunked(50).flatMap { api.progress(it.map { e -> e.eid }).entries }.associate { it.key to it.value } }.getOrDefault(emptyMap())
        return eps.map { e -> map[e.eid]?.let { e.copy(progressSec = it) } ?: e }
    }

    suspend fun episode(eid: String): Episode =
        if (demo) (DemoData.allEpisodes().firstOrNull { it.eid == eid } ?: DemoData.episodes.first()) else cached("ep:$eid", false) { api.episode(eid) }

    /** 打开单集前读取服务端进度（秒）；没有记录返回 null */
    suspend fun serverProgress(ep: Episode): Int? = api.progress(listOf(ep.eid))[ep.eid]

    data class LatestPlayed(val episode: Episode, val progressSec: Int, val playedAtMs: Long)

    /** 服务端最近收听的单集（跨设备续播用） */
    suspend fun latestPlayed(): LatestPlayed? {
        val ep = api.history(null).items.firstOrNull() ?: return null
        val (progress, playedAt) = api.progressWithTime(listOf(ep.eid))[ep.eid] ?: return null
        val ts = runCatching { Instant.parse(playedAt).toEpochMilli() }.getOrNull() ?: return null
        return LatestPlayed(ep, progress, ts)
    }

    /** 播放前补齐音频地址（列表接口有时不带 enclosure） */
    suspend fun ensurePlayable(ep: Episode): Episode {
        if (ep.audioUrl.isNotEmpty() && ep.shownotes.isNotEmpty()) return ep
        val full = runCatching { episode(ep.eid) }.getOrNull() ?: return ep
        return full.copy(
            podcastTitle = full.podcastTitle.ifEmpty { ep.podcastTitle },
            coverUrl = full.coverUrl.ifEmpty { ep.coverUrl },
            progressSec = ep.progressSec,
            color = full.color ?: ep.color,
        )
    }

    // ---------- 进度同步 ----------

    private val playedAtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    suspend fun reportProgress(ep: Episode, progressSec: Int) = progressLock.withLock {
        if (ep.pid.isEmpty() || progressSec < 0) return@withLock
        val item = PendingProgress(ep.eid, ep.pid, progressSec, playedAtFmt.format(Instant.now()))
        val batch = (local.pendingProgress.filter { it.eid != ep.eid } + item).takeLast(50)
        try {
            api.updateProgress(batch)
            local.pendingProgress = emptyList()
        } catch (e: Exception) {
            local.pendingProgress = batch // 失败先存本地，下次补发
        }
    }

    suspend fun flushPendingProgress() = progressLock.withLock {
        val pending = local.pendingProgress
        if (pending.isEmpty() || !tokens.isLoggedIn) return@withLock
        runCatching { api.updateProgress(pending) }.onSuccess { local.pendingProgress = emptyList() }
    }

    // ---------- 历史 ----------

    suspend fun history(key: JsonElement?): Page<Episode> {
        if (demo) return Page(DemoData.episodes.filter { it.progressSec > 0 } + DemoData.episodes.take(3), null)
        val page = if (key == null) cached("history", true) { api.history(null) } else api.history(key)
        return Page(withProgress(page.items), page.loadMoreKey)
    }

    // ---------- 发现 ----------

    suspend fun discovery(force: Boolean): List<DiscoverySection> = if (demo) DemoData.sections else cached("discovery", force) {
        val all = mutableListOf<DiscoverySection>()
        var key: String? = null
        // 发现流按区块 token 分段返回，最多拉 4 段
        repeat(4) {
            val (sections, next) = api.discovery(key)
            all += sections
            if (next == null || next == key) return@cached all.distinctBy { it.title }
            key = next
        }
        all.distinctBy { it.title }
    }

    suspend fun topList(category: String, force: Boolean = false): TopList =
        if (demo) TopList("榜单", category, DemoData.topLists[category] ?: DemoData.episodes, emptyList()) else cached("top:$category", force) { api.topList(category) }
    suspend fun editorPicks(key: JsonElement?): Page<Episode> = if (demo) Page(DemoData.editorPicks, null) else api.editorPicks(key)
    suspend fun newEpisodes(): List<Episode> = if (demo) DemoData.episodes else cached("pilot", false) { api.newEpisodesSquare() }
    suspend fun categories(): List<Category> = if (demo) DemoData.categories else cached("cats", false) { api.categories() }
    suspend fun categoryTabs(id: String): List<Category> =
        if (demo) listOf(Category("hot", "热门"), Category("new", "最新")) else cached("cattabs:$id", false) { api.categoryTabs(id) }
    suspend fun categoryPodcasts(id: String, tab: String, key: JsonElement?): Page<Podcast> =
        if (demo) Page(DemoData.podcasts, null) else api.categoryPodcasts(id, tab, key)

    // ---------- 搜索 ----------

    suspend fun search(keyword: String): SearchResult {
        local.recentSearches = (listOf(keyword) + local.recentSearches.filter { it != keyword })
        if (demo) return SearchResult(
            DemoData.podcasts.filter { keyword in it.title }.ifEmpty { DemoData.podcasts.take(2) },
            DemoData.allEpisodes().filter { keyword in it.title || keyword in it.podcastTitle }.ifEmpty { DemoData.episodes },
        )
        val (all, _) = api.search(keyword, "ALL")
        // ALL 只返回少量单集，再单独搜一次单集
        val eps = runCatching { api.search(keyword, "EPISODE").first.episodes }.getOrDefault(emptyList())
        return SearchResult(all.podcasts, (all.episodes + eps).distinctBy { it.eid })
    }

    suspend fun searchPresets(): List<String> = if (demo) DemoData.searchPresets else cached("presets", false) { api.searchPresets() }
    val recentSearches: List<String> get() = local.recentSearches
    fun clearRecentSearches() { local.recentSearches = emptyList() }

    // ---------- 队列 ----------

    val queue: List<Episode> get() = local.queue
    fun enqueue(ep: Episode) { local.queue = local.queue.filter { it.eid != ep.eid } + ep }
    fun removeFromQueue(eid: String) { local.queue = local.queue.filter { it.eid != eid } }
    fun moveInQueue(eid: String, delta: Int) {
        val q = local.queue.toMutableList()
        val i = q.indexOfFirst { it.eid == eid }
        val j = i + delta
        if (i < 0 || j !in q.indices) return
        q.add(j, q.removeAt(i))
        local.queue = q
    }
    fun clearQueue() { local.queue = emptyList() }

    companion object {
        const val CACHE_MS = 5 * 60 * 1000L
    }
}
