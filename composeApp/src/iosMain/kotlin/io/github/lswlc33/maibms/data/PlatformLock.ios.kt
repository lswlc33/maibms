package io.github.lswlc33.maibms.data

import platform.Foundation.NSRecursiveLock

/** iOS：Kotlin/Native 没有 synchronized 关键字，用 NSRecursiveLock（语义可重入） */
internal actual class PlatformLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
