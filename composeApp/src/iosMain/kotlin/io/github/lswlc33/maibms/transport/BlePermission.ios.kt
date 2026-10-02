package io.github.lswlc33.maibms.transport

/**
 * iOS：CoreBluetooth 的权限由系统在首次访问 CBCentralManager 时自动弹窗，
 * 没有可预检的 API，因此直接放行。当前 iOS 目标只做编译验证（无 BLE 传输实现），
 * 自动重连是空转；将来接入 CoreBluetooth 时权限提示仍走系统弹窗，这里保持不变即可。
 */
actual fun canAutoConnect(): Boolean = true
