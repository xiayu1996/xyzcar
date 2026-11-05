package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.ryan.xyzcar.data.api.XyzApi.LoginQr
import com.ryan.xyzcar.data.api.XyzApi.QrState
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.errorText
import com.ryan.xyzcar.ui.theme.Dims
import kotlinx.coroutines.delay

/** 一个二维码最多等 3 分钟，过了就显示「已失效」，点一下重新生成 */
private const val QR_WAIT_MS = 180_000L
private const val POLL_MS = 1_000L

/** 二维码矩阵：每个模块 1 格，四周留 2 格白边。纯 Java，界面和登录验证工具共用 */
fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
)

/** F1 登录：扫码登录（短信验证码接口要求人机验证，第三方客户端用不了） */
@Composable
fun LoginScreen(vm: AppViewModel) {
    var qr by remember { mutableStateOf<LoginQr?>(null) }
    var state by remember { mutableStateOf(QrState.WAITING) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }

    // 离开登录页时协程自动取消；每轮有 3 分钟上限
    LaunchedEffect(round) {
        qr = null; error = null; state = QrState.WAITING
        val q = runCatching { vm.repo.api.createLoginQr() }.getOrElse { error = errorText(it); return@LaunchedEffect }
        qr = q
        val deadline = System.currentTimeMillis() + QR_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val s = runCatching { vm.repo.api.pollLoginQr(q.id) }
                .onFailure { error = errorText(it) }
                .getOrNull() ?: continue // 网络抖动：下一轮再查
            error = null
            state = s
            if (s == QrState.CONFIRMED) { vm.onLoggedIn(); return@LaunchedEffect }
            if (s == QrState.EXPIRED) return@LaunchedEffect
        }
        state = QrState.EXPIRED
    }

    LoginContent(
        qr = qr, state = state, error = error,
        onRefresh = { round++ },
        onDemo = if (com.ryan.xyzcar.BuildConfig.DEBUG) ({ vm.enableDemo() }) else null,
    )
}

/** 无状态的登录界面，截图测试直接用 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginContent(qr: LoginQr?, state: QrState, error: String?, onRefresh: () -> Unit, onDemo: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
        ) {
            QrBox(qr, state, 300.dp, onRefresh)
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.widthIn(min = 280.dp, max = 460.dp).align(Alignment.CenterVertically),
            ) {
                Text("小宇宙车机版", style = MaterialTheme.typography.headlineLarge)
                Text(
                    when (state) {
                        QrState.WAITING -> "打开手机上的小宇宙 App，扫描左侧二维码登录"
                        QrState.SCANNED -> "已扫码，请在手机上点「确认登录」"
                        QrState.CONFIRMED -> "登录成功，正在进入…"
                        QrState.EXPIRED -> "二维码已失效，点二维码或下方按钮重新获取"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state == QrState.SCANNED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "和手机端是同一个账号。登录后会自动续期，一般不需要再扫。",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
                BigButton("刷新二维码", secondary = true, onClick = onRefresh)
                // 仅 debug 包：不登录、不发请求，用样例数据看界面（云测试 / 截图用）
                if (onDemo != null) {
                    OutlinedButton(onClick = onDemo, modifier = Modifier.heightIn(min = Dims.touch)) {
                        Text("演示模式（调试包）", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun QrBox(qr: LoginQr?, state: QrState, size: Dp, onRefresh: () -> Unit) {
    // 二维码固定白底黑码，深色模式下也能扫
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .clickable(enabled = state == QrState.EXPIRED, onClick = onRefresh),
        contentAlignment = Alignment.Center,
    ) {
        if (qr == null) {
            CircularProgressIndicator(color = Color(0xFF25B4E1))
        } else {
            val matrix = remember(qr.url) { qrMatrix(qr.url) }
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val cell = minOf(this.size.width, this.size.height) / matrix.width
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
        if (state == QrState.EXPIRED || state == QrState.SCANNED) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                Text(
                    if (state == QrState.EXPIRED) "已失效\n点击刷新" else "已扫码\n请在手机上确认",
                    color = Color(0xFF161517), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
