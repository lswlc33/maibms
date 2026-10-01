package io.github.lswlc33.maibms.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 保护/告警位域字典回归。
 *
 * 2026-10-01 用户反馈「保护和告警信息有异常」排查后的锁定项：
 * - 告警 bit18/19 = 充电中/放电中（此前被错标成「充电/放电 MOS 开」，真位号是 23/24）；
 * - 旧版缺的高位保护（电流异常/预充失败/内部通信异常…）不得再被静默吞掉；
 * - 告警卡过滤状态位后，真机常驻的 23/24/26（MOS 开×2 + 待机）不应当告警上屏。
 * 期望值全部对照 docs/13 与旧版小程序 AntDict（unpack/ 本地资料）逐位核过。
 */
class BitDictTest {

    @Test fun warnBit18_19AreChargingFlags_notMosOpen() {
        val names = BitDict.warnNames
        assertEquals("电池充电中", names[18])
        assertEquals("电池放电中", names[19])
        assertEquals("充电MOS开", names[23])
        assertEquals("放电MOS开", names[24])
    }

    @Test fun highProtectBitsArePresent() {
        // 这些此前缺失 → 板子真触发时保护卡显示「无保护动作 ✓」
        assertEquals("电流异常", BitDict.protectNames[26])
        assertEquals("预充失败", BitDict.protectNames[30])
        assertEquals("内部通信异常", BitDict.protectNames[29])
        assertEquals("放电MOS异常", BitDict.protectNames[27])
        assertEquals("BMS初始化", BitDict.protectNames[31])
    }

    @Test fun highWarnBitsArePresent() {
        assertEquals("预充失败", BitDict.warnNames[16])
        assertEquals("电压保护", BitDict.warnNames[31])
        assertEquals("时钟异常", BitDict.warnNames[43])
        assertEquals("即将低压关机", BitDict.warnNames[49])
        assertEquals("内部通信不稳定", BitDict.warnNames[53])
    }

    @Test fun mosOpenBitsShownAsImportantHints() {
        // 真机 2026-09-29 实录：告警位 0/23/24/26 常驻置位（RealFrameTest 同源）。
        // 2026-10-01 用户实测对比官方 APP：官方告警卡显示「充电 MOS 开 / 放电 MOS 开」两条
        // （不过滤任何位）；bit23/24 因此从状态过滤表中移除，跟随参考实现展示。
        // bit26（待机中）仍是状态位、继续过滤；详情弹窗则始终能看到全部四条带真实位号。
        val bits = (1UL) or (1UL shl 23) or (1UL shl 24) or (1UL shl 26)
        assertEquals(
            listOf("单体过压告警", "充电MOS开", "放电MOS开"),
            BitDict.decodeForDisplay(bits, BitDict.warnNames),
        )
        assertEquals(
            listOf("单体过压告警", "充电MOS开", "放电MOS开", "待机中"),
            BitDict.decodePairs(bits, BitDict.warnNames).map { it.second },
        )
    }

    @Test fun decodePairsSortedByBit() {
        // bit24（放电MOS开）已随 2026-10-01 的对齐展示在卡片上；bit2/bit49 是纯告警
        val bits = (1UL shl 24) or (1UL shl 2) or (1UL shl 49)
        val pairs = BitDict.decodeForDisplayPairs(bits, BitDict.warnNames)
        assertEquals(listOf(2, 24, 49), pairs.map { it.first })
        assertEquals(listOf("单体欠压告警", "放电MOS开", "即将低压关机"), pairs.map { it.second })
    }

    @Test fun protectBits64bitSafe() {
        // bit49 是保留位（无条目）；bit50 超出 Int/Long 常见位宽，验证 ULong 解码不截断
        assertTrue(BitDict.decodePairs(1UL shl 49, BitDict.protectNames).isEmpty())
        assertEquals(listOf(50 to "采集Boot模式"), BitDict.decodePairs(1UL shl 50, BitDict.protectNames))
    }
}
