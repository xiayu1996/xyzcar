package com.ryan.xyzcar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density as ResDensity
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.ryan.xyzcar.data.DemoData
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.player.PlayerUi
import com.ryan.xyzcar.ui.BottomNav
import com.ryan.xyzcar.ui.NavRail
import com.ryan.xyzcar.ui.Tab
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.ColorChip
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.EpisodeRow
import com.ryan.xyzcar.ui.components.FullPlayer
import com.ryan.xyzcar.ui.components.MiniPlayer
import com.ryan.xyzcar.ui.components.PlayerActions
import com.ryan.xyzcar.ui.components.PodcastRow
import com.ryan.xyzcar.ui.components.RoundIconButton
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SectionTitle
import com.ryan.xyzcar.ui.screens.CategoryChip
import com.ryan.xyzcar.ui.screens.EpisodePager
import com.ryan.xyzcar.ui.screens.InfoRow
import com.ryan.xyzcar.ui.screens.LoginContent
import com.ryan.xyzcar.ui.screens.MenuRow
import com.ryan.xyzcar.ui.screens.QueueRow
import com.ryan.xyzcar.ui.screens.RecommendCard
import com.ryan.xyzcar.ui.screens.SearchPill
import com.ryan.xyzcar.ui.screens.ThemeCard
import com.ryan.xyzcar.ui.screens.TickSlider
import com.ryan.xyzcar.data.api.XyzApi
import com.ryan.xyzcar.data.Category
import com.ryan.xyzcar.ui.theme.Chroma
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.ThemeMode
import com.ryan.xyzcar.ui.theme.Xyz
import com.ryan.xyzcar.ui.theme.XyzCarTheme
import org.junit.Rule
import org.junit.Test

/**
 * 界面截图（Paparazzi，在电脑上渲染，不需要模拟器）：
 *   ./gradlew recordPaparazziDebug   → app/src/test/snapshots/images/
 * 用演示数据拼出主要界面，检查配色、排版、放大后是否被裁切。
 */
class UiSnapshots {
    companion object {
        /** 有真实公开内容快照（./scripts/login-check.sh dump 生成）就用快照，没有就用合成数据 */
        init {
            val f = java.io.File("src/debug/assets/demo_snapshot.json")
            if (f.exists()) DemoData.install(
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(com.ryan.xyzcar.data.DemoSnapshot.serializer(), f.readText()),
            )
        }
    }

    /** Paparazzi 不能联网，Coil 在预览模式下也只画占位：封面从 src/test/resources/covers/ 读本地文件（文件名规则见 coverName） */
    private val coverCache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
    private val localCover: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { url ->
        coverCache.getOrPut(url) {
            java.io.File("src/test/resources/covers", coverName(url)).takeIf { it.exists() }?.let {
                android.graphics.BitmapFactory.decodeFile(it.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            }?.asImageBitmap()
        }
    }

    /** 所有截图都包一层：提供本地封面 */
    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot {
        CompositionLocalProvider(com.ryan.xyzcar.ui.components.LocalCoverOverride provides localCover) { content() }
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            screenWidth = 1920, screenHeight = 1080, xdpi = 240, ydpi = 240,
            density = ResDensity.HIGH, orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        maxPercentDifference = 1.0,
    )

    private val noop = PlayerActions({}, {}, {}, {}, {}, {}, {})
    private val playing = PlayerUi(
        episode = DemoData.episodes.getOrElse(1) { DemoData.episodes.first() }, isPlaying = true, positionMs = 754_000, durationMs = 4_200_000, speed = 1.25f, connected = true,
    )

    /** 截图用的车机横屏：1920×1080 @ 240dpi = 1280×720dp（和原型画板一样大） */
    private fun config(night: Boolean, portrait: Boolean = false, widthPx: Int = 1920) = DeviceConfig.PIXEL_C.copy(
        screenWidth = if (portrait) 1080 else widthPx, screenHeight = if (portrait) 1920 else 1080, xdpi = 240, ydpi = 240,
        density = ResDensity.HIGH, softButtons = false,
        orientation = if (portrait) ScreenOrientation.PORTRAIT else ScreenOrientation.LANDSCAPE,
        nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
    )

    @Composable
    private fun Scaled(scale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale), content = content)
    }

    /** 和 App 一样的外框：左侧导航 | 内容 + 迷你播放条 */
    @Composable
    private fun Landscape(tab: Tab?, content: @Composable () -> Unit) = androidx.compose.material3.Surface(color = Xyz.bg, contentColor = Xyz.text) {
        Row(Modifier.fillMaxSize().background(Xyz.bg)) {
            NavRail(tab) {}
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 24.dp, bottom = 16.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, top = 12.dp)) { content() }
                Spacer(Modifier.height(12.dp))
                MiniPlayer(playing, 3, noop)
            }
        }
    }

    private val eps get() = DemoData.allEpisodes()

    @Composable
    private fun Discover() {
        val x = Xyz
        ScreenHeader("发现", null) { SearchPill(Modifier.width(360.dp)) {} }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ColorChip("分类", Chroma(x.accentFill, x.accentBorder, x.accentText)) {} }
            item { ColorChip("最热榜", x.peach) {} }
            item { ColorChip("锋芒榜", x.lime) {} }
            item { ColorChip("新星榜", x.lavender) {} }
            item { ColorChip("编辑精选", Chroma(x.fill, x.border, x.text)) {} }
        }
        SectionTitle("为你推荐", "换一批") {}
        EpisodePager(eps.take(9), playing.episode?.eid, true, {}, {})
    }

    @Test fun discoverLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { Landscape(Tab.Discover) { Discover() } } }
    }

    @Test fun discoverDark() {
        paparazzi.unsafeUpdateConfig(config(night = true))
        shot { XyzCarTheme(ThemeMode.Dark) { Landscape(Tab.Discover) { Discover() } } }
    }

    @Composable
    private fun Recommend() {
        val cats = listOf(Category("all", "综合")) + DemoData.categories.map { it.copy(name = it.name.substringAfter(' ')) }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            items(cats.size) { i -> CategoryChip(cats[i], i == 0) {} }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(440.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(eps.take(4).size) { i -> RecommendCard(eps[i], i == 1, {}, {}) }
        }
    }

    @Test fun recommendLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { Landscape(Tab.Recommend) { Recommend() } } }
    }

    @Test fun recommendDark() {
        paparazzi.unsafeUpdateConfig(config(night = true))
        shot { XyzCarTheme(ThemeMode.Dark) { Landscape(Tab.Recommend) { Recommend() } } }
    }

    @Composable
    private fun Updates() {
        ScreenHeader("更新", null) {
            RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true) {}
            BigButton("我的订阅", tinted = true, icon = Icons.Filled.Layers) {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Chip("未听 + 在听", selected = true) {}
            Chip("全部") {}
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(560.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            val list = eps.take(8).mapIndexed { i, e -> if (i == 0) e.copy(progressSec = e.durationSec / 3) else e }
            items(list.size) { i -> EpisodeRow(list[i], {}, {}, {}, playing = i == 1) }
        }
    }

    @Test fun updatesLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { Landscape(Tab.Subscriptions) { Updates() } } }
    }

    @Test fun updatesDarkScaled130() {
        paparazzi.unsafeUpdateConfig(config(night = true))
        shot { XyzCarTheme(ThemeMode.Dark) { Scaled(1.3f) { Landscape(Tab.Subscriptions) { Updates() } } } }
    }

    @Test fun searchResultsLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        val k = eps.first().title.take(2)
        shot { XyzCarTheme(ThemeMode.Light) { Landscape(Tab.Search) {
            ScreenHeader("搜索", null)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Chip("综合", selected = true) {}; Chip("节目") {}; Chip("单集") {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.width(440.dp)) {
                    SectionTitle("相关节目", "更多") {}
                    DemoData.podcasts.take(3).forEach { PodcastRow(it, listOf(k)) {} }
                }
                Column(Modifier.weight(1f)) {
                    SectionTitle("单集", "更多") {}
                    eps.take(4).forEachIndexed { i, e -> EpisodeRow(if (i == 3) e.copy(paid = true) else e, {}, {}, {}, highlightWords = listOf(k)) }
                }
            }
        } } }
    }

    @Composable
    private fun Me() {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.width(520.dp)) {
                Text("演示账号", style = MaterialTheme.typography.headlineLarge, color = Xyz.text)
                Text("小宇宙账号 · 已登录", style = MaterialTheme.typography.bodyMedium, color = Xyz.sub)
                Spacer(Modifier.height(16.dp))
                MenuRow(Icons.Filled.History, "收听历史") {}
                MenuRow(Icons.Filled.DirectionsCar, "车机信息", "分屏、语音排查用") {}
                InfoRow("版本", "v0.0.2")
                InfoRow("源码地址", "github.com/xiayu1996/xyzcar", link = true) {}
                InfoRow("许可协议", "PolyForm 非商用 1.0.0")
            }
            Column(Modifier.weight(1f)) {
                Text("外观", style = MaterialTheme.typography.titleLarge, color = Xyz.text, modifier = Modifier.padding(bottom = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ThemeMode.entries.forEach { m -> ThemeCard(m, m == ThemeMode.System, Modifier.weight(1f)) {} }
                }
                Text("显示大小", style = MaterialTheme.typography.titleLarge, color = Xyz.text, modifier = Modifier.padding(top = 36.dp))
                TickSlider(2, com.ryan.xyzcar.data.SIZE_LABELS) {}
            }
        }
    }

    @Test fun meLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { Landscape(Tab.Me) { Me() } } }
    }

    @Test fun playerFull() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { FullPlayer(playing, 15, noop) } }
    }

    /** 分屏 1/3：底部导航 + 窄迷你播放条 */
    @Test fun narrowSplitLight() {
        paparazzi.unsafeUpdateConfig(config(night = false, widthPx = 630))
        shot { XyzCarTheme(ThemeMode.Light) { androidx.compose.material3.Surface(color = Xyz.bg) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    ScreenHeader("发现", null) { RoundIconButton(Icons.Filled.Search, "搜索") {} }
                    SectionTitle("为你推荐")
                    eps.take(4).forEach { EpisodeRow(it, {}, {}) }
                }
                MiniPlayer(playing, 3, noop, Modifier.padding(horizontal = 12.dp))
                BottomNav(Tab.Discover) {}
            }
        } } }
    }

    private val fakeQr = XyzApi.LoginQr("demo", "https://h5.xiaoyuzhoufm.com/oauth?qrcode_id=000000000000000000000000")

    @Test fun loginLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(fakeQr, XyzApi.QrState.WAITING, null, {}, {})
        } } }
    }

    @Test fun loginDarkScannedPortrait() {
        paparazzi.unsafeUpdateConfig(config(night = true, portrait = true))
        shot { XyzCarTheme(ThemeMode.Dark) { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(fakeQr, XyzApi.QrState.SCANNED, null, {}, null)
        } } }
    }

    @Composable
    private fun Queue() {
        val q = DemoData.episodes.take(4)
        ScreenHeader("播放列表", {}) { BigButton("清空", secondary = true) {} }
        LazyColumn {
            items(q.size) { i -> QueueRow(q[i], i == 0, i == q.lastIndex, {}, {}, {}, {}, {}) }
        }
    }

    @Test fun queueLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { Landscape(null) { Queue() } } }
    }

    /** 放大到特大时排序按钮换到第二行，标题不被挤 */
    @Test fun queueLightScaled130() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme(ThemeMode.Light) { Scaled(1.3f) { Landscape(null) { Queue() } } } }
    }
}
