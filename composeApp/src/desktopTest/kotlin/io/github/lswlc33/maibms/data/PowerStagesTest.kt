package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.ui.POWER_IDLE_DEADBAND_W
import io.github.lswlc33.maibms.ui.powerGaugeBarFill
import io.github.lswlc33.maibms.ui.powerGaugeBarIndex
import io.github.lswlc33.maibms.ui.powerGaugeDebounced
import io.github.lswlc33.maibms.ui.powerGaugeOverTop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 功率阶梯（换挡进度条）的规范化与填充数学：
 * 阶梯 = [副档位(负, 充电/动能回收共用) + 一/二/三档(正，必填)]，默认 -1500/1000/3000/5000；
 * 静置死区 |功率|<20W 当 0；填充 = (|功率|/本条上限)²；顶破末档 = 到顶摇晃。
 * 填充数学直接调用 UI 用的本体函数，不再复算一份。
 */
class PowerStagesTest {

    @Test fun defaultWhenUnset() {
        // 未设置过 → 默认四档（开箱即有表）；「不设阶梯」不再是合法状态
        AppStore.powerStagesW = emptyList()
        assertEquals(listOf(-1500, 1000, 3000, 5000), AppStore.powerStagesW)
    }

    @Test fun legacyThreePositivesUpgraded() {
        // 老数据（只有三个正档）平滑升级：补默认副档位，正档原样保留
        AppStore.powerStagesW = listOf(1500, 3000, 5000)
        assertEquals(listOf(-1500, 1500, 3000, 5000), AppStore.powerStagesW)
    }

    @Test fun subStagePreserved() {
        AppStore.powerStagesW = listOf(-2000, 1000, 3000, 5000)
        assertEquals(listOf(-2000, 1000, 3000, 5000), AppStore.powerStagesW)
    }

    @Test fun positivesDedupedSortedAndPadded() {
        // 重复去重、乱序升序；不足三个用默认值补足（不与已有值重复）
        AppStore.powerStagesW = listOf(3000, 1000, 1000)
        assertEquals(listOf(-1500, 1000, 3000, 5000), AppStore.powerStagesW)
        // 只有一个正档：默认值按序补足
        AppStore.powerStagesW = listOf(-1500, 1200)
        assertEquals(listOf(-1500, 1000, 1200, 3000), AppStore.powerStagesW)
    }

    @Test fun capsAtThreePositives() {
        // 正档最多 3 个，多出的丢弃；0 与负值（副档位之外）过滤
        AppStore.powerStagesW = listOf(-1500, 500, 1500, 2500, 3500)
        assertEquals(listOf(-1500, 500, 1500, 2500), AppStore.powerStagesW)
    }

    @Test fun normalizationIsIdempotent() {
        val once = normalizePowerStages(listOf(2000, -800, 2000, 0, 9000))
        assertEquals(once, normalizePowerStages(once))
    }

    @Test fun deadbandTreatsNearZeroAsRest() {
        // 静置死区：|功率| < 20W 一律当 0（第 1 档空条，而不是副档位/低档小填充）
        assertEquals(0, powerGaugeDebounced(19))
        assertEquals(0, powerGaugeDebounced(-19))
        assertEquals(20, powerGaugeDebounced(20))
        assertEquals(-20, powerGaugeDebounced(-20))
        assertTrue(POWER_IDLE_DEADBAND_W == 20)
        val stages = listOf(-1500, 1000, 3000, 5000)
        assertEquals(1, powerGaugeBarIndex(10, stages), "静置应显示第 1 档空条")
        assertEquals(0f, powerGaugeBarFill(10, stages))
        assertEquals(0f, powerGaugeBarFill(-10, stages))
    }

    @Test fun barIndexAndFillForDischargeGears() {
        val stages = listOf(-1500, 1000, 3000, 5000)
        // 500W：第 1 条（0~1000），线性 50% → 显示 25%
        assertEquals(1, powerGaugeBarIndex(500, stages))
        assertEquals(0.25f, powerGaugeBarFill(500, stages), 0.001f)
        // 1500W：切到第 2 条（0~3000），线性 50% → 25%
        assertEquals(2, powerGaugeBarIndex(1500, stages))
        assertEquals(0.25f, powerGaugeBarFill(1500, stages), 0.001f)
        // 2500W：仍是第 2 条（上限 3000），线性 83% → ≈69%
        assertEquals(2, powerGaugeBarIndex(2500, stages))
        assertEquals(0.6944f, powerGaugeBarFill(2500, stages), 0.001f)
        // 3000W 到达第 2 档上限：整条切到第 3 条（0~5000），显示 36%
        assertEquals(3, powerGaugeBarIndex(3000, stages))
        assertEquals(0.36f, powerGaugeBarFill(3000, stages), 0.001f)
        // 4000W：第 3 条线性 80% → 64%
        assertEquals(3, powerGaugeBarIndex(4000, stages))
        assertEquals(0.64f, powerGaugeBarFill(4000, stages), 0.001f)
        // 顶格及超出：末条画满
        assertEquals(3, powerGaugeBarIndex(5000, stages))
        assertEquals(1f, powerGaugeBarFill(5000, stages))
        assertEquals(1f, powerGaugeBarFill(9999, stages))
    }

    @Test fun subGearReversedScale() {
        // 副档位（充电/动能回收共用）：条号 0，填充 = (|功率|/副档位量程)²，顶格封顶 1
        val stages = listOf(-1500, 1000, 3000, 5000)
        assertEquals(0, powerGaugeBarIndex(-800, stages))
        assertEquals(0.2844f, powerGaugeBarFill(-800, stages), 0.001f)
        assertEquals(0, powerGaugeBarIndex(-1500, stages))
        assertEquals(1f, powerGaugeBarFill(-1500, stages), 0.001f)
        assertEquals(1f, powerGaugeBarFill(-3000, stages), "超出副档位量程封顶")
        // 自定义副档位
        val custom = listOf(-2000, 1000, 3000, 5000)
        assertEquals(0.25f, powerGaugeBarFill(-1000, custom), 0.001f)
    }

    @Test fun overTopDetection() {
        val stages = listOf(-1500, 1000, 3000, 5000)
        assertTrue(powerGaugeOverTop(5001, stages), "冲破末档上限应判定到顶")
        assertEquals(false, powerGaugeOverTop(5000, stages), "正好到上限不算冲破")
        assertEquals(false, powerGaugeOverTop(4999, stages))
        assertEquals(false, powerGaugeOverTop(-2000, stages), "副档位不参与到顶判定")
        assertEquals(false, powerGaugeOverTop(10, stages), "静置死区内不算到顶")
        assertEquals(false, powerGaugeOverTop(5001, emptyList()))
    }

    @Test fun emptyStagesAreSafe() {
        assertEquals(1, powerGaugeBarIndex(500, emptyList()))
        assertEquals(0f, powerGaugeBarFill(500, emptyList()))
        assertEquals(0f, powerGaugeBarFill(-500, emptyList()))
    }

    @Test fun fillEasesOutWithinEachBar() {
        // 用户指定的手感：条内显示 ≤ 线性（前段压缩），且随功率单调不回退；
        // 跨档整条切换时填充本来就回跳，单调性只在条内成立
        val stages = listOf(-1500, 1000, 3000, 5000)
        assertEquals(0.49f, powerGaugeBarFill(700, stages), 0.005f, "线性 70% 显示 ~50%")
        val caps = listOf(1000, 3000, 5000)
        for (i in caps.indices) {
            val lo = if (i == 0) 0 else caps[i - 1]
            val hi = caps[i]
            var prev = 0f
            // 不含上限端点：功率到达上限的瞬间整条已切下一条，该点属于下一条的起点
            for (p in lo until hi step 10) {
                val fill = powerGaugeBarFill(p, stages)
                val linear = p.toFloat() / hi
                assertTrue(fill <= linear + 1e-4f, "功率 ${p}W 显示 $fill 高于线性 $linear，曲线方向反了")
                assertTrue(fill >= prev - 1e-4f, "功率 ${p}W 填充回退：$fill < $prev")
                prev = fill
            }
        }
    }
}
