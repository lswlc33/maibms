package io.github.lswlc33.maibms.data

import java.util.concurrent.locks.ReentrantLock

/** 桌面：synchronized 语义可重入，用 ReentrantLock 等价替换 */
internal actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
