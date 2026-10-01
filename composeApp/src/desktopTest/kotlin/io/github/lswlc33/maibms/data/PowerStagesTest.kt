package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.ui.powerGearNo
import io.github.lswlc33.maibms.ui.powerGaugeFracs
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

    @Test fun gaugeFractionMath() {
        // 填充比例直接调 UI 用的本体（曾经测试复算一份、照着 bug 一起写歪）
        val stages = listOf(1000, 2000, 3000)
        // 800W：第 1 档（0~1k）走 80%，其余空
        assertEquals(listOf(0.8f, 0f, 0f), powerGaugeFracs(800, stages))
        // 1500W：第 1 档走满，第 2 档（1k~2k）走 50%
        assertEquals(listOf(1f, 0.5f, 0f), powerGaugeFracs(1500, stages))
        // 2500W：前两档走满，第 3 档（2k~3k）走 50% —— 曾是档位文案与格子矛盾的分歧区间
        assertEquals(listOf(1f, 1f, 0.5f), powerGaugeFracs(2500, stages))
        // 3000W 顶格及超出：格子画满（超出部分不封顶显示，底行照实给功率值）
        assertEquals(listOf(1f, 1f, 1f), powerGaugeFracs(3000, stages))
        assertEquals(listOf(1f, 1f, 1f), powerGaugeFracs(9999, stages))
        // 0W / 负功率（充电）：全部空
        assertEquals(listOf(0f, 0f, 0f), powerGaugeFracs(0, stages))
        assertEquals(listOf(0f, 0f, 0f), powerGaugeFracs(-500, stages))
        // 单档 = 整条卡面一格
        assertEquals(listOf(0.5f), powerGaugeFracs(500, listOf(1000)))
        // 不等距阶梯：1k/5k/6k → 区间 1k/4k/1k，中段走得慢
        assertEquals(listOf(1f, 0.25f, 0f), powerGaugeFracs(2000, listOf(1000, 5000, 6000)))
    }

    @Test fun gearNoMatchesTheFillingCell() {
        // 不变量：文案档位 == 第一个没走满的格子（全满=末档）。
        // 曾经的 bug 就是两者各用一套口径，在 2000~3000W 区间自相矛盾。
        val stages = listOf(1000, 2000, 3000)
        for (p in 0..4500 step 7) {
            val fromCells = powerGaugeFracs(p, stages)
                .indexOfFirst { it < 1f }
                .let { if (it < 0) stages.size else it + 1 }
            assertEquals(fromCells, powerGearNo(p, stages), "功率 ${p}W 时档位文案与格子填充不一致")
        }
        // 空阶梯没有档位；负功率（充电）由 UI 显示「待机」，档位函数本身不越界
        assertEquals(0, powerGearNo(500, emptyList()))
        assertEquals(1, powerGearNo(-500, stages))
        assertTrue(powerGearNo(9999, stages) == stages.size)
        // 不等距阶梯同样自洽
        val uneven = listOf(1000, 5000, 6000)
        for (p in 0..7000 step 13) {
            val fromCells = powerGaugeFracs(p, uneven)
                .indexOfFirst { it < 1f }
                .let { if (it < 0) uneven.size else it + 1 }
            assertEquals(fromCells, powerGearNo(p, uneven), "不等距阶梯 ${p}W 不一致")
        }
    }
}
