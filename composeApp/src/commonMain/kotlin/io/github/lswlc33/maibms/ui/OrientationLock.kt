package io.github.lswlc33.maibms.ui

/**
 * 程序化锁定横屏（进入横屏表盘用）。
 * Android 真支持（requestedOrientation）；桌面没有方向概念、iOS 动态改方向需要
 * 壳工程配合——两者都不支持，入口按 [supportsOrientationLock] 隐藏。
 */
expect val supportsOrientationLock: Boolean

/** lock=true 锁定传感器横屏；false 交还系统（用户自由旋转） */
expect fun lockLandscape(lock: Boolean)
