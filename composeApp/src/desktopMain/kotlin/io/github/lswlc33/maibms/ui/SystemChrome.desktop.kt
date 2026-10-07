package io.github.lswlc33.maibms.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 桌面窗口没有系统栏与屏幕圆角概念 */
actual fun systemBarsImmersive(immersive: Boolean) = Unit

actual fun screenCornerRadius(): Dp = 0.dp
