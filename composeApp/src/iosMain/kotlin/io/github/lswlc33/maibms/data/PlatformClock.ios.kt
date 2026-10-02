package io.github.lswlc33.maibms.data

import kotlin.time.TimeSource

/**
 * iOS：单调时钟。Kotlin/Native 没有 SystemClock/nanoTime，用 TimeSource.Monotonic 的
 * 进程级基准点——[uptimeMs] 因此也是「进程启动以来的毫秒数」，与 Android 的 uptime 语义一致。
 */
private val startMark = TimeSource.Monotonic.markNow()

actual fun uptimeMs(): Long = startMark.elapsedNow().inWholeMilliseconds

/**
 * iOS 拿不到「系统记录的进程创建时刻」：退化成从应用启动算起
 * （StartupTrace 会标明这一降级，与桌面端同款处理）。
 */
actual fun processStartUptimeMs(): Long? = null
