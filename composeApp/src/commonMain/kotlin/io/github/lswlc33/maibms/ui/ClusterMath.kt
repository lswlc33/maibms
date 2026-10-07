package io.github.lswlc33.maibms.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import io.github.lswlc33.maibms.data.fmt

/**
 * 横屏表盘的纯数学口径——`Plan/横屏仪表原型.html`「静态函数表」的 Kotlin 直迁，
 * 原型 JS 即参照实现。SVG 与 Compose 的角度坐标系完全同口径
 * （0° = 3 点钟方向、顺时针为正），drawArc(startAngle, sweepAngle) 可按这里的值直画。
 *
 * 阶梯两口径兼容：新规格四档 [-副档位, 一/二/三档(正)]（负数=充电/动能回收带）；
 * 旧规格 1~3 个正档（无副档位）——充电带端回退取第 1 档上限。
 */
object ClusterMath {

    /** 扫弧 270°、开口朝下：起点 135°（盘面左下），竖直向上 = 盘面正中 */
    const val START_ANGLE = 135f
    const val SWEEP = 270f
    val END_ANGLE = START_ANGLE + SWEEP

    /** 未连接/无数据时指针/弧的静默位 = 扫弧起点 */
    const val REST_ANGLE = START_ANGLE

    /** 单体电压窗口（V/串）：三元锂默认，后续可按电池类型校准 */
    const val CELL_WINDOW_MIN = 2.5f
    const val CELL_WINDOW_MAX = 4.5f

    /** 保护线默认值（V/串）：实机可读参数表保护项覆盖 */
    const val CELL_OVP = 4.30f
    const val CELL_UVP = 2.80f

    /** 低 SOC 阈值（与卡1 低电红同阈值） */
    const val SOC_LOW = 15

    /** 值 → 盘面角度：sweepAngle(52.5f, 94.5f, 87.87f) = 362.4° */
    fun sweepAngle(min: Float, max: Float, v: Float): Float =
        START_ANGLE + ((v - min) / (max - min)).coerceIn(0f, 1f) * SWEEP

    /** 电压量程：串数 × 单体窗口。(21) → 52.5..94.5 */
    fun voltageRange(cellCount: Int): ClosedFloatingPointRange<Float> =
        cellCount * CELL_WINDOW_MIN..cellCount * CELL_WINDOW_MAX

    /** 过充红区起点（V）。(21) → 90.3 */
    fun vRedFrom(cellCount: Int): Float = cellCount * CELL_OVP

    /** 过放琥珀区终点（V）。(21) → 58.8 */
    fun vAmberTo(cellCount: Int): Float = cellCount * CELL_UVP

    /**
     * 功率量程（W）：±max(|副档位|, 末档)，向上取整到 500W。
     * [-1500,1000,3000,5000] → -5000..5000；[1000,2000,3000]（旧口径）→ -3000..3000；
     * 空（未设阶梯）→ ±3000 兜底。
     */
    fun powerRangeW(stages: List<Int>): ClosedFloatingPointRange<Float> {
        val maxAbs = stages.maxOfOrNull { abs(it) } ?: 3000
        val rounded = (ceil(maxAbs / 500f) * 500f).toInt().coerceAtLeast(500)
        return -rounded.toFloat()..rounded.toFloat()
    }

    /** 充电绿带左端（W，负值）：新口径取副档位（首个负档），旧口径回退第 1 档上限 */
    fun chargeBandFromW(stages: List<Int>): Float {
        val band = stages.firstOrNull { it < 0 } ?: stages.firstOrNull() ?: 1500
        return -abs(band).toFloat()
    }

    /** 放电半区色带（W）：0→一档 绿，一→二档 蓝，二→末档 红（正档位序） */
    data class Zone(val fromW: Float, val toW: Float)

    fun dischargeZonesW(stages: List<Int>): List<Zone> {
        val positive = stages.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return emptyList()
        val bounds = mutableListOf(0f)
        positive.forEach { bounds += it.toFloat() }
        val colors = bounds.size - 1
        return (0 until colors).map { i -> Zone(bounds[i], bounds[i + 1]) }
    }

    /** 功率值 → 色带颜色序号（0=绿 1=蓝 2=红；负值=充电绿，统一按 0 处理） */
    fun zoneIndexW(stages: List<Int>, w: Float): Int {
        val zones = dischargeZonesW(stages)
        zones.forEachIndexed { i, z -> if (w <= z.toW + 1e-3f) return i }
        return zones.lastIndex.coerceAtLeast(0)
    }

    /** 刻度：major=主刻度（长），其余副刻度（短） */
    data class Tick(val value: Float, val major: Boolean)

    /** 电压盘刻度：副 1V / 主 5V */
    fun ticksV(range: ClosedFloatingPointRange<Float>): List<Tick> =
        buildTicks(range, minorStep = 1f, majorStep = 5f)

    /**
     * 功率盘刻度（W 域）：副 250W / 主 500W。
     * [useKw] 只决定 [Tick.label] 的文本：W 模式每 2000W 一标（"0/±2000/±4000"），
     * kW 模式每 1000W 一标（"0/±1..±5"）。A 案表盘环上不标数字，label 供需要时使用。
     */
    fun ticksP(range: ClosedFloatingPointRange<Float>, useKw: Boolean): List<Tick> =
        buildTicks(range, minorStep = 250f, majorStep = 500f, labelStepW = if (useKw) 1000f else 2000f,
            labelFmt = if (useKw) { v -> if (v == 0f) "0" else (if (v > 0) "+" else "") + (v / 1000).toInt() }
            else { v -> v.toInt().toString() })

    private fun buildTicks(
        range: ClosedFloatingPointRange<Float>,
        minorStep: Float, majorStep: Float,
        labelStepW: Float? = null, labelFmt: ((Float) -> String)? = null,
    ): List<Tick> {
        val out = mutableListOf<Tick>()
        // 首根对齐到 minor 网格：电压量程 52.5 起、主刻度在 5 的倍数（55/60/...），
        // 不对齐的话步进点永远错过 major 网格，全盘只剩等长副刻度
        var v = ceil(range.start / minorStep - 1e-4f) * minorStep
        while (v <= range.endInclusive + 1e-3f) {
            val major = abs(v / majorStep - (v / majorStep).roundToInt()) < 1e-4f
            val label = if (major && labelStepW != null &&
                abs(v / labelStepW - (v / labelStepW).roundToInt()) < 1e-4f) labelFmt?.invoke(v) else null
            out += Tick(v, major)
            v += minorStep
        }
        return out
    }

    /** SOC 电量条亮段数：round(soc/10)。76 → 8 */
    fun socSegments(soc: Int): Int = (soc / 10f).roundToInt().coerceIn(0, 10)

    /** 低电量：电量条整条转红 */
    fun socLow(soc: Int): Boolean = soc < SOC_LOW

    /** 功率显示：W 模式取整带符号（"+1310"），kW 模式两位小数（"+1.31"） */
    fun fmtPower(w: Float, useKw: Boolean): String {
        val sign = if (w > 0.5f) "+" else ""
        return if (useKw) sign + "%.2f".fmt(w / 1000) else sign + w.roundToInt().toString()
    }
}
