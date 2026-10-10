package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 历史连接设备档案：一台连过的设备一条，密码跟随设备走。
 *
 * - `address` 是主键（BLE MAC）；列表只能通过「连接设备」自动产生，没有手动添加入口；
 * - 显示名优先级：alias（用户备注）> name（最近广播名）；
 * - 密码沿用明文存储的现状（设置 → 权限与密码 页脚已声明）。
 */
@Serializable
data class DeviceProfile(
    val address: String,
    val name: String = "",
    val alias: String? = null,
    /** 最近一次连接（建链成功）时刻，epoch ms；列表按它倒序展示、超限淘汰 */
    val lastConnectedAt: Long = 0L,
    val passwords: Map<Int, String> = emptyMap(),
    /** 设备家族（按广播名判定）；老档案没有此字段时为 Unknown（回退按保护板处理） */
    val family: DeviceFamily = DeviceFamily.Unknown,
) {
    /** 展示名：备注优先，其次广播名，都没有给地址兜底 */
    val displayName: String
        get() = alias?.takeIf { it.isNotBlank() }
            ?: name.trim().takeIf { it.isNotEmpty() }
            ?: address
}

/**
 * 历史设备档案库。整表一个 JSON 存在 KV（数量小、写入原子，不会出现索引与内容不一致）；
 * 首次访问时把旧版散存的 `device.passwords`（地址#等级=密码;…）迁移进来并清掉旧 key。
 */
object DeviceProfiles {

    /** 上限：超出按 lastConnectedAt 淘汰最旧（连同其密码） */
    const val MAX = 20

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 全部读写共用一把锁：KV 的读-改-写（upsert/remove/rename…）与迁移都必须串行，
     * 否则 auth 线程（Default 调度器）与 UI 线程并发改档案会互相覆盖丢更新。
     */
    private val lock = PlatformLock()

    @Volatile private var migrated = false

    /** 测试专用：复位迁移标志与整表（测试各用例间 KV 是共享的 MemoryStore） */
    internal fun resetForTest() {
        withLock(lock) {
            migrated = false
            AppStore.put(AppStore.KEY_PROFILES_V1, null)
            AppStore.put(AppStore.KEY_PW, null)
            AppStore.autoConnectAddress = null
        }
    }

    fun all(): List<DeviceProfile> = withLock(lock) {
        migrateOnce()
        AppStore.get(AppStore.KEY_PROFILES_V1)?.let { text ->
            runCatching { json.decodeFromString(ListSerializer(DeviceProfile.serializer()), text) }.getOrNull()
        } ?: emptyList()
    }

    fun find(address: String?): DeviceProfile? = address?.let { addr -> all().firstOrNull { it.address == addr } }

    /** 新增或更新（按 address 合并），并滚动淘汰超限的最旧档案 */
    fun upsert(profile: DeviceProfile) = withLock(lock) {
        val merged = allLocked().filterNot { it.address == profile.address } + profile
        save(merged.sortedByDescending { it.lastConnectedAt }.take(MAX))
    }

    /** 仅刷新最近连接时间（建链成功时调用，不动名称/密码/备注） */
    fun touch(address: String?, at: Long = epochMillisNow()) {
        withLock(lock) {
            val p = allLocked().firstOrNull { it.address == address } ?: return
            if (p.lastConnectedAt == at) return
            val merged = allLocked().filterNot { it.address == address } + p.copy(lastConnectedAt = at)
            save(merged.sortedByDescending { it.lastConnectedAt }.take(MAX))
        }
    }

    fun remove(address: String) = withLock(lock) {
        save(allLocked().filterNot { it.address == address })
    }

    /** 设置/清除备注名；清成空白 = 恢复广播名 */
    fun rename(address: String, alias: String?) = withLock(lock) {
        val p = allLocked().firstOrNull { it.address == address } ?: return
        val merged = allLocked().filterNot { it.address == address } +
                p.copy(alias = alias?.trim()?.takeIf { it.isNotEmpty() })
        save(merged)
    }

    fun setPassword(address: String, level: Int, password: String) = withLock(lock) {
        val profiles = allLocked()
        val p = profiles.firstOrNull { it.address == address } ?: DeviceProfile(address = address)
        val merged = profiles.filterNot { it.address == address } + p.copy(passwords = p.passwords + (level to password))
        save(merged)
    }

    fun removePassword(address: String, level: Int) = withLock(lock) {
        val profiles = allLocked()
        val p = profiles.firstOrNull { it.address == address } ?: return
        save(profiles.filterNot { it.address == address } + p.copy(passwords = p.passwords - level))
    }

    /** 锁内读取当前表（调用方必须已持有 lock） */
    private fun allLocked(): List<DeviceProfile> {
        migrateOnce()
        return AppStore.get(AppStore.KEY_PROFILES_V1)?.let { text ->
            runCatching { json.decodeFromString(ListSerializer(DeviceProfile.serializer()), text) }.getOrNull()
        } ?: emptyList()
    }

    private fun save(profiles: List<DeviceProfile>) {
        AppStore.put(AppStore.KEY_PROFILES_V1,
            if (profiles.isEmpty()) null
            else json.encodeToString(ListSerializer(DeviceProfile.serializer()), profiles))
    }

    /**
     * 旧格式迁移（幂等，进程首次访问触发，锁内执行）：
     * 1. `地址#等级=密码;…` → 按地址并入档案（旧 key 清空）；
     * 2. 「上次连接」设备没有档案时补建（名字沿用旧记忆名 device.name）；
     * 3. 迁移出的档案若没有名字、又恰好是上次连接设备 → 补上旧记忆名。
     */
    private fun migrateOnce() {
        if (migrated) return
        var profiles = AppStore.get(AppStore.KEY_PROFILES_V1)?.let { text ->
            runCatching { json.decodeFromString(ListSerializer(DeviceProfile.serializer()), text) }.getOrNull()
        } ?: emptyList()
        var changed = false

        val legacy = AppStore.get(AppStore.KEY_PW)
        if (!legacy.isNullOrBlank()) {
            val grouped = mutableMapOf<String, MutableMap<Int, String>>()
            legacy.split(';').forEach { item ->
                // 形如 AA:BB:..#3=pw；地址里没有 =，按 #/= 逐段拆是安全的
                val head = item.substringBefore('=')
                val pw = item.substringAfter('=', "")
                val addr = head.substringBefore('#')
                val level = head.substringAfter('#', "").toIntOrNull() ?: return@forEach
                if (addr.isBlank() || pw.isEmpty()) return@forEach
                grouped.getOrPut(addr) { mutableMapOf() }[level] = pw
            }
            grouped.forEach { (addr, pw) ->
                val hit = profiles.firstOrNull { it.address == addr }
                profiles = if (hit != null) {
                    profiles.filterNot { it.address == addr } + hit.copy(passwords = hit.passwords + pw)
                } else profiles + DeviceProfile(address = addr, passwords = pw)
            }
            AppStore.put(AppStore.KEY_PW, null)
            changed = true
        }

        val last = AppStore.savedAddress
        if (last != null) {
            val hit = profiles.firstOrNull { it.address == last }
            when {
                hit == null -> { profiles = profiles + DeviceProfile(address = last, name = AppStore.savedDeviceName.orEmpty()); changed = true }
                hit.name.isBlank() && !AppStore.savedDeviceName.isNullOrBlank() -> {
                    profiles = profiles.filterNot { it.address == last } + hit.copy(name = AppStore.savedDeviceName!!)
                    changed = true
                }
            }
        }
        if (changed) save(profiles)
        migrated = true   // 整个迁移做完才置位：并发读者要么等锁拿到完整表，要么看到已完成
    }
}
