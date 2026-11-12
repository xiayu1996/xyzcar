package com.ryan.xyzcar.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import com.ryan.xyzcar.ui.components.BigButton
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.ryan.xyzcar.App
import com.ryan.xyzcar.BuildConfigInfo
import com.ryan.xyzcar.data.BydBridge
import com.ryan.xyzcar.data.CarLab
import com.ryan.xyzcar.data.DebugLog
import com.ryan.xyzcar.data.LanShare
import com.ryan.xyzcar.data.PublicFiles
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.Hairline
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 结果说明框：有内容时展开，内容变化淡入淡出 */
@Composable
private fun ResultBox(text: String) {
    val x = Xyz
    AnimatedVisibility(text.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        AnimatedContent(text, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "labResult") { r ->
            Text(r, style = MaterialTheme.typography.bodyMedium, color = x.accentText, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(x.accentFill).padding(14.dp))
        }
    }
}

/** 设置里的「车机测试」：临时测试用，默认关闭，每次操作的结果写进报告的日志 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CarLabSection(store: com.ryan.xyzcar.data.LocalStore = App.instance.local) {
    val x = Xyz
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var music by remember { mutableStateOf(store.labMusicRole) }
    var alias by remember { mutableStateOf(runCatching { CarLab.enabledAlias(ctx) }.getOrNull()) }
    var custom by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    fun run(toggle: String, on: Any?, block: () -> String) = scope.launch {
        result = withContext(Dispatchers.IO) {
            runCatching(block).getOrElse { "出错：${it.javaClass.simpleName}" }.also { DebugLog.kv("lab", "toggle", "name" to toggle, "value" to on, "ui" to it) }
        }
    }

    Text("车机测试", style = MaterialTheme.typography.titleLarge, color = x.text, modifier = Modifier.padding(top = 36.dp))
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("额外桌面入口", style = MaterialTheme.typography.titleMedium, color = x.text)
        Text("桌面多一个打开本应用的图标，测小迪「打开 + 名字」", style = MaterialTheme.typography.bodySmall, color = x.sub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            (listOf<Pair<String?, String>>(null to "不加") + CarLab.LAUNCHER_ALIASES).forEach { (cls, name) ->
                Chip(name, selected = alias == cls) { alias = cls; run("alias", cls) { CarLab.setAlias(ctx, cls) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
            Box(
                Modifier.weight(1f).heightIn(min = Dims.touch).clip(RoundedCornerShape(18.dp)).background(x.fill).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (custom.isEmpty()) Text("自定义名字", style = MaterialTheme.typography.titleMedium, color = x.sub)
                BasicTextField(
                    value = custom, onValueChange = { custom = it.take(10) }, singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = x.text), cursorBrush = SolidColor(x.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BigButton("添加到桌面", tinted = true, enabled = custom.isNotBlank()) { run("shortcut", custom) { CarLab.pinShortcut(ctx, custom) } }
        }
    }
    Hairline()
    LabSwitch("小迪默认音乐用本应用", "把小迪的默认音乐换成本应用，关闭时换回原来的", music) { on ->
        music = on; store.labMusicRole = on
        run("music", on) { if (on) BydBridge.takeMusicRole(ctx, store) else BydBridge.restoreMusicRole(ctx, store) }
    }
    ResultBox(result)
}

@Composable
private fun LabSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val x = Xyz
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = x.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = x.sub)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = x.accent, checkedThumbColor = Color.White),
            modifier = Modifier.size(width = 72.dp, height = 48.dp),
        )
    }
    Hairline()
}

/**
 * 导出给开发者（agent）的报告，放在车机信息页顶部。
 * - 保存报告文件：存到「下载/xyzcar/」和 U 盘，用车机文件管理复制走；
 * - 手机扫码下载：车机临时开一个只读网页，二维码里只是网址，手机浏览器打开即可，不经过第三方服务器；
 * - 导出媒体中心安装包：给开发者离线分析音乐源名单怎么定的。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportBar() {
    val ctx = LocalContext.current
    var share by remember { mutableStateOf<LanShare.Session?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { LanShare.stop() } }
    fun task(working: String, block: suspend () -> String) = scope.launch {
        if (busy) return@launch
        busy = true
        status = working
        status = runCatching { block() }.getOrElse { "出错：${it.javaClass.simpleName}" }
        busy = false
    }
    suspend fun report(): String = withContext(Dispatchers.IO) {
        runCatching { CarLab.report(ctx, BuildConfigInfo.VERSION) }
            .getOrElse { "report_error=${it.javaClass.simpleName}\n" + DebugLog.compact(DebugLog.read()) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        OutlinedButton(onClick = {
            task("正在保存…") {
                val text = report()
                val paths = withContext(Dispatchers.IO) { PublicFiles.save(ctx, CarLab.reportFileName(), "text/plain") { it.write(text.toByteArray()) } }
                if (paths.isEmpty()) "保存失败，试试「手机扫码下载」" else "已保存。用车机「文件管理」复制到 U 盘，再发给开发者：\n" + paths.joinToString("\n")
            }
        }, modifier = Modifier.heightIn(min = Dims.touch)) { Text("保存报告文件", style = MaterialTheme.typography.titleSmall) }
        OutlinedButton(onClick = {
            task("正在准备…") {
                val text = report()
                val s = withContext(Dispatchers.IO) { LanShare.start(text) }
                if (s == null) "车机没有连 Wi-Fi，也没有开热点。先让车机连上手机热点，再点一次" else { share = s; "" }
            }
        }, modifier = Modifier.heightIn(min = Dims.touch)) { Text("手机扫码下载", style = MaterialTheme.typography.titleSmall) }
        OutlinedButton(onClick = {
            task("正在复制，可能要半分钟…") {
                val r = withContext(Dispatchers.IO) { BydBridge.exportMediaApks(ctx) }
                if (r.isEmpty()) "没有找到媒体中心" else "已复制（给开发者分析音乐源名单）：\n" + r.joinToString("\n")
            }
        }, modifier = Modifier.heightIn(min = Dims.touch)) { Text("导出媒体中心安装包", style = MaterialTheme.typography.titleSmall) }
    }
    ResultBox(status)
    share?.let { s -> LanShareDialog(s) { LanShare.stop(); share = null } }
}

/** 手机下载报告：左边说明和关闭按钮（驾驶位一侧），右边一个网址二维码；到时间自动关闭 */
@Composable
private fun LanShareDialog(s: LanShare.Session, onClose: () -> Unit) {
    LaunchedEffect(s) {
        delay((s.until - System.currentTimeMillis()).coerceAtLeast(0))
        onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(24.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(360.dp)) {
                Text("手机扫码下载报告", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF161517))
                Text("手机和车机要在同一个网络：车机连手机热点，或手机连车机热点。扫码后用浏览器打开，把页面内容或文件发给开发者。", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5C5B61))
                s.urls.forEach { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = Color(0xFF161517)) }
                Text("${LanShare.MINUTES} 分钟后或离开本页自动关闭", style = MaterialTheme.typography.bodySmall, color = Color(0xFF5C5B61))
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = Dims.touch)) { Text("关闭") }
            }
            val matrix = remember(s) { qrMatrix(s.urls.first(), ErrorCorrectionLevel.M, margin = 2) }
            Canvas(Modifier.size(320.dp)) {
                val cell = minOf(size.width, size.height) / matrix.width
                for (yy in 0 until matrix.height) for (xx in 0 until matrix.width) {
                    if (matrix[xx, yy]) drawRect(Color.Black, Offset(xx * cell, yy * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

