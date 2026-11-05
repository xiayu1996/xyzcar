package com.ryan.xyzcar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.ryan.xyzcar.player.PlayerUi
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.theme.Accented
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.NumberStyle

data class PlayerActions(
    val toggle: () -> Unit,
    val back: () -> Unit,
    val forward: () -> Unit,
    val seekTo: (Long) -> Unit,
    val cycleSpeed: () -> Unit,
    val openEpisode: () -> Unit,
    val openQueue: () -> Unit,
)

/** 进度条：可见 8dp，触控热区 76dp；拖动结束才 seek */
@Composable
fun ProgressBar(state: PlayerUi, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = state.durationMs.coerceAtLeast(1)
    val value = dragging ?: (state.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * dur).toLong()) }
                dragging = null
            },
            enabled = state.episode != null,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
            modifier = Modifier.fillMaxWidth().height(Dims.progressHit),
        )
        Row(Modifier.fillMaxWidth()) {
            val num = MaterialTheme.typography.bodyMedium.merge(NumberStyle)
            Text(Fmt.clock((value * dur).toLong()), style = num, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                if (state.durationMs > 0) "-" + Fmt.clock(state.durationMs - (value * dur).toLong()) else "",
                style = num, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 次要按钮（倍速、队列）：浅填充胶囊 */
@Composable
private fun PillButton(onClick: () -> Unit, minWidth: Dp = Dims.touch, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = Dims.touch).widthIn(min = minWidth),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 20.dp)) { content() }
    }
}

@Composable
private fun SpeedButton(speed: Float, onClick: () -> Unit) = PillButton(onClick, minWidth = Dims.sideButton) {
    Text(
        (if (speed % 1f == 0f) "%.1f".format(speed) else "%.2f".format(speed).trimEnd('0')) + "×",
        style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
    )
}

@Composable
private fun QueueButton(size: Int, onClick: () -> Unit, compact: Boolean = false) = PillButton(onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", modifier = Modifier.size(34.dp))
        if (!compact || size > 0) {
            Spacer(Modifier.width(6.dp))
            Text(if (compact) "$size" else if (size > 0) "队列 $size" else "队列", style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
        }
    }
}

/**
 * 后退 15 / 播放 / 前进 30。
 * 宽度不够时（界面放大、竖屏窄条）按比例缩小，但不小于 76dp 触控尺寸，避免按钮被裁或重叠。
 */
@Composable
private fun Controls(state: PlayerUi, a: PlayerActions, play: Dp, side: Dp) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val gap = 12.dp
        val need = play + side * 2 + gap * 2
        val k = if (maxWidth < need) maxWidth / need else 1f
        val p = (play * k).coerceAtLeast(Dims.touch)
        val s = (side * k).coerceAtLeast(Dims.touch)
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                RoundIconButton(Icons.Filled.Replay, "后退 15 秒", size = s, plain = true, enabled = state.episode != null, onClick = a.back)
                Text("15", fontSize = (s.value * 0.15f).sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = (s.value * 0.05f).dp))
            }
            Box(contentAlignment = Alignment.Center) {
                RoundIconButton(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (state.isPlaying) "暂停" else "播放",
                    size = p, primary = true, enabled = state.episode != null, onClick = a.toggle,
                )
                if (state.buffering) CircularProgressIndicator(Modifier.size(p), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 4.dp)
            }
            RoundIconButton(Icons.Filled.Forward30, "前进 30 秒", size = s, plain = true, enabled = state.episode != null, onClick = a.forward)
        }
    }
}

/** 面板底色：节目主题色淡淡铺一层（取色自封面），没有节目色时就是普通卡片色 */
@Composable
private fun PanelSurface(state: PlayerUi, modifier: Modifier, content: @Composable () -> Unit) {
    Accented(state.episode?.color) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = modifier) { content() }
    }
}

/** 横屏：左侧竖向「正在播放」面板 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerPanelTall(state: PlayerUi, queueSize: Int, a: PlayerActions, modifier: Modifier = Modifier) {
    PanelSurface(state, modifier.fillMaxHeight()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            val ep = state.episode
            // 封面吃掉剩余空间：其余控件先排好，空间不够时封面缩小直至隐藏，控件不会被挤出面板
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val coverSize = min(maxWidth * 0.82f, maxHeight)
                if (coverSize >= 96.dp) {
                    Surface(onClick = a.openEpisode, enabled = ep != null, color = androidx.compose.ui.graphics.Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                        Cover(ep?.coverUrl.orEmpty(), coverSize, title = ep?.podcastTitle.orEmpty(), color = ep?.color)
                    }
                }
            }
            Surface(onClick = a.openEpisode, enabled = ep != null, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        ep?.title ?: "还没有播放的单集",
                        style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ep?.podcastTitle ?: "从订阅或发现里选一集开始听",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ep != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ProgressBar(state, a.seekTo)
            Controls(state, a, Dims.playButton, Dims.sideButton)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dims.gap, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SpeedButton(state.speed, a.cycleSpeed)
                QueueButton(queueSize, a.openQueue)
            }
        }
    }
}

/** 竖屏：底部「正在播放」面板 */
@Composable
fun PlayerPanelWide(state: PlayerUi, queueSize: Int, a: PlayerActions, modifier: Modifier = Modifier) {
    PanelSurface(state, modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            val ep = state.episode
            Surface(onClick = a.openEpisode, enabled = ep != null, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(ep?.coverUrl.orEmpty(), 104.dp, title = ep?.podcastTitle.orEmpty(), color = ep?.color)
                    Spacer(Modifier.width(Dims.gap))
                    Column {
                        Text(ep?.title ?: "还没有播放的单集", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            ep?.podcastTitle ?: "从订阅或发现里选一集开始听", style = MaterialTheme.typography.bodyMedium,
                            color = if (ep != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ProgressBar(state, a.seekTo)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedButton(state.speed, a.cycleSpeed)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { Controls(state, a, 112.dp, Dims.sideButton) }
                QueueButton(queueSize, a.openQueue, compact = true)
            }
        }
    }
}
