package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable

/**
 * iOS 没有系统返回键（返回手势由 UIKit 的导航栏/手势负责，Compose Multiplatform
 * 在 iOS 上的返回手势尚未接入本应用的自有返回栈），因此这里是空实现。
 * 二级页内的返回按钮照常工作；将来自定义返回手势时在此接入。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
