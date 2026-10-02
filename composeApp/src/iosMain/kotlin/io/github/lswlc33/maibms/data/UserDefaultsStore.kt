package io.github.lswlc33.maibms.data

import platform.Foundation.NSUserDefaults

/**
 * iOS 的键值落盘：NSUserDefaults（对应 Android 端的 SharedPreferences）。
 *
 * 存的内容与 Android 一致：上次连接的设备、各设备密码（明文——设置页已声明风险）、
 * 主题/日志级别/功率阶梯等偏好设置。将来要加密的话，替换成 Keychain 实现即可，
 * 上层只认 [KeyValueStore] 接口。
 */
internal class UserDefaultsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun put(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
    }
}
