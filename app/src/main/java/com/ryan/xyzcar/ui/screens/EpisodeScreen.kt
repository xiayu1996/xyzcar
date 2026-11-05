package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.ryan.xyzcar.ui.theme.Accented
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.theme.Dims

/** F12 单集详情：标题、播放、加入队列、shownotes（时间戳可点击跳转） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeScreen(vm: AppViewModel, initial: Episode, onBack: (() -> Unit)?) {
    val h = vm.loadHolder("ep:${initial.eid}") { _ -> vm.repo.episode(initial.eid) }
    LaunchedEffect(initial.eid) { h.ensureLoaded() }
    val ep = h.data?.let { it.copy(progressSec = initial.progressSec) } ?: initial
    val actions = EpisodeActions(vm)

    Accented(ep.color) {
        Column {
            ScreenHeader("单集详情", onBack)
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(ep.coverUrl, 168.dp, title = ep.podcastTitle, color = ep.color)
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ep.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            if (ep.podcastTitle.isNotEmpty()) {
                                Text(ep.podcastTitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            Text(
                                listOf(Fmt.minutes(ep.durationSec), Fmt.date(ep.pubDate)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.gap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton(if (ep.progressSec > 0) "继续播放" else "播放") { actions.play(ep) }
                        BigButton("加入队列", secondary = true) { actions.enqueue(ep) }
                        if (ep.pid.isNotEmpty()) BigButton("查看节目", secondary = true) { vm.push(Route.PodcastDetail(ep.pid)) }
                    }
                }
                item { Shownotes(vm, ep) }
            }
        }
    }
}

@Composable
fun Shownotes(vm: AppViewModel, ep: Episode) {
    Column {
        Text("节目简介", style = MaterialTheme.typography.titleLarge)
        Text("点击时间戳可以跳到对应位置", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ShownotesView(vm, ep)
    }
}
