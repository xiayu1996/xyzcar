package com.ryan.xyzcar.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ryan.xyzcar.data.Category
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.ErrorBox
import com.ryan.xyzcar.ui.components.SkeletonRows
import com.ryan.xyzcar.ui.components.card
import com.ryan.xyzcar.ui.components.pressable
import com.ryan.xyzcar.ui.errorText
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz
import com.ryan.xyzcar.ui.theme.tintOf
import kotlinx.coroutines.launch

/** 「综合」不是接口里的分类，用发现页的「为你推荐」 */
private val ALL = Category("__all", "综合")

/** 分类下的两个子列表（2026-11 实测接口的 tab） */
private val SUB_TABS = listOf("RECOMMEND" to "为你推荐", "HOT" to "近期热门")

private class RecommendState {
    var selected by mutableStateOf(ALL)
    var subTab by mutableStateOf("RECOMMEND")
    var extra by mutableStateOf<List<Episode>>(emptyList())
    var refreshing by mutableStateOf(false)
}

/**
 * 推荐页（官方「推荐」）：顶部分类胶囊 + 「全部分类」面板，下面是按节目封面取色的单集卡片。
 * 切分类时卡片区淡入 + 轻微上移，面板展开 / 收起有高度动画。
 */
@Composable
fun RecommendScreen(vm: AppViewModel) {
    val cats = vm.loadHolder("tab:Recommend:cats") { _ -> vm.repo.categories() }
    val st = vm.holder("tab:Recommend:state") { RecommendState() }
    LaunchedEffect(Unit) { cats.ensureLoaded() }
    val all = listOf(ALL) + cats.data.orEmpty()
    val x = Xyz

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            val chipState = rememberLazyListState()
            LazyRow(state = chipState, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(all, key = { it.id }) { c ->
                    CategoryChip(c, c.id == st.selected.id) { st.selected = c; st.subTab = "RECOMMEND"; vm.categoriesOpen = false }
                }
            }
            LaunchedEffect(st.selected.id, all.size) {
                val i = all.indexOfFirst { it.id == st.selected.id }
                if (i >= 0) chipState.animateScrollToItem(maxOf(0, i - 1))
            }
            Spacer(Modifier.width(12.dp))
            val arrow by animateFloatAsState(if (vm.categoriesOpen) 180f else 0f, tween(300), label = "arrow")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = Dims.touch)
                    .clip(RoundedCornerShape(18.dp))
                    .background(x.fill)
                    .pressable { vm.categoriesOpen = !vm.categoriesOpen }
                    .padding(horizontal = 20.dp),
            ) {
                Text("全部分类", style = MaterialTheme.typography.labelLarge, color = x.text)
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = x.text, modifier = Modifier.size(30.dp).rotate(arrow))
            }
        }

        AnimatedVisibility(
            vm.categoriesOpen,
            enter = expandVertically(tween(380, easing = FastOutSlowInEasing)) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(320, easing = FastOutSlowInEasing)) + fadeOut(tween(200)),
        ) {
            CategoryPanel(all, st.selected) { c -> st.selected = c; st.subTab = "RECOMMEND"; vm.categoriesOpen = false }
        }

        AnimatedContent(
            st.selected.id to st.subTab,
            transitionSpec = { (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 16 }) togetherWith fadeOut(tween(160)) },
            label = "recommendBody",
            modifier = Modifier.weight(1f),
        ) { (id, sub) ->
            if (id == ALL.id) AllBody(vm, st) else CategoryBody(vm, st, id, sub)
        }
    }
}

/** 分类胶囊：图标（接口给的分类图）+ 名称；选中时青色粗描边 + 浅青底 */
@Composable
internal fun CategoryChip(c: Category, selected: Boolean, onClick: () -> Unit) {
    val x = Xyz
    val border by animateColorAsState(if (selected) x.accent else x.border, tween(250), label = "catBorder")
    val bg by animateColorAsState(if (selected) x.accentFill else x.card, tween(250), label = "catBg")
    val w by animateDpAsState(if (selected) 3.dp else 1.dp, tween(250), label = "catW")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = Dims.touch)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(w, border, RoundedCornerShape(18.dp))
            .pressable(pressedScale = 0.94f) { onClick() }
            .padding(horizontal = 18.dp),
    ) {
        CategoryIcon(c, 30.dp)
        Spacer(Modifier.width(8.dp))
        Text(c.name, style = MaterialTheme.typography.labelLarge, color = if (selected) x.accentText else x.text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun CategoryIcon(c: Category, size: androidx.compose.ui.unit.Dp) {
    if (c.iconUrl.isNotEmpty()) {
        AsyncImage(c.iconUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    } else {
        Icon(Icons.Filled.AutoAwesome, null, tint = Xyz.accent, modifier = Modifier.size(size))
    }
}

/** 全部分类面板：网格排开，选中项略微倾斜（官方样式），点了选中并收起 */
@Composable
internal fun CategoryPanel(all: List<Category>, selected: Category, onPick: (Category) -> Unit) {
    val x = Xyz
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        val cols = (maxWidth / 150.dp).toInt().coerceIn(3, 8)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            all.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { c ->
                        val sel = c.id == selected.id
                        val rot by animateFloatAsState(if (sel) -3f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "tilt")
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .rotate(rot)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (sel) x.accentFill else x.fill)
                                .border(if (sel) 3.dp else 0.dp, if (sel) x.accent else Color.Transparent, RoundedCornerShape(18.dp))
                                .pressable(pressedScale = 0.94f) { onPick(c) }
                                .padding(vertical = 14.dp),
                        ) {
                            Text(c.name, style = MaterialTheme.typography.labelLarge, color = if (sel) x.accentText else x.text, maxLines = 1)
                            Spacer(Modifier.height(6.dp))
                            CategoryIcon(c, 40.dp)
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 综合：为你推荐 + 点「换一批」追加的推荐 */
@Composable
private fun AllBody(vm: AppViewModel, st: RecommendState) {
    val h = vm.loadHolder("tab:Recommend:all") { force -> vm.repo.recommend(force) }
    LaunchedEffect(Unit) { h.ensureLoaded() }
    val list = (st.extra + h.data.orEmpty()).distinctBy { it.eid }
    CardsBody(vm, list, h.loading && h.data == null, h.error.takeIf { h.data == null }, { h.load(true) }) {
        BigButton(if (st.refreshing) "加载中…" else "换一批", tinted = true, icon = Icons.Filled.Refresh, enabled = !st.refreshing) {
            st.refreshing = true
            vm.viewModelScope.launch {
                runCatching { vm.repo.refreshRecommend() }
                    .onSuccess { st.extra = (it + st.extra).distinctBy { e -> e.eid } }
                    .onFailure { vm.player.messages.value = errorText(it) }
                st.refreshing = false
            }
        }
    }
}

@Composable
private fun CategoryBody(vm: AppViewModel, st: RecommendState, id: String, sub: String) {
    val h = vm.loadHolder("tab:Recommend:cat:$id:$sub") { force -> vm.repo.categoryEpisodes(id, sub, force) }
    LaunchedEffect(id, sub) { h.ensureLoaded() }
    CardsBody(vm, h.data.orEmpty(), h.loading && h.data == null, h.error.takeIf { h.data == null }, { h.load(true) }, header = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SUB_TABS.forEach { (k, label) -> Chip(label, selected = sub == k) { st.subTab = k } }
        }
    })
}

/** 卡片网格；header 在最上面整行，footer 在最下面整行 */
@Composable
private fun CardsBody(
    vm: AppViewModel,
    list: List<Episode>,
    loading: Boolean,
    error: String?,
    retry: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val ps by vm.player.state.collectAsState()
    val actions = EpisodeActions(vm)
    when {
        loading -> SkeletonRows()
        error != null -> ErrorBox(error, retry)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 440.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp, start = 2.dp, end = 2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyBox("暂时没有推荐内容") }
            items(list, key = { it.eid }) { ep ->
                RecommendCard(ep, ps.isPlaying && ps.episode?.eid == ep.eid, { actions.open(ep) }, { actions.play(ep) }, Modifier.animateItem())
            }
            if (footer != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { footer() } }
        }
    }
}

/**
 * 推荐卡片：节目色浅底，左上标题（粗体两行）+ 简介，右侧封面，底部一栏节目名 / 收听数 / 带时长的播放胶囊。
 */
@Composable
internal fun RecommendCard(ep: Episode, playing: Boolean, onOpen: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val x = Xyz
    val bg = tintOf(ep.color, x)
    Column(
        modifier
            .fillMaxWidth()
            .card(RoundedCornerShape(24.dp), bg, x.shadow, null, 10.dp)
            .pressable(pressedScale = 0.98f) { onOpen() }
            .padding(18.dp),
    ) {
        Row {
            Column(Modifier.weight(1f).heightIn(min = 112.dp)) {
                Text(ep.title, style = MaterialTheme.typography.titleLarge, color = x.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val desc = ep.description.replace(Regex("""\[[^\]]*\.(png|jpe?g|gif|webp)]""", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
                if (desc.isNotEmpty()) Text(desc, style = MaterialTheme.typography.bodyMedium, color = x.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(18.dp))
            Cover(ep.coverUrl, 112.dp, title = ep.podcastTitle, color = ep.color, radius = 16.dp)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ep.podcastTitle, style = MaterialTheme.typography.bodyMedium, color = x.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (ep.playCount > 0) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Outlined.Headphones, null, tint = x.sub, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
                Text(Fmt.count(ep.playCount), style = MaterialTheme.typography.bodyMedium, color = x.sub, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = Dims.touch)
                    .clip(RoundedCornerShape(50))
                    .background(x.card.copy(alpha = if (x.dark) 0.6f else 0.85f))
                    .pressable(!ep.paid, pressedScale = 0.92f) { onPlay() }
                    .padding(horizontal = 20.dp),
            ) {
                AnimatedContent(playing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cardPlay") { p ->
                    Icon(if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p) "暂停" else "播放", tint = x.accent, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (ep.paid) "付费" else Fmt.minutes(ep.durationSec).ifEmpty { "播放" }, style = MaterialTheme.typography.labelLarge, color = x.text)
            }
        }
    }
}
