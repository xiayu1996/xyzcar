package com.ryan.xyzcar.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ryan.xyzcar.player.AutoNext
import com.ryan.xyzcar.player.PlayerUi
import com.ryan.xyzcar.ui.theme.Xyz
import kotlinx.coroutines.delay

/** 提示条的内容：[action] 是按钮文字 */
data class AutoNextNotice(val label: String, val title: String, val action: String, val swap: Boolean)

/**
 * 自动续播提示条，放在迷你播放条上方（全屏播放页里放顶部）：
 * - 单集剩 15 秒：「接下来：…」+「不要自动播」
 * - 自动接上后 10 秒内：「已自动播放：…」+「换一集」（和方向盘「下一曲」一样）
 */
@Composable
fun AutoNextBanner(state: PlayerUi, queueSize: Int, onCancel: (String) -> Unit, onSwap: () -> Unit, modifier: Modifier = Modifier) {
    val plan by AutoNext.plan.collectAsState()
    val cancelled by AutoNext.cancelledAfter.collectAsState()
    val started by AutoNext.started.collectAsState()
    val now by produceState(SystemClock.elapsedRealtime(), started) {
        while (started != null) { value = SystemClock.elapsedRealtime(); delay(500) }
    }
    val ep = state.episode
    val upcoming = plan?.takeIf {
        ep != null && it.afterEid == ep.eid && queueSize == 0 && cancelled != ep.eid &&
            state.durationMs > 0 && state.durationMs - state.positionMs <= AutoNext.ANNOUNCE_MS
    }
    val justStarted = started?.takeIf { ep?.eid == it.eid && now - it.atElapsed <= AutoNext.SWAP_WINDOW_MS }
    val notice = when {
        upcoming != null -> AutoNextNotice("接下来 · ${upcoming.reason.label}", upcoming.next.title, "不要自动播", swap = false)
        justStarted != null && ep != null -> AutoNextNotice("已自动播放 · ${justStarted.reason.label}", ep.title, "换一集", swap = true)
        else -> null
    }
    // 退场动画期间保留最后的内容
    val last = remember { arrayOfNulls<AutoNextNotice>(1) }
    if (notice != null) last[0] = notice
    AnimatedVisibility(notice != null, enter = expandVertically(tween(300)) + fadeIn(tween(300)), exit = shrinkVertically(tween(250)) + fadeOut(tween(200)), modifier = modifier) {
        last[0]?.let { n -> AutoNextBar(n) { if (n.swap) onSwap() else ep?.let { onCancel(it.eid) } } }
    }
}

/** 无状态的提示条：按钮在左（驾驶位一侧），文字在右；截图测试直接用 */
@Composable
fun AutoNextBar(n: AutoNextNotice, onAction: () -> Unit) {
    val x = Xyz
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(x.accentFill).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        AnimatedContent(n.action, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "autoNextAction") { a ->
            BigButton(a, tinted = true, onClick = onAction)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(n.label, style = MaterialTheme.typography.bodySmall, color = x.accentText, maxLines = 1)
            AnimatedContent(n.title, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "autoNextTitle") { t ->
                Text(t, style = MaterialTheme.typography.titleMedium, color = x.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
