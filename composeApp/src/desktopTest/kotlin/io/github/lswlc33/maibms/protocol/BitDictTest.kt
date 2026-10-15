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
 * - MOS 开（bit23/24）是常态，告警卡不显示；MOS 关由状态字节合成提示补显（mosClosedHints）；
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

    @Test fun mosOpenBitsFiltered_closedHintsSynthesized() {
        // 真机 2026-09-29 实录：告警位 0/23/24/26 常驻置位（RealFrameTest 同源）。
        // 2026-10-01 用户决定：MOS 开=常态不上屏（bit23/24 归入状态位过滤），
        // MOS 关时由实时帧状态字节取反合成「充电MOS关/放电MOS关」提示（mosClosedHints）。
        // 卡与详情弹窗的告警列表共用同一过滤；decodePairs 是不过滤的原始位视图（保护域/本测试用）。
        val bits = (1UL) or (1UL shl 23) or (1UL shl 24) or (1UL shl 26)
        assertEquals(
            listOf("单体过压告警"),
            BitDict.decodeForDisplay(bits, BitDict.warnNames),
        )
        assertEquals(
            listOf("单体过压告警", "充电MOS开", "放电MOS开", "待机中"),
            BitDict.decodePairs(bits, BitDict.warnNames).map { it.second },
        )
        // MOS 全开 → 无提示；只关放电 → 只提示放电
        assertEquals(emptyList(), BitDict.mosClosedHints(chMosOn = true, disMosOn = true))
        assertEquals(listOf("放电MOS关"), BitDict.mosClosedHints(chMosOn = true, disMosOn = false))
        assertEquals(listOf("充电MOS关", "放电MOS关"), BitDict.mosClosedHints(chMosOn = false, disMosOn = false))
        // 最终列表：真告警在前、MOS 关提示殿后（卡只显 3 条，状态提示不得挤掉严重告警）
        assertEquals(
            listOf("单体过压告警", "放电MOS关"),
            BitDict.displayAlarmList(bits, chMosOn = true, disMosOn = false),
        )
        assertEquals(
            listOf("单体过压告警"),
            BitDict.displayAlarmList(bits, chMosOn = true, disMosOn = true),
        )
    }

    @Test fun decodePairsSortedByBit() {
        // bit24（放电MOS开）进详情弹窗原始位视图；bit2/bit49 是纯告警
        val bits = (1UL shl 24) or (1UL shl 2) or (1UL shl 49)
        val pairs = BitDict.decodeForDisplayPairs(bits, BitDict.warnNames)
        assertEquals(listOf(2, 49), pairs.map { it.first })
        assertEquals(listOf("单体欠压告警", "即将低压关机"), pairs.map { it.second })
    }

    @Test fun stagePairsAreTheComplementOfDisplayPairs() {
        // 告警域 64 位被切成互补两半：上屏的告警 = decodeForDisplayPairs，运行状态 = statePairs。
        // 合起来必须逐位等于不过滤的 decodePairs —— 将来往 stateBits 里加位时，
        // 「两处都不显示」这种静默丢失会被这条断言当场拦住
        val bits = (1UL shl 0) or (1UL shl 11) or (1UL shl 18) or (1UL shl 19) or
                (1UL shl 20) or (1UL shl 23) or (1UL shl 24) or (1UL shl 26) or
                (1UL shl 36) or (1UL shl 45) or (1UL shl 48) or (1UL shl 53)
        val shown = BitDict.decodeForDisplayPairs(bits, BitDict.warnNames)
        val states = BitDict.statePairs(bits, BitDict.warnNames)
        val all = BitDict.decodePairs(bits, BitDict.warnNames)
        assertEquals(all.map { it.first }, (shown + states).map { it.first }.sorted())
        // 此前两处都看不到的位（充电器接入 20 / 加热 36 / 调试模式 45 / 防打火 48）现在有出口
        assertEquals(
            listOf(20 to "CAN充电连接", 36 to "电加热开启", 45 to "调试模式", 48 to "防打火中"),
            states.filter { it.first in setOf(20, 36, 45, 48) },
        )
        // 真告警（bit0/11）与「内部通信不稳定」（bit53，不在状态位集合里）仍走告警列表
        assertEquals(listOf(0, 11, 53), shown.map { it.first })
    }

    @Test fun protectBits64bitSafe() {
        // bit49 是保留位（无条目）；bit50 超出 Int/Long 常见位宽，验证 ULong 解码不截断
        assertTrue(BitDict.decodePairs(1UL shl 49, BitDict.protectNames).isEmpty())
        assertEquals(listOf(50 to "采集Boot模式"), BitDict.decodePairs(1UL shl 50, BitDict.protectNames))
    }
}
