package io.github.lswlc33.maibms.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备家族判定（按广播名）的硬不变量。
 * 名称规则来自三个官方小程序解包，见 [DeviceFamily] 的注释；改动前缀表必须同步这里的期望。
 */
class DeviceFamilyTest {

    @Test
    fun matches_by_name_prefix() {
        assertEquals(DeviceFamily.Ant, DeviceFamily.matchName("ANT@BLE24CBUB-3547"))
        assertEquals(DeviceFamily.LanBao, DeviceFamily.matchName("BlueBabe-1234"))
        assertEquals(DeviceFamily.LanBao, DeviceFamily.matchName("blue-abc"))
        assertEquals(DeviceFamily.LanBao, DeviceFamily.matchName("LB123"))
        assertEquals(DeviceFamily.LuXing, DeviceFamily.matchName("EM2APP-0001"))
        assertEquals(DeviceFamily.YanYang, DeviceFamily.matchName("YY-NiuM-73A3"))
        assertEquals(DeviceFamily.YanYang, DeviceFamily.matchName("BT37"))
        assertEquals(DeviceFamily.Jbd, DeviceFamily.matchName("JBD-SP04S034"))
        assertEquals(DeviceFamily.Jbd, DeviceFamily.matchName("SP15S001-P13S-30A"))
        assertEquals(DeviceFamily.Jbd, DeviceFamily.matchName("xiaoxiang-123"))
        assertEquals(DeviceFamily.Jikong, DeviceFamily.matchName("JK-BMS-A"))
        assertEquals(DeviceFamily.Jikong, DeviceFamily.matchName("JK_B2A8S20P"))
        assertEquals(DeviceFamily.Jikong, DeviceFamily.matchName("  jk-bms-a  "))
        assertNull(DeviceFamily.matchName("DL"))
        assertNull(DeviceFamily.matchName("dl-abcd"))
    }

    @Test
    fun added_brands_require_specific_names_or_manual_selection() {
        assertEquals(DeviceFamily.Yadi, DeviceFamily.matchName("FDA90705"))
        assertEquals(DeviceFamily.Yadi, DeviceFamily.matchName(" fd01ab2345 "))
        assertNull(DeviceFamily.matchName("FD-headphones"))
        assertNull(DeviceFamily.matchName("FD"))
        assertEquals(DeviceFamily.Mingtang, DeviceFamily.matchName("明唐-001"))
        assertEquals(DeviceFamily.Xingheng, DeviceFamily.matchName("星恒星蓝Pro"))
        assertEquals(DeviceFamily.Zhiba, DeviceFamily.matchName("智霸锂电-001"))
        assertEquals(DeviceFamily.Shanchong, DeviceFamily.matchName("闪充智行-001"))
        assertNull(DeviceFamily.matchName("XH-headphones"))
        assertNull(DeviceFamily.matchName("CF-keyboard"))
    }

    @Test
    fun xiaoha_soc_is_only_read_from_a_complete_advertised_name() {
        for (soc in listOf(0, 33, 60, 100)) {
            val name = "HBB8909006196SOC${soc.toString().padStart(3, '0')}"
            assertEquals(DeviceFamily.Xiaoha, DeviceFamily.matchName(name))
            assertEquals(soc, ScanDevice(name, "id", -60).advertisedSoc)
        }
        for (name in listOf("HBB8909006196SOC101", "HBBSOC060", "HBB123SOC60", "HBB123SOC060x", "XHSOC060")) {
            assertNull(DeviceFamily.advertisedSoc(name))
            assertNull(DeviceFamily.matchName(name))
        }
    }

    @Test
    fun discovery_only_brands_never_enable_ant_or_control_capabilities() {
        for (family in listOf(DeviceFamily.Xingheng, DeviceFamily.Zhiba, DeviceFamily.Shanchong, DeviceFamily.Xiaoha)) {
            assertFalse(family.supportsDirectConnection)
            assertFalse(family.usesAntProtocol)
            assertTrue(family.isReadOnlyBoard)
            assertTrue(family.serviceUuids.isEmpty())
            val caps = io.github.lswlc33.maibms.data.DeviceCapabilities.forFamily(family)
            assertTrue(caps.readOnly)
            assertFalse(caps.permission || caps.controlCharge || caps.controlDischarge || caps.controlBalance)
        }
    }

    @Test
    fun longest_prefix_wins() {
        // BlueBabe 必须命中蓝宝，不能被更短的前缀干扰
        assertEquals(DeviceFamily.LanBao, DeviceFamily.matchName("BlueBabe"))
    }

    @Test
    fun unknown_and_null() {
        assertNull(DeviceFamily.matchName(null))
        assertNull(DeviceFamily.matchName(""))
        assertNull(DeviceFamily.matchName("XIAOMI-Band"))
        assertNull(DeviceFamily.matchName("CJ01-EM02"))
    }

    @Test
    fun controller_is_not_target() {
        assertFalse(DeviceFamily.isTarget("CJ01-EM02"))
        assertTrue(DeviceFamily.isTarget("EM2APP-1"))
        assertTrue(DeviceFamily.isTarget("ANT-BMS"))
        assertFalse(DeviceFamily.isTarget("whatever"))
    }

    @Test
    fun kind_flags() {
        assertTrue(DeviceFamily.Ant.kind == DeviceKind.Board)
        assertFalse(DeviceFamily.Ant.isMeter)
        assertTrue(DeviceFamily.LanBao.isMeter)
        assertTrue(DeviceFamily.LuXing.isMeter)
    }
}
