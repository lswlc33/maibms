package io.github.lswlc33.maibms.ui

import kotlin.math.abs

/**
 * 卡3 功率换挡进度条的纯数学（无 Compose 依赖，单测直接调用本体）。
 *
 * 阶梯形态 = `[副档位(负), 一/二/三档(正)]`：副档位是**充电与动能回收共用**的固定量程
 * （默认 -1500W，UI 画成节能绿的反向条）；三个正档是放电档位（默认 1000/3000/5000W）。
 * 条号：**0 = 副档位**（反向填充），**1/2/3 = 一/二/三档**（正向填充，末档=运动红）。
 * 正档语义不变：功率升到某档上限就整条切下一条，顶格封顶。
 *
 * 静置死区：|功率| < [POWER_IDLE_DEADBAND_W] 一律当 0（车辆静置时的涓流/采样噪声
 * 不当成充放电）——显示为第 1 档空条，也避免充/放电临界时副档位条来回闪烁。
 *
 * 填充 = (|功率| / 本条上限)²：线性 70% 只显示约 50%（用户指定锚点，前段压缩后段冲刺）。
 *
 * 这组函数是档位/填充/到顶判定的唯一口径，测试直接调本体兜底。
 */

/** 静置死区（W）：功率绝对值低于它一律当 0 */
const val POWER_IDLE_DEADBAND_W = 20

/** 死区归零：静置功耗与采样噪声不作充放电计量 */
fun powerGaugeDebounced(powerW: Int): Int =
    if (abs(powerW) < POWER_IDLE_DEADBAND_W) 0 else powerW

/** 正档上限列表（去掉副档位）；阶梯不规范（无负值）时全部按正档处理 */
fun powerGaugeCaps(stages: List<Int>): List<Int> =
    if (stages.firstOrNull()?.let { it < 0 } == true) stages.drop(1) else stages

/** 副档位量程（正数=绝对值）；阶梯里没有负值时返回 null（无副档位） */
fun powerGaugeSubCap(stages: List<Int>): Int? =
    stages.firstOrNull()?.takeIf { it < 0 }?.let { abs(it) }

/**
 * 当前档的条号：0 = 副档位（充电/动能回收，反向条）；1..n = 放电档
 *（第一个功率还没走满的档；全走满 = 末档顶格）。空阶梯返回 1（调用方此时不显示进度条）。
 */
fun powerGaugeBarIndex(powerW: Int, stages: List<Int>): Int {
    val caps = powerGaugeCaps(stages)
    if (caps.isEmpty()) return 1
    val p = powerGaugeDebounced(powerW)
    if (p < 0 && powerGaugeSubCap(stages) != null) return 0
    if (p < 0) return 1   // 无副档位配置的防御路径：负功率按第 1 档处理
    val idx = caps.indexOfFirst { p < it }
    return if (idx < 0) caps.size else idx + 1
}

/** 本条进度条的填充比例（0..1）= (|功率| / 本条上限)²；顶格封顶 1，静置死区内为 0 */
fun powerGaugeBarFill(powerW: Int, stages: List<Int>): Float {
    val p = powerGaugeDebounced(powerW)
    val barIdx = powerGaugeBarIndex(powerW, stages)
    val cap = if (barIdx == 0) powerGaugeSubCap(stages)
              else powerGaugeCaps(stages).getOrNull(barIdx - 1)
    if (cap == null || cap <= 0) return 0f
    val linear = (abs(p).toFloat() / cap).coerceIn(0f, 1f)
    return linear * linear
}

/** 功率冲破末档上限（顶破）：用于「到顶」摇晃提示；副档位与静置死区内都不算 */
fun powerGaugeOverTop(powerW: Int, stages: List<Int>): Boolean {
    val caps = powerGaugeCaps(stages)
    return caps.isNotEmpty() && powerGaugeDebounced(powerW) > caps.last()
}
