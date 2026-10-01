package io.github.lswlc33.maibms.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 首屏计时语义：只统计"启动即自动重连"这条路径，且每个进程只上报一次 */
class StartupTraceTest {

    @Test fun reportsOncePerProcessAndOnlyAfterArm() {
        StartupTrace.resetForTest()
        assertNull(StartupTrace.elapsedToFirstScreenMs(), "没 arm 过（手动连接/桌面端）不应上报")

        StartupTrace.arm()
        val first = StartupTrace.elapsedToFirstScreenMs()
        assertNotNull(first, "arm 后首次上屏应上报耗时")
        assertTrue(first >= 0, "耗时不能为负（实测 $first）")

        assertNull(StartupTrace.elapsedToFirstScreenMs(), "每个进程只上报一次：重连反复上屏不应重复弹")
        assertNull(StartupTrace.elapsedToFirstScreenMs())
    }

    @Test fun reArmRestartsMeasurement() {
        StartupTrace.resetForTest()
        StartupTrace.arm()
        StartupTrace.elapsedToFirstScreenMs()
        StartupTrace.arm()   // 新一次"启动即连"（例如热重启测试）重新计时
        assertNotNull(StartupTrace.elapsedToFirstScreenMs(), "重新 arm 后应能再次上报")
        assertNull(StartupTrace.elapsedToFirstScreenMs())
    }

    /** 桌面端拿不到进程起点，应退化成从 arm 时刻算起，并且如实标注 */
    @Test fun fallsBackWhenProcessStartUnavailable() {
        StartupTrace.resetForTest()
        val hasProcessStart = processStartUptimeMs() != null
        StartupTrace.arm()
        StartupTrace.elapsedToFirstScreenMs()
        assertEquals(
            hasProcessStart,
            StartupTrace.usedProcessStart,
            "usedProcessStart 应如实反映是否用上了系统记录的进程起点（日志里会据此标注口径）",
        )
        StartupTrace.resetForTest()
    }
}
