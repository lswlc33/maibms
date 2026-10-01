package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.LinkState
import io.github.lswlc33.maibms.transport.MockBmsEngine
import io.github.lswlc33.maibms.transport.MockBmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI 预览快照（设置 → 外观 → UI 预览）：数据自洽 + 走既有预览机制载入 */
class UiPreviewSnapshotTest {

    private val snap = UiPreview.snapshot()

    @Test fun dataIsSelfConsistent() {
        val st = snap.status
        assertTrue(st.hasData && !st.connected, "预览快照有数据但必须是离线态")
        assertEquals(21, st.cells.size, "72114 = 21 串")
        // 平均 × 串数 ≈ 总压（单体电压卡的自检口径）；浮点比较用绝对误差
        val sum = st.cells.sumOf { it.volt }
        assertEquals(sum, st.totalVoltage, 0.05)
        assertEquals("%.3f".format(sum / st.cells.size), st.avgCell)
        // 最高 − 最低 = 压差，且标记唯一
        val volts = st.cells.map { it.volt }
        assertEquals("%.3f".format(volts.max() - volts.min()), st.deltaCell)
        assertEquals(1, st.cells.count { it.isMax })
        assertEquals(1, st.cells.count { it.isMin })
        // 总压 ≈ 实时电压 × 串数也在容量口径上自洽：剩余 < 标称
        assertTrue(st.remainCapAh in 0.0..st.totalCapAh)
        assertEquals(114.0, st.totalCapAh)
        // 告警/保护卡有内容可展示（UI 预览要能检查告警卡的排版）
        assertTrue(st.alarmList.isNotEmpty())
        assertEquals(st.alarmList, st.alarmPairs.map { it.second })
        // 功率 ≈ 电压 × 电流
        assertEquals(st.totalVoltage * st.current, st.power.toDouble(), 5.0)
    }

    @Test fun snapshotIdNeverCollidesWithStore() {
        assertEquals(-1L, snap.id, "内置快照用负 id；AppStore 的快照 id 都是正数（epoch ms）")
        assertEquals("预设", snap.timeLabel)
        assertNull(snap.deviceAddress)
    }

    @Test fun restoresThroughPreviewPipeline() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val repo = BmsRepository(scope, MockBmsEngine(scope))
                repo.setRealTransport(MockBmsTransport(MockBmsEngine(scope), scope))
                repo.start()
                kotlinx.coroutines.withTimeout(3000) { repo.enterPreview(snap) }

                assertTrue(repo.previewActive.value)
                assertEquals(snap.status.totalVoltage, MockBms.status.value.totalVoltage, 1e-9)
                assertEquals(snap.status.cells.size, MockBms.status.value.cells.size)
                assertFalse(MockBms.connected.value, "预览不置连接标志")
                // 预览中连接被拒（防护口不变）
                repo.connect(null)
                assertTrue(repo.linkState.value != LinkState.Connected)
            }
        } finally {
            scope.cancel()
        }
    }
}
