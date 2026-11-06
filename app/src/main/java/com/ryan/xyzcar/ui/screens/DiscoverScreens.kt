package com.ryan.xyzcar.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.Tab
import com.ryan.xyzcar.ui.components.AutoLoadList
import com.ryan.xyzcar.ui.components.ColorChip
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.EpisodeRow
import com.ryan.xyzcar.ui.components.ErrorBox
import com.ryan.xyzcar.ui.components.RoundIconButton
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SectionTitle
import com.ryan.xyzcar.ui.components.SkeletonRows
import com.ryan.xyzcar.ui.components.pressable
import com.ryan.xyzcar.ui.errorText
import com.ryan.xyzcar.ui.theme.Chroma
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz
import kotlinx.coroutines.launch

/** 榜单入口：category 是接口参数 */
val TOP_LISTS = listOf(
    "HOT_EPISODES_IN_24_HOURS" to "最热榜",
    "SKYROCKET_EPISODES" to "锋芒榜",
    "NEW_STAR_EPISODES" to "新星榜",
)

/** 「为你推荐」换一批的结果（只在点按钮时请求） */
private class DiscoverExtra {
    var refreshed by mutableStateOf<List<Episode>?>(null)
    var refreshing by mutableStateOf(false)
}

/** 搜索入口（灰色圆角条，点了切到「搜索」） */
@Composable
fun SearchPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val x = Xyz
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = Dims.touch)
            .clip(RoundedCornerShape(18.dp))
            .background(x.fill)
            .pressable(pressedScale = 0.98f) { onClick() }
            .padding(horizontal = 22.dp),
    ) {
        Icon(Icons.Filled.Search, null, tint = x.sub, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("搜索节目、单集", style = MaterialTheme.typography.bodyLarge, color = x.sub, maxLines = 1)
    }
}

/** 发现页：搜索条 + 入口色块 + 「为你推荐」横向分页 + 其他区块 */
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val h = vm.loadHolder("tab:Discover:data") { force -> vm.repo.discovery(force) }
    val extra = vm.holder("tab:Discover:extra") { DiscoverExtra() }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    val x = Xyz

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= Dims.wide
        val narrow = maxWidth < Dims.narrow
        Column(Modifier.fillMaxSize()) {
        ScreenHeader("发现", onBack = null) {
            if (narrow) RoundIconButton(Icons.Filled.Search, "搜索") { vm.selectTab(Tab.Search) }
            else SearchPill(Modifier.width(360.dp)) { vm.selectTab(Tab.Search) }
        }
        LazyColumn(state = h.listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item("entries") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { ColorChip("分类", Chroma(x.accentFill, x.accentBorder, x.accentText), Icons.Filled.GridView) { vm.categoriesOpen = true; vm.selectTab(Tab.Recommend) } }
                    item { ColorChip("最热榜", x.peach) { vm.push(Route.TopListRoute(TOP_LISTS[0].first, TOP_LISTS[0].second)) } }
                    item { ColorChip("锋芒榜", x.lime) { vm.push(Route.TopListRoute(TOP_LISTS[1].first, TOP_LISTS[1].second)) } }
                    item { ColorChip("新星榜", x.lavender) { vm.push(Route.TopListRoute(TOP_LISTS[2].first, TOP_LISTS[2].second)) } }
                    item { ColorChip("编辑精选", Chroma(x.fill, x.border, x.text)) { vm.push(Route.EditorPicks) } }
                    // 「新节目广场」接口 2026-11 起总返回空列表，入口隐藏
                }
            }
            val sections = h.data
            when {
                sections == null && h.loading -> item { SkeletonRows() }
                sections == null && h.error != null -> item { ErrorBox(h.error!!) { h.load(true) } }
                sections != null && sections.isEmpty() -> item { EmptyBox("暂时没有推荐内容") }
            }
            sections?.forEachIndexed { i, sec ->
                val isRecommend = i == 0 && sec.episodes.isNotEmpty()
                item("sec-$i-title") {
                    if (isRecommend) {
                        SectionTitle(sec.title, if (extra.refreshing) "加载中…" else "换一批") {
                            if (extra.refreshing) return@SectionTitle
                            extra.refreshing = true
                            vm.viewModelScope.launch {
                                runCatching { vm.repo.refreshRecommend() }
                                    .onSuccess { if (it.isNotEmpty()) extra.refreshed = it }
                                    .onFailure { vm.player.messages.value = errorText(it) }
                                extra.refreshing = false
                            }
                        }
                    } else SectionTitle(sec.title)
                }
                if (sec.podcasts.isNotEmpty()) item("sec-$i-pods") { PodcastCards(sec.podcasts) { vm.push(Route.PodcastDetail(it.pid)) } }
                val eps = if (isRecommend) extra.refreshed ?: sec.episodes else sec.episodes
                if (eps.isNotEmpty()) {
                    if (wide) {
                        item("sec-$i-pager") {
                            AnimatedContent(eps, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }, label = "recPager") { list ->
                                EpisodePager(list, ps.episode?.eid, ps.isPlaying, actions::open, actions::play)
                            }
                        }
                    } else {
                        items(eps.take(6), key = { "sec-$i-${it.eid}" }) { ep ->
                            EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                                playing = ps.isPlaying && ps.episode?.eid == ep.eid, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
        }
    }
}

/** 横向分页：每列 3 集，右侧露出下一列（官方「为你推荐」的样式），滑动会吸附到整列 */
@Composable
fun EpisodePager(eps: List<Episode>, playingEid: String?, isPlaying: Boolean, onOpen: (Episode) -> Unit, onPlay: (Episode) -> Unit) {
    val cols = eps.chunked(3)
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val colWidth = if (cols.size == 1) maxWidth else (maxWidth * 0.46f).coerceIn(440.dp, 640.dp)
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            items(cols) { col ->
                Column(Modifier.width(colWidth)) {
                    col.forEach { ep ->
                        EpisodeRow(ep, onOpen = { onOpen(ep) }, onPlay = { onPlay(ep) }, playing = isPlaying && playingEid == ep.eid)
                    }
                }
            }
        }
    }
}

/** 横向节目卡片：封面 + 标题 */
@Composable
fun PodcastCards(pods: List<Podcast>, onOpen: (Podcast) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(pods, key = { it.pid }) { p ->
            Column(Modifier.width(176.dp).clip(RoundedCornerShape(Dims.radius)).pressable { onOpen(p) }.padding(4.dp)) {
                Cover(p.coverUrl, 168.dp, title = p.title, color = p.color)
                Spacer(Modifier.height(10.dp))
                Text(p.title, style = MaterialTheme.typography.titleSmall, color = Xyz.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 单集列表页的通用写法（榜单） */
@Composable
private fun EpisodeListScreen(vm: AppViewModel, title: String, onBack: (() -> Unit)?, holderKey: String, load: suspend (Boolean) -> List<Episode>) {
    val h = vm.loadHolder(holderKey, load)
    LaunchedEffect(holderKey) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    ScreenHeader(title, onBack) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true, enabled = !h.loading) { h.load(true) }
    }
    val list = h.data
    when {
        list == null && h.loading -> SkeletonRows()
        list == null && h.error != null -> ErrorBox(h.error!!) { h.load(true) }
        list.isNullOrEmpty() -> EmptyBox("暂时没有内容")
        else -> LazyColumn(state = h.listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(list.size, key = { list[it].eid }) { i ->
                val ep = list[i]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
                    Text(
                        "${i + 1}", style = MaterialTheme.typography.titleLarge, color = if (i < 3) Xyz.accent else Xyz.sub,
                        modifier = Modifier.width(56.dp),
                    )
                    EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) }, playing = ps.isPlaying && ps.episode?.eid == ep.eid)
                }
            }
        }
    }
}

@Composable
fun TopListScreen(vm: AppViewModel, category: String, title: String, onBack: (() -> Unit)?) =
    EpisodeListScreen(vm, title, onBack, "top:$category:data") { force -> vm.repo.topList(category, force).episodes }

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
                EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                    playing = ps.isPlaying && ps.episode?.eid == ep.eid, modifier = Modifier.animateItem())
            }
            if (h.error != null) item { ErrorBox(h.error!!) { h.retry() } }
        }
    }
}
