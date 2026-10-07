package io.github.lswlc33.maibms

import android.app.Application
import android.content.Context
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.KeyValueStore
import io.github.lswlc33.maibms.transport.AndroidBleTransport
import io.github.lswlc33.maibms.transport.canAutoConnect
import java.io.File

/** 进程级 ApplicationContext：权限检查与 Toast 都要用（由 [MaibmsApp] 注入） */
object AndroidApp {
    @Volatile var context: Context? = null

    /** 当前前台 Activity：横屏锁定要设 requestedOrientation，Application context 不行 */
    @Volatile var activity: android.app.Activity? = null
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
            // 日志落盘：外部存储应用私有目录（用户可从文件管理器查看，卸载才删），
            // 启动即读回上次运行的日志（按天文件，只留最近 3 天）
            io.github.lswlc33.maibms.data.LogFileStore.setDir(
                File(getExternalFilesDir(null), "maibms/logs").absolutePath)
            io.github.lswlc33.maibms.data.BmsLog.restore()
            // 上次选择的日志级别与帧级开关也一起恢复（与设置页写入的 AppStore 对应）
            io.github.lswlc33.maibms.data.BmsLog.frameLogOn.value = AppStore.logFrameOn
            io.github.lswlc33.maibms.data.BmsLog.minLevel.value =
                io.github.lswlc33.maibms.data.BmsLog.Level.entries.firstOrNull { it.tag == AppStore.logMinLevel }
                    ?: io.github.lswlc33.maibms.data.BmsLog.Level.INFO
        }.onFailure { println("[ANTBMS/APP] E 日志文件初始化失败：$it") }
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
