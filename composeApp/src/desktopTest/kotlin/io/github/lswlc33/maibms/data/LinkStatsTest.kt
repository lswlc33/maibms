package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 链路统计（开发者页那把尺子）的窗口语义。
 *
 * 它是"轮询快不快、有没有丢帧"的唯一客观依据，算错了会直接误导优化方向，
 * 所以窗口、成功率、平均/最大的口径都钉在这里。
 */
class LinkStatsTest {

    @Test fun windowStatsReflectRecentPolls() {
        LinkStats.resetPoll()
        assertNull(LinkStats.poll.value, "没样本时不报数")
        // 两拍成功 + 一拍失败；只有过了 1 秒节流才会推出新的统计
        LinkStats.notePoll(50, ok = true, nowMs = 0)
        LinkStats.notePoll(0, ok = false, nowMs = 100)
        LinkStats.notePoll(150, ok = true, nowMs = 5_000)
        val p = LinkStats.poll.value!!
        assertEquals(3, p.samples, "失败的那拍也进样本（成功率要算它）")
        assertEquals(2, p.ok)
        assertEquals(66, p.okPercent)
        assertEquals(100, p.avgMs, "平均只算成功的拍：(50+150)/2")
        assertEquals(150, p.maxMs)
        assertEquals(3, p.totalPolls)
        LinkStats.resetPoll()
    }

    @Test fun windowIsCappedAndOldSamplesFallOut() {
        LinkStats.resetPoll()
        // 灌 100 拍（窗口 60）：最早的 40 拍应被挤出
        for (i in 1..100) LinkStats.notePoll(i, ok = true, nowMs = i * 2_000L)
        val p = LinkStats.poll.value!!
        assertEquals(60, p.samples, "窗口上限 60")
        assertEquals(60, p.ok)
        assertEquals(100, p.totalPolls, "累计计数不受窗口影响")
        assertEquals(70, p.avgMs, "窗口内是第 41~100 拍，均值 70.5 → 整数除法截断为 70")
        assertEquals(100, p.maxMs)
        LinkStats.resetPoll()
    }

    @Test fun resetClearsEverything() {
        LinkStats.resetPoll()
        LinkStats.notePoll(80, ok = true, nowMs = 5_000)
        LinkStats.resetPoll()
        assertNull(LinkStats.poll.value, "换通道/换设备后跨会话的往返耗时没有可比性，应清零")
    }

    @Test fun droppedFramesAccumulate() {
        val before = LinkStats.droppedFrames.value
        LinkStats.noteDroppedFrame()
        LinkStats.noteDroppedFrame()
        assertEquals(before + 2, LinkStats.droppedFrames.value)
    }
}
