package io.github.lswlc33.maibms.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 链路与轮询的运行时统计（开发者页展示，只在内存里、重启清零）。
 *
 * 加它的理由：此前整个应用**没有任何性能度量**——只有首屏耗时。没有它，"轮询快不快、
 * 有没有丢帧、换了档位/通道之后是好了还是坏了"只能靠感觉，性能优化也就无从判断起。
 *
 * 三件事各有用处：
 * - **往返耗时**：请求发出到应答回来，直接反映链路与设备的响应速度（换轮询档、切通道、
 *   弱信号时它最先变）；
 * - **成功率**：窗口内收到应答的拍数占比。每拍超时是常态（设备忙/信号抖动），
 *   但持续低于一半就说明该降档或换通道了；
 * - **丢帧数**：BLE 通知缓冲写满被丢掉的帧数（`tryEmit` 返回 false）。它是"采集线程卡住"
 *   的唯一直接证据，此前这个返回值被直接扔掉、丢了帧没人知道。
 */
object LinkStats {

    /** 统计窗口：最近 [WINDOW] 拍 */
    data class Poll(
        /** 窗口内样本数 */
        val samples: Int,
        /** 窗口内收到应答的拍数 */
        val ok: Int,
        /** 成功拍的往返耗时（ms） */
        val avgMs: Int,
        val maxMs: Int,
        /** 进程累计拍数 */
        val totalPolls: Int,
    ) {
        /** 成功率百分比（0~100） */
        val okPercent: Int get() = if (samples == 0) 0 else ok * 100 / samples
    }

    private const val WINDOW = 60

    /** 通知缓冲写满被丢掉的帧数（累计） */
    private val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames

    private val _poll = MutableStateFlow<Poll?>(null)
    val poll: StateFlow<Poll?> = _poll

    private val lock = PlatformLock()
    private val rtt = IntArray(WINDOW)
    private var idx = 0
    private var samples = 0
    private var totalPolls = 0
    private var lastEmitMs = 0L

    /** 记一拍：是否收到应答、往返多少毫秒 */
    fun notePoll(rttMs: Int, ok: Boolean, nowMs: Long) {
        withLock(lock) {
            totalPolls++
            if (samples < WINDOW) samples++
            rtt[idx] = if (ok) rttMs else -1
            idx = (idx + 1) % WINDOW
            // 每拍都推 StateFlow 会让开发者页按轮询节奏重组，这里最多 1 秒推一次
            if (nowMs - lastEmitMs < EMIT_INTERVAL_MS) return
            lastEmitMs = nowMs
            _poll.value = snapshotLocked()
        }
    }

    /** 通道轮切/重连后清零：跨通道的往返耗时混在一起没有意义 */
    fun resetPoll() = withLock(lock) {
        idx = 0; samples = 0; totalPolls = 0
        for (i in rtt.indices) rtt[i] = 0
        lastEmitMs = 0
        _poll.value = null
    }

    fun noteDroppedFrame() {
        withLock(lock) { _droppedFrames.value = _droppedFrames.value + 1 }
    }

    private fun snapshotLocked(): Poll {
        var sum = 0L
        var max = 0
        var ok = 0
        for (i in 0 until samples) {
            val v = rtt[i]
            if (v < 0) continue
            ok++
            sum += v
            if (v > max) max = v
        }
        return Poll(
            samples = samples,
            ok = ok,
            avgMs = if (ok == 0) 0 else (sum / ok).toInt(),
            maxMs = max,
            totalPolls = totalPolls,
        )
    }

    private const val EMIT_INTERVAL_MS = 1_000L
}
