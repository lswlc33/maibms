package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable

/**
 * 系统返回键接管：Android 用 BackHandler 走 App 内部返回栈，
 * 否则在二级页按返回会直接退出 App（真机实测踩到）。
 * 桌面端没有系统返回键，空实现。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
