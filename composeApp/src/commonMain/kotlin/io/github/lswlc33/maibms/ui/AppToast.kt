package io.github.lswlc33.maibms.ui

/**
 * 系统 Toast（一次性短提示）。Android 是真 Toast，桌面端只打到 stdout——
 * 桌面端与离屏截图工具因此不会被弹窗污染。
 */
expect fun showSystemToast(text: String)
