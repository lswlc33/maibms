package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

/** iOS：写系统剪贴板（开发者页「复制全部日志」） */
@Composable
actual fun rememberClipboardWriter(): (String) -> Boolean = remember {
    { text ->
        runCatching {
            UIPasteboard.generalPasteboard.string = text
            true
        }.getOrDefault(false)
    }
}
