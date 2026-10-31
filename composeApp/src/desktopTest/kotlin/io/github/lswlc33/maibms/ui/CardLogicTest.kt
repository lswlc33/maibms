package io.github.lswlc33.maibms.ui

import io.github.lswlc33.maibms.data.BmsStatus
import io.github.lswlc33.maibms.data.CellV
import androidx.compose.ui.state.ToggleableState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 直接测试卡片实际使用的纯逻辑，不依赖 Compose 测试运行器或蓝牙。 */
class CardLogicTest {
    @Test fun columns_reduce_for_narrow_width_and_large_text() {
        assertEquals(5, cardColumnCount(336f, 1f, 64f, 5))
        assertEquals(2, cardColumnCount(336f, 2f, 64f, 5))
        assertEquals(1, cardColumnCount(120f, 2f, 64f, 5))
        assertEquals(1, cardColumnCount(0f, 1f, 64f, 5))
        assertEquals(1, cardColumnCount(Float.NaN, 1f, 64f, 5))
        assertEquals(1, cardColumnCount(336f, 2f, 160f, 2))
    }

    @Test fun standard_dashboard_width_keeps_two_columns_but_large_text_collapses() {
        // 720px/2x 标准档扣除页面与卡片内距，实际约 300dp，不应变成长单列。
        assertEquals(2, dashboardCardColumns(300f, 1f))
        assertEquals(2, dashboardCardColumns(280f, 1f))
        assertEquals(2, dashboardCardColumns(336f, 1.1f))
        assertEquals(1, dashboardCardColumns(300f, 1.2f))
        assertEquals(1, dashboardCardColumns(300f, 2f))
        assertEquals(1, dashboardCardColumns(260f, 1f))
    }

    @Test fun unknown_switch_is_indeterminate_not_off_and_known_readonly_retains_state() {
        listOf(false, true).forEach { checked ->
            assertEquals(ToggleableState.Indeterminate, switchState(checked, unknown = true))
            assertEquals("未知", switchStateText(checked, unknown = true))
        }
        // enabled=false 是权限/忙碌状态，不影响已知设备 ON/OFF 的语义。
        assertEquals(ToggleableState.On, switchState(checked = true, unknown = false))
        assertEquals("开启", switchStateText(checked = true, unknown = false))
        assertEquals(ToggleableState.Off, switchState(checked = false, unknown = false))
        assertEquals("关闭", switchStateText(checked = false, unknown = false))
    }

    @Test fun unclassified_temperature_channels_are_not_lost() {
        val temps = listOf("MOS" to 30.0, "电池" to 24.5, "环境" to -2.0,
            "T1" to TEMP_NOT_CONNECTED, "坏探头" to Double.NaN)
        assertEquals(listOf("MOS", "电池", "环境"), presentTemperatures(temps).map { it.first })
        assertTrue(presentTemperatures(listOf("T1" to TEMP_NOT_CONNECTED)).isEmpty())
    }

    @Test fun cell_states_are_textual_and_missing_readings_do_not_claim_extrema() {
        assertEquals(listOf("最高", "最低", "均衡"), cellStateLabels(CellV(1, 3.3, true, true, true)))
        assertEquals(listOf("无读数"), cellStateLabels(CellV(1, 0.0, true, true, true, missing = true)))
        assertTrue(cellStateLabels(CellV(2, 3.3)).isEmpty())
    }

    @Test fun power_stages_do_not_filter_truncate_or_overflow_inputs() {
        assertEquals(listOf(-1500, 1000, 3000, 5000), parsePowerStages("1500", listOf("5000", "1000", "3000")))
        assertEquals(listOf(-1500, 1000, 3000, 5000), parsePowerStages("-1500", listOf("1000", "3000", "5000")))
        listOf("0", "-2147483648", "1000000", "12.5", "1x500").forEach {
            assertNull(parsePowerStages(it, listOf("1000", "3000", "5000")))
        }
        assertNull(parsePowerStages("1500", listOf("1000", "1000", "5000")))
        assertNull(parsePowerStages("1500", listOf("1000", "-3000", "5000")))
        assertNull(parsePowerStages("1500", listOf("1000", "3000", "10000000")))
    }

    @Test fun safety_advice_uses_official_mos_reason_and_never_bypasses_protection() {
        assertTrue(safetyChecks(BmsStatus()).isEmpty())
        val advice = safetyChecks(BmsStatus(hasData = true, chMosCode = 3)).joinToString(" ")
        assertTrue(advice.contains("停止相关充放电"))
        assertTrue(advice.contains("不要反复尝试开启开关"))
        assertTrue(!advice.contains("强制充电") && !advice.contains("解除保护"))
        val normal = safetyChecks(BmsStatus(hasData = true, chMosCode = 0, disMosCode = 0)).joinToString(" ")
        assertTrue(normal.contains("正常等待"))
        assertTrue(normal.contains("不要仅凭开关关闭判断故障"))
    }
}
