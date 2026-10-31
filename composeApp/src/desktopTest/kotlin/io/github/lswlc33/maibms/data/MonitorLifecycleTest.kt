package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MonitorLifecycleTest {
    @Test
    fun offOnWaitsForActualStopBeforeRestart() {
        val lifecycle = MonitorLifecycle()
        assertEquals(MonitorLifecycle.Action.Start("已连接"), lifecycle.request("已连接"))
        assertEquals(MonitorLifecycle.Action.Update("已连接"), lifecycle.started())
        assertEquals(MonitorLifecycle.Action.Stop, lifecycle.request(null))
        assertEquals(MonitorLifecycle.Action.None, lifecycle.request("重连中"))
        assertEquals(MonitorLifecycle.Action.Start("重连中"), lifecycle.stopped())
    }

    @Test
    fun pendingStartUsesLatestTextAndDoesNotDuplicateStart() {
        val lifecycle = MonitorLifecycle()
        assertEquals(MonitorLifecycle.Action.Start("连接中"), lifecycle.request("连接中"))
        assertEquals(MonitorLifecycle.Action.None, lifecycle.request("重连中"))
        assertEquals(MonitorLifecycle.Action.Update("重连中"), lifecycle.started())
    }

    @Test
    fun closeBeforeStartWaitsForDestroy() {
        val lifecycle = MonitorLifecycle()
        lifecycle.request("连接中")
        assertEquals(MonitorLifecycle.Action.Stop, lifecycle.request(null))
        assertEquals(MonitorLifecycle.Action.Stop, lifecycle.started())
        assertEquals(MonitorLifecycle.Action.None, lifecycle.stopped())
    }

    @Test
    fun failedStartDoesNotLoopButForegroundRequestCanRetry() {
        val lifecycle = MonitorLifecycle()
        lifecycle.request("连接中")
        lifecycle.failed()
        assertEquals(MonitorLifecycle.Action.None, lifecycle.stopped())
        assertEquals(MonitorLifecycle.Action.Start("连接中"), lifecycle.request(lifecycle.requestedText))
    }

    @Test
    fun closingAgainWhileStoppingCancelsReopen() {
        val lifecycle = MonitorLifecycle()
        lifecycle.request("连接中")
        lifecycle.started()
        lifecycle.request(null)
        lifecycle.request("重连中")
        lifecycle.request(null)
        assertEquals(MonitorLifecycle.Action.None, lifecycle.stopped())
    }
}
