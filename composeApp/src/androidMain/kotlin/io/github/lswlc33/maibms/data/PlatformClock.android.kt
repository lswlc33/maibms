package io.github.lswlc33.maibms.data

import android.os.Process
import android.os.SystemClock

actual fun uptimeMs(): Long = SystemClock.uptimeMillis()

/** 进程创建时刻（系统记录，含 zygote fork 之后的启动耗时）；老系统上取不到就返回 null */
actual fun processStartUptimeMs(): Long? =
    runCatching { Process.getStartUptimeMillis() }.getOrNull()
