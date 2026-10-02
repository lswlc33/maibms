package io.github.lswlc33.maibms.ui

/**
 * 卡3 功率换挡进度条的纯数学（无 Compose 依赖，单测直接调用本体）。
 *
 * 阶梯语义 = **每档的上限（W）**：如 1000/2000/3000 表示「1 档走到 1kW 换 2 档、
 * 2kW 换 3 档、3kW 顶格」。第 k 档占区间 (T(k-1), T(k)]。
 *
 * 展示形态 = **每档一条完整进度条**（刻度 0~本档上限），功率落在哪个档整条就切到
 * 哪条：500W 用 0~1000 的条、1500W 切 0~2000 的条。填充 = (功率 / 本档上限)²：
 * 线性前 70% 只显示 50%、末段 30% 冲完剩下的一半（用户指定锚点：进度条前面 50%
 * 相当于原本的 70%），显示 ≤ 线性，到上限同时到达 100%。
 *
 * 这组函数之所以单独抽出来：档位文案与进度条填充曾各用一套口径互相矛盾，
 * 现在条号/填充/风格文案都从 powerGearNo 一根藤上派生，测试直接调本体兜底。
 */

/**
 * 当前档位（1 起）：第一个功率还没走满的档；全部走满 = 末档（顶格）。
 * 空阶梯返回 0（调用方此时不显示档位）。
 */
fun powerGearNo(powerW: Int, stages: List<Int>): Int =
    if (stages.isEmpty()) 0
    else stages.indexOfFirst { powerW < it }.let { if (it < 0) stages.size else it + 1 }

/** 当前档那条完整进度条的下标（0 起）；负功率（充电）按第 1 条画空轨道 */
fun powerGaugeBarIndex(powerW: Int, stages: List<Int>): Int =
    if (stages.isEmpty()) 0
    else (powerGearNo(powerW, stages) - 1).coerceIn(0, stages.lastIndex)

/**
 * 当前档进度条的填充比例（0..1）= (功率 / 本档上限)²：线性 70% 只显示 ~50%
 * （用户指定锚点；想调强弱只改这个指数，2=平方、1=线性）。
 * 负功率 0，顶格封顶 1。
 */
fun powerGaugeBarFill(powerW: Int, stages: List<Int>): Float {
    if (stages.isEmpty() || powerW <= 0) return 0f
    val linear = (powerW.toFloat() / stages[powerGaugeBarIndex(powerW, stages)]).coerceIn(0f, 1f)
    return linear * linear
}
