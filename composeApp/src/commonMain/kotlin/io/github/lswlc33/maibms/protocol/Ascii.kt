package io.github.lswlc33.maibms.protocol

/**
 * ASCII 编解码（公共代码里没有 java 的 `Charsets.US_ASCII`：
 * 它定义在 kotlin-stdlib 的 jvmMain，iOS/Native 上不可用）。
 *
 * 语义对齐 Java 的 US_ASCII 编解码器：
 * - 编码：非 ASCII 字符一律替换为 `?`（0x3F），保证「一个字符 = 一个字节」；
 * - 解码：≥0x80 的字节替换为替换符 U+FFFD。
 *
 * 用途：密码槽（0x23 数据区）与身份区文本——都是定长 ASCII 字段，长度必须稳定。
 */
internal fun String.asciiBytes(): ByteArray = ByteArray(length) { i ->
    val c = this[i]
    if (c.code <= 0x7F) c.code.toByte() else '?'.code.toByte()
}

internal fun ByteArray.asciiString(): String {
    val sb = StringBuilder(size)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(if (v < 0x80) v.toChar() else '\uFFFD')
    }
    return sb.toString()
}
