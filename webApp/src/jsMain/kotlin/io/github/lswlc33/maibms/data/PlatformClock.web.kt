package io.github.lswlc33.maibms.data

/**
 * 平台时钟（网页端）：`performance.now()` 是页面加载起的单调毫秒，与 Android 的 uptime 同语义。
 *
 * 首屏计时的起点见 [pageLoadUptimeMs]——用的是浏览器记录的页面导航时刻，
 * 与 Android 用 `Process.getStartUptimeMillis()` 的取法一致（两边都可比）。
 */
actual fun uptimeMs(): Long = performanceNow()

actual fun processStartUptimeMs(): Long? = pageLoadUptimeMs()