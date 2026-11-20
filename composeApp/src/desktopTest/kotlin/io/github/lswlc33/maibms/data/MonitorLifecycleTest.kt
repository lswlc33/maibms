package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MonitorLifecycleTest {
    private val connected = MonitorNotification("已连接", "86V · 20A · 1865W", "设备", 86)
    private val reconnecting = MonitorNotification("正在重连", "设备", "设备")

    @Test
    fun offOnWaitsForActualStopBeforeRestart() {
        val lifecycle = MonitorLifecycle()
        assertEquals(MonitorLifecycle.Action.Start(connected), lifecycle.request(connected))
        assertEquals(MonitorLifecycle.Action.Update(connected), lifecycle.started())
        assertEquals(MonitorLifecycle.Action.Stop, lifecycle.request(null))
        assertEquals(MonitorLifecycle.Action.None, lifecycle.request(reconnecting))
        assertEquals(MonitorLifecycle.Action.Start(reconnecting), lifecycle.stopped())
    }

    @Test
    fun pendingStartUsesLatestContentAndDoesNotDuplicateStart() {
        val lifecycle = MonitorLifecycle()
        assertEquals(MonitorLifecycle.Action.Start(connected), lifecycle.request(connected))
        assertEquals(MonitorLifecycle.Action.None, lifecycle.request(reconnecting))
        assertEquals(MonitorLifecycle.Action.Update(reconnecting), lifecycle.started())
    }

    @Test
    fun closeBeforeStartWaitsForDestroy() {
        val lifecycle = MonitorLifecycle()
        lifecycle.request(connected)
        assertEquals(MonitorLifecycle.Action.Stop, lifecycle.request(null))
        assertEquals(MonitorLifecycle.Action.Stop, lifecycle.started())
        assertEquals(MonitorLifecycle.Action.None, lifecycle.stopped())
    }

    @Test
    fun failedStartDoesNotLoopButForegroundRequestCanRetry() {
        val lifecycle = MonitorLifecycle()
        lifecycle.request(connected)
        lifecycle.failed()
        assertEquals(MonitorLifecycle.Action.None, lifecycle.stopped())
        assertEquals(MonitorLifecycle.Action.Start(connected), lifecycle.request(lifecycle.requestedContent))
    }

    @Test
    fun closingAgainWhileStoppingCancelsReopen() {
        val lifecycle = MonitorLifecycle()
        lifecycle.request(connected)
        lifecycle.started()
        lifecycle.request(null)
        lifecycle.request(reconnecting)
        lifecycle.request(null)
        assertEquals(MonitorLifecycle.Action.None, lifecycle.stopped())
    }
}
