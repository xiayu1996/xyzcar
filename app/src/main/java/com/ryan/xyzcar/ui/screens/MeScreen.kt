package com.ryan.xyzcar.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ryan.xyzcar.data.Updater
import com.ryan.xyzcar.ui.UpdateState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ryan.xyzcar.BuildConfigInfo
import com.ryan.xyzcar.data.AppProbe
import com.ryan.xyzcar.data.SIZE_LABELS
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.Hairline
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.card
import com.ryan.xyzcar.ui.components.pressable
import com.ryan.xyzcar.ui.theme.DarkColors
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.LightColors
import com.ryan.xyzcar.ui.theme.ThemeMode
import com.ryan.xyzcar.ui.theme.Xyz
import com.ryan.xyzcar.ui.theme.XyzColors
import kotlinx.coroutines.delay

const val REPO_URL = "https://github.com/xiayu1996/xyzcar"
const val LICENSE_NAME = "PolyForm 非商用 1.0.0"
const val DISCLAIMER = "非官方第三方客户端，仅供个人学习交流，与小宇宙官方无关，禁止商业用途。节目内容版权归原作者及平台所有。"

/**
 * 我的（官方「个人」）：左栏昵称 + 头像、收听历史 / 播放列表 / 车机信息 / 退出登录、关于；
 * 右栏外观（浅色 / 深色 / 跟随系统）和显示大小。窄屏时上下排列。
 */
@Composable
fun MeScreen(vm: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= Dims.wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), modifier = Modifier.fillMaxSize()) {
                Column(Modifier.width(520.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { AccountColumn(vm) }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { SettingsColumn(vm) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AccountColumn(vm)
                Spacer(Modifier.height(24.dp))
                SettingsColumn(vm)
            }
        }
    }
}

@Composable
private fun AccountColumn(vm: AppViewModel) {
    val x = Xyz
    val profile = vm.loadHolder("tab:Me:profile") { _ -> vm.repo.profile() }
    LaunchedEffect(Unit) { profile.ensureLoaded() }
    val user = profile.data
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp)) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(user?.nickname, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "nick") { n ->
                Text(n?.ifEmpty { null } ?: "小宇宙账号", style = MaterialTheme.typography.headlineLarge, color = x.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("小宇宙账号 · 已登录", style = MaterialTheme.typography.bodyMedium, color = x.sub)
        }
        Spacer(Modifier.width(16.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(108.dp).card(CircleShape, x.fill, x.shadow, null, 10.dp),
        ) {
            if (user?.avatarUrl?.isNotEmpty() == true) {
                AsyncImage(user.avatarUrl, "头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Person, null, tint = x.sub, modifier = Modifier.size(56.dp))
            }
        }
    }
    MenuRow(Icons.Filled.History, "收听历史") { vm.push(Route.History) }
    MenuRow(Icons.AutoMirrored.Filled.QueueMusic, "播放列表") { vm.push(Route.Queue) }
    MenuRow(Icons.Filled.DirectionsCar, "车机信息", "分屏、语音排查用") { vm.push(Route.Diagnostics) }
    UpdateRow(vm)
    // 退出登录要点两次，避免在车上误触
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3_000); armed = false } }
    MenuRow(Icons.AutoMirrored.Filled.Logout, if (armed) "再点一次确认退出" else "退出登录", danger = armed) {
        if (armed) vm.logout() else armed = true
    }
    Spacer(Modifier.height(16.dp))
    Hairline()
    Spacer(Modifier.height(8.dp))
    val uri = LocalUriHandler.current
    InfoRow("版本", BuildConfigInfo.VERSION)
    InfoRow("源码地址", REPO_URL.removePrefix("https://"), link = true) { runCatching { uri.openUri(REPO_URL) } }
    InfoRow("许可协议", LICENSE_NAME)
    Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = x.sub, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
}

/** 检查更新 → 下载（带进度）→ 安装；首次安装先去系统设置允许本应用安装应用 */
@Composable
private fun UpdateRow(vm: AppViewModel) {
    val x = Xyz
    val ctx = LocalContext.current
    var canInstall by remember { mutableStateOf(Updater.canInstall(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = Updater.canInstall(ctx) }
    val s = vm.update
    val title = when (s) {
        UpdateState.Idle -> "检查更新"
        UpdateState.Checking -> "正在检查更新…"
        UpdateState.Latest -> "已是最新版本"
        is UpdateState.Available -> "发现新版本 ${s.release.tag}，点击下载"
        is UpdateState.Downloading -> "正在下载 ${s.release.tag}"
        is UpdateState.Ready -> if (canInstall) "安装 ${s.release.tag}" else "允许安装后即可更新"
        is UpdateState.Failed -> if (s.release != null) "下载失败，点击重试" else "检查失败，点击重试"
    }
    val hint = when (s) {
        is UpdateState.Downloading -> "${(s.progress * 100).toInt()}%"
        is UpdateState.Ready -> if (canInstall) "下载完成，已校验" else "点击打开设置，允许本应用安装应用，再返回点安装"
        is UpdateState.Failed -> s.message
        else -> "当前 ${BuildConfigInfo.VERSION}"
    }
    MenuRow(Icons.Filled.SystemUpdate, title, hint, danger = s is UpdateState.Failed) {
        when (s) {
            UpdateState.Idle, UpdateState.Latest -> vm.checkUpdate(silent = false)
            is UpdateState.Failed -> if (s.release != null) vm.downloadUpdate() else vm.checkUpdate(silent = false)
            is UpdateState.Available -> vm.downloadUpdate()
            is UpdateState.Ready -> runCatching {
                ctx.startActivity(if (canInstall) Updater.installIntent(ctx, s.file) else Updater.permissionIntent(ctx))
            }
            else -> Unit
        }
    }
    val progress by animateFloatAsState((s as? UpdateState.Downloading)?.progress ?: 1f, tween(300), label = "updateProgress")
    AnimatedVisibility(s is UpdateState.Downloading, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        LinearProgressIndicator(
            progress = { progress },
            color = x.accent,
            trackColor = x.accentFill,
            modifier = Modifier.fillMaxWidth().padding(start = 54.dp, end = 8.dp, bottom = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
    val notes = when (s) {
        is UpdateState.Available -> s.release.notes
        is UpdateState.Ready -> s.release.notes
        else -> ""
    }
    AnimatedVisibility(notes.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Text(notes, style = MaterialTheme.typography.bodySmall, color = x.sub, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 54.dp, end = 8.dp, bottom = 12.dp))
    }
}

@Composable
internal fun MenuRow(icon: ImageVector, title: String, hint: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val x = Xyz
    val fg by animateColorAsState(if (danger) MaterialTheme.colorScheme.error else x.text, tween(200), label = "menuFg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(Dims.radius)).pressable(pressedScale = 0.98f) { onClick() }.padding(horizontal = 4.dp),
    ) {
        Icon(icon, null, tint = if (danger) fg else x.accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "menuTitle") { t ->
                Text(t, style = MaterialTheme.typography.titleMedium, color = fg)
            }
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = x.sub)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = x.sub, modifier = Modifier.size(30.dp))
    }
}

@Composable
internal fun InfoRow(label: String, value: String, link: Boolean = false, onClick: (() -> Unit)? = null) {
    val x = Xyz
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.98f) { onClick() } else Modifier)
            .padding(horizontal = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = x.sub, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (link) x.accentText else x.text, fontWeight = if (link) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsColumn(vm: AppViewModel) {
    val x = Xyz
    Text("外观", style = MaterialTheme.typography.titleLarge, color = x.text, modifier = Modifier.padding(top = 16.dp, bottom = 14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEach { m ->
            ThemeCard(m, vm.themeMode == m, Modifier.weight(1f)) { vm.updateTheme(m) }
        }
    }
    Text("显示大小", style = MaterialTheme.typography.titleLarge, color = x.text, modifier = Modifier.padding(top = 36.dp, bottom = 6.dp))
    Text("调整内容区的文字和封面大小，左侧导航和播放条保持不变", style = MaterialTheme.typography.bodyMedium, color = x.sub)
    TickSlider(vm.sizeLevel, SIZE_LABELS, vm::updateSize)
    Text(
        "示例：vol.275 桃酥辣条干脆面",
        style = MaterialTheme.typography.titleMedium, color = x.text,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(x.fill).padding(20.dp),
    )
    CarLabSection()
    Spacer(Modifier.height(24.dp))
}

/** 外观预览卡：迷你界面示意 + 单选圈；「跟随系统」左浅右深 */
@Composable
internal fun ThemeCard(mode: ThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val x = Xyz
    val border by animateColorAsState(if (selected) x.accent else x.border, tween(250), label = "themeBorder")
    val w by animateDpAsState(if (selected) 3.dp else 1.dp, tween(250), label = "themeW")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(w, border, RoundedCornerShape(20.dp))
            .semantics { contentDescription = mode.label + if (selected) "，已选中" else "" }
            .pressable(pressedScale = 0.96f) { onClick() }
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp))) {
            when (mode) {
                ThemeMode.Light -> Preview(LightColors, Modifier.weight(1f))
                ThemeMode.Dark -> Preview(DarkColors, Modifier.weight(1f))
                ThemeMode.System -> { Preview(LightColors, Modifier.weight(1f)); Preview(DarkColors, Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (selected) x.accent else x.border, CircleShape),
            ) {
                val dot by animateDpAsState(if (selected) 14.dp else 0.dp, spring(Spring.DampingRatioMediumBouncy), label = "dot")
                Box(Modifier.size(dot).clip(CircleShape).background(x.accent))
            }
            Spacer(Modifier.width(10.dp))
            Text(mode.label, style = MaterialTheme.typography.labelLarge, color = x.text)
        }
    }
}

/** 预览卡里的迷你界面：标题条 + 两行列表 */
@Composable
private fun Preview(c: XyzColors, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(c.bg).border(1.dp, c.line).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(c.text))
        repeat(2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(c.accent.copy(alpha = 0.7f)))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(3.dp)).background(c.sub.copy(alpha = 0.5f)))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(16.dp).clip(CircleShape).background(c.accentFill))
            }
        }
    }
}

/**
 * 刻度滑杆（iOS 风格）：5 个刻度，点刻度或拖动都会吸附到最近一档，滑块位置弹簧动画。
 */
@Composable
internal fun TickSlider(value: Int, labels: List<String>, onChange: (Int) -> Unit) {
    val x = Xyz
    val n = labels.size
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val pad = 28.dp
        val track = maxWidth - pad * 2
        fun indexAt(px: Float, widthPx: Float, padPx: Float) = (((px - padPx) / (widthPx - padPx * 2)) * (n - 1)).let { Math.round(it) }.coerceIn(0, n - 1)
        val thumbX by animateDpAsState(pad + track * (value.toFloat() / (n - 1)), spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), label = "thumb")
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Dims.touch)
                    .semantics { contentDescription = "显示大小：${labels[value]}" }
                    .pointerInput(n) {
                        val padPx = pad.toPx()
                        detectTapGestures { o -> onChange(indexAt(o.x, size.width.toFloat(), padPx)) }
                    }
                    .pointerInput(n) {
                        val padPx = pad.toPx()
                        detectHorizontalDragGestures { change, _ -> onChange(indexAt(change.position.x, size.width.toFloat(), padPx)) }
                    },
            ) {
                Box(Modifier.align(Alignment.CenterStart).padding(start = pad).width(track).height(6.dp).clip(RoundedCornerShape(3.dp)).background(x.fill))
                Box(Modifier.align(Alignment.CenterStart).padding(start = pad).width((thumbX - pad).coerceAtLeast(0.dp)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(x.accent))
                repeat(n) { i ->
                    Box(
                        Modifier.align(Alignment.CenterStart).offset(x = pad + track * (i.toFloat() / (n - 1)) - 2.dp)
                            .size(4.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= value) x.accent else x.border),
                    )
                }
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = thumbX - 18.dp).size(36.dp)
                        .card(CircleShape, Color.White, Color(0x33000000), x.border, 6.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(32.dp)) {
                labels.forEachIndexed { i, l ->
                    Text(
                        l, fontSize = (15 + i * 2).sp,
                        color = if (i == value) x.accentText else x.sub, fontWeight = if (i == value) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.align(Alignment.CenterStart).offset(x = pad + track * (i.toFloat() / (n - 1)) - 24.dp).width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------- 车机信息（诊断） ----------------

private fun sysProp(key: String): String = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
}.getOrDefault("")

/** getprop 里和比亚迪车机、分屏、版本相关的行（只读） */
private fun relatedProps(): List<Pair<String, String>> = runCatching {
    val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
    // 边读边等（输出较多时不读会把管道塞满），最多 2 秒：某些车机系统策略下 getprop 可能卡住
    val out = StringBuilder()
    val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }.apply { isDaemon = true; start() }
    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
    reader.join(500)
    val text = synchronized(out) { out.toString() }
    val re = Regex("""^\[(.+?)]: \[(.*)]$""")
    val keep = Regex("byd|dilink|di[0-9]|baios|split|freeform|multiwin|white|platformi|ui_version|ro\\.build\\.display|version\\.incremental|voice|assistant", RegexOption.IGNORE_CASE)
    text.lines().mapNotNull { re.find(it.trim())?.destructured?.let { (k, v) -> k to v } }
        .filter { (k, v) -> keep.containsMatchIn(k) && v.isNotEmpty() && v.length < 120 }
        .take(80)
}.getOrDefault(emptyList())

private tailrec fun android.content.Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** 应用对照：声明比亚迪分屏类别的应用 + 每个第三方应用的启动配置，一屏能拍下 */
@Composable
private fun AppCompare(split: List<String>, apps: List<AppProbe.AppInfo>) {
    val x = Xyz
    fun short(list: List<String>, keep: (String) -> Boolean = { false }): String {
        if (list.isEmpty()) return "无"
        val (key, rest) = list.partition(keep)
        val shown = key + rest.take(maxOf(0, 4 - key.size))
        val more = list.size - shown.size
        return shown.joinToString("、") + if (more > 0) " 等 ${list.size} 个" else ""
    }
    Text("应用对照", style = MaterialTheme.typography.titleMedium, color = x.text, modifier = Modifier.padding(bottom = 6.dp))
    Text(
        "声明比亚迪分屏类别的应用（${split.size}）：" + if (split.isEmpty()) "无" else split.joinToString("、"),
        style = MaterialTheme.typography.bodyMedium, color = x.text, modifier = Modifier.padding(vertical = 6.dp),
    )
    Hairline()
    apps.forEach { a ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                (if (a.self) "★ " else "") + "${a.label}  ${a.pkg}",
                style = MaterialTheme.typography.bodyMedium, color = x.text, fontWeight = FontWeight.SemiBold,
            )
            Text(a.summary, style = MaterialTheme.typography.bodySmall, color = x.sub)
            Text("自定义 intent：" + short(a.customIntents) { it.startsWith("byd") || it.contains("ivi", true) }, style = MaterialTheme.typography.bodySmall, color = x.sub)
            Text("meta-data：" + short(a.metaData) { it.contains("byd", true) || it.startsWith("android.") }, style = MaterialTheme.typography.bodySmall, color = x.sub)
        }
        Hairline()
    }
    Spacer(Modifier.height(20.dp))
    Text("系统属性", style = MaterialTheme.typography.titleMedium, color = x.text, modifier = Modifier.padding(bottom = 6.dp))
}

/** 车机信息：系统版本、屏幕、是否在分屏、比亚迪相关系统属性。用来排查分屏和语音，拍照发给开发者即可 */
@Composable
fun DiagnosticsScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    val x = Xyz
    val ctx = LocalContext.current
    val conf = LocalConfiguration.current
    val props by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { relatedProps() }
    }
    val probe by androidx.compose.runtime.produceState<Pair<List<String>, List<AppProbe.AppInfo>>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppProbe.bydSplitPackages(ctx) to AppProbe.thirdPartyApps(ctx)
        }
    }
    val voice by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppProbe.voiceRoles(ctx) }
    }
    val split by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { splitRows(ctx) }
    }
    val basics = remember(conf.screenWidthDp, conf.screenHeightDp) {
        val dm = ctx.resources.displayMetrics
        listOf(
            "设备" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Android" to "${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）",
            "系统版本号" to android.os.Build.DISPLAY,
            "当前窗口" to "${conf.screenWidthDp} × ${conf.screenHeightDp} dp",
            "屏幕像素" to "${dm.widthPixels} × ${dm.heightPixels} px，密度 ${dm.density}",
            "分屏中" to if (ctx.activity()?.isInMultiWindowMode == true) "是" else "否",
            "ro.byd.ui.splitscreen" to sysProp("ro.byd.ui.splitscreen").ifEmpty { "（无）" },
            "ro.build.baios.supportsplitscreen" to sysProp("ro.build.baios.supportsplitscreen").ifEmpty { "（无）" },
            "ro.build.ui_platformized" to sysProp("ro.build.ui_platformized").ifEmpty { "（无）" },
            "App 版本" to BuildConfigInfo.VERSION,
        )
    }
    ScreenHeader("车机信息", onBack)
    Text("排查分屏和语音用：拍下这页发给开发者。只读取系统信息，不会修改任何设置。", style = MaterialTheme.typography.bodyMedium, color = x.sub)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
        val row = @Composable { k: String, v: String ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = x.sub, modifier = Modifier.weight(0.45f))
                Text(v, style = MaterialTheme.typography.bodyMedium, color = x.text, modifier = Modifier.weight(0.55f))
            }
            Hairline()
        }
        basics.forEach { (k, v) -> row(k, v) }
        if (voice.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("小迪语音", style = MaterialTheme.typography.titleMedium, color = x.text, modifier = Modifier.padding(bottom = 6.dp))
            voice.forEach { (k, v) -> row(k, v) }
        }
        if (split.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("分屏接口", style = MaterialTheme.typography.titleMedium, color = x.text, modifier = Modifier.padding(bottom = 6.dp))
            split.forEach { (k, v) -> row(k, v) }
        }
        Spacer(Modifier.height(20.dp))
        probe?.let { (splitPkgs, apps) -> AppCompare(splitPkgs, apps) }
        val extraProps = props.filter { p -> basics.none { it.first == p.first } }
        extraProps.forEach { (k, v) -> row(k, v) }
        if (props.isEmpty()) Text("没有读到比亚迪相关的系统属性", style = MaterialTheme.typography.bodyMedium, color = x.sub, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        EventLogSection {
            // 导出的完整报告：各段 + 应用对照 + 事件日志
            buildString {
                fun sec(title: String, rows: List<Pair<String, String>>) {
                    append("## ").append(title).append('\n')
                    rows.forEach { append(it.first).append(" = ").append(it.second).append('\n') }
                }
                sec("基本", basics); sec("小迪语音", voice); sec("分屏接口", split)
                probe?.let { (splitPkgs, apps) ->
                    append("## 应用对照\n声明比亚迪分屏类别：").append(splitPkgs).append('\n')
                    apps.forEach { a -> append(if (a.self) "★ " else "").append(a.label).append(' ').append(a.pkg).append(" | ").append(a.summary).append(" | ").append(a.customIntents).append(" | ").append(a.metaData).append('\n') }
                }
                sec("系统属性", extraProps)
                append("## 事件日志\n").append(com.ryan.xyzcar.data.DebugLog.read())
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
