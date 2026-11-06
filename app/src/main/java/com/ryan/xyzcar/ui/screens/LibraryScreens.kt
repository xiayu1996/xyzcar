package com.ryan.xyzcar.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Page
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.data.SearchResult
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.AutoLoadList
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.EpisodeRow
import com.ryan.xyzcar.ui.components.ErrorBox
import com.ryan.xyzcar.ui.components.PodcastRow
import com.ryan.xyzcar.ui.components.RoundIconButton
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SectionTitle
import com.ryan.xyzcar.ui.components.SkeletonRows
import com.ryan.xyzcar.ui.errorText
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------------- 搜索 ----------------

/** 搜索结果分三栏：综合（服务端 ALL）/ 节目 / 单集（后两个可翻页） */
private enum class SearchTab(val label: String, val type: String) { All("综合", "ALL"), Podcasts("节目", "PODCAST"), Episodes("单集", "EPISODE") }

private class SearchState {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var result by mutableStateOf<SearchResult?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var tab by mutableStateOf(SearchTab.All)
    var recentVersion by mutableStateOf(0)
    var job: Job? = null

    fun search(vm: AppViewModel, scope: CoroutineScope, keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        query = k; submitted = k; tab = SearchTab.All
        job?.cancel()
        job = scope.launch {
            loading = true; error = null; result = null
            try {
                result = vm.repo.search(k).first
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is com.ryan.xyzcar.data.api.AuthExpiredException) vm.onAuthExpired()
                error = errorText(e)
            } finally {
                loading = false
                recentVersion++
            }
        }
    }

    fun clear() { job?.cancel(); query = ""; submitted = ""; result = null; error = null; loading = false }
}

/** 搜索框：浅灰圆角底，无描边（官方样式） */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    val x = Xyz
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.heightIn(min = Dims.touch).clip(RoundedCornerShape(18.dp)).background(x.fill).padding(start = 22.dp, end = 4.dp),
    ) {
        Icon(Icons.Filled.Search, null, tint = x.sub, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("节目名、单集、主播", style = MaterialTheme.typography.titleMedium, color = x.sub)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = x.text),
                cursorBrush = SolidColor(x.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(value.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            RoundIconButton(Icons.Filled.Close, "清空", plain = true, tint = x.sub, onClick = onClear)
        }
    }
}

/** 搜索：大输入框 + 最近搜索 / 大家在搜；结果「综合」左节目右单集，关键词高亮 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    val st = vm.holder("tab:Search:state") { SearchState() }
    val presets = vm.loadHolder("tab:Search:presets") { _ -> vm.repo.searchPresets() }
    LaunchedEffect(Unit) { presets.ensureLoaded() }
    val scope = vm.viewModelScope
    val focus = LocalFocusManager.current
    fun go(k: String) { focus.clearFocus(); st.search(vm, scope, k) }

    ScreenHeader("搜索", onBack = null)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SearchField(st.query, { st.query = it }, { go(st.query) }, { st.clear() }, Modifier.weight(1f))
        Spacer(Modifier.width(Dims.gap))
        BigButton("搜索", enabled = st.query.isNotBlank()) { go(st.query) }
    }
    Spacer(Modifier.padding(top = 12.dp))

    val result = st.result
    val phase = when {
        st.loading -> 0
        st.error != null -> 1
        result != null -> 2
        else -> 3
    }
    AnimatedContent(phase, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "searchPhase") { p ->
        when (p) {
            0 -> SkeletonRows()
            1 -> ErrorBox(st.error.orEmpty()) { go(st.submitted) }
            2 -> if (result != null) SearchResults(vm, st, result)
            else -> {
                val recent = remember(st.recentVersion) { vm.repo.recentSearches }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (recent.isNotEmpty()) {
                        item { SectionTitle("最近搜索", "清除") { vm.repo.clearRecentSearches(); st.recentVersion++ } }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                recent.forEach { k -> Chip(k) { go(k) } }
                            }
                        }
                    }
                    val hot = presets.data.orEmpty()
                    if (hot.isNotEmpty()) {
                        item { SectionTitle("大家在搜") }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                hot.take(12).forEach { k -> Chip(k) { go(k) } }
                            }
                        }
                    }
                    if (recent.isEmpty() && hot.isEmpty() && !presets.loading) item { EmptyBox("输入关键词开始搜索") }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(vm: AppViewModel, st: SearchState, result: SearchResult) {
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    val words = result.highlight.ifEmpty { listOf(st.submitted) }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            SearchTab.entries.forEach { t -> Chip(t.label, selected = st.tab == t) { st.tab = t } }
        }
        AnimatedContent(st.tab, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "searchTab") { tab ->
            when (tab) {
                SearchTab.All -> {
                    if (result.podcasts.isEmpty() && result.episodes.isEmpty()) { EmptyBox("没有找到「${st.submitted}」相关内容"); return@AnimatedContent }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val epList: @Composable (Modifier) -> Unit = { m ->
                            LazyColumn(m, contentPadding = PaddingValues(bottom = 24.dp)) {
                                item { SectionTitle("单集", "更多") { st.tab = SearchTab.Episodes } }
                                items(result.episodes, key = { "e-${it.eid}" }) { ep ->
                                    EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                                        playing = ps.isPlaying && ps.episode?.eid == ep.eid, highlightWords = words)
                                }
                            }
                        }
                        if (maxWidth >= Dims.wide && result.podcasts.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                LazyColumn(Modifier.width(440.dp)) {
                                    item { SectionTitle("相关节目", "更多") { st.tab = SearchTab.Podcasts } }
                                    items(result.podcasts, key = { "p-${it.pid}" }) { p -> PodcastRow(p, words) { vm.push(Route.PodcastDetail(p.pid)) } }
                                }
                                epList(Modifier.weight(1f))
                            }
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                                if (result.podcasts.isNotEmpty()) {
                                    item { SectionTitle("相关节目", "更多") { st.tab = SearchTab.Podcasts } }
                                    items(result.podcasts, key = { "p-${it.pid}" }) { p -> PodcastRow(p, words) { vm.push(Route.PodcastDetail(p.pid)) } }
                                }
                                item { SectionTitle("单集", "更多") { st.tab = SearchTab.Episodes } }
                                items(result.episodes, key = { "e-${it.eid}" }) { ep ->
                                    EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                                        playing = ps.isPlaying && ps.episode?.eid == ep.eid, highlightWords = words)
                                }
                            }
                        }
                    }
                }
                SearchTab.Podcasts -> {
                    val h = vm.pagedHolder<Podcast>("tab:Search:p:${st.submitted}", { it.pid }) { key ->
                        val (r, next) = vm.repo.search(st.submitted, "PODCAST", key); Page(r.podcasts, next)
                    }
                    LaunchedEffect(st.submitted) { h.ensureLoaded() }
                    PagedBody(h, "没有找到相关节目") {
                        items(h.items, key = { it.pid }) { p -> PodcastRow(p, words) { vm.push(Route.PodcastDetail(p.pid)) } }
                    }
                }
                SearchTab.Episodes -> {
                    val h = vm.pagedHolder<Episode>("tab:Search:e:${st.submitted}", { it.eid }) { key ->
                        val (r, next) = vm.repo.search(st.submitted, "EPISODE", key); Page(r.episodes, next)
                    }
                    LaunchedEffect(st.submitted) { h.ensureLoaded() }
                    PagedBody(h, "没有找到相关单集") {
                        items(h.items, key = { it.eid }) { ep ->
                            EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                                playing = ps.isPlaying && ps.episode?.eid == ep.eid, highlightWords = words)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> PagedBody(h: com.ryan.xyzcar.ui.PagedHolder<T>, empty: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    when {
        !h.loadedOnce && h.loading -> SkeletonRows()
        h.error != null && h.items.isEmpty() -> ErrorBox(h.error!!) { h.refresh() }
        h.loadedOnce && h.items.isEmpty() -> EmptyBox(empty)
        else -> AutoLoadList(h.listState, h.loading, h.end, h::loadMore) {
            content()
            if (h.error != null) item { ErrorBox(h.error!!) { h.retry() } }
        }
    }
}

// ---------------- 收听历史（在「我的」里） ----------------

@Composable
fun HistoryScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val h = vm.pagedHolder<Episode>("history:list", { it.eid }) { key -> vm.repo.history(key) }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    ScreenHeader("收听历史", onBack) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true, enabled = !h.loading) { h.refresh() }
    }
    PagedBody(h, "还没有收听记录") {
        items(h.items, key = { it.eid }) { ep ->
            EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) },
                playing = ps.isPlaying && ps.episode?.eid == ep.eid, modifier = Modifier.animateItem())
        }
    }
}

// ---------------- 播放列表 ----------------

/** 播放列表：本地保存；当前单集播完自动播放第一项。上移 / 下移 / 移除有位移动画 */
@Composable
fun QueueScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val q = remember(vm.queueVersion) { vm.repo.queue }
    fun changed() = vm.bumpQueue()

    ScreenHeader("播放列表", onBack) {
        if (q.isNotEmpty()) BigButton("清空", secondary = true) { vm.repo.clearQueue(); changed() }
    }
    if (q.isEmpty()) {
        EmptyBox("播放列表是空的，在单集旁点「加入播放列表」")
        return
    }
    Text(
        "共 ${q.size} 集 · 当前单集播完后从第一项开始自动播放",
        style = MaterialTheme.typography.bodyMedium, color = Xyz.sub,
        modifier = Modifier.padding(bottom = Dims.gap),
    )
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(q, key = { _, ep -> ep.eid }) { i, ep ->
            Box(Modifier.animateItem()) {
                QueueRow(
                    ep, first = i == 0, last = i == q.lastIndex,
                    onOpen = { vm.push(Route.EpisodeDetail(ep)) },
                    onPlay = { vm.repo.removeFromQueue(ep.eid); changed(); vm.player.play(ep) },
                    onUp = { vm.repo.moveInQueue(ep.eid, -1); changed() },
                    onDown = { vm.repo.moveInQueue(ep.eid, 1); changed() },
                    onRemove = { vm.repo.removeFromQueue(ep.eid); changed() },
                )
            }
        }
    }
}

@Composable
internal fun QueueRow(
    ep: Episode, first: Boolean, last: Boolean,
    onOpen: () -> Unit, onPlay: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit,
) {
    // 3 个排序按钮约占 230dp；宽度不够（放大到特大）时换到第二行，避免标题被挤成每行几个字
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < QUEUE_ROW_INLINE_MIN
        val x = Xyz
        val tools: @Composable () -> Unit = {
            RoundIconButton(Icons.Filled.KeyboardArrowUp, "上移", enabled = !first, plain = true, tint = x.sub, onClick = onUp)
            RoundIconButton(Icons.Filled.KeyboardArrowDown, "下移", enabled = !last, plain = true, tint = x.sub, onClick = onDown)
            RoundIconButton(Icons.Filled.Delete, "移除", plain = true, tint = x.sub, onClick = onRemove)
        }
        if (stacked) {
            Column {
                EpisodeRow(ep, onOpen = onOpen, onPlay = onPlay)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { tools() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { EpisodeRow(ep, onOpen = onOpen, onPlay = onPlay) }
                tools()
            }
        }
    }
}

/** 封面 96 + 标题至少 260 + 播放键 72 + 3 个按钮 216 */
private val QUEUE_ROW_INLINE_MIN = 700.dp
