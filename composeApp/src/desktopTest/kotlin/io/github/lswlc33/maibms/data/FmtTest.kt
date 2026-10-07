package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 公共代码的 printf 子集（[fmt]）与 JVM `String.format` 的语义对照。
 *
 * 这个测试的价值在于**用 JVM 自带的 String.format 当参照**：本实现就是为 iOS/Native
 * 顶替它而写的（String.format 只在 kotlin-stdlib 的 jvmMain），两边逐例比对能保证
 * 换了实现之后同样的格式串输出完全一致——协议帧十六进制、电压电流小数位都靠它。
 */
class FmtTest {

    @Test fun hexMatchesJavaForAllByteValues() {
        // 关键回归：Java 的 %02X 对 Byte 按**无符号**打印（(byte)0xD8 → "D8"），
        // 曾按有符号实现成 "-28"，协议帧里所有 ≥0x80 的字节全错
        for (i in 0..255) {
            val b = i.toByte()
            assertEquals("%02X".format(b), "%02X".fmt(b), "byte $i")
        }
    }

    @Test fun matchesJavaForCommonFormats() {
        val cases: List<Pair<String, List<Any?>>> = listOf(
            "%02X" to listOf(0xD8.toByte()),
            "%X" to listOf(330),
            "%X" to listOf(-40),
            "%X" to listOf(-1L),
            "%04X" to listOf(0x1234),
            "%04X" to listOf(0x1),
            "%d" to listOf(42),
            "%d" to listOf(-42),
            "%02d" to listOf(7),
            "%s" to listOf("文本"),
            "%s" to listOf(null),
            "func=%02X reg=%d len=%d %s" to listOf(0x11, 0, 168, "02 03"),
            "F(%02X reg=%04X len=%d seg2=%s)" to listOf(0x23, 0x0A, 12, false),
            "%.3f" to listOf(4.2654),
            "%.3f" to listOf(-0.0005),
            "%.2f" to listOf(85.245),
            "%.1f" to listOf(0.0),
            "%.0f" to listOf(2.5),
            "%.1f°" to listOf(22.04),
            "参数 0x%X 限值 %d" to listOf(104, 3000),
            "首屏 %.1fs" to listOf(1.234),
            "%% 转义 %d" to listOf(1),
            "%02d:%02d:%02d" to listOf(1, 2, 59),
        )
        for ((pattern, args) in cases) {
            assertEquals(
                pattern.format(*args.toTypedArray()),
                pattern.fmt(*args.toTypedArray()),
                "格式串 $pattern 参数 $args",
            )
        }
    }

    @Test fun fixedPointFormatting() {
        // 与 Java 的 %.Nf 对照（注意 Java 用 HALF_UP 且按二进制实际值取整）
        assertEquals(String.format("%.0f", -5.0), formatFixed(-5.0, 0))
        assertEquals(String.format("%.1f", -0.5), formatFixed(-0.5, 1))
        assertEquals(String.format("%.3f", 1.0), formatFixed(1.0, 3))
        assertEquals(String.format("%.3f", 0.0), formatFixed(0.0, 3))
        assertEquals(String.format("%.1f", 10.0), formatFixed(10.0, 1))
        assertEquals(String.format("%.3f", 4.2654), formatFixed(4.2654, 3))
        assertEquals(String.format("%.2f", 85.245), formatFixed(85.245, 2))
        assertEquals(String.format("%.1f", -12.34), formatFixed(-12.34, 1))
    }

    @Test fun remainingMinBuckets() {
        // 卡3 小灰字的分钟粒度：0/负=设备未报 → "--"；分/时/天逐级进位，整单位省尾
        assertEquals("--", fmtRemainingMin(0))
        assertEquals("--", fmtRemainingMin(-1))
        assertEquals("45分", fmtRemainingMin(45))
        assertEquals("6时", fmtRemainingMin(360))
        assertEquals("6时59分", fmtRemainingMin(419))
        assertEquals("23时", fmtRemainingMin(1380))
        assertEquals("23时59分", fmtRemainingMin(1439))
        assertEquals("1天", fmtRemainingMin(1440))
        assertEquals("2天3时", fmtRemainingMin(3075))
    }

    @Test fun durationSecBuckets() {
        // 已充/距上次充电的秒粒度（分钟向下截断）：真机样例 2459s = 40分、5037s = 1时23分
        assertEquals("--", fmtDurationSec(0))
        assertEquals("<1分", fmtDurationSec(59))
        assertEquals("1分", fmtDurationSec(60))
        assertEquals("40分", fmtDurationSec(2459))
        assertEquals("1时", fmtDurationSec(3600))
        assertEquals("1时23分", fmtDurationSec(5037))
        assertEquals("23时", fmtDurationSec(82_800))
        assertEquals("23时59分", fmtDurationSec(86_399))
        assertEquals("1天", fmtDurationSec(86400))
        assertEquals("3天2时", fmtDurationSec(266_400))
    }
}
