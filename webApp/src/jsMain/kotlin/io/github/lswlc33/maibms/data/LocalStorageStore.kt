package io.github.lswlc33.maibms.data

/**
 * 网页端的键值持久化：`localStorage`。
 *
 * 与 Android 的 SharedPreferences 同角色——记住上次连接的设备、各设备密码、快照、
 * 界面偏好。注意它是**按源（origin）隔离**的：换域名/端口等于换一本新账，
 * 所以正式部署地址要固定，否则用户会"像换了台新手机"。
 *
 * 容量约 5MB（快照上限 20 张 + 配置缓存，每张几 KB~几十 KB，够用）；
 * 浏览器禁用存储（隐私模式）或写满时全部静默失败——与 Android 侧 runCatching 的兜底一致，
 * 存储坏了不能让界面跟着崩。
 */
class LocalStorageStore : KeyValueStore {
    override fun get(key: String): String? = jsLocalStorageGet(key)
    override fun put(key: String, value: String?) {
        if (value == null) jsLocalStorageRemove(key) else jsLocalStorageSet(key, value)
    }
}

/**
 * 三个原语都包在 try/catch 里：隐私模式下 `localStorage` 存在但**读写就抛**
 * （SecurityError），只在外面用 runCatching 兜一次就够——这里让异常冒到 [LocalStorageStore]，
 * 由它统一兜。故此处不再逐层 runCatching。
 */
private fun jsLocalStorageGet(key: String): String? = try {
    js("window.localStorage.getItem(key)")
} catch (_: Throwable) {
    null
}

private fun jsLocalStorageSet(key: String, value: String) {
    try {
        js("window.localStorage.setItem(key, value)")
    } catch (_: Throwable) {
        // 写满（QuotaExceededError）或隐私模式：静默，与 Android 侧 FlushFailed 一视同仁
    }
}

private fun jsLocalStorageRemove(key: String) {
    try {
        js("window.localStorage.removeItem(key)")
    } catch (_: Throwable) {
    }
}