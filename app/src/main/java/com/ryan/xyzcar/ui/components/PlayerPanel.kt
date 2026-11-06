package com.ryan.xyzcar.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.ryan.xyzcar.data.ThemeColor
import com.ryan.xyzcar.player.PlayerUi
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.NumberStyle
import com.ryan.xyzcar.ui.theme.Xyz
import com.ryan.xyzcar.ui.theme.parseHex

data class PlayerActions(
    val toggle: () -> Unit,
    val back: () -> Unit,
    val forward: () -> Unit,
    val seekTo: (Long) -> Unit,
    val cycleSpeed: () -> Unit,
    val openEpisode: () -> Unit,
    val openQueue: () -> Unit,
    val expand: () -> Unit = {},
    val collapse: () -> Unit = {},
    val cycleSleep: () -> Unit = {},
)

private fun speedText(speed: Float) = (if (speed % 1f == 0f) "%.1f".format(speed) else "%.2f".format(speed).trimEnd('0')) + "x"

/** 后退 15 秒图标：回转箭头中间写 15（Material 没有现成的 Replay15 填色版） */
@Composable
private fun Rewind15(tint: Color, size: Dp) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Icon(Icons.Filled.Replay, null, tint = tint, modifier = Modifier.fillMaxSize())
        Text("15", color = tint, fontSize = (size.value * 0.24f).sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = size * 0.08f))
    }
}

@Composable
private fun Rewind15Button(tint: Color, size: Dp, iconSize: Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.coerceAtLeast(Dims.touch)).clip(CircleShape)
            .semantics { contentDescription = "后退 15 秒" }
            .pressable(enabled, pressedScale = 0.88f) { onClick() },
    ) { Rewind15(if (enabled) tint else tint.copy(alpha = 0.3f), iconSize) }
}

// ---------------- 迷你播放条 ----------------

/**
 * 底部迷你播放条（官方的白色悬浮卡片）：封面 / 标题 + 时间 / 后退 15 · 前进 30（宽屏）/ 带进度环的播放键 / 播放列表。
 * 点卡片展开全屏播放页。切换单集时封面和标题淡入淡出。
 */
@Composable
fun MiniPlayer(state: PlayerUi, queueSize: Int, a: PlayerActions, modifier: Modifier = Modifier) {
    val x = Xyz
    val ep = state.episode
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 760.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(Dims.miniPlayer)
                .card(RoundedCornerShape(24.dp), x.card, x.shadow, x.border, 18.dp)
                .pressable(enabled = ep != null, pressedScale = 0.985f) { a.expand() }
                .padding(start = 14.dp, end = 10.dp),
        ) {
            AnimatedContent(ep?.eid, transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(200)) }, label = "miniCover") { _ ->
                Cover(ep?.coverUrl.orEmpty(), 68.dp, title = ep?.podcastTitle.orEmpty(), color = ep?.color, radius = 10.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(ep?.title, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "miniTitle") { title ->
                    Text(
                        title ?: "还没有播放的单集", style = MaterialTheme.typography.titleSmall, color = if (title != null) x.text else x.sub,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (ep != null && state.durationMs > 0) "${Fmt.clock(state.positionMs)} / ${Fmt.clock(state.durationMs)}" else ep?.podcastTitle ?: "从推荐或订阅里选一集开始听",
                    style = MaterialTheme.typography.bodyMedium.merge(NumberStyle).copy(fontWeight = FontWeight.Medium), color = x.sub, maxLines = 1,
                )
            }
            if (wide) {
                Rewind15Button(x.text, Dims.touch, 44.dp, ep != null, a.back)
                RoundIconButton(Icons.Filled.Forward30, "前进 30 秒", plain = true, enabled = ep != null, tint = x.text, onClick = a.forward)
            }
            Box(contentAlignment = Alignment.Center) {
                PlayButton(
                    playing = state.isPlaying,
                    progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else null,
                    onClick = a.toggle, enabled = ep != null, size = 64.dp,
                )
                if (state.buffering) CircularProgressIndicator(Modifier.size(64.dp), color = x.accent, strokeWidth = 3.dp)
            }
            Box {
                RoundIconButton(Icons.AutoMirrored.Filled.QueueMusic, "播放列表", plain = true, tint = x.text, onClick = a.openQueue)
                if (queueSize > 0) {
                    Text(
                        "$queueSize", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 6.dp).clip(CircleShape).background(x.accent).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

// ---------------- 全屏播放页 ----------------

/** 播放页强调色：节目色里在深色底上够亮的那个，没有就用品牌青 */
private fun playerAccent(c: ThemeColor?): Color {
    val candidates = listOfNotNull(c?.light, c?.original).mapNotNull { parseHex(it) }
    return candidates.firstOrNull { it.luminance() > 0.28f } ?: Color(0xFF56BBE8)
}

/** 背景：节目色压暗后做对角渐变（封面取色），没有节目色时用深蓝灰 */
private fun playerBackground(c: ThemeColor?): Brush {
    val base = parseHex(c?.dark ?: "") ?: parseHex(c?.original ?: "") ?: Color(0xFF2A3542)
    val top = lerp(base, Color(0xFF15171C), 0.55f)
    return Brush.linearGradient(listOf(top, lerp(top, Color(0xFF111217), 0.5f), Color(0xFF111217)))
}

/**
 * 进度条：可见 10dp、触控热区 72dp；可点按跳转、可拖动（拖动结束才 seek）。
 */
@Composable
fun SeekBar(state: PlayerUi, accent: Color, track: Color, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = state.durationMs.coerceAtLeast(1)
    val live = (state.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    val shown by animateFloatAsState(dragging ?: live, tween(if (dragging != null) 0 else 450), label = "seek")
    val enabled = state.episode != null && state.durationMs > 0
    Box(
        modifier
            .fillMaxWidth()
            .height(Dims.progressHit)
            .semantics { contentDescription = "播放进度 ${Fmt.clock(state.positionMs)}" }
            .pointerInput(enabled, dur) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
            }
            .pointerInput(enabled, dur) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { onSeek((it * dur).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.Center,
    ) {
        val thumb by animateFloatAsState(if (dragging != null) 1.35f else 1f, label = "thumb")
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val h = 10.dp.toPx()
            val y = (size.height - h) / 2
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(accent, Offset(0f, y), Size(size.width * shown, h), CornerRadius(h / 2))
            drawCircle(Color.White, 12.dp.toPx() * thumb, Offset(size.width * shown, size.height / 2))
        }
    }
}

@Composable
private fun PillAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = Dims.touch)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .pressable { onClick() }
            .padding(horizontal = 26.dp),
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun WhiteIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Dp, enabled: Boolean = true, alpha: Float = 1f, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.coerceAtLeast(Dims.touch)).clip(CircleShape).pressable(enabled, pressedScale = 0.88f) { onClick() },
    ) {
        Icon(icon, desc, tint = Color.White.copy(alpha = if (enabled) alpha else 0.3f), modifier = Modifier.size(size * 0.66f))
    }
}

/** 全屏播放页：横屏左封面右控制，竖屏 / 分屏上下排列。始终深色底，强调色取自节目封面 */
@Composable
fun FullPlayer(state: PlayerUi, sleepMinutesLeft: Int?, a: PlayerActions, modifier: Modifier = Modifier) {
    val ep = state.episode
    val accent by animateColorAsState(playerAccent(ep?.color), tween(500), label = "playerAccent")
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(playerBackground(ep?.color))
            .pointerInput(Unit) { detectTapGestures { } }, // 吃掉点击，不穿透到下面的页面
    ) {
        val landscape = maxWidth > maxHeight * 1.15f && maxWidth >= 720.dp
        val coverSize = if (landscape) min(maxHeight * 0.62f, maxWidth * 0.34f) else min(maxWidth * 0.62f, maxHeight * 0.34f)
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)) {
            WhiteIcon(Icons.Filled.KeyboardArrowDown, "收起播放页", 72.dp, onClick = a.collapse)
            val cover = @Composable {
                AnimatedContent(ep?.eid, transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(250)) }, label = "bigCover") { _ ->
                    Box(Modifier.pressable(ep != null, pressedScale = 0.98f) { a.openEpisode() }) {
                        Cover(ep?.coverUrl.orEmpty(), coverSize, title = ep?.podcastTitle.orEmpty(), color = ep?.color, radius = 20.dp)
                    }
                }
            }
            val controls = @Composable { m: Modifier -> PlayerControls(state, sleepMinutesLeft, accent, a, m) }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    Box(Modifier.padding(start = 16.dp)) { cover() }
                    controls(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                    cover()
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerUi, sleepMinutesLeft: Int?, accent: Color, a: PlayerActions, modifier: Modifier) {
    val ep = state.episode
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AnimatedContent(ep?.title, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }, label = "bigTitle") { t ->
            Text(t ?: "还没有播放的单集", color = Color.White, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(ep?.podcastTitle.orEmpty(), color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column {
            SeekBar(state, accent, Color.White.copy(alpha = 0.18f), a.seekTo)
            Row(Modifier.fillMaxWidth()) {
                val num = MaterialTheme.typography.bodyLarge.merge(NumberStyle)
                Text(Fmt.clock(state.positionMs), style = num, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.weight(1f))
                Text(if (state.durationMs > 0) "-" + Fmt.clock(state.durationMs - state.positionMs) else "", style = num, color = Color.White.copy(alpha = 0.85f))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val k = (maxWidth / 620.dp).coerceIn(0.72f, 1f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(Dims.touch).clip(CircleShape).pressable(pressedScale = 0.88f) { a.cycleSpeed() },
                ) {
                    AnimatedContent(state.speed, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "speed") { s ->
                        Text(speedText(s), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                    }
                }
                Rewind15Button(Color.White, 92.dp * k, 92.dp * k * 0.66f, ep != null, a.back)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(128.dp * k).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).pressable(ep != null, pressedScale = 0.9f) { a.toggle() },
                ) {
                    androidx.compose.animation.Crossfade(state.isPlaying, animationSpec = tween(200), label = "bigPlay") { playing ->
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(80.dp * k))
                    }
                    if (state.buffering) CircularProgressIndicator(Modifier.size(128.dp * k), color = accent, strokeWidth = 4.dp)
                }
                WhiteIcon(Icons.Filled.Forward30, "前进 30 秒", 92.dp * k, ep != null, onClick = a.forward)
                Box(contentAlignment = Alignment.Center) {
                    WhiteIcon(Icons.Filled.Bedtime, if (sleepMinutesLeft != null) "定时关闭，剩 $sleepMinutesLeft 分钟" else "定时关闭", 72.dp, alpha = if (sleepMinutesLeft != null) 1f else 0.75f, onClick = a.cycleSleep)
                    if (sleepMinutesLeft != null) {
                        Text("$sleepMinutesLeft", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(accent).padding(horizontal = 6.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            PillAction(Icons.AutoMirrored.Filled.QueueMusic, "播放列表", a.openQueue)
            if (ep != null) PillAction(Icons.Filled.Info, "单集简介", a.openEpisode)
        }
    }
}

/** 列表行里小的进度环（不带按钮），给需要单独显示进度的地方用 */
@Composable
fun ProgressRing(progress: Float, color: Color, size: Dp) {
    Canvas(Modifier.size(size).rotate(-90f)) {
        val w = 3.dp.toPx()
        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(w))
        drawArc(color, 0f, 360f * progress.coerceIn(0f, 1f), false, style = Stroke(w, cap = StrokeCap.Round))
    }
}
