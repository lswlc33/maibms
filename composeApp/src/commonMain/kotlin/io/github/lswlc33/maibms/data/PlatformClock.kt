package io.github.lswlc33.maibms.data

/**
 * 单调时钟：只用来算「相差多少毫秒」，不是挂钟时间。
 *
 * 两个函数必须来自同一个时间基准（Android 都是 uptime、桌面都是 nanoTime），
 * 这样 `now - processStart` 才有意义。
 */

/** 当前时刻（单调，毫秒） */
expect fun uptimeMs(): Long

/**
 * 进程真正的启动时刻，拿不到返回 null。
 * Android 用 `Process.getStartUptimeMillis()`（API 24+），所以「应用启动→上屏」这个
 * 秒数是从**系统记录的进程创建时刻**算起，而不是我们自己随手打的一个点。
 */
expect fun processStartUptimeMs(): Long?
