package io.github.lswlc33.maibms.data

actual fun uptimeMs(): Long = System.nanoTime() / 1_000_000

/** 桌面端拿不到「进程启动时刻」，StartupTrace 会退化成从 arm 时刻算起 */
actual fun processStartUptimeMs(): Long? = null
