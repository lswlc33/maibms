package io.github.lswlc33.maibms.transport

/**
 * 现在能不能直接发起 BLE 连接（决定冷启动要不要自动重连）。
 *
 * Android 12+ 连接设备需要运行时权限 BLUETOOTH_CONNECT；没有就先不连——
 * 把连接发起时机提到界面之前以后，这个检查必须前置，否则会在权限对话框还没点的时候
 * 就去碰蓝牙栈（SecurityException）。桌面端没有蓝牙后端，恒 true。
 */
expect fun canAutoConnect(): Boolean
