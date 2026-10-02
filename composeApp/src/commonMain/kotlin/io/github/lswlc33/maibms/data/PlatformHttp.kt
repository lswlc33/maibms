package io.github.lswlc33.maibms.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 平台 HTTP GET（「检查更新」读 GitHub Releases 用）。
 *
 * 非 2xx、超时、网络异常、空响应一律返回 null——调用方按「这个源失败」处理并尝试下一个镜像。
 * JVM 用 HttpURLConnection，iOS 用 NSURLSession（回调式，包成挂起函数）。
 */
internal expect suspend fun httpGetText(url: String, timeoutMs: Int, userAgent: String, accept: String): String?

/**
 * 网络/IO 用的调度器：JVM 是 Dispatchers.IO（专为阻塞 IO 设的弹性线程池）；
 * iOS 线程模型里没有单独的 IO 池，用 Dispatchers.Default。
 */
internal expect val ioDispatcher: CoroutineDispatcher
