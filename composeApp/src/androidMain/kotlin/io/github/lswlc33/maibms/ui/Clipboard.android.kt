package io.github.lswlc33.maibms.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberClipboardWriter(): (String) -> Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        { text ->
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) false
            else runCatching {
                cm.setPrimaryClip(ClipData.newPlainText("antbms-log", text))
                true
            }.getOrDefault(false)
        }
    }
}
