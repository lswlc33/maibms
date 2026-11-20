package io.github.lswlc33.maibms.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.lswlc33.maibms.MainActivity
import io.github.lswlc33.maibms.data.MonitorNotification

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
        val content = MonitorNotification(
            title = intent?.getStringExtra(EXTRA_TITLE) ?: "等待数据",
            text = intent?.getStringExtra(EXTRA_TEXT) ?: "等待设备数据",
            deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME).orEmpty(),
            soc = intent?.getIntExtra(EXTRA_SOC, -1)?.takeIf { it in 0..100 },
        )
        ensureChannels(this)
        return try {
            postMonitorNotification(this, content) {
                startForeground(io.github.lswlc33.maibms.data.Notifications.ID_MONITOR, it)
            }
            running = true
            starting = false
            publishStatus(this)
            onStarted?.invoke()
            START_NOT_STICKY
        } catch (e: Exception) {
            // 极少数机型/权限策略下 startForeground 会抛（如通知被完全禁用）：
            // 不能因为一条通知把监控整个搞崩，让用户看得见原因更重要
            android.util.Log.w(TAG, "startForeground 失败：${e.message}")
            running = false
            starting = false
            publishStatus(this, "后台监控启动失败，请回到前台重试")
            onFailed?.invoke()
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        running = false
        starting = false
        publishStatus(this, io.github.lswlc33.maibms.data.Notifications.monitoring.value.error)
        super.onDestroy()
        onStopped?.invoke()
    }

    companion object {
        @Volatile
        internal var running = false
        @Volatile
        internal var starting = false
        internal var onStarted: (() -> Unit)? = null
        internal var onStopped: (() -> Unit)? = null
        internal var onFailed: (() -> Unit)? = null

        internal fun publishStatus(context: Context, error: String? = null) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val channelAllowed = nm?.getNotificationChannel(CHANNEL_MONITOR)?.importance != NotificationManager.IMPORTANCE_NONE
            io.github.lswlc33.maibms.data.Notifications.monitoring.value =
                io.github.lswlc33.maibms.data.MonitoringState(
                    running = running,
                    notificationAllowed = permission && nm?.areNotificationsEnabled() == true && channelAllowed,
                    error = error,
                )
        }

        const val TAG = "ANTBMS"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DEVICE_NAME = "deviceName"
        private const val EXTRA_SOC = "soc"

        fun intent(context: Context, content: MonitorNotification): Intent =
            Intent(context, BmsMonitorService::class.java)
                .putExtra(EXTRA_TITLE, content.title)
                .putExtra(EXTRA_TEXT, content.text)
                .putExtra(EXTRA_DEVICE_NAME, content.deviceName)
                .putExtra(EXTRA_SOC, content.soc ?: -1)

        /**
         * 两个渠道：监控（低优先级、无声、常驻）与告警（默认优先级、有提示音）。
         * 分开建是因为它们的气质完全不同——常驻那条每分钟都在，不能响。
         */
        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITOR, "后台监控", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "保持设备连接与轮询，实时显示充放电、电量、剩余容量、电压、电流、功率和温度" },
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

        /** 同一条前台服务通知申请实时活动；构建或投递失败后从新 Builder 回退，避免半写属性。 */
        internal fun postMonitorNotification(
            context: Context,
            content: MonitorNotification,
            post: (Notification) -> Unit,
        ) {
            if (Build.VERSION.SDK_INT >= 36 && LiveUpdate.allowed(context)) {
                try {
                    val builder = monitorBuilder(context, content)
                    LiveUpdate.promote(builder, content.soc)
                    post(builder.build())
                    return
                } catch (error: Throwable) {
                    android.util.Log.w(TAG, "实时活动不可用，回退普通监控通知", error)
                }
            }
            post(monitorBuilder(context, content).build())
        }

        private fun monitorBuilder(context: Context, content: MonitorNotification): Notification.Builder =
            Notification.Builder(context, CHANNEL_MONITOR)
                .setContentTitle(content.title)
                .setContentText(content.text.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(content.text))
                .setSubText(content.deviceName.takeIf { it.isNotBlank() })
                .setSmallIcon(io.github.lswlc33.maibms.R.drawable.ic_notify_monitor)
                .setContentIntent(contentIntent(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)

        /**
         * 沿用 dashboard 的原生提升资格检查；36+ 才访问新 API，旧系统不加载新符号。
         * 使用官方公开的通知 extras（与 setRequestPromotedOngoing/setShortCriticalText 等价），
         * 保留项目 compileSdk 35，不为这一个能力升级整套 AGP/Kotlin 构建链。
         */
        private object LiveUpdate {
            private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
            private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
            private val canPostPromotedNotifications by lazy {
                runCatching { NotificationManager::class.java.getMethod("canPostPromotedNotifications") }.getOrNull()
            }

            fun allowed(context: Context): Boolean = try {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager != null && canPostPromotedNotifications?.invoke(manager) == true
            } catch (_: Throwable) {
                false
            }

            fun promote(builder: Notification.Builder, soc: Int?) {
                builder.addExtras(android.os.Bundle().apply {
                    putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                    soc?.takeIf { it in 0..100 }?.let { putString(EXTRA_SHORT_CRITICAL_TEXT, "$it%") }
                })
            }
        }

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
