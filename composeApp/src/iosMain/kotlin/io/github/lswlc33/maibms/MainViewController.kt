package io.github.lswlc33.maibms

import androidx.compose.ui.window.ComposeUIViewController
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.LogFileStore
import io.github.lswlc33.maibms.ui.App
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/**
 * iOS 入口：framework 暴露给 Xcode 壳工程的视图控制器工厂
 * （壳工程调用 `MainViewControllerKt.MainViewController()`）。
 *
 * 现阶段 iOS 是**编译目标**（Android 为第一交付平台）：本文件保证 framework 有可用入口，
 * 数据管线与 Android 一样在进入界面之前装配（日志目录、读回、KV 存储）。
 * iOS 的 KV 存储暂用内存实现（重启不保留设置）——接真机适配时换成 NSUserDefaults 即可。
 */
private var bootstrapped = false

private fun bootstrapOnce() {
    if (bootstrapped) return
    bootstrapped = true
    AppStore.store = object : io.github.lswlc33.maibms.data.KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }
    runCatching {
        // 沙盒 Application Support 下的 maibms-logs（用户可在「文件」App 取走）
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
        val dir = base?.let { "$it/maibms-logs" }
        if (dir != null) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                dir, withIntermediateDirectories = true, attributes = null, error = null
            )
            LogFileStore.setDir(dir)
            BmsLog.restore()
        }
    }
    BmsLog.frameLogOn.value = AppStore.logFrameOn
    BmsLog.minLevel.value =
        BmsLog.Level.entries.firstOrNull { it.tag == AppStore.logMinLevel } ?: BmsLog.Level.INFO
    BmsLog.i("APP", "应用启动（iOS）")
}

fun MainViewController(): UIViewController = ComposeUIViewController {
    bootstrapOnce()
    App()
}
