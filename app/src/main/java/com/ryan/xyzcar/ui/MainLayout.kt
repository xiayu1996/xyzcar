package com.ryan.xyzcar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.ryan.xyzcar.ui.components.PlayerActions
import com.ryan.xyzcar.ui.components.PlayerPanelTall
import com.ryan.xyzcar.ui.components.PlayerPanelWide
import com.ryan.xyzcar.ui.screens.ContentArea
import com.ryan.xyzcar.ui.theme.Dims

private fun Tab.icon(): ImageVector = when (this) {
    Tab.Discover -> Icons.Filled.Explore
    Tab.Subscriptions -> Icons.Filled.Subscriptions
    Tab.Search -> Icons.Filled.Search
    Tab.History -> Icons.Filled.History
}

/**
 * 主布局：
 * - 横屏：左「正在播放」(40%) | 竖向导航栏 | 内容
 * - 竖屏：内容 / 导航栏 / 底部「正在播放」
 */
@Composable
fun MainLayout(vm: AppViewModel) {
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp >= config.screenHeightDp
    val ps by vm.player.state.collectAsState()
    val queueSize = remember(ps.episode?.eid, vm.queueVersion) { vm.repo.queue.size }
    val snackbar = remember { SnackbarHostState() }
    val msg by vm.player.messages.collectAsState()
    LaunchedEffect(msg) {
        msg?.let { snackbar.showSnackbar(it); vm.player.messages.value = null }
    }

    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    val actions = remember {
        PlayerActions(
            toggle = vm.player::togglePlay,
            back = vm.player::seekBack,
            forward = vm.player::seekForward,
            seekTo = vm.player::seekTo,
            cycleSpeed = vm.player::cycleSpeed,
            openEpisode = { vm.player.state.value.episode?.let { vm.push(Route.EpisodeDetail(it)) } },
            openQueue = { vm.push(Route.Queue) },
        )
    }
    // 旋转屏幕时内容区整体搬家，不丢状态
    val content = remember { movableContentOf { ContentArea(vm) } }

    // 提示条放在内容区底部，不挡标题和返回键
    val contentWithSnackbar = @Composable { m: Modifier ->
        Box(m) {
            content()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { data ->
                Snackbar(
                    data,
                    shape = RoundedCornerShape(50),
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerPanelTall(ps, queueSize, actions, Modifier.weight(0.4f))
                NavRail(vm.currentTab.takeIf { vm.stack.size == 1 }, vm.current == Route.Settings, vm::selectTab) { vm.push(Route.Settings) }
                contentWithSnackbar(Modifier.weight(0.6f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                contentWithSnackbar(Modifier.weight(1f).fillMaxWidth())
                NavBar(vm.currentTab.takeIf { vm.stack.size == 1 }, vm.current == Route.Settings, vm::selectTab) { vm.push(Route.Settings) }
                PlayerPanelWide(ps, queueSize, actions)
            }
        }
    }
}

/** 导航项：选中是浅填充底 + 墨色，未选中是灰色图标（参考小宇宙底部 Tab 的克制用色） */
@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, height: Dp = 96.dp, showLabel: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Dims.radius),
        color = if (selected) cs.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) cs.onSurface else cs.onSurfaceVariant,
        modifier = modifier.height(height),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(4.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(38.dp))
            if (showLabel) Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

/** 竖向导航：高度不够时先压缩每项高度、再去掉文字，最后允许滚动，保证「设置」不被裁掉 */
@Composable
fun NavRail(selected: Tab?, settingsSelected: Boolean, onTab: (Tab) -> Unit, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.width(Dims.railWidth).fillMaxHeight()) {
        val count = Tab.entries.size + 1
        val gap = 8.dp
        val itemH = ((maxHeight - gap * (count - 1) - 24.dp) / count).coerceIn(Dims.touch, 96.dp)
        val showLabel = itemH >= 88.dp
        val fits = itemH * count + gap * (count - 1) <= maxHeight
        Column(
            Modifier.fillMaxSize().then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Tab.entries.forEach { t ->
                NavItem(t.label, t.icon(), selected == t, Modifier.fillMaxWidth(), itemH, showLabel) { onTab(t) }
            }
            if (fits) Spacer(Modifier.weight(1f))
            NavItem("设置", Icons.Filled.Settings, settingsSelected, Modifier.fillMaxWidth(), itemH, showLabel, onSettings)
        }
    }
}

@Composable
fun NavBar(selected: Tab?, settingsSelected: Boolean, onTab: (Tab) -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tab.entries.forEach { t ->
            NavItem(t.label, t.icon(), selected == t, Modifier.weight(1f), 88.dp) { onTab(t) }
        }
        NavItem("设置", Icons.Filled.Settings, settingsSelected, Modifier.weight(1f), 88.dp, onClick = onSettings)
    }
}
