package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ryan.xyzcar.data.Category
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.AutoLoadList
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.EpisodeRow
import com.ryan.xyzcar.ui.components.ErrorBox
import com.ryan.xyzcar.ui.components.PodcastRow
import com.ryan.xyzcar.ui.components.RoundIconButton
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SectionTitle
import com.ryan.xyzcar.ui.components.SkeletonRows
import com.ryan.xyzcar.ui.theme.Dims

/** 榜单入口：category 是接口参数 */
private val TOP_LISTS = listOf(
    "HOT_EPISODES_IN_24_HOURS" to "最热榜",
    "SKYROCKET_EPISODES" to "锋芒榜",
    "NEW_STAR_EPISODES" to "新星榜",
)

/** F9 发现页：入口（榜单 / 分类 / 精选 / 新节目）+ 推荐区块 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val h = vm.loadHolder("tab:Discover:data") { force -> vm.repo.discovery(force) }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)

    ScreenHeader("发现", onBack = null) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", enabled = !h.loading) { h.load(true) }
    }
    LazyColumn(
        state = h.listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("entries") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TOP_LISTS.forEach { (cat, name) -> Chip(name) { vm.push(Route.TopListRoute(cat, name)) } }
                Chip("分类") { vm.push(Route.Categories) }
                Chip("编辑精选") { vm.push(Route.EditorPicks) }
                // 「新节目广场」接口 2026-11 起总返回空列表，入口先隐藏（页面代码保留）
            }
        }
        val sections = h.data
        when {
            sections == null && h.loading -> item { SkeletonRows() }
            sections == null && h.error != null -> item { ErrorBox(h.error!!) { h.load(true) } }
            sections != null && sections.isEmpty() -> item { EmptyBox("暂时没有推荐内容") }
        }
        sections?.forEachIndexed { i, sec ->
            item("sec-$i-title") { SectionTitle(sec.title) }
            if (sec.podcasts.isNotEmpty()) {
                item("sec-$i-pods") { PodcastCards(sec.podcasts) { vm.push(Route.PodcastDetail(it.pid)) } }
            }
            items(sec.episodes.take(6), key = { "sec-$i-${it.eid}" }) { ep ->
                EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) }, playing = ps.episode?.eid == ep.eid)
            }
        }
    }
}

/** 横向节目卡片：封面 + 标题 */
@Composable
fun PodcastCards(pods: List<Podcast>, onOpen: (Podcast) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dims.gap)) {
        items(pods, key = { it.pid }) { p ->
            Surface(
                onClick = { onOpen(p) },
                shape = RoundedCornerShape(Dims.radius),
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.width(184.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Cover(p.coverUrl, 160.dp, title = p.title, color = p.color)
                    Spacer(Modifier.height(8.dp))
                    Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 单集列表页的通用写法（榜单 / 新节目广场） */
@Composable
private fun EpisodeListScreen(vm: AppViewModel, title: String, onBack: (() -> Unit)?, holderKey: String, load: suspend (Boolean) -> List<Episode>) {
    val h = vm.loadHolder(holderKey, load)
    LaunchedEffect(holderKey) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    ScreenHeader(title, onBack) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", enabled = !h.loading) { h.load(true) }
    }
    val list = h.data
    when {
        list == null && h.loading -> SkeletonRows()
        list == null && h.error != null -> ErrorBox(h.error!!) { h.load(true) }
        list.isNullOrEmpty() -> EmptyBox("暂时没有内容")
        else -> LazyColumn(
            state = h.listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.eid }) { ep ->
                EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) }, playing = ps.episode?.eid == ep.eid)
            }
        }
    }
}

@Composable
fun TopListScreen(vm: AppViewModel, category: String, title: String, onBack: (() -> Unit)?) =
    EpisodeListScreen(vm, title, onBack, "top:$category:data") { force -> vm.repo.topList(category, force).episodes }

@Composable
fun NewEpisodesScreen(vm: AppViewModel, onBack: (() -> Unit)?) =
    EpisodeListScreen(vm, "新节目广场", onBack, "pilot:data") { _ -> vm.repo.newEpisodes() }

@Composable
fun EditorPicksScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val h = vm.pagedHolder<Episode>("picks:data", { it.eid }) { key -> vm.repo.editorPicks(key) }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    ScreenHeader("编辑精选", onBack)
    when {
        !h.loadedOnce && h.loading -> SkeletonRows()
        h.error != null && h.items.isEmpty() -> ErrorBox(h.error!!) { h.refresh() }
        h.loadedOnce && h.items.isEmpty() -> EmptyBox("暂时没有内容")
        else -> AutoLoadList(h.listState, h.loading, h.end, h::loadMore) {
            items(h.items, key = { it.eid }) { ep ->
                EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) }, playing = ps.episode?.eid == ep.eid)
            }
            if (h.error != null) item { ErrorBox(h.error!!) { h.loadMore() } }
        }
    }
}

/** 全部分类 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val h = vm.loadHolder("cats:data") { _ -> vm.repo.categories() }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    ScreenHeader("分类", onBack)
    val cats = h.data
    when {
        cats == null && h.loading -> SkeletonRows()
        cats == null && h.error != null -> ErrorBox(h.error!!) { h.load(true) }
        cats.isNullOrEmpty() -> EmptyBox("暂时没有分类")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dims.gap),
                    verticalArrangement = Arrangement.spacedBy(Dims.gap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    cats.forEach { c -> Chip(c.name.trim()) { vm.push(Route.CategoryRoute(c.id, c.name.trim())) } }
                }
            }
        }
    }
}

private class CategoryState { var tab by mutableStateOf<String?>(null) }

/** 分类下的节目：顶部 tab + 分页节目列表 */
@Composable
fun CategoryScreen(vm: AppViewModel, id: String, name: String, onBack: (() -> Unit)?) {
    val tabs = vm.loadHolder("cat:$id:tabs") { _ -> vm.repo.categoryTabs(id) }
    val st = vm.holder("cat:$id:state") { CategoryState() }
    LaunchedEffect(id) { tabs.ensureLoaded() }
    val list: List<Category> = tabs.data.orEmpty()
    val tab = st.tab ?: list.firstOrNull()?.id

    ScreenHeader(name, onBack)
    if (tabs.data == null) {
        if (tabs.error != null) ErrorBox(tabs.error!!) { tabs.load(true) } else SkeletonRows()
        return
    }
    if (list.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = Dims.gap)) {
            items(list, key = { it.id }) { t -> Chip(t.name, selected = t.id == tab) { st.tab = t.id } }
        }
    }
    if (tab == null) { EmptyBox("暂时没有节目"); return }
    androidx.compose.runtime.key(tab) {
        val h = vm.pagedHolder<Podcast>("cat:$id:list:$tab", { it.pid }) { key -> vm.repo.categoryPodcasts(id, tab, key) }
        LaunchedEffect(tab) { h.ensureLoaded() }
        when {
            !h.loadedOnce && h.loading -> SkeletonRows()
            h.error != null && h.items.isEmpty() -> ErrorBox(h.error!!) { h.refresh() }
            h.loadedOnce && h.items.isEmpty() -> EmptyBox("暂时没有节目")
            else -> AutoLoadList(h.listState, h.loading, h.end, h::loadMore) {
                items(h.items, key = { it.pid }) { p -> PodcastRow(p) { vm.push(Route.PodcastDetail(p.pid)) } }
                if (h.error != null) item { ErrorBox(h.error!!) { h.loadMore() } }
            }
        }
    }
}
