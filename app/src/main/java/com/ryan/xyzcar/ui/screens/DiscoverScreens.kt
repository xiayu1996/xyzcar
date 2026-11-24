package com.ryan.xyzcar.ui.screens

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import com.ryan.xyzcar.data.DiscoverySection
import com.ryan.xyzcar.ui.components.loadPhase
import com.ryan.xyzcar.ui.components.Swap
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
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

/** 「换一批」追加的推荐（只在点按钮时请求） */
private class DiscoverExtra {
    var refreshed by mutableStateOf<List<Episode>>(emptyList())
    var refreshing by mutableStateOf(false)
    var topTab by mutableStateOf(0)
}

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

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val h = vm.loadHolder("tab:Discover:data") { force -> vm.repo.discovery(force) }
    val extra = vm.holder("tab:Discover:extra") { DiscoverExtra() }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val playingEid by vm.player.playingEid.collectAsState()
    val actions = EpisodeActions(vm)
    val x = Xyz

    BoxWithConstraints(Modifier.fillMaxSize()) {
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
                    item { ColorChip("编辑精选", x.peach) { vm.push(Route.EditorPicks) } }
                    // 「新节目广场」接口 2026-11 起总返回空列表，入口隐藏
                }
            }
            val sections = h.data
            val phase = loadPhase(sections == null && h.loading, sections == null && h.error != null, sections != null && sections.isEmpty())
            if (phase < 3) item("state") {
                Swap(phase, Modifier.animateItem()) { p ->
                    when (p) {
                        0 -> SkeletonRows()
                        1 -> ErrorBox(h.error.orEmpty()) { h.load(true) }
                        else -> EmptyBox("暂时没有推荐内容")
                    }
                }
            }
            val tops = sections.orEmpty().filter { it.topCategory.isNotEmpty() && it.episodes.isNotEmpty() }
            var topsShown = false
            sections?.forEachIndexed { i, sec ->
                if (sec.topCategory.isNotEmpty()) {
                    if (!topsShown && tops.isNotEmpty()) {
                        topsShown = true
                        item("tops") {
                            TopListsBlock(modifier = Modifier.animateItem(), tops = tops, selected = extra.topTab, onSelect = { extra.topTab = it }, playingEid = playingEid, onOpen = actions::open, onPlay = actions::play) { t ->
                                vm.push(Route.TopListRoute(t.topCategory, t.title))
                            }
                        }
                    }
                    return@forEachIndexed
                }
                val isRecommend = i == 0 && sec.episodes.isNotEmpty()
                item("sec-$i-title") { Box(Modifier.animateItem()) {
                    when {
                        isRecommend -> SectionTitle(sec.title, if (extra.refreshing) "加载中…" else "换一批") {
                            if (extra.refreshing) return@SectionTitle
                            extra.refreshing = true
                            vm.viewModelScope.launch {
                                runCatching { vm.repo.refreshRecommend() }
                                    .onSuccess { if (it.isNotEmpty()) extra.refreshed = (it + extra.refreshed).distinctBy { e -> e.eid }.take(MAX_EXTRA) }
                                    .onFailure { vm.player.messages.value = errorText(it) }
                                extra.refreshing = false
                            }
                        }
                        sec.title == "编辑精选" -> SectionTitle(sec.title, "全部") { vm.push(Route.EditorPicks) }
                        else -> SectionTitle(sec.title)
                    }
                } }
                if (sec.podcasts.isNotEmpty()) item("sec-$i-pods") { PodcastCards(sec.podcasts) { vm.push(Route.PodcastDetail(it.pid)) } }
                val eps = if (isRecommend) (extra.refreshed + sec.episodes).distinctBy { it.eid } else sec.episodes
                if (eps.isNotEmpty()) {
                    item("sec-$i-pager") {
                        // contentKey 取首集：换一批后分页回到第一列
                        AnimatedContent(eps, Modifier.animateItem(), contentKey = { it.firstOrNull()?.eid }, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }, label = "recPager") { list ->
                            EpisodePager(list, playingEid, actions::open, actions::play)
                        }
                    }
                }
            }
        }
        }
    }
}

private const val MAX_EXTRA = 27

@Composable
internal fun TopListsBlock(
    tops: List<DiscoverySection>,
    selected: Int,
    onSelect: (Int) -> Unit,
    playingEid: String?,
    onOpen: (Episode) -> Unit,
    onPlay: (Episode) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFull: (DiscoverySection) -> Unit,
) {
    val x = Xyz
    val sel = selected.coerceIn(0, tops.lastIndex)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp)) {
            tops.forEachIndexed { i, t ->
                val on = i == sel
                val color by animateColorAsState(if (on) x.accent else x.sub.copy(alpha = 0.55f), tween(250), label = "topTabColor")
                val scale by animateFloatAsState(if (on) 1f else 0.86f, tween(250), label = "topTabScale")
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .heightIn(min = Dims.touch)
                        .clip(RoundedCornerShape(14.dp))
                        .pressable(!on, pressedScale = 0.94f) { onSelect(i) }
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        t.title, style = MaterialTheme.typography.titleLarge, color = color,
                        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val cur = tops[sel]
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.heightIn(min = Dims.touch).clip(RoundedCornerShape(50))
                    .pressable { onOpenFull(cur) }.padding(horizontal = 20.dp),
            ) { Text("完整榜单", style = MaterialTheme.typography.labelLarge, color = x.sub) }
        }
        AnimatedContent(
            sel,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { dir * it / 5 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -dir * it / 5 } + fadeOut(tween(180)))
            },
            label = "topTab",
        ) { i ->
            // 刷新后榜单变少时，退场中的旧页可能拿到越界的序号
            tops.getOrNull(i)?.let { EpisodePager(it.episodes, playingEid, onOpen, onPlay, ranked = true) }
        }
    }
}

/** 一屏只放整数列：露出下一列半截时只剩一排播放键，像每集有两个播放键 */
@Composable
fun EpisodePager(eps: List<Episode>, playingEid: String?, onOpen: (Episode) -> Unit, onPlay: (Episode) -> Unit, ranked: Boolean = false) {
    val cols = eps.chunked(3)
    val state = rememberLazyListState()
    val gap = 28.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perScreen = if (maxWidth >= 880.dp) 2 else 1
        val colWidth = (maxWidth - gap * (perScreen - 1)) / perScreen
        Column {
            LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(cols.size) { c ->
                    Column(Modifier.width(colWidth)) {
                        cols[c].forEachIndexed { r, ep ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (ranked) {
                                    val n = c * 3 + r + 1
                                    Text(
                                        "$n", style = MaterialTheme.typography.titleLarge, color = if (n <= 3) Xyz.accent else Xyz.sub,
                                        modifier = Modifier.width(44.dp),
                                    )
                                }
                                EpisodeRow(ep, onOpen = { onOpen(ep) }, onPlay = { onPlay(ep) }, playing = playingEid == ep.eid, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            val pages = (cols.size - perScreen + 1).coerceAtLeast(1)
            if (pages > 1) {
                val current by remember { derivedStateOf { if (state.canScrollForward) state.firstVisibleItemIndex else pages - 1 } }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    repeat(pages) { i ->
                        val on = i == current
                        val w by animateDpAsState(if (on) 22.dp else 8.dp, label = "dotW")
                        val c by animateColorAsState(if (on) Xyz.accent else Xyz.border, label = "dotC")
                        Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(c))
                    }
                }
            }
        }
    }
}

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

@Composable
private fun EpisodeListScreen(vm: AppViewModel, title: String, onBack: (() -> Unit)?, holderKey: String, load: suspend (Boolean) -> List<Episode>) {
    val h = vm.loadHolder(holderKey, load)
    LaunchedEffect(holderKey) { h.ensureLoaded() }
    val playingEid by vm.player.playingEid.collectAsState()
    val actions = EpisodeActions(vm)
    ScreenHeader(title, onBack) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true, enabled = !h.loading) { h.load(true) }
    }
    val list = h.data.orEmpty()
    Swap(loadPhase(h.data == null && h.loading, h.data == null && h.error != null, list.isEmpty())) { phase -> when (phase) {
        0 -> SkeletonRows()
        1 -> ErrorBox(h.error.orEmpty()) { h.load(true) }
        2 -> EmptyBox("暂时没有内容")
        else -> LazyColumn(state = h.listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(list.size, key = { list[it].eid }) { i ->
                val ep = list[i]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
                    Text(
                        "${i + 1}", style = MaterialTheme.typography.titleLarge, color = if (i < 3) Xyz.accent else Xyz.sub,
                        modifier = Modifier.width(56.dp),
                    )
                    EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) }, playing = playingEid == ep.eid)
                }
            }
        }
    } }
}

@Composable
fun TopListScreen(vm: AppViewModel, category: String, title: String, onBack: (() -> Unit)?) =
    EpisodeListScreen(vm, title, onBack, "top:$category:data") { force -> vm.repo.topList(category, force).episodes }

@Composable
fun EditorPicksScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val h = vm.pagedHolder<Episode>("picks:data", { it.eid }) { key -> vm.repo.editorPicks(key) }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val playingEid by vm.player.playingEid.collectAsState()
    val actions = EpisodeActions(vm)
    ScreenHeader("编辑精选", onBack)
    Swap(loadPhase(!h.loadedOnce && h.loading, h.error != null && h.items.isEmpty(), h.loadedOnce && h.items.isEmpty())) { phase -> when (phase) {
        0 -> SkeletonRows()
        1 -> ErrorBox(h.error.orEmpty()) { h.refresh() }
        2 -> EmptyBox("暂时没有内容")
        else -> AutoLoadList(h.listState, h.loading, h.end, h::loadMore) {
            items(h.items, key = { it.eid }) { ep ->
                EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                    playing = playingEid == ep.eid, modifier = Modifier.animateItem())
            }
            if (h.error != null) item { ErrorBox(h.error!!) { h.retry() } }
        }
    } }
}
