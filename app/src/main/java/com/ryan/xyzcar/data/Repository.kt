package com.ryan.xyzcar.data

import com.ryan.xyzcar.data.api.XyzApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class Repository(
    val api: XyzApi,
    private val local: LocalStore,
    private val tokens: TokenStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** accessOrder = true：按访问顺序淘汰；过期项在写入时清掉 */
    private val memCache = object : LinkedHashMap<String, Pair<Long, Any>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Any>>?) = size > MAX_CACHED
    }
    private val progressLock = Mutex()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, force: Boolean, load: suspend () -> T): T {
        val hit = synchronized(memCache) { memCache[key] }
        if (!force && hit != null && System.currentTimeMillis() - hit.first < CACHE_MS) return hit.second as T
        val v = load()
        val now = System.currentTimeMillis()
        synchronized(memCache) {
            memCache.entries.removeAll { now - it.value.first >= CACHE_MS }
            memCache[key] = now to v
        }
        return v
    }

    fun invalidate(prefix: String) = synchronized(memCache) {
        memCache.keys.removeAll { it.startsWith(prefix) }
    }

    /** 页面自己的状态还在，放掉列表缓存回来也不会白屏 */
    fun trimMemory() {
        synchronized(memCache) { memCache.clear() }
        transcriptCache = null
    }

    var demo = false

    val isLoggedIn get() = tokens.isLoggedIn

    val accountId: String? get() = tokens.activeId
    fun accounts(): List<Account> = tokens.accounts()

    /** null 用于添加账号的登录页；加锁让切换前发出的上报和 token 刷新落在旧账号上；不刷新 token */
    suspend fun switchAccount(id: String?) {
        progressLock.withLock { api.withAuthLock { tokens.activate(id) } }
        memCache.clear()
        profileCache = null
    }

    suspend fun removeAccount(id: String) {
        progressLock.withLock { api.withAuthLock { tokens.remove(id) } }
        local.dropAccount(id)
        memCache.clear()
        profileCache = null
    }

    /** 成功返回 null，失败返回原因 */
    suspend fun finishLogin(): Throwable? {
        val id = tokens.activeId ?: return null
        val user = runCatching { api.profile() }.getOrElse { return it }
        progressLock.withLock {
            api.withAuthLock {
                tokens.setProfile(id, user)
                tokens.mergeDuplicate(id)
            }
        }
        memCache.clear()
        profileCache = user
        return null
    }

    fun logout() {
        tokens.clear()
        memCache.clear()
        profileCache = null
    }

    data class SubscriptionPage(val items: List<Podcast>, val loadMoreKey: JsonElement?, val fromDisk: Boolean = false)

    suspend fun subscriptionsFirstPage(force: Boolean): SubscriptionPage = if (demo) SubscriptionPage(DemoData.podcasts, null) else try {
        cached("subs", force) {
            val page = api.subscriptions(null)
            local.putCache("subs", json.encodeToString(ListSerializer(Podcast.serializer()), page.items))
            SubscriptionPage(page.items, page.loadMoreKey)
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

    suspend fun podcast(pid: String, force: Boolean = false): Podcast =
        if (demo) (DemoData.podcasts.firstOrNull { it.pid == pid } ?: DemoData.allEpisodes().firstOrNull { it.pid == pid }?.let { e -> Podcast(e.pid, e.podcastTitle, "", "", e.coverUrl, color = e.color) } ?: DemoData.podcasts.first()) else cached("pod:$pid", force) { api.podcast(pid) }

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

    suspend fun serverProgress(ep: Episode): Int? = api.progress(listOf(ep.eid))[ep.eid]

    data class LatestPlayed(val episode: Episode, val progressSec: Int, val playedAtMs: Long)

    suspend fun latestPlayed(): LatestPlayed? {
        val ep = api.history(null).items.firstOrNull() ?: return null
        val (progress, playedAt) = api.progressWithTime(listOf(ep.eid))[ep.eid] ?: return null
        val ts = runCatching { Instant.parse(playedAt).toEpochMilli() }.getOrNull() ?: return null
        return LatestPlayed(ep, progress, ts)
    }

    /** 列表接口有时不带 enclosure，播放前补齐 */
    suspend fun ensurePlayable(ep: Episode): Episode {
        // 列表单集不带 shownotes（见 lite()），但播放用不到，不多等一次请求
        if (ep.audioUrl.isNotEmpty() && ep.podcastTitle.isNotEmpty()) return ep
        val full = runCatching { episode(ep.eid) }.getOrNull() ?: return ep
        return full.copy(
            podcastTitle = full.podcastTitle.ifEmpty { ep.podcastTitle },
            coverUrl = full.coverUrl.ifEmpty { ep.coverUrl },
            progressSec = ep.progressSec,
            color = full.color ?: ep.color,
        )
    }

    private class CachedTranscript(val eid: String, val lines: List<TranscriptLine>)
    /** 一集上百 KB，只留当前这一集，不进列表缓存 */
    @Volatile private var transcriptCache: CachedTranscript? = null

    /** mediaId 从单集详情取：播放器里的单集对象不一定带 */
    suspend fun transcript(eid: String): List<TranscriptLine> {
        if (demo) return DemoData.transcript()
        transcriptCache?.takeIf { it.eid == eid }?.let { return it.lines }
        val mediaId = episode(eid).transcriptMediaId
        val lines = if (mediaId.isEmpty()) emptyList() else api.transcript(eid, mediaId)
        transcriptCache = CachedTranscript(eid, lines)
        return lines
    }

    private val playedAtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** [account] 是取到进度那一刻的账号；已经切走就记在它名下，切回来再补发 */
    suspend fun reportProgress(ep: Episode, progressSec: Int, account: String? = tokens.activeId) = progressLock.withLock {
        if (ep.pid.isEmpty() || progressSec < 0 || account == null) return@withLock
        val item = PendingProgress(ep.eid, ep.pid, progressSec, playedAtFmt.format(Instant.now()))
        if (account != tokens.activeId) {
            if (account !in tokens.ids) return@withLock
            local.setPendingProgressOf(account, (local.pendingProgressOf(account).filter { it.eid != ep.eid } + item).takeLast(50))
            return@withLock
        }
        val batch = (local.pendingProgress.filter { it.eid != ep.eid } + item).takeLast(50)
        try {
            api.updateProgress(batch)
            local.pendingProgress = emptyList()
        } catch (e: Exception) {
            local.pendingProgress = batch
        }
    }

    suspend fun flushPendingProgress() = progressLock.withLock {
        val pending = local.pendingProgress
        if (pending.isEmpty() || !tokens.isLoggedIn) return@withLock
        runCatching { api.updateProgress(pending) }.onSuccess { local.pendingProgress = emptyList() }
    }

    /** 合并服务端进度，用来区分未听 / 在听 / 已听完 */
    suspend fun inbox(key: JsonElement?, force: Boolean = false): Page<Episode> {
        if (demo) return Page(DemoData.allEpisodes().take(12), null)
        val page = if (key == null) cached("inbox", force) { api.inbox(null) } else api.inbox(key)
        return Page(withProgress(page.items), page.loadMoreKey)
    }

    @Volatile private var profileCache: User? = null

    suspend fun profile(): User {
        profileCache?.let { return it }
        if (demo) return User("demo", "演示账号").also { profileCache = it }
        val id = tokens.activeId
        return api.profile().also { u ->
            if (id != null && id == tokens.activeId) { profileCache = u; tokens.setProfile(id, u) }
        }
    }

    suspend fun history(key: JsonElement?): Page<Episode> {
        if (demo) return Page(DemoData.episodes.filter { it.progressSec > 0 } + DemoData.episodes.take(3), null)
        val page = if (key == null) cached("history", true) { api.history(null) } else api.history(key)
        return Page(withProgress(page.items), page.loadMoreKey)
    }

    suspend fun discovery(force: Boolean): List<DiscoverySection> =
        if (demo) withFullLists(DemoData.sections, { DemoData.topLists[it].orEmpty() }, { DemoData.editorPicks })
        else cached("discovery", force) {
            val all = mutableListOf<DiscoverySection>()
            var key: String? = null
            // 发现流按区块 token 分段返回
            for (i in 0 until 4) {
                val (sections, next) = api.discovery(key)
                all += sections
                if (next == null || next == key) break
                key = next
            }
            withFullLists(all.distinctBy { it.title }, { topList(it, force).episodes }, { editorPicksFirst(force) })
        }

    /** 发现流里榜单、编辑精选只给 3 集（2026-11 实测），完整接口有 15 集；取不到就保留原来的 3 集 */
    private suspend fun withFullLists(
        sections: List<DiscoverySection>,
        top: suspend (String) -> List<Episode>,
        picks: suspend () -> List<Episode>,
    ): List<DiscoverySection> = coroutineScope {
        sections.map { s ->
            val cat = s.topCategory.ifEmpty { TOP_LIST_TITLES.entries.firstOrNull { it.value == s.title }?.key.orEmpty() }
            async {
                val full = try {
                    when {
                        cat.isNotEmpty() -> top(cat)
                        s.title == "编辑精选" -> picks()
                        else -> null
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                s.copy(topCategory = cat, episodes = full?.takeIf { it.size > s.episodes.size } ?: s.episodes)
            }
        }.awaitAll()
    }

    private suspend fun editorPicksFirst(force: Boolean): List<Episode> = cached("picks:first", force) { api.editorPicks(null).items }

    suspend fun topList(category: String, force: Boolean = false): TopList =
        if (demo) TopList("榜单", category, DemoData.topLists[category] ?: DemoData.episodes, emptyList()) else cached("top:$category", force) { api.topList(category) }
    suspend fun editorPicks(key: JsonElement?): Page<Episode> = if (demo) Page(DemoData.editorPicks, null) else api.editorPicks(key)
    suspend fun newEpisodes(): List<Episode> = if (demo) DemoData.episodes else cached("pilot", false) { api.newEpisodesSquare() }
    suspend fun categories(): List<Category> = if (demo) DemoData.categories else cached("cats", false) { api.categories() }
    /** 「推荐」页的「综合」即发现页的「为你推荐」 */
    suspend fun recommend(force: Boolean): List<Episode> =
        discovery(force).firstOrNull { it.episodes.isNotEmpty() }?.episodes.orEmpty()

    /** 「换一批」：只在用户点按钮时调用 */
    suspend fun refreshRecommend(): List<Episode> = if (demo) DemoData.allEpisodes().shuffled().take(3) else api.refreshRecommend()

    /** [tab]：RECOMMEND 为你推荐 / HOT 近期热门 */
    suspend fun categoryEpisodes(id: String, tab: String, force: Boolean = false): List<Episode> =
        if (demo) DemoData.allEpisodes().let { all -> all.drop(id.hashCode().mod(maxOf(1, all.size - 6))).take(8) }
        else cached("catEps:$id:$tab", force) { api.categoryEpisodes(id, tab) }

    suspend fun categoryTabs(id: String): List<Category> =
        if (demo) listOf(Category("hot", "热门"), Category("new", "最新")) else cached("cattabs:$id", false) { api.categoryTabs(id) }
    suspend fun categoryPodcasts(id: String, tab: String, key: JsonElement?): Page<Podcast> =
        if (demo) Page(DemoData.podcasts, null) else api.categoryPodcasts(id, tab, key)

    /** type：ALL 综合（一页）/ PODCAST / EPISODE（可翻页）；服务端排序和官方一致（2026-11 实测），不自己重排 */
    suspend fun search(keyword: String, type: String = "ALL", key: JsonElement? = null): Pair<SearchResult, JsonElement?> {
        if (key == null && type == "ALL") local.recentSearches = (listOf(keyword) + local.recentSearches.filter { it != keyword })
        if (demo) {
            val pods = DemoData.podcasts.filter { keyword in it.title }.ifEmpty { DemoData.podcasts.take(2) }
            val eps = DemoData.allEpisodes().filter { keyword in it.title || keyword in it.podcastTitle }.ifEmpty { DemoData.episodes }
            return SearchResult(if (type == "EPISODE") emptyList() else pods, if (type == "PODCAST") emptyList() else eps, listOf(keyword)) to null
        }
        return api.search(keyword, type, key)
    }

    suspend fun searchPresets(): List<String> = if (demo) DemoData.searchPresets else cached("presets", false) { api.searchPresets() }
    val recentSearches: List<String> get() = local.recentSearches
    fun clearRecentSearches() { local.recentSearches = emptyList() }

    /** 不走缓存：刚听完的进度可能还没上报完，靠 [exclude] 排除 */
    suspend fun pickNext(finished: Episode, exclude: Set<String>): NextPicker.Pick? {
        if (demo) return DemoData.allEpisodes().firstOrNull { it.eid != finished.eid && it.eid !in exclude }?.let { NextPicker.Pick(it, NextPicker.Reason.InProgress) }
        val sameShow = runCatching { neighborsInShow(finished) }.getOrDefault(emptyList())
        val history = runCatching {
            val eps = api.history(null).items
            val prog = api.progressWithTime(eps.map { it.eid })
            eps.mapNotNull { e ->
                val (sec, at) = prog[e.eid] ?: return@mapNotNull null
                NextPicker.parseTime(at)?.let { NextPicker.Played(e.copy(progressSec = sec), it) }
            }
        }.getOrDefault(emptyList())
        val fresh = if (!local.autoFresh) emptyList() else runCatching { withProgress(api.inbox(null).items) }.getOrDefault(emptyList())
        return NextPicker.choose(
            finished, sameShow, history, fresh, System.currentTimeMillis(),
            local.autoRejected.toSet(), exclude, local.autoFresh,
        )
    }

    /** 返回比它新的一集和它自己（最新在前，带进度） */
    private suspend fun neighborsInShow(ep: Episode): List<Episode> {
        if (ep.pid.isEmpty()) return emptyList()
        var key: JsonElement? = null
        var prev: Episode? = null
        repeat(5) {
            val page = api.episodes(ep.pid, key)
            val list = listOfNotNull(prev) + page.items
            val i = list.indexOfFirst { it.eid == ep.eid }
            if (i == 0) return list.take(1)
            if (i > 0) return withProgress(list.subList(i - 1, i + 1))
            prev = page.items.lastOrNull()
            key = page.loadMoreKey ?: return emptyList()
        }
        return emptyList()
    }

    fun rejectAuto(eid: String) { local.autoRejected = local.autoRejected.filter { it != eid } + eid }

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
        /** 够来回切换几个分类、节目，又不会越积越多 */
        const val MAX_CACHED = 40
    }
}
