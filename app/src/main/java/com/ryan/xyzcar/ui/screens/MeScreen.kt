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
import com.ryan.xyzcar.data.SIZE_LABELS
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.Hairline
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.card
import com.ryan.xyzcar.ui.components.pressable
import com.ryan.xyzcar.ui.theme.DarkColors
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.data.DebugLog
import androidx.compose.material3.TextButton
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

/**
 * 检查更新 → 下载（带进度）→ 安装。比亚迪车机不给第三方应用安装权限（设置里也没有开关），
 * 这时安装包另存到「下载/xyzcar/」和 U 盘，点击打开车机的文件管理去装。
 */
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
        is UpdateState.Ready -> if (canInstall) "安装 ${s.release.tag}" else "用文件管理安装 ${s.release.tag}"
        is UpdateState.Failed -> if (s.release != null) "下载失败，点击重试" else "检查失败，点击重试"
    }
    val hint = when (s) {
        is UpdateState.Downloading -> "${(s.progress * 100).toInt()}%"
        is UpdateState.Ready -> when {
            canInstall -> "下载完成，已校验"
            s.copies.isEmpty() -> "车机不允许本应用直接安装，安装包也没能另存，请用 U 盘安装"
            else -> "车机不允许本应用直接安装。安装包已存到 ${s.copies.first()}，点这里打开文件管理，找到它点击安装"
        }
        is UpdateState.Failed -> s.message
        else -> "当前 ${BuildConfigInfo.VERSION}"
    }
    MenuRow(Icons.Filled.SystemUpdate, title, hint, danger = s is UpdateState.Failed) {
        when (s) {
            UpdateState.Idle, UpdateState.Latest -> vm.checkUpdate(silent = false)
            is UpdateState.Failed -> if (s.release != null) vm.downloadUpdate() else vm.checkUpdate(silent = false)
            is UpdateState.Available -> vm.downloadUpdate()
            is UpdateState.Ready -> {
                val fm = if (canInstall) null else Updater.fileManagerIntent(ctx)
                val r = runCatching { ctx.startActivity(fm ?: Updater.installIntent(ctx, s.file)) }
                DebugLog.kv("update", "install_click", "via" to (fm?.component?.packageName ?: fm?.`package` ?: "installer"), "ok" to r.isSuccess, "error" to r.exceptionOrNull()?.javaClass?.simpleName)
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
    AnimatedVisibility(s is UpdateState.Ready && !canInstall, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        if (s is UpdateState.Ready) TextButton(
            onClick = {
                val r = runCatching { ctx.startActivity(Updater.installIntent(ctx, s.file)) }
                DebugLog.kv("update", "install_click", "via" to "installer_direct", "ok" to r.isSuccess, "error" to r.exceptionOrNull()?.javaClass?.simpleName)
            },
            modifier = Modifier.padding(start = 46.dp).heightIn(min = Dims.touch),
        ) { Text("也可以试试直接安装", style = MaterialTheme.typography.titleSmall, color = x.accent) }
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

private tailrec fun android.content.Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * 车机信息：导出按钮 + 几行摘要。原始数据（小迪设置表、分屏接口、应用对照、系统属性、事件日志）只进导出的报告，屏幕上不列。
 */
@Composable
fun DiagnosticsScreen(onBack: (() -> Unit)?) {
    val x = Xyz
    val ctx = LocalContext.current
    val conf = LocalConfiguration.current
    val music by androidx.compose.runtime.produceState<String?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ryan.xyzcar.data.BydBridge.readSwitch(ctx, com.ryan.xyzcar.data.BydBridge.MUSIC_SWITCH)?.let { pkg ->
                runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            } ?: "读不到"
        }
    }
    val rows = listOf(
        "App 版本" to BuildConfigInfo.VERSION,
        "车机系统" to android.os.Build.DISPLAY,
        "Android" to "${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）",
        "当前窗口" to "${conf.screenWidthDp} × ${conf.screenHeightDp} dp" + if (ctx.activity()?.isInMultiWindowMode == true) "，分屏中" else "",
        "小迪默认音乐" to (music ?: "…"),
    )
    ScreenHeader("车机信息", onBack)
    Text("出问题时保存报告或用手机扫码下载，发给开发者", style = MaterialTheme.typography.bodyMedium, color = x.sub)
    ExportBar()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(k, style = MaterialTheme.typography.bodyLarge, color = x.sub, modifier = Modifier.weight(0.35f))
                Text(v, style = MaterialTheme.typography.bodyLarge, color = x.text, modifier = Modifier.weight(0.65f))
            }
            Hairline()
        }
        Spacer(Modifier.height(24.dp))
    }
}
