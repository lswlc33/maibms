package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.MockBmsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 历史设备档案：旧格式迁移 / 增删改与上限淘汰 / 自动重连目标解析与回退 / 级联删快照 / 密码 API 兼容。
 */
class DeviceProfilesTest {

    /** 各用例间共享 MemoryStore，先复位；可选预置「上次连接」与旧格式密码串 */
    private fun reset(savedAddress: String? = null, savedName: String? = null, legacyPw: String? = null) {
        AppStore.clearSnapshots()
        DeviceProfiles.resetForTest()
        AppStore.savedAddress = savedAddress
        AppStore.savedDeviceName = savedName
        AppStore.put(AppStore.KEY_PW, legacyPw)
    }

    // ---- 迁移 ----

    @Test fun legacyPasswordsMigrateIntoProfiles() {
        reset(
            savedAddress = "AA:BB:CC",
            savedName = "ANT@OLD",
            legacyPw = "AA:BB:CC#3=pw3;AA:BB:CC#2=pw2;DD:EE:FF#1=pw1",
        )
        val all = DeviceProfiles.all()
        assertEquals(2, all.size, "旧串里两台设备各自成档案")
        val aa = all.first { it.address == "AA:BB:CC" }
        assertEquals(mapOf(3 to "pw3", 2 to "pw2"), aa.passwords)
        assertEquals("ANT@OLD", aa.name, "上次连接设备迁移时沿用旧记忆名")
        assertNull(AppStore.get(AppStore.KEY_PW), "旧 key 迁移后清空")
        assertEquals(all, DeviceProfiles.all(), "迁移幂等：二次访问不变")
    }

    @Test fun legacySavedDeviceWithoutPasswordGetsProfile() {
        reset(savedAddress = "11:22:33", savedName = "ANT@LAST")
        val all = DeviceProfiles.all()
        assertEquals(1, all.size)
        assertEquals("11:22:33", all[0].address)
        assertEquals("ANT@LAST", all[0].name)
    }

    // ---- 增删改与上限 ----

    @Test fun upsertMergesAndEvictsOldest() {
        reset()
        repeat(20) { i ->
            DeviceProfiles.upsert(DeviceProfile(
                address = "ADDR-$i", name = "dev$i", lastConnectedAt = 1_000L + i,
                passwords = if (i == 0) mapOf(3 to "pw0") else emptyMap(),
            ))
        }
        assertEquals(20, DeviceProfiles.all().size)
        // 第 21 台进来（时间最新）：最旧的 ADDR-0 连同密码被淘汰
        DeviceProfiles.upsert(DeviceProfile(address = "ADDR-NEW", name = "new", lastConnectedAt = 99_999L))
        val all = DeviceProfiles.all()
        assertEquals(20, all.size)
        assertNull(DeviceProfiles.find("ADDR-0"))
        assertEquals("new", DeviceProfiles.find("ADDR-NEW")!!.name)
    }

    @Test fun touchUpdatesTimeOnly() {
        reset()
        DeviceProfiles.upsert(DeviceProfile(address = "A1", name = "one", lastConnectedAt = 100L, passwords = mapOf(2 to "x")))
        DeviceProfiles.touch("A1", 500L)
        val p = DeviceProfiles.find("A1")!!
        assertEquals(500L, p.lastConnectedAt)
        assertEquals("one", p.name)
        assertEquals(mapOf(2 to "x"), p.passwords, "touch 不动名称与密码")
    }

    @Test fun renameAliasPriority() {
        reset()
        DeviceProfiles.upsert(DeviceProfile(address = "A1", name = "ANT@BLE"))
        DeviceProfiles.rename("A1", "阳台电池")
        assertEquals("阳台电池", DeviceProfiles.find("A1")!!.displayName)
        // 清成空白 = 恢复广播名
        DeviceProfiles.rename("A1", "  ")
        assertEquals("ANT@BLE", DeviceProfiles.find("A1")!!.displayName)
    }

    // ---- 自动重连目标 ----

    @Test fun autoConnectResolutionAndFallback() {
        reset(savedAddress = "LAST")
        DeviceProfiles.upsert(DeviceProfile(address = "LAST", name = "last", lastConnectedAt = 200L))
        DeviceProfiles.upsert(DeviceProfile(address = "FIXED", name = "fixed", lastConnectedAt = 100L))
        AppStore.autoConnectAddress = "FIXED"

        fun resolve() = AppStore.autoConnectAddress
            ?.takeIf { addr -> DeviceProfiles.all().any { it.address == addr } }
            ?: AppStore.savedAddress

        assertEquals("FIXED", resolve(), "显式指定 → 生效")
        DeviceProfiles.remove("FIXED")
        assertEquals("LAST", resolve(), "指定的设备被删后回退上次连接")
    }

    // ---- 级联删除 ----

    @Test fun deleteDeviceCascadesPasswordsAndSnapshots() {
        reset(savedAddress = "DEV-A", savedName = "a")
        DeviceProfiles.upsert(DeviceProfile(address = "DEV-A", name = "a", lastConnectedAt = 300L, passwords = mapOf(3 to "pw")))
        DeviceProfiles.upsert(DeviceProfile(address = "DEV-B", name = "b", lastConnectedAt = 200L))
        AppStore.saveSnapshotJson(1L, SnapshotCodec.encode(snapshot(1L, "DEV-A")))
        AppStore.saveSnapshotJson(2L, SnapshotCodec.encode(snapshot(2L, "DEV-A")))
        AppStore.saveSnapshotJson(3L, SnapshotCodec.encode(snapshot(3L, "DEV-B")))
        AppStore.autoConnectAddress = "DEV-A"

        val scope = CoroutineScope(Dispatchers.Default)
        val repo = BmsRepository(scope, MockBmsEngine(scope))
        repo.deleteDevice("DEV-A")

        assertNull(DeviceProfiles.find("DEV-A"), "档案已删")
        assertNull(AppStore.loadPassword("DEV-A", 3), "密码随档案消失")
        assertEquals(listOf("DEV-B"), AppStore.loadSnapshots().map { it.deviceAddress }, "A 的快照级联删除，B 的保留")
        assertNull(AppStore.autoConnectAddress, "显式目标被删后清空（回退上次连接）")
        // 删除的是「上次连接」设备时必须一并忘记：否则重启还会对它无密码自动重连
        assertNull(AppStore.savedAddress, "上次连接指向被删设备时应一并清除")
        assertNull(AppStore.savedDeviceName)
    }

    private fun snapshot(id: Long, address: String) = BmsSnapshot(
        id = id, deviceAddress = address, deviceName = "n-$address", timeLabel = "10-01 12:00",
        status = BmsStatus(), liveParams = emptyMap(), identity = emptyMap(),
    )

    // ---- 密码 API 兼容（旧调用点零改动） ----

    @Test fun passwordApiBackedByProfiles() {
        reset()
        AppStore.savePassword("AA", 3, "pw3")
        assertEquals("pw3", AppStore.loadPassword("AA", 3))
        assertEquals("pw3", DeviceProfiles.find("AA")!!.passwords[3], "API 写入落在档案里")
        AppStore.savePassword("AA", 2, "pw2")
        AppStore.removePassword("AA", 3)
        assertNull(AppStore.loadPassword("AA", 3))
        assertEquals("pw2", AppStore.loadPassword("AA", 2))
        // 无档案的设备 loadPassword 安全返回 null
        assertNull(AppStore.loadPassword("NOPE", 1))
        assertTrue(DeviceProfiles.find("NOPE") == null, "读取不建空档案")
    }
}
