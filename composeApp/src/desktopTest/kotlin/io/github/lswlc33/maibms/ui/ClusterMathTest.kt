package io.github.lswlc33.maibms.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 横屏表盘数学（ClusterMath）——`Plan/横屏仪表原型.html`「静态函数表」的参考值逐条锁定。
 * 角度口径：0°=3 点钟方向、顺时针为正（SVG 与 Compose drawArc 同口径）。
 */
class ClusterMathTest {

    private val stages4 = listOf(-1500, 1000, 3000, 5000)   // 新四档口径（含副档位）
    private val stages3 = listOf(1000, 2000, 3000)          // 旧三档口径（无副档位）

    @Test fun sweepAngleMatchesBlueprint() {
        // 蓝图参考值：sweepAngle(52.5, 94.5, 87.87) = 362.4°；sweepAngle(-5, 5, 1.09k) = 299.4°
        assertEquals(362.4f, ClusterMath.sweepAngle(52.5f, 94.5f, 87.87f), 0.05f)
        assertEquals(299.4f, ClusterMath.sweepAngle(-5000f, 5000f, 1090f), 0.05f)
        // 越界钳制到扫弧两端
        assertEquals(ClusterMath.START_ANGLE, ClusterMath.sweepAngle(52.5f, 94.5f, 0f))
        assertEquals(ClusterMath.END_ANGLE, ClusterMath.sweepAngle(52.5f, 94.5f, 999f))
        // 竖直向上（270°）= 量程正中
        assertEquals(270f, ClusterMath.sweepAngle(-5000f, 5000f, 0f), 0.01f)
    }

    @Test fun voltageRangeAndZones() {
        val r = ClusterMath.voltageRange(21)
        assertEquals(52.5f, r.start); assertEquals(94.5f, r.endInclusive)
        assertEquals(90.3f, ClusterMath.vRedFrom(21))
        assertEquals(58.8f, ClusterMath.vAmberTo(21))
    }

    @Test fun powerRangeAndChargeBand() {
        val r4 = ClusterMath.powerRangeW(stages4)
        assertEquals(-5000f, r4.start); assertEquals(5000f, r4.endInclusive)
        assertEquals(-1500f, ClusterMath.chargeBandFromW(stages4))
        // 旧口径（无副档位）：充电带回退第 1 档上限；量程按最大档取整到 500W
        val r3 = ClusterMath.powerRangeW(stages3)
        assertEquals(-3000f, r3.start); assertEquals(3000f, r3.endInclusive)
        assertEquals(-1000f, ClusterMath.chargeBandFromW(stages3))
        // 取整：4600W 的档位 → ±5000；空阶梯兜底 ±3000
        assertEquals(-5000f, ClusterMath.powerRangeW(listOf(-1500, 1000, 3000, 4600)).start)
        assertEquals(-3000f, ClusterMath.powerRangeW(emptyList()).start)
    }

    @Test fun dischargeZonesFollowStages() {
        val zones = ClusterMath.dischargeZonesW(stages4)
        assertEquals(3, zones.size)
        assertEquals(0f, zones[0].fromW); assertEquals(1000f, zones[0].toW)
        assertEquals(3000f, zones[1].toW); assertEquals(5000f, zones[2].toW)
        // 色带序号：0.5kW→绿(0)、2kW→蓝(1)、4kW→红(2)、负值=充电绿(0)
        assertEquals(0, ClusterMath.zoneIndexW(stages4, 500f))
        assertEquals(1, ClusterMath.zoneIndexW(stages4, 2000f))
        assertEquals(2, ClusterMath.zoneIndexW(stages4, 4000f))
        assertEquals(0, ClusterMath.zoneIndexW(stages4, -1200f))
    }

    @Test fun ticksCoverRangeWithMajorMinor() {
        val ticksP = ClusterMath.ticksP(-5000f..5000f, useKw = false)
        assertEquals(41, ticksP.size)                        // ±5000W / 250W 步进
        assertEquals(21, ticksP.count { it.major })          // 主刻度每 500W
        // 电压刻度：52.5..94.5 网格对齐后 53..94 每 1V → 42 根；主刻度 55/60/.../90 共 8 根
        val ticksV = ClusterMath.ticksV(52.5f..94.5f)
        assertEquals(42, ticksV.size)
        assertEquals(8, ticksV.count { it.major })
    }

    @Test fun socSegmentsAndLow() {
        assertEquals(8, ClusterMath.socSegments(76))
        assertEquals(0, ClusterMath.socSegments(0))
        assertEquals(10, ClusterMath.socSegments(100))
        assertEquals(10, ClusterMath.socSegments(96))
        assertTrue(ClusterMath.socLow(14))
        assertTrue(!ClusterMath.socLow(15))
    }

    @Test fun fmtPowerUnits() {
        // W 默认取整、kW 两位小数，正功率带 "+"
        assertEquals("+1090", ClusterMath.fmtPower(1090f, useKw = false))
        assertEquals("+1.09", ClusterMath.fmtPower(1090f, useKw = true))
        assertEquals("-12400", ClusterMath.fmtPower(-12400f, useKw = false))
        assertEquals("0", ClusterMath.fmtPower(0f, useKw = false))
    }

    /** 极坐标回归：270°（竖直向上）应落在盘心正上方 —— y 分量为负 */
    @Test fun pointOnUpwardIsNegativeY() {
        val rad = 270.0 * PI / 180.0
        val y = sin(rad).toFloat()
        val x = cos(rad).toFloat()
        assertTrue(x < 0.01f && x > -0.01f)
        assertTrue(y < -0.99f)
    }
}
