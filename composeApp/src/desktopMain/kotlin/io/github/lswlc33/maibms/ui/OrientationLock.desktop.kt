package io.github.lswlc33.maibms.ui

/** 桌面端没有「方向」概念：宽窗口自动进表盘（宽度断点），无需也不能锁定 */
actual val supportsOrientationLock: Boolean = false

actual fun lockLandscape(lock: Boolean) = Unit
