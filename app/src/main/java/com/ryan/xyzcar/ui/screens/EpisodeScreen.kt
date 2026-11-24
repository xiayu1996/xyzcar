package com.ryan.xyzcar.ui.screens

import com.ryan.xyzcar.ui.theme.Xyz
import com.ryan.xyzcar.ui.theme.NumberStyle
import com.ryan.xyzcar.ui.components.SeekBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.ryan.xyzcar.ui.components.pressable
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
                        Box(Modifier.pressable(ep.pid.isNotEmpty(), pressedScale = 0.97f) { vm.push(Route.PodcastDetail(ep.pid)) }) {
                            Cover(ep.coverUrl, 168.dp, title = ep.podcastTitle, color = ep.color)
                        }
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ep.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            if (ep.podcastTitle.isNotEmpty()) {
                                Text(
                                    ep.podcastTitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(ep.pid.isNotEmpty(), pressedScale = 0.97f) { vm.push(Route.PodcastDetail(ep.pid)) }.padding(vertical = 6.dp),
                                )
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
                item { NowPlayingSeek(vm, ep.eid) }
                item { Shownotes(vm, ep) }
            }
        }
    }
}

/** 单独成块，只有这里跟着进度重组 */
@Composable
private fun NowPlayingSeek(vm: AppViewModel, eid: String) {
    val currentEid by vm.player.currentEid.collectAsState()
    AnimatedVisibility(
        currentEid == eid,
        enter = expandVertically(tween(320)) + fadeIn(tween(280)),
        exit = shrinkVertically(tween(280)) + fadeOut(tween(180)),
    ) {
        val ps by vm.player.state.collectAsState()
        Column {
            SeekBar(ps, MaterialTheme.colorScheme.primary, Xyz.fill, vm.player::seekTo)
            Row(Modifier.fillMaxWidth()) {
                val num = MaterialTheme.typography.bodyLarge.merge(NumberStyle)
                Text(Fmt.clock(ps.positionMs), style = num, color = Xyz.sub)
                Spacer(Modifier.weight(1f))
                if (ps.durationMs > 0) Text("-" + Fmt.clock(ps.durationMs - ps.positionMs), style = num, color = Xyz.sub)
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
