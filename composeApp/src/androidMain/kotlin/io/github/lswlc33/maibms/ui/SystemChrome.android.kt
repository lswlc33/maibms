package io.github.lswlc33.maibms.ui

import android.os.Build
import android.view.WindowInsets
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lswlc33.maibms.AndroidApp

actual fun systemBarsImmersive(immersive: Boolean) {
    val activity = AndroidApp.activity ?: return
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    // 滑动边缘临时呼出系统栏，几秒后自动隐藏——仪表不被打断
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
    else controller.show(WindowInsetsCompat.Type.systemBars())
}

/**
 * 状态栏图标颜色跟**应用**主题（不是系统主题）：`enableEdgeToEdge()` 默认按系统夜景配色，
 * 应用外观设成跟系统错开时（深色系统 + 浅色应用），状态栏会给白字压浅色顶栏。
 * 这里用 isAppearanceLightStatusBars 显式定：浅色应用 = 深色图标。「跟随系统」交回
 * enableEdgeToEdge 的默认行为（按系统夜间模式），与之前真机验收过的表现一致。
 */
actual fun systemBarsAppearance(appearance: AppAppearance) {
    val activity = AndroidApp.activity ?: return
    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        .isAppearanceLightStatusBars = when (appearance) {
        AppAppearance.Light -> true
        AppAppearance.Dark -> false
        AppAppearance.System -> activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}

actual fun screenCornerRadius(): Dp {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0.dp
    val insets = AndroidApp.activity?.window?.decorView?.rootWindowInsets ?: return 0.dp
    val density = AndroidApp.context?.resources?.displayMetrics?.density ?: return 0.dp
    val maxPx = listOf(
        android.view.RoundedCorner.POSITION_TOP_LEFT,
        android.view.RoundedCorner.POSITION_TOP_RIGHT,
        android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
        android.view.RoundedCorner.POSITION_BOTTOM_RIGHT,
    ).maxOfOrNull { insets.getRoundedCorner(it)?.radius ?: 0 } ?: 0
    return (maxPx / density).dp
}
