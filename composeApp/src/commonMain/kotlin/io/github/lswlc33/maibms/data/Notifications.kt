package io.github.lswlc33.maibms.data

import kotlin.concurrent.Volatile

/**
 * 通知出口（可注入）。
 *
 * 与 [AppStore.store] / [LogFileStore.setDir] / `Bms.repository.setRealTransport` 同一套做法：
 * **平台侧把自己的实现塞进来**，公共代码只调接口。没被注入 = 空实现，
 * 于是桌面端与 iOS 不需要写任何平台代码（iOS 在 Windows 上编译不了，写了也无法验证）。
 *
 * 放在 `data/` 而不是 `ui/`：调用方是数据层（轮询/告警判定），
 * 界面只读 [Notifications.supported] 与调 [Notifications.ensurePermission]。
 *
 * 注入点：Android 的 `MaibmsApp.onCreate`。
 */
interface Notifier {
    /**
     * 常驻监控通知：非 null 表示"正在后台监控"（Android 会起前台服务保活 + 常驻通知），
     * null 表示停止监控。
     */
    fun updateMonitoring(text: String?)

    /** 一次性告警通知；[id] 相同的通知会覆盖（避免同一件事刷屏） */
    fun alert(id: Int, title: String, text: String)

    /**
     * 确保有发通知的权限（Android 13+ 的 POST_NOTIFICATIONS）。**非阻塞**：
     * 弹系统授权框就返回，界面不等结果；用户拒绝后由系统层面静默丢弃通知。
     */
    fun ensurePermission()
}

/** 通知出口注册处：默认空实现（桌面/iOS/单测都走它） */
object Notifications {
    @Volatile
    var impl: Notifier? = null

    /** 当前平台是否支持后台监控与通知 */
    val supported: Boolean get() = impl != null

    fun updateMonitoring(text: String?) {
        impl?.updateMonitoring(text)
    }

    fun alert(id: Int, title: String, text: String) {
        impl?.alert(id, title, text)
    }

    fun ensurePermission() {
        impl?.ensurePermission()
    }

    /** 常驻监控通知 id */
    const val ID_MONITOR = 1001

    /** 保护/告警通知 id（同一件事只有一条，更新而不是叠加） */
    const val ID_ALERT = 1002

    /** 链路通知 id（失联） */
    const val ID_ALERT_LINK = 1003
}
