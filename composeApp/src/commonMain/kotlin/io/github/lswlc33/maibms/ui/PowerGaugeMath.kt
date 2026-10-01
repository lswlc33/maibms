package io.github.lswlc33.maibms.ui

/**
 * 卡3 功率换挡进度条的纯数学（无 Compose 依赖，单测直接调用本体）。
 *
 * 阶梯语义 = **每档的上限（W）**：如 1000/2000/3000 表示「1 档走到 1kW 换 2 档、
 * 2kW 换 3 档、3kW 顶格」。第 k 档占区间 (T(k-1), T(k)]，第 1 档从 0 起。
 *
 * 这组函数之所以单独抽出来：档位文案与格子填充曾经各用一套口径
 * （文案按上限、格子按每档宽度），2000~3000W 区间会出现「文案 3 档、第 3 格还是空的」，
 * 而单测里是复算一份实现、照着 bug 一起写歪了。现在测试直接调本体，
 * 并用「文案档位 == 第一个没走满的格子」这条不变量兜底。
 */

/** 各档填充比例（0..1，与 stages 一一对应）；功率为负（充电）时全部为 0 */
fun powerGaugeFracs(powerW: Int, stages: List<Int>): List<Float> {
    var start = 0
    return stages.map { cap ->
        val width = (cap - start).coerceAtLeast(1)   // 去重后不会出现 0 宽；防御除零
        val frac = when {
            powerW >= cap -> 1f
            powerW <= start -> 0f
            else -> (powerW - start).toFloat() / width
        }
        start = cap
        frac
    }
}

/**
 * 当前档位（1 起）：第一个功率还没走满的档；全部走满 = 末档（顶格）。
 * 空阶梯返回 0（调用方此时不显示档位）。
 */
fun powerGearNo(powerW: Int, stages: List<Int>): Int =
    if (stages.isEmpty()) 0
    else stages.indexOfFirst { powerW < it }.let { if (it < 0) stages.size else it + 1 }
