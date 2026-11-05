package com.ryan.xyzcar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.data.ThemeColor
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.parseHex

/** 仅截图测试用：不联网时按 URL 直接提供封面图。App 里始终为 null，走 Coil 正常加载 */
val LocalCoverOverride = androidx.compose.runtime.staticCompositionLocalOf<((String) -> androidx.compose.ui.graphics.ImageBitmap?)?> { null }

/**
 * 封面：小圆角（约边长 8%）+ 柔和投影。
 * 没有图片时用节目主题色铺底，显示标题首字。
 */
@Composable
fun Cover(url: String, size: Dp, modifier: Modifier = Modifier, title: String = "", color: ThemeColor? = null) {
    val shape = RoundedCornerShape((size.value * 0.08f).coerceAtLeast(6f).dp)
    val darkBg = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tint = color?.original?.let(::parseHex) ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .shadow(if (darkBg) 0.dp else (size.value / 16f).coerceIn(2f, 12f).dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(tint),
    ) {
        if (url.isEmpty() && title.isNotEmpty()) {
            Text(
                title.take(1),
                color = if (tint.luminance() > 0.5f) Color(0xCC000000) else Color.White.copy(alpha = 0.92f),
                fontSize = (size.value * 0.42f).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
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

/**
 * 圆形图标按钮，触控区域不小于 76dp。
 * primary：强调色实心（播放）；plain：无底色（播放面板的后退 / 前进）；默认：浅填充底。
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
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = when {
        primary -> cs.primary
        plain -> Color.Transparent
        tinted -> cs.primaryContainer
        else -> cs.surfaceVariant
    }
    val fg = when {
        primary -> cs.onPrimary
        tinted -> cs.primary
        else -> cs.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        contentColor = if (enabled) fg else fg.copy(alpha = 0.35f),
        modifier = Modifier.size(size.coerceAtLeast(Dims.touch)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(size * if (plain) 0.62f else 0.5f))
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = Dims.touch).padding(bottom = 8.dp),
    ) {
        if (onBack != null) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", plain = true, onClick = onBack)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** 列表行：去掉卡片底，靠留白和细分隔线区分；正在播放的行用强调色浅底 */
@Composable
private fun ListRow(onClick: () -> Unit, highlighted: Boolean = false, content: @Composable () -> Unit) {
    Column {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(Dims.radius),
            color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) { content() }
        }
        HorizontalDivider(Modifier.padding(start = Dims.cover + 28.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun EpisodeRow(
    ep: Episode,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onEnqueue: (() -> Unit)? = null,
    showPodcast: Boolean = true,
    playing: Boolean = false,
) {
    com.ryan.xyzcar.ui.theme.Accented(ep.color) {
        ListRow(onOpen, highlighted = playing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(ep.coverUrl, Dims.cover, title = ep.podcastTitle.ifEmpty { ep.title }, color = ep.color)
                Spacer(Modifier.width(Dims.gap))
                Column(Modifier.weight(1f)) {
                    Text(ep.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (playing) {
                            Icon(Icons.Filled.GraphicEq, "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        val sub = listOfNotNull(
                            ep.podcastTitle.takeIf { showPodcast && it.isNotEmpty() },
                            Fmt.date(ep.pubDate).takeIf { !showPodcast && it.isNotEmpty() },
                            Fmt.remaining(ep.durationSec, ep.progressSec).takeIf { it.isNotEmpty() },
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(
                                sub, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (ep.progressSec > 0 && ep.durationSec > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (ep.progressSec.toFloat() / ep.durationSec).coerceIn(0f, 1f) },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth(0.5f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
                if (onEnqueue != null) {
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(Icons.AutoMirrored.Filled.PlaylistAdd, "加入队列", plain = true, onClick = onEnqueue)
                }
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Filled.PlayArrow, "播放", tinted = true, onClick = onPlay)
            }
        }
    }
}

@Composable
fun PodcastRow(p: Podcast, onOpen: () -> Unit) {
    com.ryan.xyzcar.ui.theme.Accented(p.color) {
        ListRow(onOpen) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = Dims.touch)) {
                Cover(p.coverUrl, Dims.cover, title = p.title, color = p.color)
                Spacer(Modifier.width(Dims.gap))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.starred) {
                            Icon(Icons.Filled.Star, "星标", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    val sub = listOfNotNull(
                        p.author.takeIf { it.isNotEmpty() && it != p.title },
                        Fmt.date(p.latestEpisodePubDate).takeIf { it.isNotEmpty() }?.let { "更新于 $it" },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 大号错误提示 + 重试按钮（不用小号 Toast） */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(Dims.gap))
        BigButton("重试", onClick = onRetry)
    }
}

/** 胶囊按钮。secondary：浅填充底 + 墨色字（次要操作，如「已订阅」） */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (secondary) cs.surfaceVariant else cs.primary,
        contentColor = (if (secondary) cs.onSurface else cs.onPrimary).let { if (enabled) it else it.copy(alpha = 0.4f) },
        modifier = modifier.heightIn(min = Dims.touch),
    ) {
        Box(Modifier.padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 骨架屏：加载中占位 */
@Composable
fun SkeletonRows(count: Int = 5) {
    Column(verticalArrangement = Arrangement.spacedBy(Dims.gap)) {
        repeat(count) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Box(Modifier.size(Dims.cover).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.width(Dims.gap))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(26.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(0.4f).height(20.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                }
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
    LazyColumn(
        state = state,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        content()
        if (loading) {
            item("__loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = Dims.touch),
            ) {
                Box(Modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text(action, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 胶囊（分类、搜索建议、缩放档位）：未选中浅填充，选中墨色实心；高度 76dp */
@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) cs.secondary else cs.surfaceVariant,
        contentColor = if (selected) cs.onSecondary else cs.onSurface,
        modifier = Modifier.heightIn(min = Dims.touch),
    ) {
        Box(Modifier.padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
