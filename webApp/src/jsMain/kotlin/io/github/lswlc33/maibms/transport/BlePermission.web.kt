package io.github.lswlc33.maibms.transport

/**
 * 网页端：恒 true。
 *
 * 这个 expect 存在的理由是 Android 12+ 的运行时权限（BLUETOOTH_CONNECT）——没有它去碰蓝牙栈
 * 会抛 SecurityException。Web Bluetooth 没有这一层：授权形态是"用户点一下【选择设备】、
 * 在浏览器列表里挑一台"，权限与连接动作天然同时发生，所以不需要前置检查。
 */
actual fun canAutoConnect(): Boolean = true