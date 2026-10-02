package io.github.lswlc33.maibms.data

/**
 * 跨平台可重入锁——Kotlin 的 `synchronized` 是 JVM 关键字（Native 上不存在），
 * 而 KV 的「读-改-写」（DeviceProfiles 档案、BmsLog 环形缓冲）必须串行，
 * 所以这里抽成平台原语：JVM 用 ReentrantLock，iOS 用 NSRecursiveLock。
 *
 * [withLock] 是 inline 的公共包装：保留守卫代码里 `?: return` 这类非局部返回的写法。
 */
internal expect class PlatformLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> withLock(lock: PlatformLock, block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
