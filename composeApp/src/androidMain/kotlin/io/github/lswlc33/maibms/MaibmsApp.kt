package io.github.lswlc33.maibms

import android.app.Application
import android.content.Context
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.KeyValueStore
import io.github.lswlc33.maibms.transport.AndroidBleTransport
import io.github.lswlc33.maibms.transport.canAutoConnect

/** 进程级 ApplicationContext：权限检查与 Toast 都要用（由 [MaibmsApp] 注入） */
object AndroidApp {
    @Volatile var context: Context? = null
}

/**
 * 进程入口：持久化 + BLE 传输 + 「连上次设备」全部提到 Activity 之前。
 *
 * 原先这些都在 MainActivity.onCreate 里、自动重连还挂在 App() 的 LaunchedEffect 上，
 * 也就是要等界面组合完才开始连——对"打开就要看电池状态"来说白等了几百毫秒。
 * 现在建链与 Activity 创建、首帧渲染并行；App() 里的 start() 保留作桌面端兜底（幂等）。
 */
class MaibmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidApp.context = applicationContext
        // 落盘：上次连接的设备 + 各设备密码 + 快照（协议无状态，重启后要能自动重连）
        AppStore.store = object : KeyValueStore {
            private val sp = getSharedPreferences("antbms", MODE_PRIVATE)
            override fun get(key: String): String? = sp.getString(key, null)
            override fun put(key: String, value: String?) {
                sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }
        runCatching {
            Bms.repository.setRealTransport(AndroidBleTransport(applicationContext))
        }.onFailure { BmsLog.e("APP", "BLE 传输初始化失败：$it") }
        // 权限没给就先不连（连接在界面之前发起，权限对话框还没点就碰蓝牙栈会抛 SecurityException）；
        // 用户授权后从界面手动连接即可
        if (canAutoConnect()) {
            Bms.repository.start()
        } else {
            BmsLog.i("APP", "蓝牙权限未授予，暂不自动重连（界面授权后从扫描列表连接）")
        }
    }
}
