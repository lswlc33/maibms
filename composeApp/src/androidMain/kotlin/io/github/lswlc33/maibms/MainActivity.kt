package io.github.lswlc33.maibms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.lswlc33.maibms.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidApp.activity = this
        // 挖孔/刘海屏：允许内容延伸进 cutout 区（沉浸），具体避让交给 safeDrawing insets——
        // 不开 ALWAYS 的话横屏时系统不报告挖孔 inset，左侧摄像头区就会压住表盘
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        // 数据管线（持久化 + BLE 传输 + 自动重连）已在 MaibmsApp.onCreate 里装配并启动，
        // 与这里的界面创建并行；App() 里保留一次 start() 兜底（幂等，桌面端靠它挂收集器）
        requestBlePermissions()
        setContent { App() }
    }

    override fun onDestroy() {
        if (AndroidApp.activity === this) AndroidApp.activity = null
        super.onDestroy()
    }

    private fun requestBlePermissions() {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 31) listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) else listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1001)
        }
    }
}
