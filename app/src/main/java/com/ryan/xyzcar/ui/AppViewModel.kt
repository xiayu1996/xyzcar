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
import kotlinx.serialization.json.JsonElement
import java.io.IOException

enum class Tab(val label: String) { Discover("发现"), Subscriptions("订阅"), Search("搜索"), History("历史") }

sealed class Route(val key: String) {
    data class Home(val tab: Tab) : Route("tab:${tab.name}")
    data class PodcastDetail(val pid: String) : Route("pod:$pid")
    data class EpisodeDetail(val episode: Episode) : Route("ep:${episode.eid}")
    data class TopListRoute(val category: String, val title: String) : Route("top:$category")
    data class CategoryRoute(val id: String, val name: String) : Route("cat:$id")
    data object Categories : Route("cats")
    data object EditorPicks : Route("picks")
    data object NewEpisodes : Route("pilot")
    data object Queue : Route("queue")
    data object Settings : Route("settings")
}

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

    fun loadMore() {
        if (loading || end || !loadedOnce) return
        fetch(reset = false)
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

    /** 界面缩放：实车屏幕密度未知，留一个手动调节 */
    var uiScale by mutableStateOf(app.local.uiScale)
        private set
    fun updateScale(scale: Float) { uiScale = scale; app.local.uiScale = scale }

    val stack = mutableStateListOf<Route>(Route.Home(Tab.Subscriptions))
    val current: Route get() = stack.last()
    val currentTab: Tab get() = (stack.first() as? Route.Home)?.tab ?: Tab.Subscriptions

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
        // 离开的页面（非首页）状态可以丢弃，省内存
        stack.drop(1).forEach { dropRouteHolders(it.key) }
        stack.clear()
        stack.add(Route.Home(tab))
    }

    fun push(route: Route) {
        if (stack.lastOrNull()?.key == route.key) return
        stack.add(route)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        val removed = stack.removeAt(stack.lastIndex)
        if (stack.none { it.key == removed.key }) dropRouteHolders(removed.key)
        return true
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
