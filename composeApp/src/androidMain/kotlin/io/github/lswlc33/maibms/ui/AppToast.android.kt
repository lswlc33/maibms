package io.github.lswlc33.maibms.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.lswlc33.maibms.AndroidApp
import io.github.lswlc33.maibms.data.BmsLog

actual fun showSystemToast(text: String) {
    val ctx = AndroidApp.context
    if (ctx == null) {
        BmsLog.w("APP", "Toast 未显示（应用 context 尚未注入）：$text")
        return
    }
    // 调用方可能在任意线程：统一投到主线程再弹
    Handler(Looper.getMainLooper()).post {
        runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
            .onFailure { BmsLog.w("APP", "Toast 弹出失败：$it") }
    }
}
