package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable

/**
 * 剪贴板写入（开发者页「复制全部日志」用）：Android 走 ClipboardManager，桌面走 AWT。
 * 返回是否写入成功，界面据此给出反馈——不要留一个点了没反应的按钮。
 */
@Composable
expect fun rememberClipboardWriter(): (String) -> Boolean
