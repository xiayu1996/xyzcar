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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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

/**
 * 快退 / 快进图标：同一个回转箭头（快进水平镜像）+ 同一套数字字形，两边大小、粗细一致。
 * 数字按图标尺寸换算，不跟系统字号缩放，免得放大字号后撑出圆圈。
 */
@Composable
private fun SkipIcon(seconds: Int, forward: Boolean, tint: Color, size: Dp) {
    val numSize = with(LocalDensity.current) { (size * 0.3f).toSp() }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Icon(Icons.Filled.Replay, null, tint = tint, modifier = Modifier.fillMaxSize().graphicsLayer { if (forward) scaleX = -1f })
        Text("$seconds", color = tint, fontSize = numSize, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = size * 0.09f))
    }
}

@Composable
private fun SkipButton(forward: Boolean, tint: Color, iconSize: Dp, enabled: Boolean, onClick: () -> Unit) {
    val seconds = if (forward) 30 else 15
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size((iconSize * 1.4f).coerceAtLeast(Dims.touch)).clip(CircleShape)
            .semantics { contentDescription = if (forward) "前进 $seconds 秒" else "后退 $seconds 秒" }
            .pressable(enabled, pressedScale = 0.88f) { onClick() },
    ) { SkipIcon(seconds, forward, if (enabled) tint else tint.copy(alpha = 0.3f), iconSize) }
}

/** 实心播放键：整组控制里唯一的实心元素；缓冲时图标换成转圈 */
@Composable
private fun PlayFab(playing: Boolean, buffering: Boolean, enabled: Boolean, size: Dp, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.coerceAtLeast(Dims.touch))
            .semantics { contentDescription = if (playing) "暂停" else "播放" }
            .pressable(enabled, pressedScale = 0.9f) { onClick() },
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(if (enabled) bg else bg.copy(alpha = 0.35f)))
        androidx.compose.animation.Crossfade(if (buffering && enabled) null else playing, animationSpec = tween(200), label = "fab") { s ->
            if (s == null) CircularProgressIndicator(Modifier.size(size * 0.4f), color = fg, strokeWidth = 3.dp)
            else Icon(if (s) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = fg, modifier = Modifier.size(size * 0.5f))
        }
    }
}

// ---------------- 迷你播放条 ----------------

/**
 * 底部迷你播放条（官方的白色悬浮卡片）：后退 15 · 播放（实心）· 前进 30 | 封面 | 标题 / 时间 · 节目 | 播放列表；
 * 底边一条细进度线（Android 车机「最小化控制栏」的做法），不再在播放键外画进度环。
 * 控制组放在最左边：左舵车驾驶员离屏幕左侧最近。窄屏只留播放键。点卡片其余位置展开全屏播放页。
 */
@Composable
fun MiniPlayer(state: PlayerUi, queueSize: Int, a: PlayerActions, modifier: Modifier = Modifier) {
    val x = Xyz
    val ep = state.episode
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 760.dp
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dims.miniPlayer)
                .card(RoundedCornerShape(24.dp), x.card, x.shadow, x.border, 18.dp)
                .pressable(enabled = ep != null, pressedScale = 0.985f) { a.expand() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 10.dp)) {
                if (wide) SkipButton(false, x.text, 44.dp, ep != null, a.back)
                PlayFab(state.isPlaying, state.buffering, ep != null, 60.dp, accent, MaterialTheme.colorScheme.onPrimary, a.toggle)
                if (wide) SkipButton(true, x.text, 44.dp, ep != null, a.forward)
                Spacer(Modifier.width(if (wide) 14.dp else 10.dp))
                AnimatedContent(ep?.eid, transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(200)) }, label = "miniCover") { _ ->
                    Cover(ep?.coverUrl.orEmpty(), 60.dp, title = ep?.podcastTitle.orEmpty(), color = ep?.color, radius = 10.dp)
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
                        when {
                            ep == null -> "从推荐或订阅里选一集开始听"
                            state.durationMs > 0 -> "${Fmt.clock(state.positionMs)} / ${Fmt.clock(state.durationMs)} · ${ep.podcastTitle}"
                            else -> ep.podcastTitle
                        },
                        style = MaterialTheme.typography.bodyMedium.merge(NumberStyle).copy(fontWeight = FontWeight.Medium), color = x.sub,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
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
            if (ep != null && state.durationMs > 0) {
                val p by animateFloatAsState((state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f), tween(450), label = "miniProgress")
                val track = accent.copy(alpha = 0.28f)
                Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 5.dp).height(3.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    drawRoundRect(accent, size = Size(size.width * p, size.height), cornerRadius = r)
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

/** 播放页下方的次要操作：同一形状、同一高度、等宽；[active] 时用强调色（如定时已开） */
@Composable
private fun PillAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, accent: Color, stacked: Boolean,
    modifier: Modifier = Modifier, active: Boolean = false, label: (@Composable (Color) -> Unit)? = null, onClick: () -> Unit,
) {
    val fg = if (active) accent else Color.White.copy(alpha = 0.9f)
    val bg by animateColorAsState(if (active) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.09f), label = "pillBg")
    val content: @Composable () -> Unit = {
        Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
        if (label != null) label(fg) else Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.heightIn(min = Dims.touch).clip(RoundedCornerShape(20.dp)).background(bg)
        .semantics { contentDescription = text }.pressable { onClick() }
    if (stacked) {
        Column(m.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) { content() }
    } else {
        Row(m.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) { content() }
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

/** 全屏播放页：横屏左控制右封面（控制靠驾驶位），竖屏 / 分屏上下排列。始终深色底，强调色取自节目封面 */
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
        // 竖排时高度够才把大封面放上面；分屏这类矮窗口改成小封面和标题并排，保证控制一屏放得下
        val winHeight = maxHeight
        val bigCover = landscape || maxHeight >= 860.dp
        val coverSize = when {
            landscape -> min(maxHeight * 0.62f, maxWidth * 0.34f)
            bigCover -> min(maxWidth * 0.62f, maxHeight * 0.34f)
            else -> 104.dp
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)) {
            WhiteIcon(Icons.Filled.KeyboardArrowDown, "收起播放页", 72.dp, onClick = a.collapse)
            val cover = @Composable {
                AnimatedContent(ep?.eid, transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(250)) }, label = "bigCover") { _ ->
                    Box(Modifier.pressable(ep != null, pressedScale = 0.98f) { a.openEpisode() }) {
                        Cover(ep?.coverUrl.orEmpty(), coverSize, title = ep?.podcastTitle.orEmpty(), color = ep?.color, radius = if (bigCover) 20.dp else 14.dp)
                    }
                }
            }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    PlayerControls(state, sleepMinutesLeft, accent, a, Modifier.weight(1f))
                    Box(Modifier.padding(end = 16.dp)) { cover() }
                }
            } else {
                // 放得下时垂直居中；窗口特别矮（分屏缩成小窗）时可以上下滑，不会把按钮挤出屏幕
                val viewport = winHeight - 32.dp - 72.dp
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                ) {
                    if (bigCover) cover()
                    PlayerControls(state, sleepMinutesLeft, accent, a, Modifier.fillMaxWidth(), inlineCover = if (bigCover) null else cover)
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlayerUi, sleepMinutesLeft: Int?, accent: Color, a: PlayerActions, modifier: Modifier,
    inlineCover: (@Composable () -> Unit)? = null,
) {
    val ep = state.episode
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val titleBlock = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedContent(ep?.title, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }, label = "bigTitle") { t ->
                    Text(
                        t ?: "还没有播放的单集", color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = if (inlineCover != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    )
                }
                Text(ep?.podcastTitle.orEmpty(), color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (inlineCover != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                inlineCover()
                Box(Modifier.weight(1f)) { titleBlock() }
            }
        } else titleBlock()
        Column {
            SeekBar(state, accent, Color.White.copy(alpha = 0.18f), a.seekTo)
            Row(Modifier.fillMaxWidth()) {
                val num = MaterialTheme.typography.bodyLarge.merge(NumberStyle)
                Text(Fmt.clock(state.positionMs), style = num, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.weight(1f))
                Text(if (state.durationMs > 0) "-" + Fmt.clock(state.durationMs - state.positionMs) else "", style = num, color = Color.White.copy(alpha = 0.85f))
            }
        }
        // 主控制：只有三个，后退 · 播放 · 前进，等距居中；播放键是唯一的实心元素
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val k = (maxWidth / 560.dp).coerceIn(0.7f, 1f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp * k, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                SkipButton(false, Color.White, 68.dp * k, ep != null, a.back)
                PlayFab(state.isPlaying, state.buffering, ep != null, 120.dp * k, accent, Color(0xFF111217), a.toggle)
                SkipButton(true, Color.White, 68.dp * k, ep != null, a.forward)
            }
        }
        // 次要操作：倍速、定时、播放列表、简介，同一种胶囊等宽排一行；窄时图标在上文字在下
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 600.dp
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val w = Modifier.weight(1f)
                PillAction(Icons.Filled.Speed, "倍速 ${speedText(state.speed)}", accent, stacked, w, active = state.speed != 1f,
                    label = { c ->
                        AnimatedContent(state.speed, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "speed") { s ->
                            Text(speedText(s), color = c, style = MaterialTheme.typography.labelLarge.merge(NumberStyle), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }, onClick = a.cycleSpeed)
                PillAction(Icons.Filled.Bedtime, if (sleepMinutesLeft != null) "定时关闭，剩 $sleepMinutesLeft 分钟" else "定时关闭", accent, stacked, w,
                    active = sleepMinutesLeft != null,
                    label = { c ->
                        AnimatedContent(sleepMinutesLeft, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sleep") { m ->
                            Text(if (m != null) "剩 $m 分" else "定时", color = c, style = MaterialTheme.typography.labelLarge.merge(NumberStyle), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }, onClick = a.cycleSleep)
                PillAction(Icons.AutoMirrored.Filled.QueueMusic, "播放列表", accent, stacked, w, onClick = a.openQueue)
                if (ep != null) PillAction(Icons.Filled.Info, "简介", accent, stacked, w, onClick = a.openEpisode)
            }
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
