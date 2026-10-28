package io.github.lswlc33.maibms.data

/**
 * 跨平台可重入锁的网页端实现。
 *
 * Kotlin/JS 是**单线程**的：所有 Kotlin 代码都在同一条事件循环上跑，不存在两条线程
 * 同时进临界区的情况，所以这里天然是空实现（真正的异步交错由协程挂起点处理，
 * 而仓库侧用于保护「读-改-写」的临界区里没有任何挂起调用）。
 */
internal actual class PlatformLock actual constructor() {
    actual fun lock() {}
    actual fun unlock() {}
}