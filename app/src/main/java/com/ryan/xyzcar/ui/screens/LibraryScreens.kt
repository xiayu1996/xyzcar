package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.SearchResult
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.AutoLoadList
import com.ryan.xyzcar.ui.components.BigButton
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
import com.ryan.xyzcar.ui.errorText
import com.ryan.xyzcar.ui.theme.Dims
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------------- F10 搜索 ----------------

private class SearchState {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var result by mutableStateOf<SearchResult?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var recentVersion by mutableStateOf(0)
    val listState = androidx.compose.foundation.lazy.LazyListState()
    var job: Job? = null

    fun search(vm: AppViewModel, scope: CoroutineScope, keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        query = k; submitted = k
        job?.cancel()
        job = scope.launch {
            loading = true; error = null; result = null
            try {
                result = vm.repo.search(k)
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

/** F10 搜索：大输入框 + 热门搜索 / 最近搜索胶囊；结果分「节目」「单集」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    val st = vm.holder("tab:Search:state") { SearchState() }
    val presets = vm.loadHolder("tab:Search:presets") { _ -> vm.repo.searchPresets() }
    LaunchedEffect(Unit) { presets.ensureLoaded() }
    val scope = vm.viewModelScope
    val focus = LocalFocusManager.current
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    fun go(k: String) { focus.clearFocus(); st.search(vm, scope, k) }

    ScreenHeader("搜索", onBack = null)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = st.query,
            onValueChange = { st.query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            placeholder = { Text("节目名、单集、主播", style = MaterialTheme.typography.titleLarge) },
            trailingIcon = if (st.query.isNotEmpty()) ({
                RoundIconButton(Icons.Filled.Close, "清空") { st.clear() }
            }) else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go(st.query) }),
            shape = RoundedCornerShape(Dims.radius),
            modifier = Modifier.weight(1f).heightIn(min = Dims.touch),
        )
        Spacer(Modifier.width(Dims.gap))
        RoundIconButton(Icons.Filled.Search, "搜索", primary = true, enabled = st.query.isNotBlank()) { go(st.query) }
    }
    Spacer(Modifier.padding(top = Dims.gap / 2))

    val result = st.result
    when {
        st.loading -> SkeletonRows()
        st.error != null -> ErrorBox(st.error!!) { go(st.submitted) }
        result != null -> {
            if (result.podcasts.isEmpty() && result.episodes.isEmpty()) {
                EmptyBox("没有找到「${st.submitted}」相关内容")
                return
            }
            LazyColumn(
                state = st.listState,
                verticalArrangement = Arrangement.spacedBy(Dims.gap),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (result.podcasts.isNotEmpty()) {
                    item("pt") { SectionTitle("节目") }
                    items(result.podcasts, key = { "p-${it.pid}" }) { p -> PodcastRow(p) { vm.push(Route.PodcastDetail(p.pid)) } }
                }
                if (result.episodes.isNotEmpty()) {
                    item("et") { SectionTitle("单集") }
                    items(result.episodes, key = { "e-${it.eid}" }) { ep ->
                        EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) }, playing = ps.episode?.eid == ep.eid)
                    }
                }
            }
        }
        else -> {
            val recent = remember(st.recentVersion) { vm.repo.recentSearches }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dims.gap), modifier = Modifier.fillMaxSize()) {
                if (recent.isNotEmpty()) {
                    item {
                        SectionTitle("最近搜索", "清除") { vm.repo.clearRecentSearches(); st.recentVersion++ }
                    }
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

// ---------------- F13 收听历史 ----------------

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val h = vm.pagedHolder<Episode>("tab:History:list", { it.eid }) { key -> vm.repo.history(key) }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    ScreenHeader("收听历史", onBack = null) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", enabled = !h.loading) { h.refresh() }
    }
    when {
        !h.loadedOnce && h.loading -> SkeletonRows()
        h.error != null && h.items.isEmpty() -> ErrorBox(h.error!!) { h.refresh() }
        h.loadedOnce && h.items.isEmpty() -> EmptyBox("还没有收听记录")
        else -> AutoLoadList(h.listState, h.loading, h.end, h::loadMore) {
            items(h.items, key = { it.eid }) { ep ->
                EpisodeRow(ep, onOpen = { actions.open(ep) }, onPlay = { actions.play(ep) }, onEnqueue = { actions.enqueue(ep) }, playing = ps.episode?.eid == ep.eid)
            }
            if (h.error != null) item { ErrorBox(h.error!!) { h.loadMore() } }
        }
    }
}

// ---------------- F14 播放队列 ----------------

/** 播放队列：本地保存；当前单集播完自动播放第一项 */
@Composable
fun QueueScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val q = remember(vm.queueVersion) { vm.repo.queue }
    fun changed() = vm.bumpQueue()

    ScreenHeader("播放队列", onBack) {
        if (q.isNotEmpty()) BigButton("清空", secondary = true) { vm.repo.clearQueue(); changed() }
    }
    if (q.isEmpty()) {
        EmptyBox("队列是空的，在单集旁点「加入队列」")
        return
    }
    Text(
        "共 ${q.size} 集 · 当前单集播完后从第一项开始自动播放",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Dims.gap),
    )
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Dims.gap),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(q, key = { _, ep -> ep.eid }) { i, ep ->
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

@Composable
internal fun QueueRow(
    ep: Episode, first: Boolean, last: Boolean,
    onOpen: () -> Unit, onPlay: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(Dims.radius),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 4 个按钮约占 340dp；放大到 130% 时一行放不下，标题会被挤成每行几个字 → 按钮换到第二行
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(12.dp)) {
            val stacked = maxWidth < QUEUE_ROW_INLINE_MIN
            val info: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                Cover(ep.coverUrl, Dims.cover, title = ep.podcastTitle.ifEmpty { ep.title }, color = ep.color)
                Spacer(Modifier.width(Dims.gap))
                Column(Modifier.weight(1f)) {
                    Text(ep.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(ep.podcastTitle, Fmt.remaining(ep.durationSec, ep.progressSec)).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val actions: @Composable () -> Unit = {
                RoundIconButton(Icons.Filled.KeyboardArrowUp, "上移", enabled = !first, plain = true, onClick = onUp)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Filled.KeyboardArrowDown, "下移", enabled = !last, plain = true, onClick = onDown)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Filled.Delete, "移除", plain = true, onClick = onRemove)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Filled.PlayArrow, "播放", tinted = true, onClick = onPlay)
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { info() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    info()
                    Spacer(Modifier.width(8.dp))
                    actions()
                }
            }
        }
    }
}

/** 封面 88 + 间距 16 + 4 个按钮约 340 + 标题至少 240 */
private val QUEUE_ROW_INLINE_MIN = 690.dp
