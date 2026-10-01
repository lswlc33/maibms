package io.github.lswlc33.maibms.transport

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.github.lswlc33.maibms.AndroidApp

/**
 * API 31+ 要 BLUETOOTH_CONNECT（连接与读设备名都要它）；
 * API ≤30 只有扫描才要定位权限，按 MAC 直连靠安装期就有的 BLUETOOTH/BLUETOOTH_ADMIN，
 * 所以这里放行——不能因为用户没给定位权限就不自动重连（老机型上会平白断掉自动重连）。
 */
actual fun canAutoConnect(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val ctx = AndroidApp.context ?: return false
    return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
