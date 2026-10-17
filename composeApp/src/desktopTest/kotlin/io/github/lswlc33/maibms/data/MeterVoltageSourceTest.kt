package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.meter.LuXingProtocol
import io.github.lswlc33.maibms.protocol.meter.modbusCrc16
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 陆行电量计「车辆静止、电压却一直跳」的回归测试（真机报障，2026-10）。
 *
 * 根因是两个帧都往 `totalVoltage` 写：108 实时帧是电量计**自测**的电压，90 BMS 帧里的
 * `batTotalVolt` 是**被中继那块保护板自报**的电压——两套测量（90 帧里专门给了
 * `volCalGain1/volCalOffset1` 给中继那一路做校准），而一拍 600ms 会把两条应答都合并进状态，
 * 于是界面在那个大数字上来回跳。官方首页与 CSV 的「总电压」只认 108 帧。
 *
 * 协议层单测只能证明 90 帧解析结果里没有电压；这里把规则钉在**合并层**——
 * 就算将来又有人往中继帧里塞电压，这条用例会立刻红。
 */
class MeterVoltageSourceTest {

    @Test
    fun relay_frame_does_not_overwrite_total_voltage() {
        MockBms.clearForRealDevice()
        MockBms.connectedDeviceName.value = "EM2APP-A1B2"
        val p = LuXingProtocol()
        try {
            // 每拍：先到 108 实时帧（电量计自测 52.31V），再到 90 中继帧（保护板自报 48.0V）
            repeat(3) { beat ->
                MockBms.applyMeterReading(p.onData(modbusResp(realtimeFrame(52.31, 1.23)))!!)
                assertEquals(
                    52.31, MockBms.status.value.totalVoltage, 1e-9,
                    "第 ${beat + 1} 拍的 108 实时帧应给出电量计自测电压",
                )
                MockBms.applyMeterReading(p.onData(modbusResp(bmsFrame(48.0)))!!)
                assertEquals(
                    52.31, MockBms.status.value.totalVoltage, 1e-9,
                    "第 ${beat + 1} 拍：中继帧不得覆盖总电压（否则界面在两个值之间来回跳）",
                )
            }
            val st = MockBms.status.value
            assertEquals("ANT-BLE", st.relayBoardName, "中继那块保护板的名字应进中继卡")
            assertEquals("EM2APP-A1B2", st.deviceName, "本机设备名不得被中继板的名字顶替")
        } finally {
            MockBms.connectedDeviceName.value = null
            MockBms.clearForRealDevice()
        }
    }

    // ---- 合成帧（字段偏移与 MeterProtocolTest 一致，仅保留本用例需要的字段） ----

    private class Writer(val d: ByteArray) {
        var i = 0
        fun u8(v: Int) { d[i++] = v.toByte() }
        fun i8(v: Int) = u8(v and 0xFF)
        fun u16(v: Int) { u8(v and 0xFF); u8(v ushr 8 and 0xFF) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16((v ushr 16 and 0xFFFF).toInt()) }
        fun skip(n: Int) { i += n }
    }

    /** len=108：实时帧（电量计自测电压/电流），只有它提供总电压 */
    private fun realtimeFrame(volts: Double, amps: Double): ByteArray {
        val d = ByteArray(108)
        val w = Writer(d)
        w.u8(4)                       // cellNum
        w.u8(1)                       // batType
        w.u8(1); w.i8(68)             // mosTemp：原始 68 − 40 = 28℃
        w.u8(0); w.i8(0)              // 均衡温度 0 路
        w.u8(0)                       // rsv
        w.u8(2); w.i8(60); w.i8(61); w.skip(6)   // batTemps：原始 60/61 − 40 = 20/21℃
        w.u8(0); w.u8(1)              // min/max temp idx
        w.u8(0); w.i8(0)              // validMaxTemp/minTemp
        w.u16(240)                    // power
        w.u32((volts * 1000).toLong())   // 系统电压 mV
        w.u32((amps * 100).toLong())     // 系统电流 /100
        repeat(4) { w.u16(4200 - it * 10) }      // 单体 /1000
        w.skip(60 - 2 * 4)
        w.u8(3); w.u8(2)              // max/min 电压下标
        w.u16(4200); w.u16(4170)      // validMax/Min 电压
        w.u16(0); w.u16(0)            // 最大充/放电流
        w.u8(0); w.u8(0)              // 死区
        w.u16(1000); w.u16(500)       // 额定/实际容量
        w.u8(80); w.u8(100)           // SOC / SOH
        return d
    }

    /** len=90：BMS 数据（中继板自报电压 + 品牌/名称/MAC）；电压不该被采用 */
    private fun bmsFrame(relayVolts: Double): ByteArray {
        val d = ByteArray(90)
        val w = Writer(d)
        w.u8(1); w.u8(1)              // bmsBound / bmsConnected
        w.skip(4)                     // Tx/Rx
        w.skip(4)                     // rpm/speed
        w.skip(4)                     // tempIn/ntc
        w.skip(4)                     // volCalGain1/volCalOffset1
        w.u32((relayVolts * 1000).toLong())   // batTotalVolt
        w.skip(4)                     // localSoc/skip/localcap
        w.skip(2)                     // localCellNum/BatType
        w.skip(22)                    // ocvConfig0..10
        w.u8(2)                       // 彦阳/蚂蚁…此处取 2 = 蚂蚁
        "ANT-BLE".forEach { w.u8(it.code) }
        w.skip(33 - 7)
        listOf(0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33).forEach { w.u8(it) }
        return d
    }

    /** Modbus FC03 应答：`01 03 <len> <data> <crcLo> <crcHi>` */
    private fun modbusResp(data: ByteArray): ByteArray {
        val f = byteArrayOf(0x01, 0x03, data.size.toByte()) + data
        val crc = modbusCrc16(f)
        return f + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }
}
