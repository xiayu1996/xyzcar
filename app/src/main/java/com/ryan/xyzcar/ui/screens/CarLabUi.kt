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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ryan.xyzcar.App
import com.ryan.xyzcar.data.BydBridge
import com.ryan.xyzcar.data.DebugLog
import com.ryan.xyzcar.ui.components.Hairline
import com.ryan.xyzcar.ui.theme.Dims
import com.ryan.xyzcar.ui.theme.Xyz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** 设置里的「车机实验功能」：全部默认关闭，打开 / 关闭的结果写进车机信息的日志 */
@Composable
internal fun CarLabSection() {
    val x = Xyz
    val ctx = LocalContext.current
    val store = App.instance.local
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(store.labVoiceRegister) }
    var music by remember { mutableStateOf(store.labMusicRole) }
    var split by remember { mutableStateOf(store.labSplitPersist) }
    var result by remember { mutableStateOf("") }
    fun run(block: () -> String) = scope.launch { result = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "出错：${it.javaClass.simpleName}" } } }

    Text("车机实验功能", style = MaterialTheme.typography.titleLarge, color = x.text, modifier = Modifier.padding(top = 36.dp, bottom = 6.dp))
    Text("比亚迪车机的集成测试，默认全部关闭。每一步的结果都记在「车机信息」的事件日志里。", style = MaterialTheme.typography.bodyMedium, color = x.sub)
    LabSwitch("注册到小迪音乐列表", "打开后每次启动向小迪登记为音乐应用，之后可在小迪设置或底部音乐卡片里选择本应用；不会改默认音乐", register) { on ->
        register = on; store.labVoiceRegister = on
        run { if (on) BydBridge.registerMusicApp(ctx) else "已关闭（系统里已登记的条目无法撤回，不影响使用）".also { DebugLog.log("voice", "关闭注册开关") } }
    }
    LabSwitch("小迪默认音乐用本应用", "打开后对小迪说「播放音乐」「继续播放」交给本应用；关闭时恢复原来的默认音乐", music) { on ->
        music = on; store.labMusicRole = on
        run { if (on) BydBridge.takeMusicRole(ctx, store) else BydBridge.restoreMusicRole(ctx, store) }
    }
    LabSwitch("加入系统分屏列表", "打开后每次启动请求车机把本应用当作可分屏应用（车机重启后需要重新打开本应用）", split) { on ->
        split = on; store.labSplitPersist = on
        run { if (on) BydBridge.addPersistentSplitApp(ctx) else "已关闭，车机重启后失效".also { DebugLog.log("split", "关闭分屏常驻开关") } }
    }
    AnimatedVisibility(result.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        AnimatedContent(result, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "labResult") { r ->
            Text(r, style = MaterialTheme.typography.bodyMedium, color = x.accentText, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(x.accentFill).padding(14.dp))
        }
    }
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

/** 事件日志（最新在上，最多显示 300 行）+ 清空 / 导出二维码 */
@Composable
internal fun EventLogSection(report: () -> String) {
    val x = Xyz
    var version by remember { mutableIntStateOf(0) }
    val lines = remember(version) { DebugLog.read().lines().filter { it.isNotBlank() }.asReversed().take(300) }
    var qr by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text("事件日志", style = MaterialTheme.typography.titleMedium, color = x.text, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { scope.launch { qr = withContext(Dispatchers.Default) { qrChunks(report()) } } }, modifier = Modifier.heightIn(min = Dims.touch)) {
            Text("导出二维码", style = MaterialTheme.typography.titleSmall)
        }
        OutlinedButton(onClick = { version++ }, modifier = Modifier.heightIn(min = Dims.touch)) { Text("刷新", style = MaterialTheme.typography.titleSmall) }
        OutlinedButton(onClick = { DebugLog.clear(); version++ }, modifier = Modifier.heightIn(min = Dims.touch)) { Text("清空", style = MaterialTheme.typography.titleSmall) }
    }
    Text("记录启动方式、分屏变化、哪个系统组件连了播放服务、实验开关的结果和崩溃。出问题后点「导出二维码」，用手机逐张扫码，把文字发给开发者。", style = MaterialTheme.typography.bodySmall, color = x.sub)
    if (lines.isEmpty()) Text("暂无记录", style = MaterialTheme.typography.bodyMedium, color = x.sub, modifier = Modifier.padding(vertical = 8.dp))
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = x.text, modifier = Modifier.padding(vertical = 3.dp)) }
    qr?.let { chunks -> QrPager(chunks) { qr = null } }
}

/** 报告压缩成 gzip + base64，按 1500 字符切片，每片前缀「XYZ 序号/总数」 */
internal fun qrChunks(text: String): List<String> {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
    val b64 = java.util.Base64.getEncoder().encodeToString(bos.toByteArray())
    val parts = b64.chunked(1500)
    return parts.mapIndexed { i, p -> "XYZ ${i + 1}/${parts.size} $p" }
}

@Composable
private fun QrPager(chunks: List<String>, onClose: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(28.dp),
        ) {
            AnimatedContent(page, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "qrPage") { p ->
                val matrix = remember(p) { qrMatrix(chunks[p]) }
                Canvas(Modifier.size(440.dp)) {
                    val cell = minOf(size.width, size.height) / matrix.width
                    for (yy in 0 until matrix.height) for (xx in 0 until matrix.width) {
                        if (matrix[xx, yy]) drawRect(Color.Black, Offset(xx * cell, yy * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(240.dp)) {
                Text("第 ${page + 1} / ${chunks.size} 张", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF161517))
                Text("用手机扫码，把每张扫出的文字按顺序发给开发者", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5C5B61))
                OutlinedButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.fillMaxWidth().heightIn(min = Dims.touch)) { Text("上一张") }
                OutlinedButton(onClick = { page++ }, enabled = page < chunks.lastIndex, modifier = Modifier.fillMaxWidth().heightIn(min = Dims.touch)) { Text("下一张") }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = Dims.touch)) { Text("关闭") }
            }
        }
    }
}

/** 车机信息里的「分屏接口」段 */
internal fun splitRows(ctx: android.content.Context): List<Pair<String, String>> {
    val tx = BydBridge.transactions()
    return listOf(
        "本应用分屏标记" to BydBridge.splitMetaData(ctx),
        "系统事务" to "可见 ${tx.visible} 个" + (tx.error?.let { "，$it" } ?: ""),
        "分屏相关事务" to tx.split.entries.joinToString("、") { "${it.key}#${it.value}" }.ifEmpty { "无" },
        "本应用可分屏" to BydBridge.isSupportSplit(ctx.packageName, ctx),
    ) + BydBridge.unionInfo(ctx) + BydBridge.splitSettings(ctx)
}
