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
        // 数据管线（持久化 + BLE 传输 + 自动重连）已在 MaibmsApp.onCreate 里装配并启动，
        // 与这里的界面创建并行；App() 里保留一次 start() 兜底（幂等，桌面端靠它挂收集器）
        requestBlePermissions()
        setContent { App() }
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
