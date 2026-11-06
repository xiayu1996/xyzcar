package com.ryan.xyzcar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ryan.xyzcar.ui.components.FullPlayer
import com.ryan.xyzcar.ui.components.MiniPlayer
import com.ryan.xyzcar.ui.components.PlayerActions
import com.ryan.xyzcar.ui.components.pressable
import com.ryan.xyzcar.ui.screens.ContentArea
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz

fun Tab.icon(selected: Boolean): ImageVector = when (this) {
    Tab.Discover -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
    Tab.Recommend -> if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome
    Tab.Subscriptions -> if (selected) Icons.Filled.Subscriptions else Icons.Outlined.Subscriptions
    Tab.Search -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
    Tab.Me -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
}

/**
 * 主布局（按宽度自适应，分屏时也能用）：
 * - 宽（≥ 600dp）：左侧竖向导航 | 内容 + 底部迷你播放条
 * - 窄（分屏 1/3、竖屏手机）：内容 / 迷你播放条 / 底部导航
 * 全屏播放页盖在最上层，从底部滑出。
 */
@Composable
fun MainLayout(vm: AppViewModel) {
    val ps by vm.player.state.collectAsState()
    val sleepEnd by vm.player.sleepEndsAt.collectAsState()
    val queueSize = remember(ps.episode?.eid, vm.queueVersion) { vm.repo.queue.size }
    val snackbar = remember { SnackbarHostState() }
    val msg by vm.player.messages.collectAsState()
    LaunchedEffect(msg) {
        msg?.let { snackbar.showSnackbar(it); vm.player.messages.value = null }
    }

    BackHandler(enabled = vm.playerExpanded || vm.stack.size > 1) { vm.back() }

    val actions = remember {
        PlayerActions(
            toggle = vm.player::togglePlay,
            back = vm.player::seekBack,
            forward = vm.player::seekForward,
            seekTo = vm.player::seekTo,
            cycleSpeed = vm.player::cycleSpeed,
            openEpisode = { vm.player.state.value.episode?.let { vm.playerExpanded = false; vm.push(Route.EpisodeDetail(it)) } },
            openQueue = { vm.playerExpanded = false; vm.push(Route.Queue) },
            expand = { vm.playerExpanded = true },
            collapse = { vm.playerExpanded = false },
            cycleSleep = vm.player::cycleSleep,
        )
    }
    // 旋转、分屏改变宽度时内容区整体搬家，不丢状态、不重放转场
    val content = remember { movableContentOf { ContentArea(vm) } }

    val contentWithSnackbar = @Composable { m: Modifier ->
        Box(m) {
            content()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { data ->
                Snackbar(data, shape = RoundedCornerShape(50), containerColor = Xyz.text, contentColor = Xyz.bg)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Xyz.bg).safeDrawingPadding()) {
        val narrow = maxWidth < Dims.narrow
        if (narrow) {
            Column(Modifier.fillMaxSize()) {
                contentWithSnackbar(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
                MiniPlayer(ps, queueSize, actions, Modifier.padding(horizontal = 12.dp))
                BottomNav(vm.currentTab, vm::selectTab)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavRail(vm.currentTab, vm::selectTab)
                Column(Modifier.weight(1f).fillMaxHeight().padding(end = 24.dp, bottom = 16.dp)) {
                    contentWithSnackbar(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, top = 12.dp))
                    Spacer(Modifier.height(12.dp))
                    MiniPlayer(ps, queueSize, actions)
                }
            }
        }
        AnimatedVisibility(
            vm.playerExpanded,
            enter = slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(360, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(300)),
        ) {
            // 定时剩余分钟：自己每 10 秒更新一次（暂停时播放状态不刷新）
            val left by androidx.compose.runtime.produceState<Int?>(null, sleepEnd) {
                while (true) {
                    value = sleepEnd?.let { ((it - android.os.SystemClock.elapsedRealtime()) / 60_000L + 1).toInt().coerceAtLeast(1) }
                    if (sleepEnd == null) break
                    kotlinx.coroutines.delay(10_000)
                }
            }
            FullPlayer(ps, left, actions)
        }
    }
}

/** 导航项：选中是强调色图标 + 浅青底，颜色切换有渐变 */
@Composable
private fun NavItem(tab: Tab, selected: Boolean, modifier: Modifier, height: Dp, showLabel: Boolean, onClick: () -> Unit) {
    val x = Xyz
    val fg by animateColorAsState(if (selected) x.accent else x.sub, tween(250), label = "navFg")
    val bg by animateColorAsState(if (selected) x.accentFill else Color.Transparent, tween(250), label = "navBg")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .semantics { this.selected = selected }
            .pressable(pressedScale = 0.92f) { onClick() },
    ) {
        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(tab.icon(selected), contentDescription = tab.label, tint = fg, modifier = Modifier.size(34.dp))
        }
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(tab.label, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

/** 竖向导航：高度不够时先压缩每项高度、再去掉文字，最后允许滚动 */
@Composable
fun NavRail(selected: Tab?, onTab: (Tab) -> Unit) {
    BoxWithConstraints(Modifier.width(Dims.railWidth).fillMaxHeight().padding(vertical = 16.dp, horizontal = 8.dp)) {
        val count = Tab.entries.size
        val gap = 6.dp
        val itemH = ((maxHeight - gap * (count - 1)) / count).coerceIn(Dims.touch, 104.dp)
        val showLabel = itemH >= 84.dp
        val fits = itemH * count + gap * (count - 1) <= maxHeight
        Column(
            Modifier.fillMaxSize().then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
        ) {
            Tab.entries.forEach { t -> NavItem(t, selected == t, Modifier.fillMaxWidth(), itemH, showLabel) { onTab(t) } }
        }
    }
}

/** 底部导航（窄屏 / 分屏 1/3） */
@Composable
fun BottomNav(selected: Tab?, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Tab.entries.forEach { t -> NavItem(t, selected == t, Modifier.weight(1f), 88.dp, true) { onTab(t) } }
    }
}
