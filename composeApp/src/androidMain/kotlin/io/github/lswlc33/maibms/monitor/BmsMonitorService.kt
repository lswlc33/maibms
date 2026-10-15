package io.github.lswlc33.maibms.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.lswlc33.maibms.MainActivity

/**
 * 后台监控的前台服务：**只做「保活 + 常驻通知」**。
 *
 * BLE 连接与轮询仍归 `Application` 作用域的 `BmsRepository`（进程活着它们就活着），
 * 所以这里不需要绑定、不需要传数据——把已有的架构硬搬进 Service 只会多一层状态同步。
 *
 * 没有它的话：退到后台既没有界面也没有通知，进程随时可能被回收，
 * "后台监控"就是一句空话。有了它，用户也看得见"现在正在监控"（通知栏那条）。
 *
 * 服务类型 `connectedDevice`（Android 14+ 要求声明）：本服务确实是为维持与已配对设备的连接而存在。
 */
class BmsMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "监控中"
        ensureChannels(this)
        return try {
            startForeground(io.github.lswlc33.maibms.data.Notifications.ID_MONITOR, monitorNotification(this, text))
            START_NOT_STICKY
        } catch (e: Exception) {
            // 极少数机型/权限策略下 startForeground 会抛（如通知被完全禁用）：
            // 不能因为一条通知把监控整个搞崩，让用户看得见原因更重要
            android.util.Log.w(TAG, "startForeground 失败：${e.message}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    companion object {
        const val TAG = "ANTBMS"
        const val EXTRA_TEXT = "text"

        fun intent(context: Context, text: String): Intent =
            Intent(context, BmsMonitorService::class.java).putExtra(EXTRA_TEXT, text)

        /**
         * 两个渠道：监控（低优先级、无声、常驻）与告警（默认优先级、有提示音）。
         * 分开建是因为它们的气质完全不同——常驻那条每分钟都在，不能响。
         */
        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITOR, "后台监控", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "保持与保护板的连接并轮询，常驻显示当前电量" },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT, "电池告警", NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "保护/告警新触发、以及设备失联时提醒" },
            )
        }

        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERT = "alert"

        /** 点通知回到 App（单 Activity，直接拉起主界面） */
        private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun monitorNotification(context: Context, text: String): Notification =
            Notification.Builder(context, CHANNEL_MONITOR)
                .setContentTitle("麻衣 BMS · 后台监控中")
                .setContentText(text)
                .setSmallIcon(io.github.lswlc33.maibms.R.drawable.ic_notify_monitor)
                .setContentIntent(contentIntent(context))
                .setOngoing(true)
                .setShowWhen(false)
                .build()

        fun alertNotification(context: Context, title: String, text: String): Notification =
            Notification.Builder(context, CHANNEL_ALERT)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(io.github.lswlc33.maibms.R.drawable.ic_notify_monitor)
                .setContentIntent(contentIntent(context))
                .setAutoCancel(true)
                .build()
    }
}
