package io.github.lswlc33.maibms

import androidx.compose.ui.window.ComposeUIViewController
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.LogFileStore
import io.github.lswlc33.maibms.data.iosAppSupportDir
import io.github.lswlc33.maibms.transport.IosBleTransport
import io.github.lswlc33.maibms.ui.App
import platform.UIKit.UIViewController

/**
 * iOS 入口：framework 暴露给 Xcode 壳工程的视图控制器工厂
 * （壳工程调用 `MainViewControllerKt.MainViewController()`）。
 *
 * 数据管线与 Android 一样在进入界面之前装配：日志目录、日志读回、KV 存储、BLE 传输。
 * iOS 的 KV 存储暂用内存实现（重启不保留设置）——接真机适配时换成 NSUserDefaults。
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
        // 沙盒 Application Support 下的 maibms-logs（okio 建目录与写文件）
        iosAppSupportDir()?.let { base ->
            LogFileStore.setDir("$base/maibms-logs")
            BmsLog.restore()
        }
    }
    BmsLog.frameLogOn.value = AppStore.logFrameOn
    BmsLog.minLevel.value =
        BmsLog.Level.entries.firstOrNull { it.tag == AppStore.logMinLevel } ?: BmsLog.Level.INFO
    BmsLog.i("APP", "应用启动（iOS）")
    // BLE 传输：CoreBluetooth 实现；首次访问会触发系统蓝牙权限弹窗
    runCatching {
        Bms.repository.setRealTransport(IosBleTransport())
    }.onFailure { BmsLog.e("APP", "BLE 传输初始化失败：$it") }
}

fun MainViewController(): UIViewController = ComposeUIViewController {
    bootstrapOnce()
    App()
}
