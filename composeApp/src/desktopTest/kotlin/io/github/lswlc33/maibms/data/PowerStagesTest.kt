package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.ui.powerGaugeBarFill
import io.github.lswlc33.maibms.ui.powerGaugeBarIndex
import io.github.lswlc33.maibms.ui.powerGearNo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 功率换挡进度条的阶梯解析（AppStore.powerStagesW）与填充数学：
 * 第 1 档必填、2/3 档可选、非法输入过滤、重复去重、上限 3 档；
 * 阶梯语义 = 每档的**上限**（W），填充数学直接调用 UI 用的本体函数，不再复算一份。
 */
class PowerStagesTest {

    @Test fun defaultEmpty() {
        AppStore.powerStagesW = emptyList()
        assertEquals(emptyList(), AppStore.powerStagesW, "未设置 = 不显示进度条")
    }

    @Test fun singleStage() {
        AppStore.powerStagesW = listOf(1000)
        assertEquals(listOf(1000), AppStore.powerStagesW)
    }

    @Test fun threeStages() {
        AppStore.powerStagesW = listOf(1000, 2000, 3000)
        assertEquals(listOf(1000, 2000, 3000), AppStore.powerStagesW)
    }

    @Test fun capsAtThreeAndDropsInvalid() {
        // 4 个值只取前 3；0/负数被过滤
        AppStore.powerStagesW = listOf(500, 0, -3, 1500, 2500, 9999)
        assertEquals(listOf(500, 1500, 2500), AppStore.powerStagesW)
    }

    @Test fun duplicatesDeduped() {
        // 重复档位会画出 0 宽度的格子，读取时直接去重（且不占 3 档的名额）
        AppStore.powerStagesW = listOf(1000, 1000, 2000)
        assertEquals(listOf(1000, 2000), AppStore.powerStagesW)
    }

    @Test fun stagesSortedAscendingRegardlessOfInputOrder() {
        // 乱序输入强制升序：换挡进度条的数学与输入顺序无关
        AppStore.powerStagesW = listOf(3000, 1000, 2000)
        assertEquals(listOf(1000, 2000, 3000), AppStore.powerStagesW)
    }

    @Test fun gaugeBarMath() {
        // 整条切换制 + 前段压缩缓曲线：填充直接调 UI 用的本体（曾经测试复算一份、照着 bug 一起写歪）
        val stages = listOf(1000, 2000, 3000)
        // 800W：第 1 条（0~1000），线性 80% → 显示 0.8² = 64%
        assertEquals(0, powerGaugeBarIndex(800, stages))
        assertEquals(0.64f, powerGaugeBarFill(800, stages), 0.001f)
        // 1500W：整条切到第 2 条（0~2000），线性 75% → 显示 56.25%（绝对刻度，不扣除前面档走过的量）
        assertEquals(1, powerGaugeBarIndex(1500, stages))
        assertEquals(0.5625f, powerGaugeBarFill(1500, stages), 0.001f)
        // 2500W：第 3 条（0~3000），线性 83.3% → 显示 ≈ 69.4%
        assertEquals(2, powerGaugeBarIndex(2500, stages))
        assertEquals(0.6944f, powerGaugeBarFill(2500, stages), 0.001f)
        // 顶格及超出：末条画满（超出部分不封顶显示，底行照实给功率值）
        assertEquals(2, powerGaugeBarIndex(3000, stages))
        assertEquals(1f, powerGaugeBarFill(3000, stages))
        assertEquals(1f, powerGaugeBarFill(9999, stages))
        // 0W / 负功率（充电）：画第 1 条的空轨道
        assertEquals(0, powerGaugeBarIndex(0, stages))
        assertEquals(0f, powerGaugeBarFill(0, stages))
        assertEquals(0f, powerGaugeBarFill(-500, stages))
        // 单档 = 唯一一条；500W 线性一半 → 显示 25%
        assertEquals(0, powerGaugeBarIndex(500, listOf(1000)))
        assertEquals(0.25f, powerGaugeBarFill(500, listOf(1000)), 0.001f)
        // 不等距阶梯：2kW 在 1k/5k/6k → 第 2 条，线性 40% → 显示 16%
        assertEquals(1, powerGaugeBarIndex(2000, listOf(1000, 5000, 6000)))
        assertEquals(0.16f, powerGaugeBarFill(2000, listOf(1000, 5000, 6000)), 0.001f)
    }

    @Test fun gaugeBarFillCurveFrontLoadedValue() {
        // 用户指定锚点：线性 70% 只显示 ~50%（进度条前面 50% 相当于原本的 70%）
        //（跨档整条切换时填充本来就回跳，单调性只在条内成立）
        val stages = listOf(1000, 2000, 3000)
        // 700W = 第 1 条线性 70% → 显示 ≈ 49%
        assertEquals(0.49f, powerGaugeBarFill(700, stages), 0.005f)
        val bounds = listOf(0, 1000, 2000, 3000)
        for (g in stages.indices) {
            var prev = 0f
            // 不含上限端点：功率到达上限的瞬间整条已切下一条，该点属于下一条的起点
            for (p in bounds[g] until bounds[g + 1] step 10) {
                val fill = powerGaugeBarFill(p, stages)
                val linear = p.toFloat() / stages[g]
                assertTrue(fill <= linear + 1e-4f, "功率 ${p}W 显示 $fill 高于线性 $linear，曲线方向反了")
                assertTrue(fill >= prev - 1e-4f, "功率 ${p}W 填充回退：$fill < $prev")
                prev = fill
            }
        }
    }

    @Test fun gearSwitchesAtEachCap() {
        // 不变量：功率到达某档上限的瞬间整条切到下一档（999W→条1、1000W→条2）
        val stages = listOf(1000, 2000, 3000)
        assertEquals(0, powerGaugeBarIndex(999, stages))
        assertEquals(1, powerGaugeBarIndex(1000, stages))
        assertEquals(1, powerGaugeBarIndex(1999, stages))
        assertEquals(2, powerGaugeBarIndex(2000, stages))
        // 条号与档位文案始终一致（barIndex == gearNo-1，全功率域扫描）
        for (p in 0..4500 step 7) {
            assertEquals(powerGearNo(p, stages) - 1, powerGaugeBarIndex(p, stages),
                "功率 ${p}W 档位文案与进度条不一致")
        }
        // 不等距阶梯同样自洽
        val uneven = listOf(1000, 5000, 6000)
        for (p in 0..7000 step 13) {
            assertEquals(powerGearNo(p, uneven) - 1, powerGaugeBarIndex(p, uneven),
                "不等距阶梯 ${p}W 不一致")
        }
        // 空阶梯没有档位；负功率（充电）由 UI 显示「待机」，档位函数本身不越界
        assertEquals(0, powerGearNo(500, emptyList()))
        assertEquals(1, powerGearNo(-500, stages))
        assertEquals(stages.size, powerGearNo(9999, stages))
    }
}
