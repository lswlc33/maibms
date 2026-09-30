package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

object BmsColors {
    // 参考截图（MIUI 省电与电池）取色：主色绿 + 图表蓝 + 中性灰
    val Primary = Color(0xFF4CAF50)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD5EFD8)
    val OnPrimaryContainer = Color(0xFF0C3D14)
    /** 电池卡填充：其上有白色小字，比主色压深一档保证可读 */
    val GreenFill = Color(0xFF3E9E4A)
    val OkGreen = Color(0xFF3E9E4A)
    val OkBg = Color(0xFFD5EFD8)
    val WarnAmber = Color(0xFFB26A00)
    val WarnBg = Color(0xFFFFE7C2)
    val BadRed = Color(0xFFD23B36)
    val BadBg = Color(0xFFFFDCD9)
    val OffGray = Color(0xFF8A8A8E)
    val OffBg = Color(0xFFE9E9EB)
    // 单体最高/最低的描边与趋势曲线：浅色卡底上要 ≥3.0
    val CellMax = Color(0xFFB8761A)
    val CellMin = Color(0xFF2F6FE0)
    // 图表蓝（参考截图折线）
    val ChartBlue = Color(0xFF3B82F6)
    // 图标网格彩色圆
    val IcBlue = Color(0xFF3B82F6)
    val IcTeal = Color(0xFF12A594)
    val IcPurple = Color(0xFF8B6BD8)
    val IcAmber = Color(0xFFD08A28)   // 指标圆点用，底色非文字
    val IcGreen = Color(0xFF3E9E4A)
    val IcRed = Color(0xFFD23B36)
}

private val LightScheme = lightColorScheme(
    primary = BmsColors.Primary,
    onPrimary = BmsColors.OnPrimary,
    primaryContainer = BmsColors.PrimaryContainer,
    onPrimaryContainer = BmsColors.OnPrimaryContainer,
    secondary = Color(0xFF5C6B5E),
    secondaryContainer = Color(0xFFD5EFD8),
    onSecondaryContainer = Color(0xFF0C3D14),
    // 扁平卡：底为极浅灰，卡为纯白（无描边，靠明度差分层）
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEEF0),
    onSurfaceVariant = Color(0xFF8A8A8E),
    outline = Color(0xFFB0B0B5),
    outlineVariant = Color(0xFFE5E5E7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF7F7F9),
    surfaceContainerHigh = Color(0xFFEDEEF0),
    surfaceContainerHighest = Color(0xFFE4E5E8),
    error = BmsColors.BadRed,
    errorContainer = BmsColors.BadBg,
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5CC96A),
    onPrimary = Color(0xFF06300C),
    primaryContainer = Color(0xFF1E3A21),
    onPrimaryContainer = Color(0xFFD5EFD8),
    secondary = Color(0xFFB6C9B8),
    secondaryContainer = Color(0xFF1E3A21),
    onSecondaryContainer = Color(0xFFD5EFD8),
    // 深色按截图：纯黑底 + 深灰卡
    background = Color(0xFF000000),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF9A9A9E),
    outline = Color(0xFF6E6E73),
    outlineVariant = Color(0xFF2E2E30),
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF252527),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    error = Color(0xFFFF6B62),
    errorContainer = Color(0xFF4A1210),
    onError = Color(0xFF2A0706),
    onErrorContainer = Color(0xFFFFDCD9),
)

@Composable
fun BmsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = BmsShapes,
    ) {
        // M3 的 bodyLarge 行高固定在 24sp，Text 只改 fontSize 时行高不变——13sp 的列表行会被
        // 撑到 34dp（24 行参数就多出两屏）。这里换成随字号等比缩放的 1.2em，密集列表才能压紧。
        ProvideTextStyle(TextStyle.Default.copy(lineHeight = 1.2.em)) { content() }
    }
}

val BmsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** 大卡圆角（截图风格：无边框扁平卡，20dp） */
val CardShape: Shape get() = RoundedCornerShape(20.dp)
