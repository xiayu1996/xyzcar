package com.ryan.xyzcar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryan.xyzcar.data.ThemeColor

/** 车机尺寸规范（PRD「车机适配与 UX」→ 尺寸规范） */
object Dims {
    val touch = 76.dp          // 最小触控区域
    val gap = 16.dp            // 相邻触控区域间距
    val playButton = 128.dp    // 播放 / 暂停（全界面最大）
    val sideButton = 96.dp     // 后退 / 前进 / 倍速
    val progressHit = 76.dp    // 进度条触控热区高度
    val progressBar = 8.dp     // 进度条可见高度
    val cover = 88.dp          // 列表封面
    val radius = 16.dp
    val railWidth = 120.dp
}

/*
 * 设计语言（参考小宇宙并提炼）：
 * - 底色中性、内容优先：白 / 近黑底，墨色粗标题，灰色辅助信息，少用分隔线和卡片
 * - 颜色来自节目：每个节目有接口给的主题色（取自封面），节目页、单集页、播放面板用它做强调色
 * - 品牌青色 #25B4E1 只在没有节目色时兜底
 * - 胶囊形按钮、封面小圆角 + 柔和投影、时间数字等宽
 */
val Brand = Color(0xFF25B4E1)
private val Ink = Color(0xFF161517)

private val Dark = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF00141C),
    primaryContainer = Brand.copy(alpha = 0.18f).compositeOver(Color(0xFF161618)),
    onPrimaryContainer = Color(0xFFF5F5F7),
    secondary = Color(0xFFF5F5F7),         // 选中态胶囊：浅色底 + 深色字
    onSecondary = Color(0xFF0B0B0C),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF161618),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF26262A),    // 填充色：按钮、胶囊底
    onSurfaceVariant = Color(0xFFA1A1A8),  // 辅助文字，对 surface 对比度约 7:1
    outline = Color(0xFF6E6E73),
    outlineVariant = Color(0xFF2A2A2E),    // 分隔线
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0E8EBF),
    onPrimary = Color.White,
    primaryContainer = Brand.copy(alpha = 0.12f).compositeOver(Color.White),
    onPrimaryContainer = Ink,
    secondary = Ink,                        // 选中态胶囊：墨色底 + 白字
    onSecondary = Color.White,
    background = Color(0xFFF7F6F5),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFEFF1),
    onSurfaceVariant = Color(0xFF6C6C70),   // 对白底约 5:1
    outline = Color(0xFFAEAEB2),
    outlineVariant = Color(0xFFE8E8EB),
    error = Color(0xFFD32F2F),
)

/** 主文字 32sp，次文字 24sp；标题统一粗体 */
private val CarTypography = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 26.sp, lineHeight = 36.sp),
    bodyMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    labelLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
)

/** 时间、计数用的等宽数字 */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)

@Composable
fun XyzCarTheme(content: @Composable () -> Unit) {
    // 跟随系统：车机夜间是深色
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = CarTypography, content = content)
}

fun parseHex(hex: String): Color? = runCatching {
    val h = hex.trim().removePrefix("#")
    when (h.length) {
        6 -> Color(("FF$h").toLong(16))
        8 -> Color(h.toLong(16))
        else -> null
    }
}.getOrNull()

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/**
 * 从节目主题色里选一个在当前底色上看得清的颜色（对比度 ≥ 3:1，用于大号按钮和强调文字），
 * 都不合格就返回 null，沿用品牌色。
 */
fun pickAccent(c: ThemeColor?, scheme: ColorScheme): Color? {
    if (c == null) return null
    val darkBg = scheme.background.luminance() < 0.5f
    val order = if (darkBg) listOf(c.original, c.light) else listOf(c.original, c.dark)
    return order.mapNotNull { parseHex(it) }.firstOrNull {
        contrast(it, scheme.surface) >= 3f && contrast(it, scheme.background) >= 3f
    }
}

/** 用节目主题色替换强调色：页面里的主按钮、进度条、时间戳链接都会跟着变 */
@Composable
fun Accented(color: ThemeColor?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(color, base) {
        val accent = pickAccent(color, base) ?: return@remember base
        val darkBg = base.background.luminance() < 0.5f
        base.copy(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.45f) Color(0xFF111111) else Color.White,
            primaryContainer = accent.copy(alpha = if (darkBg) 0.22f else 0.12f).compositeOver(base.surface),
            onPrimaryContainer = base.onSurface,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}
