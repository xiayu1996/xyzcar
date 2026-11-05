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
import com.ryan.xyzcar.ui.NavBar
import com.ryan.xyzcar.ui.NavRail
import com.ryan.xyzcar.ui.Tab
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.Cover
import com.ryan.xyzcar.ui.components.EpisodeRow
import com.ryan.xyzcar.ui.components.PlayerActions
import com.ryan.xyzcar.ui.components.PlayerPanelTall
import com.ryan.xyzcar.ui.components.PlayerPanelWide
import com.ryan.xyzcar.ui.components.PodcastRow
import com.ryan.xyzcar.ui.components.RoundIconButton
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SectionTitle
import com.ryan.xyzcar.ui.screens.LoginContent
import com.ryan.xyzcar.ui.screens.PodcastCards
import com.ryan.xyzcar.ui.screens.QueueRow
import com.ryan.xyzcar.data.api.XyzApi
import com.ryan.xyzcar.ui.theme.Accented
import com.ryan.xyzcar.ui.theme.Dims
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

    private fun config(night: Boolean, portrait: Boolean = false) = DeviceConfig.PIXEL_C.copy(
        screenWidth = if (portrait) 1080 else 1920, screenHeight = if (portrait) 1920 else 1080, xdpi = 240, ydpi = 240,
        density = ResDensity.HIGH, softButtons = false,
        orientation = if (portrait) ScreenOrientation.PORTRAIT else ScreenOrientation.LANDSCAPE,
        nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
    )

    @Composable
    private fun Scaled(scale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale), content = content)
    }

    @Composable
    private fun Landscape(tab: Tab?, content: @Composable () -> Unit) = androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Row(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayerPanelTall(playing, 3, noop, Modifier.weight(0.4f))
            NavRail(tab, false, {}, {})
            Box(Modifier.weight(0.6f).fillMaxHeight()) { Column { content() } }
        }
    }

    @Composable
    private fun Subscriptions() {
        ScreenHeader("我的订阅", null) { RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true) {} }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(DemoData.podcasts) { PodcastRow(it) {} }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Discover() {
        ScreenHeader("发现", null) { RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true) {} }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("最热榜", "锋芒榜", "新星榜", "分类", "编辑精选").forEach { Chip(it) {} }
                }
            }
            DemoData.sections.forEachIndexed { i, sec ->
                item("t$i") { SectionTitle(sec.title) }
                if (sec.podcasts.isNotEmpty()) item("p$i") { PodcastCards(sec.podcasts) {} }
                items(sec.episodes.take(3), key = { "e$i-${it.eid}" }) { e -> EpisodeRow(e, {}, {}, {}, playing = e.eid == playing.episode?.eid) }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun EpisodeDetail(ep: Episode) {
        Accented(ep.color) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ScreenHeader("单集详情", {})
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(ep.coverUrl, 168.dp, title = ep.podcastTitle, color = ep.color)
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ep.title, style = MaterialTheme.typography.titleLarge, maxLines = 3)
                        Spacer(Modifier.height(8.dp))
                        Text(ep.podcastTitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text("1 小时 10 分 · 11月3日", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dims.gap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("继续播放") {}
                    BigButton("加入队列", secondary = true) {}
                    BigButton("查看节目", secondary = true) {}
                }
                Text("节目简介", style = MaterialTheme.typography.titleLarge)
                Text(
                    buildAnnotatedString {
                        listOf("00:30" to "开场", "12:05" to "正题：城市与记忆", "1:02:03" to "结尾").forEach { (t, s) ->
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append(t) }
                            append(" $s\n")
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }

    @Test fun subscriptionsLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme { Landscape(Tab.Subscriptions) { Subscriptions() } } }
    }

    @Test fun discoverDark() {
        paparazzi.unsafeUpdateConfig(config(night = true))
        shot { XyzCarTheme { Landscape(Tab.Discover) { Discover() } } }
    }

    @Test fun episodeDetailLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme { Landscape(null) { EpisodeDetail(DemoData.episodes.getOrElse(1) { DemoData.episodes.first() }) } } }
    }

    @Test fun episodeDetailDarkScaled130() {
        paparazzi.unsafeUpdateConfig(config(night = true))
        shot { XyzCarTheme { Scaled(1.3f) { Landscape(null) { EpisodeDetail(DemoData.episodes.getOrElse(1) { DemoData.episodes.first() }) } } } }
    }

    @Test fun portraitLightScaled130() {
        paparazzi.unsafeUpdateConfig(config(night = false, portrait = true))
        shot {
            XyzCarTheme {
                Scaled(1.3f) { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f).fillMaxWidth()) { Discover() }
                        NavBar(Tab.Discover, false, {}, {})
                        PlayerPanelWide(playing, 3, noop)
                    }
                } }
            }
        }
    }

    private val fakeQr = XyzApi.LoginQr("demo", "https://h5.xiaoyuzhoufm.com/oauth?qrcode_id=000000000000000000000000")

    @Test fun loginLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(fakeQr, XyzApi.QrState.WAITING, null, {}, {})
        } } }
    }

    @Test fun loginDarkScannedPortrait() {
        paparazzi.unsafeUpdateConfig(config(night = true, portrait = true))
        shot { XyzCarTheme { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
            LoginContent(fakeQr, XyzApi.QrState.SCANNED, null, {}, null)
        } } }
    }

    @Test fun topListLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        val eps = DemoData.topLists["HOT_EPISODES_IN_24_HOURS"] ?: DemoData.episodes
        shot { XyzCarTheme { Landscape(Tab.Discover) {
            ScreenHeader("最热榜", {})
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(eps) { e -> EpisodeRow(e, {}, {}, {}, playing = false) }
            }
        } } }
    }

    @Test fun historyDark() {
        paparazzi.unsafeUpdateConfig(config(night = true))
        shot { XyzCarTheme { Landscape(Tab.History) {
            ScreenHeader("收听历史", null) { RoundIconButton(Icons.Filled.Refresh, "刷新", plain = true) {} }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(DemoData.episodes + DemoData.editorPicks.drop(6)) { e -> EpisodeRow(e, {}, {}, {}, playing = e.eid == playing.episode?.eid) }
            }
        } } }
    }

    @Test fun podcastEpisodesLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        val pod = DemoData.podcasts.first()
        val eps = DemoData.podcastEpisodes[pod.pid] ?: DemoData.episodes
        shot { XyzCarTheme { Landscape(null) { Accented(pod.color) {
            ScreenHeader(pod.title, {})
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(pod.coverUrl, 140.dp, title = pod.title, color = pod.color)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(pod.title, style = MaterialTheme.typography.titleLarge)
                    Text(pod.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(pod.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) { items(eps) { e -> EpisodeRow(e, {}, {}, {}, playing = false) } }
        } } } }
    }

    @Composable
    private fun Queue() {
        val q = DemoData.episodes.take(4)
        ScreenHeader("播放队列", {}) { BigButton("清空", secondary = true) {} }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Dims.gap)) {
            items(q.size) { i -> QueueRow(q[i], i == 0, i == q.lastIndex, {}, {}, {}, {}, {}) }
        }
    }

    @Test fun queueLight() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme { Landscape(null) { Queue() } } }
    }

    /** Test Lab 发现：130% 时标题被 4 个按钮挤成每行几个字 */
    @Test fun queueLightScaled130() {
        paparazzi.unsafeUpdateConfig(config(night = false))
        shot { XyzCarTheme { Scaled(1.3f) { Landscape(null) { Queue() } } } }
    }
}
