package io.github.lswlc33.maibms.protocol.meter

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 电量计协议：组帧结构 + 应答解码（合成帧自洽性；量纲以 EM02.js 的换算为准）。 */
class MeterProtocolTest {

    // ---- 蓝宝 FA50 ----

    @Test
    fun lanbao_read_frame_structure() {
        val p = LanBaoProtocol()
        val f = p.nextPollFrames().first()
        assertEquals(0xFA, f[0].toInt() and 0xFF)
        assertEquals(0x50, f[1].toInt() and 0xFF)
        assertEquals(0x28, f[2].toInt() and 0xFF)
        assertEquals(0xA0, f[3].toInt() and 0xFF)
        assertEquals(0x15, f[4].toInt() and 0xFF)
        assertEquals(9, f[6].toInt() and 0xFF)
        assertEquals(listOf(6, 6, 6, 6, 6, 6), (7..12).map { f[it].toInt() and 0xFF })
        assertEquals(1, f[13].toInt() and 0xFF)
        assertEquals(0xE5, f[18].toInt() and 0xFF)
    }

    @Test
    fun lanbao_decodes_table1() {
        val p = LanBaoProtocol()
        val data = ByteArray(111)
        putBE(data, 0, 2, 4800)
        putBE(data, 2, 4, 500)
        putBE(data, 6, 2, 800)
        data[14] = 25
        putBE(data, 19, 4, 240)
        putBE(data, 82, 2, 4200)
        putBE(data, 84, 2, 4180)
        putBE(data, 86, 2, 20)
        data[50] = 2
        listOf(0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33).forEachIndexed { i, v -> data[51 + i] = v.toByte() }
        val r = p.onData(lanbaoFrame(1, data))!!
        assertEquals(48.0, r.totalVoltage)
        assertEquals(5.0, r.current)
        assertEquals(80, r.soc)
        assertEquals(240, r.power)
        assertEquals(4.2, r.maxCell)
        assertEquals(4.18, r.minCell)
        assertEquals(0.02, r.deltaCell)
        assertEquals("彦阳保护板", r.relayBoardBrand)
        assertEquals("AA:BB:CC:11:22:33", r.relayBoardMac)
    }

    @Test
    fun lanbao_rejects_bad_crc_or_foreign_frame() {
        val p = LanBaoProtocol()
        assertNull(p.onData(byteArrayOf(0x7E, 0x01, 0x11)))
        // 正确帧但故意破坏 CRC → 必须拒绝
        val data = ByteArray(111)
        val bad = lanbaoFrame(1, data).also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertNull(p.onData(bad))
    }

    // ---- 陆行 EM2APP ----

    @Test
    fun luxing_poll_frames_match_em02_commands() {
        val p = LuXingProtocol()
        // 官方 EM02.js：40 00/89、40 92/54、42 00/45、41 52/48（每拍 4 条）
        val expected = listOf(
            byteArrayOf(0x01, 0x03, 0x0F, 0xA0.toByte(), 0x00, 0x59, 0x86.toByte(), 0xC6.toByte()),
            byteArrayOf(0x01, 0x03, 0x0F, 0xFC.toByte(), 0x00, 0x36, 0x06, 0xF8.toByte()),
            byteArrayOf(0x01, 0x03, 0x10, 0x68, 0x00, 0x2D, 0x00, 0xCB.toByte()),
            byteArrayOf(0x01, 0x03, 0x10, 0x38, 0x00, 0x30, 0xC0.toByte(), 0xD3.toByte()),
        )
        assertEquals(4, p.nextPollFrames().size)
        expected.forEachIndexed { i, e -> assertTrue(p.nextPollFrames()[i].contentEquals(e)) }
    }

    @Test
    fun luxing_decodes_realtime_len108() {
        val p = LuXingProtocol()
        val d = ByteArray(108)
        var i = 0
        fun u8(v: Int) { d[i++] = v.toByte() }
        fun i8(v: Int) { d[i++] = v.toByte() }
        fun u16(v: Int) { d[i++] = v.toByte(); d[i++] = (v ushr 8).toByte() }
        fun i16(v: Int) = u16(if (v < 0) v + 0x10000 else v)
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        u8(4)                     // cellNum
        u8(1)                     // batType
        u8(1); i8(32)             // mosTemp
        u8(0); i8(-40)            // 均衡温度 0 路
        u8(0)                     // rsv
        u8(2); i8(20); i8(21); repeat(6) { i8(0) }   // batTemps[8]
        u8(0); u8(1)              // min/max temp idx
        u8(0); i8(0)              // validMaxTemp/minTemp
        i16(240)                  // power
        u32(48000)                // 系统电压 mV → 48.0V
        u32(12345)                // 系统电流 /100 → 123.45A
        u16(4200); u16(4190); u16(4180); u16(4210)   // 单体 /1000
        repeat(60 - 2 * 4) { u8(0) }
        u8(3); u8(2)              // max/min 电压下标
        u16(4210); u16(4180)      // validMax/Min 电压
        u16(0); u16(0)            // 最大充/放电流
        u8(0); u8(0)              // 死区
        u16(1000)                 // 额定容量 /10 → 100.0Ah
        u16(500)                  // 实际容量 → 50.0Ah
        u8(80)                    // SOC
        u8(100)                   // SOH
        assertEquals(108, i)
        val r = p.onData(modbusResp(d))!!
        assertEquals(48.0, r.totalVoltage)
        assertEquals(123.45, r.current)
        assertEquals(240, r.power)
        assertEquals(80, r.soc)
        assertEquals(100.0, r.totalCapAh)
        assertEquals(50.0, r.remainCapAh)
        assertEquals(4, r.cells!!.size)
        assertEquals(4.21, r.maxCell)
        assertEquals(4.18, r.minCell)
        assertTrue(r.temps!!.any { it.first == "MOS" && it.second == 32.0 })
    }

    @Test
    fun luxing_realtime_sentinels_become_null() {
        val p = LuXingProtocol()
        val d = ByteArray(108)
        var i = 0
        fun u8(v: Int) { d[i++] = v.toByte() }
        fun i8(v: Int) { d[i++] = v.toByte() }
        fun u16(v: Int) { d[i++] = v.toByte(); d[i++] = (v ushr 8).toByte() }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        u8(1); u8(0)                 // cellNum=1, batType
        u8(0); i8(0)                 // mosTemp 0 路
        u8(0); i8(0)                 // balaTemp 0 路
        u8(0); u8(0)                 // rsv / batTempNum=0
        repeat(8) { i8(0) }          // batTemps
        u8(0); u8(0); u8(0); i8(0)   // 温度下标/限值
        u16(0x7FFF)                  // power 未上报
        u32(0xFFFFFFFFL)             // 电压未上报
        u32(0x7FFFFFFFL)             // 电流未上报（哨兵是 0x7FFFFFFF）
        u16(0xFFFF)                  // 单体未接
        repeat(60 - 2 * 1) { u8(0) }
        u8(0); u8(0)                 // max/min 下标
        u16(0xFFFF); u16(0xFFFF)     // 最高/最低单体未上报
        u16(0); u16(0)               // 充/放电流
        u8(0); u8(0)                 // 死区
        u16(0xFFFF); u16(0xFFFF)     // 容量未上报
        u8(0); u8(0)                 // soc / soh
        assertEquals(108, i)
        val r = p.onData(modbusResp(d))!!
        assertNull(r.totalVoltage)
        assertNull(r.current)
        assertNull(r.power)
        assertNull(r.totalCapAh)
        assertNull(r.remainCapAh)
        assertNull(r.maxCell)
        assertNull(r.cells)
    }

    @Test
    fun luxing_decodes_bms_data_len90() {
        val p = LuXingProtocol()

        val d = ByteArray(90)
        var i = 0
        fun u8(v: Int) { d[i++] = v.toByte() }
        fun u16(v: Int) { d[i++] = v.toByte(); d[i++] = (v ushr 8).toByte() }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        u8(1); u8(1)              // bmsBound / bmsConnected
        u16(0); u16(0)            // Tx/Rx
        u16(0); u16(0)            // rpm/speed
        u16(0); u16(0)            // tempIn/ntc
        u16(0); u16(0)            // cal
        u32(48000)                // 总压 mV → 48.0V
        u8(0); u8(0)              // localSoc/skip
        u16(0)                    // localcap
        u8(0); u8(0)              // localCellNum/BatType
        repeat(11) { u16(0) }     // ocv
        u8(2)                     // BoundBmsVendorID = 蚂蚁
        "ANT-BLE".forEach { u8(it.code) }; repeat(33 - 7) { u8(0) }
        listOf(0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33).forEach { u8(it) }
        assertEquals(90, i)
        val r = p.onData(modbusResp(d))!!
        assertEquals(48.0, r.totalVoltage)
        assertEquals("蚂蚁保护板", r.relayBoardBrand)
        assertEquals("AA:BB:CC:11:22:33", r.relayBoardMac)
        assertEquals("ANT-BLE", r.deviceName)
    }

    // ---- 家族/能力 ----

    @Test
    fun protocol_selection_and_caps() {
        assertTrue(MeterProtocol.forFamily(DeviceFamily.LanBao) is LanBaoProtocol)
        assertTrue(MeterProtocol.forFamily(DeviceFamily.LuXing) is LuXingProtocol)
        assertNull(MeterProtocol.forFamily(DeviceFamily.Ant))
        assertEquals(DeviceCapabilities.LanBao, DeviceCapabilities.forFamily(DeviceFamily.LanBao))
        assertTrue(DeviceCapabilities.LuXing.perCellVoltage)
    }

    // ---- helpers ----

    private fun lanbaoFrame(dataNumber: Int, data: ByteArray): ByteArray {
        val head = ByteArray(16)
        head[0] = 0xFA.toByte(); head[1] = 0x50.toByte(); head[4] = 0x15
        head[13] = dataNumber.toByte()
        val body = head + data
        val crc = lanbaoCrc(body)
        return body + byteArrayOf((crc ushr 8).toByte(), (crc and 0xFF).toByte(), 0xE5.toByte())
    }

    private fun lanbaoCrc(b: ByteArray): Int {
        var v = 0xFFFF
        for (x in b) {
            v = v xor (x.toInt() and 0xFF)
            repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 49960 else v ushr 1 }
        }
        return v and 0xFFFF
    }

    private fun modbusResp(data: ByteArray): ByteArray {
        val f = byteArrayOf(0x01, 0x03, data.size.toByte()) + data
        val crc = modbusCrc16(f)
        return f + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    private fun putBE(d: ByteArray, off: Int, len: Int, v: Int) {
        for (i in 0 until len) d[off + i] = ((v shr (8 * (len - 1 - i))) and 0xFF).toByte()
    }
}
