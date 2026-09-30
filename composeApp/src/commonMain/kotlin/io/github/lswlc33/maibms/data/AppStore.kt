package io.github.lswlc33.maibms.data

/**
 * 极简键值持久化：记住上次连接的设备与各设备密码（协议层无状态，这些必须落在本地）。
 * 平台在启动时注入实现（Android=SharedPreferences，桌面=用户目录文件），未注入时退化为内存。
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

private object MemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

object AppStore {
    var store: KeyValueStore = MemoryStore

    fun get(key: String): String? = runCatching { store.get(key) }.getOrNull()
    fun put(key: String, value: String?) = runCatching { store.put(key, value) }.let { }

    // ---- 上次连接的设备 ----
    var savedAddress: String?
        get() = get(KEY_ADDR)
        set(v) = put(KEY_ADDR, v)

    var savedDeviceName: String?
        get() = get(KEY_NAME)
        set(v) = put(KEY_NAME, v)

    // ---- 密码库（按设备地址分槽，格式：地址#等级=密码;...） ----

    fun loadPassword(address: String, level: Int): String? =
        get(KEY_PW)?.split(';')?.firstOrNull { it.startsWith("$address#$level=") }
            ?.substringAfter('=')

    fun savePassword(address: String, level: Int, password: String) {
        val key = "$address#$level"
        val kept = get(KEY_PW)?.split(';')?.filter { it.isNotBlank() && !it.startsWith("$key=") } ?: emptyList()
        put(KEY_PW, (kept + "$key=$password").joinToString(";"))
    }

    /** 删掉该级密码（校验失败说明存的是错的） */
    fun removePassword(address: String, level: Int) {
        val key = "$address#$level"
        val kept = get(KEY_PW)?.split(';')?.filter { it.isNotBlank() && !it.startsWith("$key=") } ?: emptyList()
        put(KEY_PW, kept.joinToString(";").ifBlank { null })
    }

    /** 是否允许「启动即自动重连」：用户主动断开后置 false，重新选设备后置 true */
    var autoReconnect: Boolean
        get() = get(KEY_AUTOCONN) != "0"
        set(v) = put(KEY_AUTOCONN, if (v) "1" else "0")

    /** 连接后自动升级到的目标等级（0 = 自动取已记住的最高可用级） */
    var autoUpgradeTarget: Int
        get() = get(KEY_AUTOUP)?.toIntOrNull() ?: 0
        set(v) = put(KEY_AUTOUP, v.toString())

    // ---- 界面偏好 ----
    /** "system" | "light" | "dark" */
    var themeMode: String
        get() = get(KEY_THEME) ?: "system"
        set(v) = put(KEY_THEME, v)

    private const val KEY_AUTOCONN = "device.autoReconnect"
    private const val KEY_AUTOUP = "device.autoUpgradeTarget"
    private const val KEY_THEME = "ui.theme"
    private const val KEY_ADDR = "device.address"
    private const val KEY_NAME = "device.name"
    private const val KEY_PW = "device.passwords"
}
