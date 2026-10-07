package io.github.lswlc33.maibms.ui

import androidx.compose.ui.unit.Dp

/**
 * 横屏表盘的系统 chrome 控制（沉浸 + 异形屏避让），Android 真实现，其余平台空实现。
 */

/** true = 隐藏状态栏与手势条（从边缘滑动临时呼出）；false = 恢复显示 */
expect fun systemBarsImmersive(immersive: Boolean)

/** 屏幕圆角半径（四角最大值，已换算 Dp）：表盘四角避让用；平台不支持/读不到为 0 */
expect fun screenCornerRadius(): Dp
