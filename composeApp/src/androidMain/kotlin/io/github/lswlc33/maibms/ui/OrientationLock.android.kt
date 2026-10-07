package io.github.lswlc33.maibms.ui

import android.content.pm.ActivityInfo
import io.github.lswlc33.maibms.AndroidApp

actual val supportsOrientationLock: Boolean = true

actual fun lockLandscape(lock: Boolean) {
    // 表盘入口/退出都在主线程的点击回调里，直接设即可；Activity 缺席（极端时序）则静默跳过
    AndroidApp.activity?.requestedOrientation = if (lock) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
