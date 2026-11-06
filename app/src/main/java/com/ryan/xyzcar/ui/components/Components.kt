package com.ryan.xyzcar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.data.ThemeColor
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.theme.Chroma
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz
import com.ryan.xyzcar.ui.theme.parseHex

/** 仅截图测试用：不联网时按 URL 直接提供封面图。App 里始终为 null，走 Coil 正常加载 */
val LocalCoverOverride = androidx.compose.runtime.staticCompositionLocalOf<((String) -> androidx.compose.ui.graphics.ImageBitmap?)?> { null }

// ---------------- 交互手感 ----------------

/** 按下时轻微缩小、松手弹回（弹簧动画），所有可点的卡片和按钮都用 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = role, onClick = onClick)
}

/** 卡片：圆角 + 柔和投影（深色模式不投影，用描边） */
fun Modifier.card(shape: Shape, bg: Color, shadow: Color, border: Color?, elevation: Dp = 12.dp): Modifier =
    this
        .then(if (shadow.alpha > 0f) Modifier.shadow(elevation, shape, ambientColor = shadow, spotColor = shadow) else Modifier)
        .clip(shape)
        .background(bg)
        .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)

// ---------------- 封面 ----------------

/** 封面：小圆角 + 柔和投影；没有图片时用节目主题色铺底、显示标题首字 */
@Composable
fun Cover(url: String, size: Dp, modifier: Modifier = Modifier, title: String = "", color: ThemeColor? = null, radius: Dp? = null) {
    val shape = RoundedCornerShape(radius ?: (size.value * 0.1f).coerceAtLeast(6f).dp)
    val x = Xyz
    val tint = color?.original?.let(::parseHex) ?: x.fill
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .shadow(if (x.dark) 0.dp else (size.value / 14f).coerceIn(2f, 16f).dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(shape)
            .background(tint),
    ) {
        if (url.isEmpty() && title.isNotEmpty()) {
            Text(
                title.take(1),
                color = if (tint.luminance() > 0.5f) Color(0xCC000000) else Color.White.copy(alpha = 0.92f),
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Black,
            )
        }
        if (url.isNotEmpty()) {
            val override = LocalCoverOverride.current?.invoke(url)
            if (override != null) {
                androidx.compose.foundation.Image(override, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// ---------------- 按钮 ----------------

/**
 * 圆形图标按钮，触控区域不小于 72dp。
 * primary：强调色实心；plain：无底色；tinted：浅青底 + 青色图标；默认：浅灰底。
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    description: String,
    size: Dp = Dims.touch,
    primary: Boolean = false,
    enabled: Boolean = true,
    plain: Boolean = false,
    tinted: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val x = Xyz
    val cs = MaterialTheme.colorScheme
    val bg = when {
        primary -> cs.primary
        plain -> Color.Transparent
        tinted -> x.accentFill
        else -> x.fill
    }
    val fg = tint ?: when {
        primary -> cs.onPrimary
        tinted -> cs.primary
        else -> x.text
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.coerceAtLeast(Dims.touch))
            .clip(CircleShape)
            .background(bg)
            .pressable(enabled, pressedScale = 0.9f, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) fg else fg.copy(alpha = 0.3f), modifier = Modifier.size(size * if (plain) 0.6f else 0.46f))
    }
}

/**
 * 播放键：浅青圆底 + 青色三角；有收听进度时外圈是进度环（官方「更新」列表的样式）。
 * 进度变化、播放 / 暂停切换都有动画。
 */
@Composable
fun PlayButton(
    playing: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    size: Dp = 68.dp,
    enabled: Boolean = true,
    description: String = if (playing) "暂停" else "播放",
) {
    val x = Xyz
    val accent = MaterialTheme.colorScheme.primary
    val p by animateFloatAsState(progress ?: 0f, tween(600), label = "ring")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.coerceAtLeast(Dims.touch))
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled, pressedScale = 0.88f, onClick = onClick),
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(x.accentFill))
        if (progress != null && progress > 0f) {
            Canvas(Modifier.size(size - 6.dp).rotate(-90f)) {
                val w = 3.5.dp.toPx()
                drawArc(accent.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(w))
                drawArc(accent, 0f, 360f * p.coerceIn(0f, 1f), false, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        androidx.compose.animation.Crossfade(playing, animationSpec = tween(200), label = "playIcon") { isPlaying ->
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = description, tint = accent, modifier = Modifier.size(size * 0.48f),
            )
        }
    }
}

/** 胶囊按钮。secondary：浅底；tinted：浅青底 + 深青字 */
@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    tinted: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val x = Xyz
    val cs = MaterialTheme.colorScheme
    val bg = when { tinted -> x.accentFill; secondary -> x.fill; else -> cs.primary }
    val fg = when { tinted -> x.accentText; secondary -> x.text; else -> cs.onPrimary }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = Dims.touch)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .pressable(enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 28.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/**
 * 入口色块（官方「发现」页顶部那排）：浅色块 + 同色系描边和文字。
 */
@Composable
fun ColorChip(text: String, chroma: Chroma, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = Dims.touch)
            .clip(RoundedCornerShape(16.dp))
            .background(chroma.bg)
            .border(1.dp, chroma.border, RoundedCornerShape(16.dp))
            .pressable { onClick() }
            .padding(horizontal = 22.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = chroma.fg, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = chroma.fg, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 筛选胶囊：选中浅青底 + 深青粗字，未选中浅灰底 + 灰字；颜色切换有渐变 */
@Composable
fun Chip(text: String, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val x = Xyz
    val bg by animateColorAsState(if (selected) x.accentFill else x.fill, tween(250), label = "chipBg")
    val fg by animateColorAsState(if (selected) x.accentText else x.sub, tween(250), label = "chipFg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = Dims.touch)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pressable(role = Role.RadioButton) { onClick() }
            .padding(horizontal = 26.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

// ---------------- 标题 ----------------

/** 页面大标题（粗黑 40sp），二级页面左侧带返回 */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(bottom = 8.dp),
    ) {
        if (onBack != null) RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", plain = true, onClick = onBack)
        Text(
            title,
            style = if (onBack == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
            color = Xyz.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** 分区标题：品牌青色（官方「为你推荐」「编辑精选」） */
@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Xyz.accent, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.heightIn(min = Dims.touch).clip(RoundedCornerShape(50)).pressable { onAction() }.padding(horizontal = 20.dp),
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = Xyz.sub)
            }
        }
    }
}

// ---------------- 列表行 ----------------

/** 关键词高亮（搜索结果）：高亮词用强调色 + 粗体 */
fun highlight(text: String, words: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    if (words.isEmpty()) { append(text); return@buildAnnotatedString }
    val re = Regex(words.filter { it.isNotBlank() }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    var last = 0
    re.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** 「在听 · 剩 12 分钟」这类状态：在听用强调色，其他用灰色 */
fun episodeMeta(ep: Episode, showPodcast: Boolean, showDate: Boolean = true): Pair<String, Boolean> {
    if (ep.paid) return "付费单集 · 暂不支持播放" to false
    val listening = ep.progressSec > 0 && ep.durationSec > 0 && ep.progressSec < ep.durationSec - 30 && !ep.finished
    if (listening) return "在听 · 剩 ${Fmt.minutes(ep.durationSec - ep.progressSec)}" to true
    val done = ep.finished || (ep.durationSec > 0 && ep.progressSec >= ep.durationSec - 30)
    return listOfNotNull(
        ep.podcastTitle.takeIf { showPodcast && it.isNotEmpty() },
        Fmt.minutes(ep.durationSec).takeIf { it.isNotEmpty() },
        Fmt.date(ep.pubDate).takeIf { showDate && it.isNotEmpty() },
        "已听完".takeIf { done },
    ).joinToString(" · ") to false
}

/**
 * 单集行（官方「更新」「为你推荐」的样式）：封面 | 节目名（灰）/ 标题（两行）/ 状态 | 加入播放列表 | 播放键（带进度环）。
 */
@Composable
fun EpisodeRow(
    ep: Episode,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onEnqueue: (() -> Unit)? = null,
    showPodcast: Boolean = true,
    playing: Boolean = false,
    highlightWords: List<String> = emptyList(),
    coverSize: Dp = Dims.cover,
    modifier: Modifier = Modifier,
) {
    val x = Xyz
    com.ryan.xyzcar.ui.theme.Accented(ep.color) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dims.radius))
                .pressable(pressedScale = 0.98f) { onOpen() }
                .alpha(if (ep.paid) 0.55f else 1f)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        ) {
            Cover(ep.coverUrl, coverSize, title = ep.podcastTitle.ifEmpty { ep.title }, color = ep.color)
            Spacer(Modifier.width(Dims.gap))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    highlight(ep.title, highlightWords, x.accentText),
                    style = MaterialTheme.typography.titleMedium, color = x.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(playing, enter = fadeIn(), exit = fadeOut()) {
                        Row {
                            Icon(Icons.Filled.GraphicEq, "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    val (meta, strong) = episodeMeta(ep, showPodcast)
                    Text(
                        meta, style = MaterialTheme.typography.bodyMedium,
                        color = if (strong) x.accentText else x.sub, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onEnqueue != null && !ep.paid) {
                Spacer(Modifier.width(4.dp))
                RoundIconButton(Icons.AutoMirrored.Filled.PlaylistAdd, "加入播放列表", plain = true, tint = x.sub, onClick = onEnqueue)
            }
            Spacer(Modifier.width(4.dp))
            val ring = if (ep.durationSec > 0 && ep.progressSec > 0) ep.progressSec.toFloat() / ep.durationSec else null
            PlayButton(playing = playing, progress = ring, onClick = onPlay, enabled = !ep.paid)
        }
    }
}

@Composable
fun PodcastRow(p: Podcast, highlightWords: List<String> = emptyList(), trailing: (@Composable () -> Unit)? = null, onOpen: () -> Unit) {
    val x = Xyz
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dims.radius))
            .pressable(pressedScale = 0.98f) { onOpen() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Cover(p.coverUrl, Dims.cover, title = p.title, color = p.color)
        Spacer(Modifier.width(Dims.gap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(highlight(p.title, highlightWords, x.accentText), style = MaterialTheme.typography.titleMedium, color = x.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                p.author.takeIf { it.isNotEmpty() && it != p.title },
                p.subscriptionCount.takeIf { it > 0 }?.let { "${Fmt.count(it)} 人订阅" },
                Fmt.date(p.latestEpisodePubDate).takeIf { it.isNotEmpty() }?.let { "$it 更新" },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = x.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotEmpty()) Text(p.description, style = MaterialTheme.typography.bodySmall, color = x.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

// ---------------- 状态 ----------------

/** 大号错误提示 + 重试按钮（不用小号 Toast） */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(Dims.gap))
        BigButton("重试", tinted = true, onClick = onRetry)
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Xyz.sub)
    }
}

/** 骨架屏：加载中占位，明暗呼吸 */
@Composable
fun SkeletonRows(count: Int = 5) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    val c = Xyz.fill
    Column(verticalArrangement = Arrangement.spacedBy(Dims.gap), modifier = Modifier.alpha(a)) {
        repeat(count) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp)) {
                Box(Modifier.size(Dims.cover).clip(RoundedCornerShape(10.dp)).background(c))
                Spacer(Modifier.width(Dims.gap))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.4f).height(20.dp).clip(RoundedCornerShape(6.dp)).background(c))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(0.8f).height(26.dp).clip(RoundedCornerShape(6.dp)).background(c))
                }
                Box(Modifier.size(68.dp).clip(CircleShape).background(c))
            }
        }
    }
}

/** 列表：滚动到底自动加载更多 */
@Composable
fun AutoLoadList(
    state: LazyListState,
    loading: Boolean,
    end: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    content: LazyListScope.() -> Unit,
) {
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, loading, end) { if (nearEnd && !loading && !end) onLoadMore() }
    LazyColumn(state = state, contentPadding = contentPadding, modifier = modifier.fillMaxSize()) {
        content()
        if (loading) {
            item("__loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Xyz.accent) }
            }
        }
    }
}

/** 分隔线（官方列表行之间很淡的线） */
@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Xyz.line))

/** 小标签（「已订阅」「付费」） */
@Composable
fun Tag(text: String, bg: Color, fg: Color) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
