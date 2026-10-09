package io.github.lswlc33.maibms.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    /** 更新渠道：stable=稳定版（正式 Release，默认）；preview=预览版（含 Prerelease） */
    var updateChannel: String
        get() = get(KEY_UPDATE_CHANNEL) ?: "stable"
        set(v) = put(KEY_UPDATE_CHANNEL, v)

    // ---- 配置缓存（≠ 快照）：自动重连设备「上次成功连接」的设置项，仅供未连接时只读展示 ----

    /**
     * 每台设备一份配置缓存（设置项 + 身份区，不含任何实时数据）。
     * 配置项不是实时数据、不常变动，缓存它让未连接时配置页/关于页仍有内容可看。
     */
    @Serializable
    data class ParamsCache(
        val savedAt: Long,
        val params: Map<Int, Int>,
        val identity: Map<String, String>,
    )

    fun saveParamsCache(address: String, cache: ParamsCache) {
        put(paramsCacheKey(address), jsonForCache.encodeToString(ParamsCache.serializer(), cache))
    }

    fun loadParamsCache(address: String): ParamsCache? =
        get(paramsCacheKey(address))?.let { text ->
            runCatching { jsonForCache.decodeFromString(ParamsCache.serializer(), text) }.getOrNull()
        }

    fun deleteParamsCache(address: String) = put(paramsCacheKey(address), null)

    private val jsonForCache = Json { ignoreUnknownKeys = true }
    private fun paramsCacheKey(address: String) = "cache.params.$address"

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

    // ---- 开发者日志偏好（BmsLog 启动时读回，设置页修改即落盘） ----

    /** 帧级日志（TX/RX 报文）开关 */
    var logFrameOn: Boolean
        get() = get(KEY_LOG_FRAME) == "1"
        set(v) = put(KEY_LOG_FRAME, if (v) "1" else "0")

    /** 最低显示级别："D" / "I" / "W" / "E"（非法值回退 INFO） */
    var logMinLevel: String
        get() = get(KEY_LOG_LEVEL) ?: "I"
        set(v) = put(KEY_LOG_LEVEL, v)

    private const val KEY_LOG_FRAME = "log.frameOn"
    private const val KEY_LOG_LEVEL = "log.minLevel"

    // ---- 功率换挡进度条（仪表盘卡3，纯展示的情绪价值） ----

    /** 换挡进度条开关（点击卡3切换，默认开） */
    var powerGaugeEnabled: Boolean
        get() = get(KEY_POWER_GAUGE) != "0"
        set(v) = put(KEY_POWER_GAUGE, if (v) "1" else "0")

    /**
     * 功率阶梯（W）：第 1 个是**副档位上限**（负数，充电/动能回收共用，固定量程），
     * 后三个是放电一/二/三档上限（正数升序）。默认 -1500/1000/3000/5000——开箱即有表，
     * 「不设阶梯」不再是合法状态（双击才是关表的唯一开关）。
     * 读取时规范化（见 [normalizePowerStages]）：老数据（如只有 1000/2000/3000 三个正档）
     * 也能平滑升级——补上默认副档位，正档不足三个用默认值补足。
     */
    var powerStagesW: List<Int>
        get() = normalizePowerStages(
            get(KEY_POWER_STAGES)?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        )
        set(v) = put(KEY_POWER_STAGES, normalizePowerStages(v).joinToString(","))

    private const val KEY_POWER_GAUGE = "ui.powerGauge"
    private const val KEY_POWER_STAGES = "ui.powerStagesW"

    // ---- 横屏表盘 ----

    /** 表盘功率盘显示单位：true=kW，false=W（默认，即「瓦」） */
    var clusterPowerKw: Boolean
        get() = get(KEY_CLUSTER_POWER_KW) == "1"
        set(v) = put(KEY_CLUSTER_POWER_KW, if (v) "1" else "0")

    private const val KEY_CLUSTER_POWER_KW = "cluster.powerKw"

    /**
     * 轮询间隔（ms）：三档预设 [POLL_INTERVAL_PRESETS_MS]（900 / 600 / 300），默认 600。
     * 900 = 更稳更省电（弱信号场景）；300 = 数据最跟手（弱信号下可能丢帧）。
     * 读写都吸附到最近档位；老版本开发者页存过任意值（旧键 `test.pollIntervalMs`），
     * 读取时同样吸附迁移，无需手动清理。
     */
    var pollIntervalMs: Int
        get() {
            val raw = get(KEY_POLL_INTERVAL)?.toIntOrNull()
                ?: get(KEY_POLL_INTERVAL_LEGACY)?.toIntOrNull()
            return raw?.let { nearestPollPreset(it) } ?: POLL_INTERVAL_DEFAULT_MS
        }
        set(v) = put(KEY_POLL_INTERVAL, nearestPollPreset(v).toString())

    private const val KEY_POLL_INTERVAL = "device.pollIntervalMs"
    private const val KEY_POLL_INTERVAL_LEGACY = "test.pollIntervalMs"   // 旧测试项的键：只读迁移

    private const val KEY_AUTOCONN = "device.autoReconnect"
    private const val KEY_AUTOUP = "device.autoUpgradeTarget"
    internal const val KEY_PROFILES_V1 = "device.profiles.v1"   // 历史设备档案 JSON（DeviceProfiles 读写）
    internal const val KEY_PW = "device.passwords"              // 旧格式密码串：仅供 DeviceProfiles 迁移读取
    private const val KEY_AUTOCONNECT = "device.autoConnect"    // 自动重连显式目标（空=上次连接）
    private const val KEY_UPDATE_CHANNEL = "ui.updateChannel"   // 更新渠道：stable / preview
    private const val KEY_SNAP_ENABLED = "snapshot.enabled"
    private const val KEY_SNAP_INDEX = "snapshot.index"
    private const val KEY_THEME = "ui.theme"
    private const val KEY_ADDR = "device.address"
    private const val KEY_NAME = "device.name"
}

/* ---------- 功率阶梯（换挡进度条）的默认值与规范化 ---------- */

/** 功率阶梯默认值：副档位（充电/动能回收共用，负值）+ 放电一/二/三档上限（正数升序） */
val DEFAULT_POWER_STAGES = listOf(-1500, 1000, 3000, 5000)

/**
 * 把任意输入规范化为 `[副档位(负), 一档, 二档, 三档]`：
 * - 副档位取第一个负值，没有则用默认 -1500（老数据平滑升级）；
 * - 正档去重升序取前 3 个，不足三个用默认 1000/3000/5000 补足（不与已有值重复）。
 * 幂等：对规范化结果再跑一次不变。
 */
fun normalizePowerStages(raw: List<Int>): List<Int> {
    val sub = raw.firstOrNull { it < 0 } ?: DEFAULT_POWER_STAGES.first()
    val positives = raw.filter { it > 0 }.distinct().sorted().take(3).toMutableList()
    for (d in DEFAULT_POWER_STAGES.drop(1)) {
        if (positives.size >= 3) break
        if (d !in positives) positives.add(d)
    }
    positives.sort()
    return listOf(sub) + positives
}

/* ---------- 轮询间隔的三档预设 ---------- */

/** 轮询间隔预设（ms）：顺序即界面展示顺序（900 更稳 / 600 默认 / 300 最跟手） */
val POLL_INTERVAL_PRESETS_MS = listOf(900, 600, 300)

/** 默认轮询间隔（ms）：600——数据跟手与链路稳定的平衡点 */
const val POLL_INTERVAL_DEFAULT_MS = 600

/** 任意值吸附到最近的预设档位（并列取靠前的，如 450 → 600）；空列表兜底回默认 */
internal fun nearestPollPreset(v: Int): Int =
    POLL_INTERVAL_PRESETS_MS.minByOrNull { kotlin.math.abs(it - v) } ?: POLL_INTERVAL_DEFAULT_MS
