package com.ryan.xyzcar.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ryan.xyzcar.App
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Page
import com.ryan.xyzcar.data.api.AuthExpiredException
import com.ryan.xyzcar.player.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ryan.xyzcar.BuildConfig
import com.ryan.xyzcar.data.Updater
import com.ryan.xyzcar.data.PublicFiles
import com.ryan.xyzcar.data.DebugLog
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** 收听历史、设置放在「我的」里，与官方一致 */
enum class Tab(val label: String) { Discover("发现"), Recommend("推荐"), Subscriptions("订阅"), Search("搜索"), Me("我的") }

sealed class Route(val key: String) {
    data class Home(val tab: Tab) : Route("tab:${tab.name}")
    data class PodcastDetail(val pid: String) : Route("pod:$pid")
    data class EpisodeDetail(val episode: Episode) : Route("ep:${episode.eid}")
    data class TopListRoute(val category: String, val title: String) : Route("top:$category")
    data object EditorPicks : Route("picks")
    data object SubsShows : Route("subs-shows")
    data object History : Route("history")
    data object Queue : Route("queue")
    data object Diagnostics : Route("diag")
}

enum class NavDir { Forward, Back, TabUp, TabDown }

fun errorText(e: Throwable): String = when (e) {
    is AuthExpiredException -> e.message.orEmpty()
    is com.ryan.xyzcar.data.api.ApiException -> e.message.orEmpty()
    is IOException -> "网络不可用，请检查车机网络"
    else -> "出错了：${e.message ?: e.javaClass.simpleName}"
}

class LoadHolder<T : Any>(
    private val scope: CoroutineScope,
    private val onAuthExpired: () -> Unit,
    private val loader: suspend (force: Boolean) -> T,
) {
    var data by mutableStateOf<T?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val listState = LazyListState()
    private var job: Job? = null

    fun load(force: Boolean = false) {
        if (loading && !force) return
        job?.cancel()
        job = scope.launch {
            loading = true; error = null
            try {
                data = loader(force)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is AuthExpiredException) onAuthExpired()
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun ensureLoaded() { if (data == null && !loading) load() }
}

class PagedHolder<T>(
    private val scope: CoroutineScope,
    private val onAuthExpired: () -> Unit,
    private val dedupeKey: (T) -> String,
    private val loader: suspend (key: JsonElement?) -> Page<T>,
) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var end by mutableStateOf(false)
    var loadedOnce by mutableStateOf(false)
    val listState = LazyListState()
    private var key: JsonElement? = null

    fun refresh() {
        key = null; end = false
        fetch(reset = true)
    }

    /** 失败后不自动重试，等用户点「重试」，否则断网或筛选不满一屏时会不停发请求 */
    fun loadMore() {
        if (loading || end || !loadedOnce || error != null) return
        fetch(reset = false)
    }

    fun retry() {
        error = null
        loadMore()
    }

    fun ensureLoaded() { if (!loadedOnce && !loading) refresh() }

    private fun fetch(reset: Boolean) {
        scope.launch {
            loading = true; error = null
            try {
                val page = loader(if (reset) null else key)
                if (reset) items.clear()
                val seen = items.map(dedupeKey).toHashSet()
                items.addAll(page.items.filter { seen.add(dedupeKey(it)) })
                key = page.loadMoreKey
                end = page.loadMoreKey == null || page.items.isEmpty()
                loadedOnce = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is AuthExpiredException) onAuthExpired()
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app = App.instance
    val repo = app.repo
    val player = PlayerConnection(application)

    var loggedIn by mutableStateOf(repo.isLoggedIn)
        private set

    /** 队列存在 prefs 里，改动后 +1 触发界面刷新 */
    var queueVersion by mutableStateOf(0)
        private set
    fun bumpQueue() { queueVersion++ }

    /** 5 档，只缩放内容区 */
    var sizeLevel by mutableStateOf(app.local.sizeLevel)
        private set
    val uiScale: Float get() = com.ryan.xyzcar.data.SIZE_SCALES[sizeLevel]
    fun updateSize(level: Int) { sizeLevel = level; app.local.sizeLevel = level }

    var themeMode by mutableStateOf(com.ryan.xyzcar.ui.theme.ThemeMode.of(app.local.themeMode))
        private set
    fun updateTheme(m: com.ryan.xyzcar.ui.theme.ThemeMode) { themeMode = m; app.local.themeMode = m.key }

    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private var updateJob: Job? = null

    /** silent = 启动或回到前台时的自动检查 */
    fun checkUpdate(silent: Boolean) {
        if (updateJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (silent && (repo.demo || now - app.local.lastUpdateCheck < 24 * 3600_000L || now - app.local.lastUpdateFailure < 3600_000L)) return
        if (!silent) update = UpdateState.Checking
        updateJob = viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { app.updater.latest() } }
            r.onSuccess { rel ->
                app.local.lastUpdateCheck = System.currentTimeMillis()
                val newer = Updater.isNewer(rel.version, BuildConfig.VERSION_NAME)
                val busy = update is UpdateState.Downloading || update is UpdateState.Ready
                when {
                    newer && silent && (busy || app.local.skippedUpdate == rel.tag) -> Unit
                    newer -> {
                        update = UpdateState.Available(rel)
                        if (silent && app.local.notifiedUpdate != rel.tag) {
                            app.local.notifiedUpdate = rel.tag
                            player.messages.value = "发现新版本 ${rel.tag}，可在「我的」里更新"
                        }
                    }
                    !silent -> update = UpdateState.Latest
                }
            }.onFailure { e ->
                if (silent) app.local.lastUpdateFailure = System.currentTimeMillis()
                else update = UpdateState.Failed("检查失败：${e.message}", null)
            }
        }
    }

    fun skipUpdate() {
        val rel = (update as? UpdateState.Available)?.release ?: return
        app.local.skippedUpdate = rel.tag
        update = UpdateState.Idle
        player.messages.value = "已跳过 ${rel.tag}，有更新的版本时再提示"
    }

    fun downloadUpdate() {
        val rel = when (val s = update) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        if (updateJob?.isActive == true) return
        update = UpdateState.Downloading(rel, 0f)
        updateJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { app.updater.download(rel) { p -> update = UpdateState.Downloading(rel, p) } }
            }.onSuccess { apk ->
                // 车机不允许本应用直接安装时，把安装包存到文件管理能看到的位置
                val canInstall = Updater.canInstall(app)
                val copies = if (canInstall) emptyList() else withContext(Dispatchers.IO) {
                    PublicFiles.prune(app, "xyzcar-%.apk")
                    PublicFiles.save(app, apk.name, "application/vnd.android.package-archive") { out -> apk.inputStream().use { it.copyTo(out) } }
                }
                DebugLog.kv("update", "ready", "tag" to rel.tag, "can_install" to canInstall, "copies" to copies.size, "installer" to Updater.selfInstaller(app))
                update = UpdateState.Ready(rel, apk, copies)
            }
                .onFailure { update = UpdateState.Failed("下载失败：${it.message}", rel) }
        }
    }

    /** 全屏播放页不进路由栈，返回键先关它 */
    var playerExpanded by mutableStateOf(false)

    var transcriptOn by mutableStateOf(app.local.transcriptOn)
        private set
    fun toggleTranscript() { transcriptOn = !transcriptOn; app.local.transcriptOn = transcriptOn }

    suspend fun loadTranscript(eid: String): com.ryan.xyzcar.ui.components.TranscriptUi = try {
        val lines = repo.transcript(eid)
        if (lines.isEmpty()) com.ryan.xyzcar.ui.components.TranscriptUi.None("这一集没有文字稿")
        else com.ryan.xyzcar.ui.components.TranscriptUi.Ready(lines)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (e is AuthExpiredException) onAuthExpired()
        com.ryan.xyzcar.ui.components.TranscriptUi.None(if (e is com.ryan.xyzcar.data.api.ApiException) "这一集暂时没有文字稿" else errorText(e))
    }

    /** 「发现」页的「分类」入口也会打开它 */
    var categoriesOpen by mutableStateOf(false)

    val stack = mutableStateListOf<Route>(Route.Home(Tab.Subscriptions))
    val current: Route get() = stack.last()
    val currentTab: Tab get() = (stack.first() as? Route.Home)?.tab ?: Tab.Subscriptions
    var navDir by mutableStateOf(NavDir.Forward)
        private set

    /** 按 route.key 保存，旋转屏幕、返回上一页都不重新加载 */
    private val holders = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <H : Any> holder(key: String, create: () -> H): H = holders.getOrPut(key, create) as H

    fun dropHolder(key: String) { holders.remove(key) }

    /** holder key 约定为 route.key 或 route.key + ":xxx" */
    private fun dropRouteHolders(routeKey: String) {
        holders.keys.filter { it == routeKey || it.startsWith("$routeKey:") }.forEach { holders.remove(it) }
    }

    fun <T : Any> loadHolder(key: String, loader: suspend (Boolean) -> T): LoadHolder<T> =
        holder(key) { LoadHolder(viewModelScope, ::onAuthExpired, loader) }

    fun <T> pagedHolder(key: String, id: (T) -> String, loader: suspend (JsonElement?) -> Page<T>): PagedHolder<T> =
        holder(key) { PagedHolder(viewModelScope, ::onAuthExpired, id, loader) }

    fun selectTab(tab: Tab) {
        if (stack.size == 1 && currentTab == tab) return
        navDir = if (tab.ordinal >= currentTab.ordinal) NavDir.TabDown else NavDir.TabUp
        val leaving = stack.drop(1).map { it.key }
        stack.clear()
        stack.add(Route.Home(tab))
        dropLater(leaving)
    }

    fun push(route: Route) {
        if (stack.lastOrNull()?.key == route.key) return
        navDir = NavDir.Forward
        stack.add(route)
    }

    fun back(): Boolean {
        if (playerExpanded) { playerExpanded = false; return true }
        if (stack.size <= 1) return false
        navDir = NavDir.Back
        val removed = stack.removeAt(stack.lastIndex)
        dropLater(listOf(removed.key))
        return true
    }

    /** 等退场动画播完再丢：动画期间旧页面仍在重组，立即丢会闪骨架屏并多发一次请求 */
    private fun dropLater(keys: List<String>) {
        if (keys.isEmpty()) return
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_000)
            keys.filter { k -> stack.none { it.key == k } }.forEach { dropRouteHolders(it) }
        }
    }

    var accounts by mutableStateOf(repo.accounts())
        private set
    val accountId: String? get() = repo.accountId

    /** 非 null 时登录页显示「取消」，可回到原账号 */
    var addingFrom by mutableStateOf<String?>(null)
        private set

    private var switching: Job? = null

    init {
        // 迁移来的账号缺 uid / 昵称：补取一次，账号列表才有名字，过期重扫也能并回原账号
        if (loggedIn && repo.accounts().any { it.id == repo.accountId && it.uid.isEmpty() }) viewModelScope.launch {
            runCatching { repo.profile() }
            refreshAccounts()
        }
    }

    private fun changeAccount(id: String?, after: () -> Unit = {}) {
        if (switching?.isActive == true) return
        switching = viewModelScope.launch {
            com.ryan.xyzcar.player.PlaybackService.current?.detachAccount()
            repo.switchAccount(id)
            enterAccount()
            after()
        }
    }

    private fun enterAccount() {
        holders.clear()
        playerExpanded = false
        stack.clear(); stack.add(Route.Home(Tab.Subscriptions))
        accounts = repo.accounts()
        loggedIn = repo.isLoggedIn
        if (loggedIn) {
            com.ryan.xyzcar.player.PlaybackService.current?.attachAccount()
            player.reloadAccount()
        }
        bumpQueue()
    }

    fun refreshAccounts() { accounts = repo.accounts() }

    fun openPodcast(pid: String) { playerExpanded = false; push(Route.PodcastDetail(pid)) }

    fun openEpisodeById(eid: String) {
        viewModelScope.launch {
            runCatching { repo.episode(eid) }
                .onSuccess { playerExpanded = false; push(Route.EpisodeDetail(it)) }
                .onFailure { player.messages.value = "打不开这一集：${errorText(it)}" }
        }
    }

    fun switchAccount(id: String) {
        if (id == repo.accountId) return
        val target = accounts.firstOrNull { it.id == id } ?: return
        if (!target.loggedIn) { addAccount(); return } // 过期账号重新扫码后会并回原账号
        changeAccount(id) { player.messages.value = "已切换到「${target.nickname.ifEmpty { "小宇宙账号" }}」" }
    }

    fun addAccount() {
        if (repo.demo) return
        val from = repo.accountId?.takeIf { repo.isLoggedIn }
        changeAccount(null) { addingFrom = from }
    }

    fun cancelAddAccount() {
        val from = addingFrom ?: return
        addingFrom = null
        changeAccount(from)
    }

    fun onLoggedIn() {
        viewModelScope.launch {
            repo.finishLogin()?.let { DebugLog.kv("auth", "profile_after_login_failed", "error" to it.javaClass.simpleName) }
            addingFrom = null
            enterAccount()
        }
    }

    fun enableDemo() {
        // debug 包有真实内容快照时优先用快照，否则用合成数据
        runCatching {
            app.assets.open("demo_snapshot.json").bufferedReader().use { r ->
                com.ryan.xyzcar.data.DemoData.install(
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString(com.ryan.xyzcar.data.DemoSnapshot.serializer(), r.readText()),
                )
            }
        }
        repo.demo = true
        if (app.local.lastPlayed == null) app.local.lastPlayed = com.ryan.xyzcar.data.LastPlayed(com.ryan.xyzcar.data.DemoData.episodes.first(), 754_000)
        loggedIn = true
    }

    /** 只处理当前账号的 refresh 401；过期账号留在列表里，重新扫码后并回 */
    fun onAuthExpired() {
        if (repo.demo || !loggedIn || repo.isLoggedIn || addingFrom != null || switching?.isActive == true) return
        val expired = accounts.firstOrNull { it.id == repo.accountId }
        val next = repo.accounts().firstOrNull { it.loggedIn }
        changeAccount(next?.id) {
            if (next != null) player.messages.value = "「${expired?.nickname?.ifEmpty { null } ?: "原账号"}」登录已过期，已切换到「${next.nickname.ifEmpty { "小宇宙账号" }}」"
        }
    }

    fun logout() {
        if (repo.demo) return
        val id = repo.accountId ?: return
        if (switching?.isActive == true) return
        switching = viewModelScope.launch {
            com.ryan.xyzcar.player.PlaybackService.current?.detachAccount()
            runCatching { repo.flushPendingProgress() }
            repo.removeAccount(id)
            val next = repo.accounts().firstOrNull { it.loggedIn }
            repo.switchAccount(next?.id)
            enterAccount()
        }
    }

    override fun onCleared() {
        player.release()
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Latest : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val release: Updater.Release, val progress: Float) : UpdateState
    /** [copies]：车机不允许直接安装时安装包另存的位置，供文件管理安装 */
    data class Ready(val release: Updater.Release, val file: java.io.File, val copies: List<String> = emptyList()) : UpdateState
    data class Failed(val message: String, val release: Updater.Release?) : UpdateState
}
