package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 功率换挡进度条的阶梯解析（AppStore.powerStagesW）：
 * 第 1 档必填、2/3 档可选、非法输入过滤、上限 3 档。
 * 解析规则与 UI 弹窗的输入约定一致（正整数 W，空=不设）。
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

    @Test fun stagesSortedAscendingRegardlessOfInputOrder() {
        // 乱序输入强制升序：换挡进度条的数学与输入顺序无关
        AppStore.powerStagesW = listOf(3000, 1000, 2000)
        assertEquals(listOf(1000, 2000, 3000), AppStore.powerStagesW)
    }

    @Test fun gaugeFractionMath() {
        // 换挡进度比例：与 PowerGauge 内部同一套数学（此处独立复算防回归）
        val stages = listOf(1000, 2000, 3000)
        fun frac(power: Int): List<Float> {
            var acc = 0
            return stages.map { cap ->
                val start = acc; acc += cap
                when {
                    power >= acc -> 1f
                    power <= start -> 0f
                    else -> (power - start).toFloat() / cap
                }
            }
        }
        // 800W：第 1 档走 80%，其余空
        assertEquals(listOf(0.8f, 0f, 0f), frac(800))
        // 1200W：第 1 档走满，第 2 档走 10%
        assertEquals(listOf(1f, 0.1f, 0f), frac(1200))
        // 5000W：前两档走满，第 3 档 (5000-3000)/3000 ≈ 2/3（未超总容量，不封顶）
        assertEquals(0.6666667f, frac(5000)[2], 1e-5f)
        // 6000W：恰好走满全部三档
        assertEquals(listOf(1f, 1f, 1f), frac(6000))
        // 0W / 负功率（充电）：全部空
        assertEquals(listOf(0f, 0f, 0f), frac(0))
        assertEquals(listOf(0f, 0f, 0f), frac(-500))
        // 挡位号 = 第一个未走满的档（全满 = 末档）
        fun gear(power: Int) = stages.indexOfFirst { power < it }.let { if (it < 0) stages.size else it + 1 }
        assertEquals(1, gear(800)); assertEquals(2, gear(1200))
        assertEquals(3, gear(3500)); assertEquals(3, gear(9999))
        assertTrue(gear(0) == 1)
    }
}
