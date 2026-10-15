package io.github.lswlc33.maibms.monitor

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.lswlc33.maibms.AndroidApp
import io.github.lswlc33.maibms.data.Notifications
import io.github.lswlc33.maibms.data.Notifier

/**
 * Android 的通知实现（`Notifications.impl` 的注入体）。
 *
 * - **常驻监控**：需要时起 [BmsMonitorService]（前台服务保活 + 常驻通知），之后只更新那条通知；
 *   关掉时停服务、撤通知。
 * - **告警**：普通通知，两条固定 id（保护/告警、失联），同一件事更新而不是叠加。
 * - 权限：Android 13+ 的 `POST_NOTIFICATIONS` 在用户打开开关时申请一次；
 *   被拒时不报错——系统会静默丢弃通知，界面上开关仍在（用户可以自己去系统设置里给权限）。
 *
 * 线程：调用方可能在 Default 调度器（轮询协程）里，所以内部不碰界面状态；
 * 申请权限要回主线程，用 [android.app.Activity.runOnUiThread]。
 */
class AndroidNotifier(private val context: Context) : Notifier {

    /** 前台服务是否在跑（没有公开 API 可查，只能自己记）。进程被杀会一并复位 */
    @Volatile
    private var serviceRunning = false

    override fun updateMonitoring(text: String?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        BmsMonitorService.ensureChannels(context)
        if (text == null) {
            if (serviceRunning) {
                serviceRunning = false
                runCatching { context.stopService(Intent(context, BmsMonitorService::class.java)) }
            }
            nm.cancel(Notifications.ID_MONITOR)
            return
        }
        if (serviceRunning) {
            // 已在跑：直接用同 id 更新，**不能**再 startForegroundService——
            // Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException
            runCatching { nm.notify(Notifications.ID_MONITOR, BmsMonitorService.monitorNotification(context, text)) }
        } else {
            serviceRunning = true
            runCatching { context.startForegroundService(BmsMonitorService.intent(context, text)) }
                .onFailure {
                    serviceRunning = false
                    android.util.Log.w(BmsMonitorService.TAG, "启动后台监控服务失败：${it.message}")
                }
        }
    }

    override fun alert(id: Int, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        BmsMonitorService.ensureChannels(context)
        runCatching { nm.notify(id, BmsMonitorService.alertNotification(context, title, text)) }
    }

    override fun ensurePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = AndroidApp.activity ?: return
        activity.runOnUiThread {
            val granted = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                }
            }
        }
    }

    private companion object {
        const val REQ_NOTIFICATIONS = 1002
    }
}
