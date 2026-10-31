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

    private val lifecycle = io.github.lswlc33.maibms.data.MonitorLifecycle()

    init {
        BmsMonitorService.onStarted = { serviceStarted() }
        BmsMonitorService.onStopped = { serviceStopped() }
        BmsMonitorService.onFailed = { serviceFailed() }
    }

    @Synchronized
    override fun updateMonitoring(text: String?) {
        perform(lifecycle.request(text))
    }

    @Synchronized
    private fun serviceStarted() { perform(lifecycle.started()) }

    @Synchronized
    private fun serviceStopped() { perform(lifecycle.stopped()) }

    @Synchronized
    private fun serviceFailed() { lifecycle.failed() }

    private fun perform(action: io.github.lswlc33.maibms.data.MonitorLifecycle.Action) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        BmsMonitorService.ensureChannels(context)
        when (action) {
            is io.github.lswlc33.maibms.data.MonitorLifecycle.Action.Start -> {
                BmsMonitorService.starting = true
                runCatching { context.startForegroundService(BmsMonitorService.intent(context, action.text)) }
                    .onFailure {
                        BmsMonitorService.starting = false
                        lifecycle.failed()
                        BmsMonitorService.publishStatus(context, "后台监控启动失败，请回到前台重试")
                        android.util.Log.w(BmsMonitorService.TAG, "启动后台监控服务失败：${it.message}")
                    }
            }
            is io.github.lswlc33.maibms.data.MonitorLifecycle.Action.Update -> {
                runCatching { nm.notify(Notifications.ID_MONITOR, BmsMonitorService.monitorNotification(context, action.text)) }
                    .onFailure { BmsMonitorService.publishStatus(context, "通知更新失败，请检查系统通知权限") }
                    .onSuccess { BmsMonitorService.publishStatus(context) }
            }
            io.github.lswlc33.maibms.data.MonitorLifecycle.Action.Stop -> {
                runCatching { context.stopService(Intent(context, BmsMonitorService::class.java)) }
                    .onSuccess {
                        if (!it && !BmsMonitorService.starting && !BmsMonitorService.running) {
                            perform(lifecycle.stopped())
                        }
                    }
                    .onFailure {
                        lifecycle.failed()
                        BmsMonitorService.publishStatus(context, "后台监控停止未确认，请回到前台检查")
                    }
                nm.cancel(Notifications.ID_MONITOR)
                BmsMonitorService.publishStatus(context, Notifications.monitoring.value.error)
            }
            io.github.lswlc33.maibms.data.MonitorLifecycle.Action.None -> Unit
        }
    }

    @Synchronized
    override fun refreshStatus() {
        BmsMonitorService.ensureChannels(context)
        BmsMonitorService.publishStatus(context, Notifications.monitoring.value.error)
        if (io.github.lswlc33.maibms.data.AppForeground.active.value) {
            lifecycle.requestedText?.let { updateMonitoring(it) }
        }
    }

    override fun alert(id: Int, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        BmsMonitorService.ensureChannels(context)
        runCatching { nm.notify(id, BmsMonitorService.alertNotification(context, title, text)) }
    }

    override fun ensurePermission() {
        refreshStatus()
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
