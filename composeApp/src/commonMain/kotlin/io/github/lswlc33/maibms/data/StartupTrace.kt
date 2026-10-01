package io.github.lswlc33.maibms.data

/**
 * 冷启动首屏计时：**应用启动 → 首页画出第一帧真机数据**。
 *
 * 只统计"启动即自动重连"这一条路径（[arm] 由 `BmsRepository.start()` 在发起自动重连前调用）——
 * 手动从扫描列表连、掉线重连回来的耗时跟"应用启动"无关，掺进来就没意义了。
 * 起点优先取系统记录的进程创建时刻（见 [processStartUptimeMs]），拿不到才退化成 arm 时刻。
 *
 * 每个进程只上报一次；桌面端与离屏截图工具不会 arm，因此不参与。
 */
object StartupTrace {

    /** arm 时刻；0 = 本次进程不走"启动即连"路径 */
    private var armedAtMs = 0L
    private var reported = false

    /** 最近一次上报是否用上了系统记录的进程起点（false = 退化成从 arm 时刻算起） */
    var usedProcessStart = false
        private set

    /** 由 start() 在发起冷启动自动重连前调用 */
    fun arm() {
        armedAtMs = uptimeMs()
        reported = false
    }

    /**
     * 首页第一次带着实时数据完成组合时调用。
     * 返回「启动→上屏」毫秒数并置位（每进程只返回一次）；未 arm 过或已上报过返回 null。
     */
    fun elapsedToFirstScreenMs(): Long? {
        if (armedAtMs == 0L || reported) return null
        reported = true
        val start = processStartUptimeMs()
        usedProcessStart = start != null
        return (uptimeMs() - (start ?: armedAtMs)).coerceAtLeast(0L)
    }

    /** 仅供单测复位全局状态 */
    fun resetForTest() {
        armedAtMs = 0L
        reported = false
        usedProcessStart = false
    }
}
