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
        // 落盘：上次连接的设备 + 各设备密码（协议无状态，重启后要能自动重连）
        io.github.lswlc33.maibms.data.AppStore.store = object : io.github.lswlc33.maibms.data.KeyValueStore {
            private val sp = getSharedPreferences("antbms", MODE_PRIVATE)
            override fun get(key: String): String? = sp.getString(key, null)
            override fun put(key: String, value: String?) {
                sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }
        // 注入真实 BLE 传输（演示模式关闭时启用）
        runCatching {
            io.github.lswlc33.maibms.data.Bms.repository.setRealTransport(
                io.github.lswlc33.maibms.transport.AndroidBleTransport(applicationContext)
            )
        }
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
