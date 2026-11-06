package com.ryan.xyzcar.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Page
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.PagedHolder
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.EpisodeRow
import com.ryan.xyzcar.ui.components.ErrorBox
import com.ryan.xyzcar.ui.components.RoundIconButton
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SkeletonRows
import com.ryan.xyzcar.ui.components.pressable
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz

/** 「未听 + 在听」：没听完的单集（服务端标记已听完、或进度到最后 30 秒的算听完） */
fun Episode.isTodo(): Boolean = !finished && !(durationSec > 0 && progressSec >= durationSec - 30)

private class SubsState {
    var todoOnly by mutableStateOf(true)
    var offline by mutableStateOf(false)
    var force = false
}

/** 网格版的「滚动到底自动加载」 */
@Composable
fun <T> AutoLoadGrid(
    h: PagedHolder<T>,
    state: LazyGridState,
    minCell: androidx.compose.ui.unit.Dp,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, h.loading, h.end, h.error) { if (nearEnd && !h.loading && !h.end && h.error == null) h.loadMore() }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(minCell),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
        if (h.loading && h.loadedOnce) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Xyz.accent) }
        }
        if (h.error != null && h.items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { ErrorBox(h.error!!) { h.retry() } }
    }
}

/**
 * 订阅（官方「订阅 → 更新」）：订阅节目的新单集，默认只看「未听 + 在听」，可切「全部」。
 * 右上角「我的订阅」进节目网格。切换筛选时列表项有位移 / 淡入淡出动画。
 */
@Composable
fun SubscriptionsScreen(vm: AppViewModel) {
    val st = vm.holder("tab:Subscriptions:state") { SubsState() }
    val h = vm.pagedHolder<Episode>("tab:Subscriptions:inbox", { it.eid }) { key ->
        vm.repo.inbox(key, st.force).also { st.force = false }
    }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    val grid = remember { LazyGridState() }

    ScreenHeader("更新", onBack = null) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true, enabled = !h.loading) { st.force = true; h.refresh() }
        BigButton("我的订阅", tinted = true, icon = Icons.Filled.Layers) { vm.push(Route.SubsShows) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Chip("未听 + 在听", selected = st.todoOnly) { st.todoOnly = true }
        Chip("全部", selected = !st.todoOnly) { st.todoOnly = false }
    }
    val shown = if (st.todoOnly) h.items.filter { it.isTodo() } else h.items
    // 只看未听时一页里可能大半都听完了：不够一屏就自动再拉一页，最多拉到 100 集为止
    LaunchedEffect(st.todoOnly, shown.size, h.loading, h.end) {
        if (st.todoOnly && h.loadedOnce && shown.size < 8 && !h.end && !h.loading && h.error == null && h.items.size < 100) h.loadMore()
    }
    when {
        !h.loadedOnce && h.loading -> SkeletonRows()
        h.error != null && h.items.isEmpty() -> ErrorBox(h.error!!) { h.refresh() }
        h.loadedOnce && h.items.isEmpty() -> EmptyBox("订阅的节目还没有更新，去「推荐」看看")
        else -> AutoLoadGrid(h, grid, 560.dp) {
            if (shown.isEmpty() && h.end) item(span = { GridItemSpan(maxLineSpan) }) { EmptyBox("都听完了，切到「全部」看看") }
            items(shown, key = { it.eid }) { ep ->
                EpisodeRow(
                    ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                    playing = ps.isPlaying && ps.episode?.eid == ep.eid, modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** 我的订阅：节目封面网格，按最新单集时间排序（和官方一致） */
@Composable
fun SubsShowsScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val st = vm.holder("subs-shows:state") { SubsState() }
    val h = vm.pagedHolder<Podcast>("subs-shows:list", { it.pid }) { key ->
        if (key == null) {
            val first = vm.repo.subscriptionsFirstPage(st.force)
            st.force = false
            st.offline = first.fromDisk
            Page(first.items, first.loadMoreKey)
        } else {
            val more = vm.repo.subscriptionsMore(key, emptySet())
            Page(more.items, more.loadMoreKey)
        }
    }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val grid = remember { LazyGridState() }
    val x = Xyz

    ScreenHeader("我的订阅", onBack) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true, enabled = !h.loading) { st.force = true; h.refresh() }
    }
    Text(
        if (h.loadedOnce) "${h.items.size}${if (h.end) "" else "+"} 个节目 · 按最新单集排序" else "按最新单集排序",
        style = MaterialTheme.typography.bodyMedium, color = x.sub, modifier = Modifier.padding(bottom = 12.dp),
    )
    AnimatedVisibility(st.offline) {
        Text("网络不可用，显示的是上次缓存的订阅", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
    }
    when {
        !h.loadedOnce && h.loading -> SkeletonRows()
        h.error != null && h.items.isEmpty() -> ErrorBox(h.error!!) { h.refresh() }
        h.loadedOnce && h.items.isEmpty() -> EmptyBox("还没有订阅节目，去「推荐」看看")
        else -> AutoLoadGrid(h, grid, 180.dp) {
            items(h.items, key = { it.pid }) { p ->
                Column(
                    Modifier
                        .animateItem()
                        .padding(bottom = 20.dp)
                        .clip(RoundedCornerShape(Dims.radius))
                        .pressable { vm.push(Route.PodcastDetail(p.pid)) }
                        .padding(4.dp),
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Cover(p.coverUrl, maxWidth, title = p.title, color = p.color)
                        if (p.starred) Icon(Icons.Filled.Star, "星标", tint = x.accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(p.title, style = MaterialTheme.typography.titleSmall, color = x.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Fmt.date(p.latestEpisodePubDate).takeIf { it.isNotEmpty() }?.let {
                        Text("$it 更新", style = MaterialTheme.typography.bodySmall, color = x.sub, maxLines = 1)
                    }
                }
            }
        }
    }
}
