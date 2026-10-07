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
