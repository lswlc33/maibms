package io.github.lswlc33.maibms.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * 真机联调日志：扫描 / 连接 / 通道探测 / 收发帧都进这里，开发者页直接显示。
 * 没有 adb 也能在手机上看到链路发生了什么；同时仍 println 到 logcat。
 */
object BmsLog {
    private const val MAX = 300
    private val origin = TimeSource.Monotonic.markNow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    val frameLogOn = MutableStateFlow(true)

    fun add(tag: String, msg: String) {
        println("[ANTBMS/$tag] $msg")
        if (!frameLogOn.value) return
        val ms = origin.elapsedNow().inWholeMilliseconds
        val stamp = "%6d.%03d".format(ms / 1000, ms % 1000)
        _lines.value = (_lines.value + "$stamp $tag $msg").takeLast(MAX)
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }

    fun clear() { _lines.value = emptyList() }
}
