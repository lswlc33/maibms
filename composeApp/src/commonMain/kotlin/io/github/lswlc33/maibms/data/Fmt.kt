package io.github.lswlc33.maibms.data

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 公共代码里的最小 printf 子集——**不能用 JVM 的 `String.format`**：它定义在 kotlin-stdlib
 * 的 jvmMain（`StringsJVM.kt`），iOS/Native 上不存在，留在 commonMain 会编译失败。
 *
 * 只实现本仓库用到的格式：`%s` `%d` `%X` `%x` `%02d` `%02X` `%04X` `%.Nf`，`%%` 转义。
 * 宽度/精度按 Java 语义处理；不认识的转换符原样输出，宁可显示难看也不崩。
 */
internal fun String.fmt(vararg args: Any?): String {
    val out = StringBuilder(length + args.size * 4)
    var argIdx = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%') { out.append(c); i++; continue }
        if (i + 1 < length && this[i + 1] == '%') { out.append('%'); i += 2; continue }
        // 解析 %[0][width][.precision]conv
        var j = i + 1
        val zeroPad = j < length && this[j] == '0'
        if (zeroPad) j++
        var width = 0
        while (j < length && this[j].isDigit()) { width = width * 10 + (this[j] - '0'); j++ }
        var precision = -1
        if (j < length && this[j] == '.') {
            j++
            var p = 0
            while (j < length && this[j].isDigit()) { p = p * 10 + (this[j] - '0'); j++ }
            precision = p
        }
        if (j >= length) { out.append(c); i++; continue }
        when (this[j]) {
            's' -> out.append(args.getOrNull(argIdx++)?.toString() ?: "null")
            'd' -> out.append(padNumber(asLong(args.getOrNull(argIdx++)).toString(), width, zeroPad))
            'X' -> out.append(padNumber(hexDigits(args.getOrNull(argIdx++), upper = true), width, zeroPad))
            'x' -> out.append(padNumber(hexDigits(args.getOrNull(argIdx++), upper = false), width, zeroPad))
            'f' -> out.append(formatFixed(asDouble(args.getOrNull(argIdx++)), precision.coerceAtLeast(0)))
            else -> { out.append('%'); i++; continue }
        }
        i = j + 1
    }
    return out.toString()
}

/**
 * `%x` / `%X` 的取值语义必须与 Java 的 Formatter 一致：**按参数自身位宽当无符号数**打印。
 * 例如 `(byte) 0xD8` → `D8`、`-40`（Int）→ `FFFFFFD8`、`-1L` → `FFFFFFFFFFFFFFFF`。
 * 若按有符号打印会得到 `-28` 这类串——协议帧里所有 ≥0x80 的字节都会错（曾经踩到，
 * FrameTest 的逐字节遮蔽断言立刻抓了出来）。
 */
private fun hexDigits(v: Any?, upper: Boolean): String {
    val masked: ULong = when (v) {
        is Byte -> v.toLong().toULong() and 0xFFu
        is Short -> v.toLong().toULong() and 0xFFFFu
        is Int -> v.toLong().toULong() and 0xFFFF_FFFFu
        is Long -> v.toULong()
        is UByte -> v.toULong()
        is UShort -> v.toULong()
        is UInt -> v.toULong()
        is ULong -> v
        is Boolean -> if (v) 1uL else 0uL
        else -> asLong(v).toULong()
    }
    val s = masked.toString(16)
    return if (upper) s.uppercase() else s
}

/** printf 的整数字符串补位：零填充时负号在补位之外（-007 而非 0-07） */
private fun padNumber(s: String, width: Int, zeroPad: Boolean): String {
    if (s.length >= width) return s
    val neg = s.startsWith("-")
    val body = if (neg) s.substring(1) else s
    val padded = body.padStart(width - if (neg) 1 else 0, if (zeroPad) '0' else ' ')
    return if (neg) "-$padded" else padded
}

/** 定点小数（四舍五入到指定位数），等价于 printf 的 `%.Nf` */
internal fun formatFixed(v: Double, digits: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Inf" else "-Inf"
    val scale = 10.0.pow(digits)
    val scaled = (abs(v) * scale).roundToLong()
    val intPart = scaled / scale.toLong()
    if (digits == 0) {
        val neg = v < 0 && intPart != 0L
        return (if (neg) "-" else "") + intPart.toString()
    }
    val frac = (scaled % scale.toLong()).toString().padStart(digits, '0')
    val neg = v < 0 && (intPart != 0L || frac.any { it != '0' })
    return (if (neg) "-" else "") + intPart.toString() + "." + frac
}

private fun asLong(v: Any?): Long = when (v) {
    is Long -> v
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is UInt -> v.toLong()
    is ULong -> v.toLong()
    is Double -> v.toLong()
    is Float -> v.toLong()
    is Boolean -> if (v) 1L else 0L
    else -> 0L
}

private fun asDouble(v: Any?): Double = when (v) {
    is Double -> v
    is Float -> v.toDouble()
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Short -> v.toDouble()
    is Byte -> v.toDouble()
    else -> 0.0
}

/**
 * 分钟 → 紧凑时长（充电/放电剩余时间，扩展段 86/88）：0/负=设备未报，显示 "--"。
 * <60 "X分"；<1天 "X时Y分"（整时省分）；再大 "X天Y时"（整时省时）。
 */
internal fun fmtRemainingMin(min: Int): String = when {
    min <= 0 -> "--"
    min < 60 -> "${min}分"
    min < 1440 -> {
        val h = min / 60; val m = min % 60
        if (m == 0) "${h}时" else "${h}时${m}分"
    }
    else -> {
        val d = min / 1440; val h = min % 1440 / 60
        if (h == 0) "${d}天" else "${d}天${h}时"
    }
}

/**
 * 秒 → 紧凑时长（本次充电时长/上次充电间隔，扩展段 78/82）：0/负="--"。
 * 分钟粒度截断：<60 "<1分"；<1时 "X分"；<1天 "X时Y分"；再大 "X天Y时"。
 */
internal fun fmtDurationSec(sec: Long): String = when {
    sec <= 0L -> "--"
    sec < 60L -> "<1分"
    sec < 3600L -> "${sec / 60}分"
    sec < 86400L -> {
        val h = sec / 3600; val m = sec % 3600 / 60
        if (m == 0L) "${h}时" else "${h}时${m}分"
    }
    else -> {
        val d = sec / 86400; val h = sec % 86400 / 3600
        if (h == 0L) "${d}天" else "${d}天${h}时"
    }
}
