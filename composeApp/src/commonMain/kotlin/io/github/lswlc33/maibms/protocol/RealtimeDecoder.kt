package io.github.lswlc33.maibms.protocol

/**
 * 实时数据（0x11 应答）解码，docs/05-实时数据.md。
 * 数据区布局：头部 28B + 单体 2N + 温度 2M + 核心段 78B + 扩展段 24B(可选)。
 * 扩展段判据：总长 > 106 + 2N + 2M（不是 130+…）。
 */
object RealtimeDecoder {

    class Result(
        val permission: Int,
        val battStateCode: Int,
        val cellCount: Int,
        val tempCount: Int,
        val protectBits: ULong,
        val warnBits: ULong,
        val cells: List<Double>,           // V
        val temps: List<Double>,           // ℃（s16）
        val mosTemp: Double,
        val balanceTemp: Double,
        val totalVoltage: Double,          // V
        val current: Double,               // A（s16 /10）
        val soc: Int,
        val soh: Int,
        val chMos: Int,
        val disMos: Int,
        val balanceState: Int,
        val bmsType: Int,
        val physicalCapAh: Double,
        val remainCapAh: Double,
        val cycleCapAh: Double,
        val powerW: Int,
        val runtimeSec: Long,       // 设备累计运行秒数
        val balanceBits: ULong,
        val maxCellV: Double, val maxCellIdx: Int,
        val minCellV: Double, val minCellIdx: Int,
        val deltaCellV: Double,
        val avgCellV: Double,
        val batteryType: Int,
        val totalDischargeCapAh: Double,
        val totalChargeCapAh: Double,
        val hasExt: Boolean,
    )

    fun decode(data: ByteArray): Result {
        require(data.size >= 28) { "数据区过短：${data.size}" }
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u16le(i: Int) = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
        fun s16le(i: Int): Int {
            val v = u16le(i); return if (v >= 0x8000) v - 0x10000 else v
        }
        fun u32le(i: Int): Long =
            (data[i].toLong() and 0xFF) or ((data[i + 1].toLong() and 0xFF) shl 8) or
            ((data[i + 2].toLong() and 0xFF) shl 16) or ((data[i + 3].toLong() and 0xFF) shl 24)
        fun s32le(i: Int): Int = u32le(i).toInt()
        fun u64le(i: Int): ULong {
            var v = 0UL
            for (k in 7 downTo 0) v = (v shl 8) or (data[i + k].toULong() and 0xFFu)
            return v
        }

        val permission = u8(0)
        val battState = u8(1)
        val m = u8(2)
        val n = u8(3)
        val protect = u64le(4)
        val warn = u64le(12)

        val t0 = 28 + 2 * n + 2 * m
        val hasExt = data.size > 106 + 2 * n + 2 * m
        // 头部声明的串数/温度数可能大于实际数据区（截断帧/脏数据）：
        // 只查 28 会在 t0+69 处越界，帧被 runCatching 静默吞掉；这里给出可排查的失败原因
        require(data.size >= t0 + 70) {
            "数据区截断：需 ${t0 + 70} 字节（$n 串 + $m 温度），实际 ${data.size}"
        }

        // 单体电压：u16 & 0x1FFF / 1000
        val cells = (0 until n).map { (u16le(28 + 2 * it) and 0x1FFF) / 1000.0 }
        // 温度：s16 ℃（-40 = 未接）
        val temps = (0 until m).map { s16le(28 + 2 * n + 2 * it) / 1.0 }

        val mosTemp = s16le(t0) / 1.0
        val balanceTemp = s16le(t0 + 2) / 1.0
        val totalV = u16le(t0 + 4) / 100.0
        val current = s16le(t0 + 6) / 10.0
        val soc = u16le(t0 + 8)
        val soh = u16le(t0 + 10)
        val disMos = u8(t0 + 12)
        val chMos = u8(t0 + 13)
        val balanceState = u8(t0 + 14)
        val bmsType = u8(t0 + 15)
        val physicalCap = u32le(t0 + 16) / 1_000_000.0
        val remainCap = u32le(t0 + 20) / 1_000_000.0
        val cycleCap = u32le(t0 + 24) / 1000.0
        val power = s32le(t0 + 28)
        val runtimeSec = u32le(t0 + 32)   // 秒（2026-09-30 实测 1字/s；官方 ms 解读会慢 1000 倍）
        val balanceBits = u32le(t0 + 36).toULong()   // 位图机型：位 i = 第 i+1 串均衡中
        val maxV = u16le(t0 + 40) / 1000.0; val maxIdx = u16le(t0 + 42)
        val minV = u16le(t0 + 44) / 1000.0; val minIdx = u16le(t0 + 46)
        val deltaV = u16le(t0 + 48) / 1000.0
        val avgV = u16le(t0 + 50) / 1000.0
        // t0+52/54/56/58: DSV/DV/CV/COM
        val batteryType = u16le(t0 + 60)
        val totalDisCap = u32le(t0 + 62) / 1000.0
        val totalChgCap = u32le(t0 + 66) / 1000.0
        // t0+70/74: 累计放/充时间

        return Result(
            permission, battState, n, m, protect, warn,
            cells, temps, mosTemp, balanceTemp,
            totalV, current, soc, soh, chMos, disMos, balanceState, bmsType,
            physicalCap, remainCap, cycleCap, power, runtimeSec, balanceBits,
            maxV, maxIdx, minV, minIdx, deltaV, avgV, batteryType,
            totalDisCap, totalChgCap, hasExt
        )
    }

    fun battStateText(code: Int): String = when (code) {
        0 -> "无状态"; 1 -> "静止"; 2 -> "充电"; 3 -> "待机"; 4 -> "放电"; 5 -> "异常"; else -> "未知($code)"
    }

    fun batteryTypeText(type: Int): String = CELL_TYPE[type.toLong()] ?: "未知"
}
