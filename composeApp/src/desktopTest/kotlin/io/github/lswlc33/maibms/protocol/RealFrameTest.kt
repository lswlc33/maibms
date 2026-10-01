package io.github.lswlc33.maibms.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真机实录回归（2026-09-29，设备 ANT@BLE24CBUB-3547 / F9:99:1B:2B:1B:70，20S 三元锂 113Ah）。
 *
 * 这些字节都是从手机 logcat 原样抄下来的，不是构造的样例——
 * 0x11 实时帧用于锁定解码口径，0x23 应答用于锁定组合帧解析（曾被两条错误假设一起打断）。
 */
class RealFrameTest {

    private fun hexStr(s: String) =
        s.trim().split(' ', '\n').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /** 真机 0x11 实时帧：178 字节 = 6 头 + 168 数据 + CRC2 + AA55 */
    private val realRealtime = hexStr(
        "7E A1 11 00 00 A8 01 03 04 14 00 00 00 00 00 00 00 00 01 00 80 05 00 00 00 00 00 00 00 00 00 00 00 00 " +
        "AB 10 AF 10 AB 10 AB 10 AB 10 B0 10 AD 10 AF 10 AC 10 AB 10 AB 10 AB 10 AE 10 AB 10 AA 10 AB 10 AB 10 AB 10 AB 10 " +
        "AD 10 17 00 17 00 17 00 16 00 17 00 17 00 " +
        "58 21 01 00 64 00 64 00 01 01 00 00 40 3E BC 06 79 33 BC 06 A8 CE 46 00 08 00 00 00 FB EF 1D 02 00 00 00 00 " +
        "B0 10 06 00 AA 10 0F 00 06 00 AC 10 00 00 7C 00 78 00 A8 02 F1 FA EF 7D 42 00 61 1F 4B 00 9F 86 0B 00 03 8B 0F 00 " +
        "00 00 00 00 AD 13 00 00 00 00 58 29 01 00 1A 6A AA 55"
    )

    @Test fun realtimeFrameParses() {
        assertEquals(178, realRealtime.size)
        val p = FrameParser()
        val frames = p.feed(realRealtime)
        assertEquals(1, frames.size)
        assertEquals(0x11, frames[0].func)
        assertEquals(168, frames[0].data.size)
        assertEquals(0, p.buffered)
    }

    /** 断言值＝手机界面当时显示的值（两者逐项一致） */
    @Test fun realtimeDecodeMatchesDeviceUi() {
        val r = RealtimeDecoder.decode(FrameParser().feed(realRealtime).first().data)

        assertEquals(1, r.permission)
        assertEquals(3, r.battStateCode)                     // 待机
        assertEquals(20, r.cellCount)                        // 20 串（85.36V ÷ 4.268V）
        assertEquals(4, r.tempCount)

        assertEquals(85.36, r.totalVoltage)
        assertEquals(0.1, r.current)
        assertEquals(8, r.powerW)                            // 85.36 × 0.1
        assertEquals(100, r.soc)
        assertEquals(100, r.soh)
        assertEquals(113.0, r.physicalCapAh)
        assertEquals(112.997241, r.remainCapAh, 1e-6)
        assertEquals(4640.424, r.cycleCapAh, 1e-3)           // 界面「循环 4640 Ah」

        assertEquals(4.272, r.maxCellV)
        assertEquals(6, r.maxCellIdx)                        // 1-based
        assertEquals(4.266, r.minCellV)
        assertEquals(15, r.minCellIdx)
        assertEquals(4.268, r.avgCellV)
        assertEquals(0.006, r.deltaCellV)                    // 4.272 − 4.266

        assertEquals(1, r.chMos)
        assertEquals(1, r.disMos)
        assertEquals(0, r.balanceState)
        assertEquals(64241, r.batteryType)                   // 三元锂
        assertEquals(23.0, r.mosTemp)
        assertEquals(listOf(23.0, 23.0, 23.0, 22.0), r.temps)

        assertEquals(35516411L, r.runtimeSec)                // 单位秒=411天01:40:11（按 ms 误读会显示 09:51:56）
        assertTrue(r.hasExt)
        assertEquals(emptyList(), BitDict.decode(r.protectBits, BitDict.protectNames))
        // 真机帧告警位 0/23/24/26：bit23/24（MOS 开）自 2026-10-01 起跟随官方实现展示，
        // bit26（待机中）仍是状态位被过滤
        assertEquals(listOf("单体过压告警", "充电MOS开", "放电MOS开"),
            BitDict.decodeForDisplay(r.warnBits, BitDict.warnNames))
    }

    /** 真机 0x23 应答：段1 无 AA55，紧跟 0xFF 开头无帧头段，AA55 只在最末尾 */
    private val realAuth = hexStr("7E A1 43 4A 01 02 01 00 9D 5B FF 05 00 00 20 31 AA 55")

    @Test fun authResponseCombinedParses() {
        val p = FrameParser()
        val frames = p.feed(realAuth)
        assertEquals(2, frames.size)
        assertEquals(0x43, frames[0].func)                   // 0x23 的应答
        assertEquals(330, frames[0].reg)                     // 一级密码槽
        assertEquals(1, frames[0].data[0].toInt() and 0xFF)  // 权限 1 级
        assertTrue(frames[1].isSeg2)
        assertEquals(0xFF, frames[1].func)
        assertEquals(0, p.buffered)
    }
}
