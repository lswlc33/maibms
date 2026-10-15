package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 告警通知的上升沿判定回归。
 *
 * 这里是"半夜会不会被吵醒"的唯一判据：位域是电平语义，直接按位报会每拍都命中同一个保护；
 * 报得太多用户会关掉通知，报得太少等于没有——所以边界都钉在测试里。
 */
class AlarmNotifierTest {

    private val protectOvp = 1UL shl 1          // 单体过压保护
    private val warnSoc1 = 1UL shl 12           // SOC一级告警
    private val warnChgMosOn = 1UL shl 23       // 充电MOS开（状态位，不该通知）
    private val warnStandby = 1UL shl 26        // 待机中（状态位，不该通知）

    @Test fun onlyRisingEdgeIsReported() {
        val n = AlarmNotifier()
        assertEquals(listOf("单体过压保护"), n.onFrame(protectOvp, 0UL, 0).map { it.name })
        // 保护一直存在：后续每一拍都不该再报
        assertEquals(emptyList(), n.onFrame(protectOvp, 0UL, 600).map { it.name })
        assertEquals(emptyList(), n.onFrame(protectOvp, 0UL, 1_200).map { it.name })
    }

    @Test fun flapWithinCooldownIsMergedIntoOneAlert() {
        val n = AlarmNotifier(cooldownMs = 10_000)
        assertEquals(1, n.onFrame(protectOvp, 0UL, 0).size, "第一次触发要报")
        // 保护→恢复→又保护：同一个事故在阈值附近抖动，冷却期内只提醒一次
        assertEquals(emptyList(), n.onFrame(0UL, 0UL, 5_000).map { it.name })
        assertEquals(emptyList(), n.onFrame(protectOvp, 0UL, 9_000).map { it.name })
        // 位一直保持置位：冷却过去也不该报——没有新信息（被压掉的那个上升沿算已消费）
        assertEquals(emptyList(), n.onFrame(protectOvp, 0UL, 10_000).map { it.name })
        assertEquals(emptyList(), n.onFrame(protectOvp, 0UL, 60_000).map { it.name })
    }

    @Test fun genuineReTriggerAfterCooldownAlertsAgain() {
        val n = AlarmNotifier(cooldownMs = 10_000)
        assertEquals(1, n.onFrame(protectOvp, 0UL, 0).size)
        assertEquals(emptyList(), n.onFrame(0UL, 0UL, 20_000).map { it.name }, "恢复：位落下")
        // 冷却过了之后的第二次触发是"又一次事故"，必须报出来（不能被同类合并吃掉）
        assertEquals(listOf("单体过压保护"), n.onFrame(protectOvp, 0UL, 30_000).map { it.name })
    }

    @Test fun stateBitsInWarnDomainNeverAlert() {
        val n = AlarmNotifier()
        // 真机常驻置位的是 MOS 开/待机这类运行状态：它们是"始终为真"的状态，通知它们毫无意义
        assertEquals(emptyList(), n.onFrame(0UL, warnChgMosOn or warnStandby, 0).map { it.name })
    }

    @Test fun realAlarmsInWarnDomainAlert() {
        val n = AlarmNotifier()
        assertEquals(listOf("SOC一级告警"), n.onFrame(0UL, warnSoc1, 0).map { it.name })
        // 告警与状态位混在一起时只挑告警
        assertEquals(
            listOf("SOC一级告警"),
            AlarmNotifier().onFrame(0UL, warnSoc1 or warnChgMosOn or warnStandby, 0).map { it.name },
        )
    }

    @Test fun protectAndWarnDomainsAreIndependent() {
        // bit0 在两域里都存在且含义不同（保护=设置电芯类型，告警=单体过压告警）
        val n = AlarmNotifier()
        val both = n.onFrame(1UL, 1UL, 0)
        assertEquals(2, both.size)
        assertEquals(listOf(true, false), both.map { it.isProtect })
        assertTrue(both[0].name != both[1].name)
    }

    @Test fun multipleBitsReportedInBitOrder() {
        val n = AlarmNotifier()
        val bits = (1UL shl 16) or (1UL shl 2) or protectOvp     // 短路保护 / 单体2级过压 / 单体过压保护
        assertEquals(
            listOf("单体过压保护", "单体2级过压", "短路保护"),
            n.onFrame(bits, 0UL, 0).map { it.name },
        )
    }

    @Test fun resetDropsBaselineSoDeviceSwitchCannotInherit() {
        // 换设备场景：A 台已在保护，切到 B 台后 B 台的同一位置位必须当成"新出现"报出来；
        // 否则"上一台的状态"会把本台真正的新保护吃掉
        val n = AlarmNotifier()
        n.onFrame(protectOvp, 0UL, 0)
        n.reset()
        assertEquals(listOf("单体过压保护"), n.onFrame(protectOvp, 0UL, 100).map { it.name })
    }

    @Test fun firstFrameReportsExistingConditions() {
        // 「连上时已经在保护」也要能被判定出来（要不要打扰由调用方按前台/后台决定）
        val n = AlarmNotifier()
        assertEquals(listOf("单体过压保护", "SOC一级告警"), n.onFrame(protectOvp, warnSoc1, 0).map { it.name })
    }
}
