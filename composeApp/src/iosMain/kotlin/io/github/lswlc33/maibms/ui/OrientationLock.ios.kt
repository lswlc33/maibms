package io.github.lswlc33.maibms.ui

/**
 * iOS 暂不做程序化锁向：动态改 supportedInterfaceOrientations 需要壳工程持有
 * 并刷新根 UIViewController，属后续真机适配。iPad/横屏由用户手动旋转，
 * 旋转后靠宽度断点自动进表盘。
 */
actual val supportsOrientationLock: Boolean = false

actual fun lockLandscape(lock: Boolean) = Unit
