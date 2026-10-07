package io.github.lswlc33.maibms.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * iOS 沉浸与圆角需要壳工程（SwiftUI 外壳）配合 prefersStatusBarHidden /
 * prefersHomeIndicatorAutoHidden / displayCornerRadius，属后续真机适配；先空实现。
 */
actual fun systemBarsImmersive(immersive: Boolean) = Unit

actual fun screenCornerRadius(): Dp = 0.dp
