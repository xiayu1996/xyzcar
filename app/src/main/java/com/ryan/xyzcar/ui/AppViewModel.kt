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

/** 和官方 App 一样的五个主入口：收听历史、设置在「我的」里 */
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

/** 页面切换方向：决定转场动画往哪边滑 */
enum class NavDir { Forward, Back, TabUp, TabDown }

fun errorText(e: Throwable): String = when (e) {
    is AuthExpiredException -> e.message.orEmpty()
    is com.ryan.xyzcar.data.api.ApiException -> e.message.orEmpty()
    is IOException -> "网络不可用，请检查车机网络"
    else -> "出错了：${e.message ?: e.javaClass.simpleName}"
}

/** 单次加载的状态 */
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

/** 分页列表的状态；滚动到底调用 loadMore */
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

    /**
     * 加载下一页（滚动到底时自动调用）。上次失败后不再自动重试，要用户点「重试」（[retry]），
     * 否则断网时滚动触发、筛选不够一屏触发都会变成一直发请求。
     */
    fun loadMore() {
        if (loading || end || !loadedOnce || error != null) return
        fetch(reset = false)
    }

    /** 用户点「重试」：清掉错误再加载下一页 */
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

    /** 队列存在本地 prefs，改动后 +1 触发界面刷新 */
    var queueVersion by mutableStateOf(0)
        private set
    fun bumpQueue() { queueVersion++ }

    /** 显示大小（5 档，只缩放内容区） */
    var sizeLevel by mutableStateOf(app.local.sizeLevel)
        private set
    val uiScale: Float get() = com.ryan.xyzcar.data.SIZE_SCALES[sizeLevel]
    fun updateSize(level: Int) { sizeLevel = level; app.local.sizeLevel = level }

    var themeMode by mutableStateOf(com.ryan.xyzcar.ui.theme.ThemeMode.of(app.local.themeMode))
        private set
    fun updateTheme(m: com.ryan.xyzcar.ui.theme.ThemeMode) { themeMode = m; app.local.themeMode = m.key }

    /** 应用内更新状态（「我的」里的检查更新行） */
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private var updateJob: Job? = null

    /** silent = 启动时的自动检查：每天最多一次，演示模式不查，同一版本只提示一次 */
    fun checkUpdate(silent: Boolean) {
        if (updateJob?.isActive == true) return
        if (silent && (repo.demo || System.currentTimeMillis() - app.local.lastUpdateCheck < 24 * 3600_000L)) return
        if (!silent) update = UpdateState.Checking
        updateJob = viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { app.updater.latest() } }
            r.onSuccess { rel ->
                app.local.lastUpdateCheck = System.currentTimeMillis()
                val newer = Updater.isNewer(rel.version, BuildConfig.VERSION_NAME)
                when {
                    newer -> {
                        update = UpdateState.Available(rel)
                        if (silent && app.local.notifiedUpdate != rel.tag) {
                            app.local.notifiedUpdate = rel.tag
                            player.messages.value = "发现新版本 ${rel.tag}，可在「我的」里更新"
                        }
                    }
                    !silent -> update = UpdateState.Latest
                }
            }.onFailure { e -> if (!silent) update = UpdateState.Failed("检查失败：${e.message}", null) }
        }
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
                // 车机不让本应用直接安装时，把安装包放到文件管理看得到的地方，交给文件管理安装
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

    /** 全屏播放页（盖在内容上，从底部滑出；不进路由栈，返回键先关它） */
    var playerExpanded by mutableStateOf(false)

    /** 「推荐」页的分类面板是否展开（「发现」页的「分类」入口会打开它） */
    var categoriesOpen by mutableStateOf(false)

    val stack = mutableStateListOf<Route>(Route.Home(Tab.Subscriptions))
    val current: Route get() = stack.last()
    val currentTab: Tab get() = (stack.first() as? Route.Home)?.tab ?: Tab.Subscriptions
    var navDir by mutableStateOf(NavDir.Forward)
        private set

    /** 每个页面的状态按 route.key 保存：旋转屏幕、返回上一页都不重新加载 */
    private val holders = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <H : Any> holder(key: String, create: () -> H): H = holders.getOrPut(key, create) as H

    fun dropHolder(key: String) { holders.remove(key) }

    /** 页面关闭时清掉它的状态（holder key 约定为 route.key 或 route.key + ":xxx"） */
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

    /**
     * 离开的页面状态等退场动画播完再丢：动画期间旧页面还在重组，
     * 立即丢弃会让它拿到一个空的新状态、闪一下骨架屏，还会多发一次请求。
     */
    private fun dropLater(keys: List<String>) {
        if (keys.isEmpty()) return
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_000)
            keys.filter { k -> stack.none { it.key == k } }.forEach { dropRouteHolders(it) }
        }
    }

    fun onLoggedIn() {
        loggedIn = true
        holders.clear()
        selectTab(Tab.Subscriptions)
    }

    fun enableDemo() {
        // debug 包带有真实公开内容的快照时优先用快照（封面、音频都是真的），没有就用合成数据
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

    fun onAuthExpired() {
        if (repo.demo) return
        repo.logout()
        loggedIn = false
    }

    fun logout() = onAuthExpired()

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
    /** [copies]：车机不让直接安装时，安装包另存到的位置（给文件管理装） */
    data class Ready(val release: Updater.Release, val file: java.io.File, val copies: List<String> = emptyList()) : UpdateState
    data class Failed(val message: String, val release: Updater.Release?) : UpdateState
}
