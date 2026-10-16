package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 中继信息卡的「该不该在场」判据与收起偏好。
 *
 * - `hasRelayInfo`：品牌/名称/MAC 任一非 "--" 才算有中继信息——中继卡与卡1 上的展开小标
 *   共用这一条判据，少写一处就会出"卡没了、小标还挂着"这类不一致；
 * - `AppStore.relayCardCollapsed`：默认**展开**，写了就读得回来；存储里出现别的值时按展开
 *   （宁可多显示一块信息，也别让用户找不到它）。
 * 键名写字符串字面量：AppStore 里的常量是 private，测试要直接摆布原始存储。
 */
class RelayCardCollapseTest {

    private fun reset() = AppStore.put("ui.relayCardCollapsed", null)

    @Test fun relayInfoNeedsAnyReportedField() {
        assertFalse(BmsStatus().hasRelayInfo, "空态：三个字段都是 --")
        assertFalse(
            BmsStatus(deviceName = "ANT@BLE24CBUB-3547", totalVoltage = 85.24, hasData = true).hasRelayInfo,
            "保护板直连：接上了也不是中继",
        )
        assertTrue(BmsStatus(relayBoardBrand = "蚂蚁保护板").hasRelayInfo, "陆行只报了品牌也算")
        assertTrue(BmsStatus(relayBoardName = "ANT@BLE24CBUB-3547").hasRelayInfo)
        assertTrue(BmsStatus(relayBoardMac = "F9:99:1B:2B:1B:70").hasRelayInfo)
    }

    @Test fun collapsedDefaultsToExpandedAndRoundTrips() {
        reset()
        assertFalse(AppStore.relayCardCollapsed, "默认展开：没动过的用户应当看得见信息")
        AppStore.relayCardCollapsed = true
        assertTrue(AppStore.relayCardCollapsed, "收起后读回来还是收起")
        AppStore.relayCardCollapsed = false
        assertEquals("0", AppStore.get("ui.relayCardCollapsed"), "落盘值只有 0/1")
        // 存储里是别的值（改过 prefs / 老版本写入）：按展开处理
        AppStore.put("ui.relayCardCollapsed", "yes")
        assertFalse(AppStore.relayCardCollapsed)
        reset()
    }
}
