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
        // 电领（DL）已移除，不再识别
        assertNull(DeviceFamily.matchName("DL"))
        assertNull(DeviceFamily.matchName("dl-abcd"))
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
