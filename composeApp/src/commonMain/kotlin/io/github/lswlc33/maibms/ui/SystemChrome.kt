package io.github.lswlc33.maibms.ui

import androidx.compose.ui.unit.Dp

/**
 * 横屏表盘的系统 chrome 控制（沉浸 + 异形屏避让），Android 真实现，其余平台空实现。
 */

/**
 * 应用外观。系统栏文字配色要跟的是**应用**主题而不是系统主题——外观设置里可以强制浅色/
 * 深色，跟系统错开时（深色系统 + 浅色应用）状态栏会变成白字压浅色背景，读不出来。
 */
enum class AppAppearance { System, Light, Dark }

/** true = 隐藏状态栏与手势条（从边缘滑动临时呼出）；false = 恢复显示 */
expect fun systemBarsImmersive(immersive: Boolean)

/**
 * 把生效中的应用外观报给平台，用于让系统栏文字配色跟上它（Android 由 enableEdgeToEdge
 * 按系统夜景自适应，暂不介入；iOS 由壳工程用 `preferredColorScheme` 落地）。
 */
expect fun systemBarsAppearance(appearance: AppAppearance)

/** 屏幕圆角半径（四角最大值，已换算 Dp）：表盘四角避让用；平台不支持/读不到为 0 */
expect fun screenCornerRadius(): Dp
