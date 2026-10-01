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

    // ---- 密码库（跟随历史设备档案存储，见 DeviceProfiles；旧复合格式由迁移接管） ----

    fun loadPassword(address: String, level: Int): String? =
        DeviceProfiles.find(address)?.passwords?.get(level)

    fun savePassword(address: String, level: Int, password: String) {
        DeviceProfiles.setPassword(address, level, password)
    }

    /** 删掉该级密码（校验失败说明存的是错的） */
    fun removePassword(address: String, level: Int) {
        DeviceProfiles.removePassword(address, level)
    }

    /** 自动重连目标：null/空 = 上次连接的设备；指定设备被删除后由消费方回退默认 */
    var autoConnectAddress: String?
        get() = get(KEY_AUTOCONNECT)?.takeIf { it.isNotBlank() }
        set(v) = put(KEY_AUTOCONNECT, v?.takeIf { it.isNotBlank() })

    /** 是否允许「启动即自动重连」：用户主动断开后置 false，重新选设备后置 true */
    var autoReconnect: Boolean
        get() = get(KEY_AUTOCONN) != "0"
        set(v) = put(KEY_AUTOCONN, if (v) "1" else "0")

    /** 连接后自动升级到的目标等级（0 = 自动取已记住的最高可用级） */
    var autoUpgradeTarget: Int
        get() = get(KEY_AUTOUP)?.toIntOrNull() ?: 0
        set(v) = put(KEY_AUTOUP, v.toString())

    // ---- 数据快照（JSON 全文存 KV；索引行管理列表，新在前，上限自动滚动） ----

    /** 连接完成全量同步后是否自动记录快照（默认开） */
    var snapshotEnabled: Boolean
        get() = get(KEY_SNAP_ENABLED) != "0"
        set(v) = put(KEY_SNAP_ENABLED, if (v) "1" else "0")

    /** 已保存快照的 id 列表（epoch ms），新的在前 */
    fun snapshotIds(): List<Long> =
        get(KEY_SNAP_INDEX)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    fun loadSnapshotJson(id: Long): String? = get(snapKey(id))

    /** 写入一张快照并维护索引；超过 [SNAPSHOT_MAX] 张时淘汰最旧的 */
    fun saveSnapshotJson(id: Long, json: String) {
        put(snapKey(id), json)
        val kept = snapshotIds().filterNot { it == id }
        val merged = (listOf(id) + kept).take(SNAPSHOT_MAX)
        // 被滚出上限的旧快照连同内容一起删掉，避免残留孤儿 JSON
        snapshotIds().drop(SNAPSHOT_MAX - 1).forEach { put(snapKey(it), null) }
        put(KEY_SNAP_INDEX, merged.joinToString(","))
    }

    fun deleteSnapshot(id: Long) {
        put(snapKey(id), null)
        put(KEY_SNAP_INDEX, snapshotIds().filterNot { it == id }.joinToString(",").ifBlank { null })
    }

    fun clearSnapshots() {
        snapshotIds().forEach { put(snapKey(it), null) }
        put(KEY_SNAP_INDEX, null)
    }

    /** 全部已存快照（解码失败的残条跳过）；级联删除与快照列表页共用 */
    fun loadSnapshots(): List<BmsSnapshot> =
        snapshotIds().mapNotNull { id -> loadSnapshotJson(id)?.let { SnapshotCodec.decode(it) } }

    /** 删除某设备名下的全部快照（历史设备删除时级联调用） */
    fun deleteSnapshotsForDevice(address: String) {
        loadSnapshots().filter { it.deviceAddress == address }.forEach { deleteSnapshot(it.id) }
    }

    private fun snapKey(id: Long) = "snapshot.$id"

    private const val SNAPSHOT_MAX = 20

    // ---- 界面偏好 ----
    /** "system" | "light" | "dark" */
    var themeMode: String
        get() = get(KEY_THEME) ?: "system"
        set(v) = put(KEY_THEME, v)

    // ---- 功率换挡进度条（仪表盘卡3，纯展示的情绪价值） ----

    /** 换挡进度条开关（点击卡3切换，默认开） */
    var powerGaugeEnabled: Boolean
        get() = get(KEY_POWER_GAUGE) != "0"
        set(v) = put(KEY_POWER_GAUGE, if (v) "1" else "0")

    /**
     * 功率阶梯（W），逗号分隔，1~3 个：第一个必填，后两个可选；不填 = 不设阶梯。
     * 形似变速箱换挡：功率走满第 1 档后进入第 2 档，以此类推。读取时强制升序，
     * 保证换挡进度条的数学与输入顺序无关。
     */
    var powerStagesW: List<Int>
        get() = get(KEY_POWER_STAGES)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }?.sorted()?.take(3) ?: emptyList()
        set(v) = put(KEY_POWER_STAGES, v.filter { it > 0 }.take(3).joinToString(",").ifBlank { null })

    private const val KEY_POWER_GAUGE = "ui.powerGauge"
    private const val KEY_POWER_STAGES = "ui.powerStagesW"

    private const val KEY_AUTOCONN = "device.autoReconnect"
    private const val KEY_AUTOUP = "device.autoUpgradeTarget"
    internal const val KEY_PROFILES_V1 = "device.profiles.v1"   // 历史设备档案 JSON（DeviceProfiles 读写）
    internal const val KEY_PW = "device.passwords"              // 旧格式密码串：仅供 DeviceProfiles 迁移读取
    private const val KEY_AUTOCONNECT = "device.autoConnect"    // 自动重连显式目标（空=上次连接）
    private const val KEY_SNAP_ENABLED = "snapshot.enabled"
    private const val KEY_SNAP_INDEX = "snapshot.index"
    private const val KEY_THEME = "ui.theme"
    private const val KEY_ADDR = "device.address"
    private const val KEY_NAME = "device.name"
}
