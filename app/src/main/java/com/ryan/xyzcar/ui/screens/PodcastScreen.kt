package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.theme.Accented
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.errorText
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.components.AutoLoadList
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.EpisodeRow
import com.ryan.xyzcar.ui.components.ErrorBox
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SkeletonRows
import com.ryan.xyzcar.ui.theme.Dims

/** F4 节目详情 + 单集列表（倒序、分页、显示已听进度） */
@Composable
fun PodcastScreen(vm: AppViewModel, pid: String, onBack: (() -> Unit)?) {
    val info = vm.loadHolder("pod:$pid:info") { force -> vm.repo.podcast(pid, force) }
    val eps = vm.pagedHolder<Episode>("pod:$pid:eps", { it.eid }) { key -> vm.repo.episodes(pid, key) }
    LaunchedEffect(pid) { info.ensureLoaded(); eps.ensureLoaded() }
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)

    Accented(info.data?.color) { Column {
    ScreenHeader(info.data?.title ?: "节目", onBack)
    AutoLoadList(eps.listState, eps.loading && eps.loadedOnce, eps.end, eps::loadMore) {
        item("header") {
            info.data?.let { PodcastHeader(vm, it) { updated -> info.data = updated } }
                ?: info.error?.let { ErrorBox(it) { info.load(true) } }
        }
        when {
            !eps.loadedOnce && eps.loading -> item { SkeletonRows() }
            eps.error != null && eps.items.isEmpty() -> item { ErrorBox(eps.error!!) { eps.refresh() } }
            eps.loadedOnce && eps.items.isEmpty() -> item { EmptyBox("还没有单集") }
        }
        items(eps.items, key = { it.eid }) { ep ->
            val e = if (ep.podcastTitle.isEmpty()) ep.copy(podcastTitle = info.data?.title.orEmpty(), coverUrl = ep.coverUrl.ifEmpty { info.data?.coverUrl.orEmpty() }, color = ep.color ?: info.data?.color) else ep
            EpisodeRow(
                e, onOpen = { actions.open(e) }, onPlay = { actions.play(e) },
                onEnqueue = { actions.enqueue(e) }, showPodcast = false, playing = ps.episode?.eid == e.eid,
            )
        }
        if (eps.error != null && eps.items.isNotEmpty()) item { ErrorBox(eps.error!!) { eps.retry() } }
    }
    } }
}

@Composable
private fun PodcastHeader(vm: AppViewModel, p: Podcast, onUpdated: (Podcast) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(p.coverUrl, 168.dp, title = p.title, color = p.color)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(p.episodeCount.takeIf { it > 0 }?.let { "$it 集" }, p.subscriptionCount.takeIf { it > 0 }?.let { "${Fmt.count(it)} 人订阅" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SubscribeButtons(vm, p, onUpdated)
            }
        }
        if (p.description.isNotEmpty()) {
            Spacer(Modifier.height(Dims.gap))
            Text(p.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(Dims.gap))
    }
}

/** F11 订阅管理（点「已订阅」即取消）：订阅 / 取消订阅、星标，结果直接写回服务端（手机端同步） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscribeButtons(vm: AppViewModel, p: Podcast, onUpdated: (Podcast) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Podcast?, fallback: Podcast) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { updated ->
                    onUpdated(updated?.let { u -> u.copy(coverUrl = u.coverUrl.ifEmpty { p.coverUrl }) } ?: fallback)
                    vm.repo.invalidate("pod:${p.pid}")
                    if (p.subscribed && fallback.subscribed != p.subscribed) vm.player.messages.value = "已取消订阅：${p.title}"
                    vm.dropHolder("subs-shows:list")
                }
                .onFailure { vm.player.messages.value = errorText(it) }
            busy = false
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.gap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(if (p.subscribed) "已订阅 · 点击取消" else "订阅", enabled = !busy, secondary = p.subscribed) {
            run({ vm.repo.setSubscribed(p.pid, !p.subscribed) }, p.copy(subscribed = !p.subscribed, starred = false))
        }
        if (p.subscribed) {
            BigButton(if (p.starred) "★ 已星标" else "☆ 星标", enabled = !busy, secondary = true) {
                run({ vm.repo.setStarred(p.pid, !p.starred) }, p.copy(starred = !p.starred))
            }
        }
    }
}
