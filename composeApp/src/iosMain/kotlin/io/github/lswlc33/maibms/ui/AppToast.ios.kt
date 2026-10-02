package io.github.lswlc33.maibms.ui

/**
 * iOS 的短提示：没有 Android 的 Toast，这里打到 stdout（Xcode 控制台可见），
 * 与桌面端同款处理——提醒不弹窗、不打断，操作反馈主要仍由界面自身表达。
 * 将来接原生可用 UIAlertController 的短暂展示或自绘 overlay。
 */
actual fun showSystemToast(text: String) {
    println("[TOAST] $text")
}
