package com.ryan.xyzcar.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryan.xyzcar.data.ThemeColor

/** 车机尺寸规范：触控区域不小于 72dp，相邻间距 16dp */
object Dims {
    val touch = 72.dp
    val gap = 16.dp
    val playButton = 128.dp
    val sideButton = 96.dp
    val progressHit = 72.dp
    val cover = 96.dp
    val radius = 16.dp
    val railWidth = 112.dp
    val miniPlayer = 84.dp
    /** 窄于这个宽度用底部导航（分屏 1/3 等） */
    val narrow = 600.dp
    /** 宽于这个宽度用两栏 / 横向分页 */
    val wide = 960.dp
}

/*
 * 设计语言（按官方 iOS 日间截图提炼）：
 * - 白底 / 近黑底，粗黑大标题，灰色辅助文字；分区标题用品牌青色
 * - 强调色只用于图标、播放键和选中态，浅青底 + 深青字做按钮
 * - 入口胶囊用浅色块（青 / 桃 / 青柠 / 薰衣草），推荐卡片用节目封面取色的浅底
 */
@Immutable
data class XyzColors(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val fill: Color,
    val line: Color,
    val border: Color,
    val accent: Color,
    val accentText: Color,
    val accentFill: Color,
    val accentBorder: Color,
    val peach: Chroma,
    val lime: Chroma,
    val lavender: Chroma,
    val shadow: Color,
)

/** 色块胶囊：底 / 描边 / 文字 */
@Immutable
data class Chroma(val bg: Color, val border: Color, val fg: Color)

val LightColors = XyzColors(
    dark = false,
    bg = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF), text = Color(0xFF1A1A1C), sub = Color(0xFF75757B),
    fill = Color(0xFFF2F3F5), line = Color(0xFFEFEFF2), border = Color(0xFFE6E6EA),
    accent = Color(0xFF2E9BCF), accentText = Color(0xFF17719F), accentFill = Color(0xFFE8F5FC), accentBorder = Color(0xFFCDE8F6),
    peach = Chroma(Color(0xFFFDEEE3), Color(0xFFF8DCC8), Color(0xFFB4501A)),
    lime = Chroma(Color(0xFFEEF9CF), Color(0xFFDCEFA6), Color(0xFF4A6A0C)),
    lavender = Chroma(Color(0xFFEEEDFC), Color(0xFFDCDAF7), Color(0xFF4B48B8)),
    shadow = Color(0x12182738),
)

val DarkColors = XyzColors(
    dark = true,
    bg = Color(0xFF121214), card = Color(0xFF1B1C1F), text = Color(0xFFF2F2F4), sub = Color(0xFFA0A0A7),
    fill = Color(0xFF1F2024), line = Color(0xFF2A2B2F), border = Color(0xFF2E2F34),
    accent = Color(0xFF56BBE8), accentText = Color(0xFF6CC6EE), accentFill = Color(0x2956BBE8), accentBorder = Color(0x6156BBE8),
    peach = Chroma(Color(0x29F0884A), Color(0x52F0884A), Color(0xFFF4A574)),
    lime = Chroma(Color(0x26A0D63C), Color(0x52A0D63C), Color(0xFFBFE27A)),
    lavender = Chroma(Color(0x298B86F0), Color(0x528B86F0), Color(0xFFB7B3F7)),
    shadow = Color(0x00000000),
)

/** 主题切换时颜色每帧都在变：用普通 CompositionLocal，只重组读到它的地方 */
val LocalXyz = compositionLocalOf { LightColors }

/** 当前配色（页面里统一用这个取色） */
val Xyz: XyzColors @Composable get() = LocalXyz.current

private fun scheme(c: XyzColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = if (c.dark) Color(0xFF06212E) else Color.White,
        primaryContainer = c.accentFill.compositeOver(c.bg),
        onPrimaryContainer = c.accentText,
        secondary = c.text,
        onSecondary = c.bg,
        background = c.bg,
        onBackground = c.text,
        surface = c.bg,
        onSurface = c.text,
        surfaceVariant = c.fill,
        onSurfaceVariant = c.sub,
        surfaceContainer = c.card,
        surfaceContainerHigh = c.card,
        outline = c.border,
        outlineVariant = c.line,
        error = if (c.dark) Color(0xFFFF8A80) else Color(0xFFD32F2F),
        inverseSurface = c.text,
        inverseOnSurface = c.bg,
    )
}

/** 主文字 24sp、辅助 20sp；大标题 40sp 粗黑 */
private val CarTypography = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold),
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodySmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
)

/** 时间、计数用的等宽数字 */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)

/** 外观设置：浅色 / 深色 / 跟随系统 */
enum class ThemeMode(val key: String, val label: String) {
    Light("light", "浅色"), Dark("dark", "深色"), System("system", "跟随系统");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: System }
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> isSystemInDarkTheme()
}

private val colorAnim = tween<Color>(durationMillis = 450)

@Composable
private fun animated(c: Color, label: String): Color = animateColorAsState(c, colorAnim, label = label).value

@Composable
private fun animated(c: Chroma): Chroma = Chroma(animated(c.bg, "cb"), animated(c.border, "cbd"), animated(c.fg, "cf"))

/** 切换深浅色时所有颜色 450ms 渐变，不是一下子闪过去 */
@Composable
private fun animate(t: XyzColors): XyzColors = XyzColors(
    dark = t.dark,
    bg = animated(t.bg, "bg"), card = animated(t.card, "card"), text = animated(t.text, "text"), sub = animated(t.sub, "sub"),
    fill = animated(t.fill, "fill"), line = animated(t.line, "line"), border = animated(t.border, "border"),
    accent = animated(t.accent, "accent"), accentText = animated(t.accentText, "accentText"),
    accentFill = animated(t.accentFill, "accentFill"), accentBorder = animated(t.accentBorder, "accentBorder"),
    peach = animated(t.peach), lime = animated(t.lime), lavender = animated(t.lavender),
    shadow = animated(t.shadow, "shadow"),
)

@Composable
fun XyzCarTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val c = animate(if (isDark(mode)) DarkColors else LightColors)
    CompositionLocalProvider(LocalXyz provides c) {
        MaterialTheme(colorScheme = scheme(c), typography = CarTypography, content = content)
    }
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
 * 从节目主题色里选一个在当前底色上看得清的颜色（对比度 ≥ 3:1），都不合格返回 null（沿用品牌色）。
 */
fun pickAccent(c: ThemeColor?, scheme: ColorScheme): Color? {
    if (c == null) return null
    val darkBg = scheme.background.luminance() < 0.5f
    val order = if (darkBg) listOf(c.original, c.light) else listOf(c.original, c.dark)
    return order.mapNotNull { parseHex(it) }.firstOrNull {
        contrast(it, scheme.surface) >= 3f && contrast(it, scheme.background) >= 3f
    }
}

/**
 * 推荐卡片的浅色底：节目色和底色混合。浅色模式接近白、深色模式接近卡片色，文字仍用正文色。
 */
fun tintOf(c: ThemeColor?, x: XyzColors): Color {
    val base = parseHex(c?.light?.ifEmpty { c.original } ?: "") ?: parseHex(c?.original ?: "") ?: x.accent
    return base.copy(alpha = if (x.dark) 0.20f else 0.22f).compositeOver(x.card)
}

/** 用节目主题色替换强调色：页面里的主按钮、进度条、时间戳链接都会跟着变 */
@Composable
fun Accented(color: ThemeColor?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val x = LocalXyz.current
    val accent = remember(color, base) { pickAccent(color, base) }
    if (accent == null) { content(); return }
    val scheme = remember(accent, base) {
        base.copy(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.45f) Color(0xFF111111) else Color.White,
            primaryContainer = accent.copy(alpha = if (x.dark) 0.22f else 0.12f).compositeOver(base.surface),
            onPrimaryContainer = base.onSurface,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}
