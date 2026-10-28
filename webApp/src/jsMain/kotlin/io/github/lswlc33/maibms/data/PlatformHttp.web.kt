package io.github.lswlc33.maibms.data

import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * 平台 HTTP（网页端）：`fetch` 包成挂起函数。
 *
 * 与 JVM/iOS 实现同一套契约：非 2xx、超时、网络异常（含 CORS 被拒）、空响应一律返回 null，
 * 调用方按"这个源失败"处理并尝试下一个镜像。
 *
 * 直连 GitHub API 会撞 CORS（浏览器不给读响应头），所以网页端的「检查更新」走
 * jsDelivr 之类的允许跨域的镜像；失败也只是少一个可选功能，不影响主流程。
 */
internal actual suspend fun httpGetText(
    url: String,
    timeoutMs: Int,
    userAgent: String,
    accept: String,
): String? = try {
    // Promise 的 reject 在 JS 里没有具体异常类型（TypeError: Failed to fetch 等），
    // 而且这里已经 .catch(() => null) 收口，所以理论上不会抛；仍然兜一层，
    // 免得任何意外把「检查更新」这条可选路径变成崩溃点
    fetchTextPromise(url, timeoutMs, userAgent, accept).await()
} catch (_: Throwable) {
    null
}

/**
 * `fetch` → 文本（失败返回 null）。`AbortSignal.timeout` 是浏览器原生的超时，
 * 比手搓 setTimeout + abort 干净；老浏览器没有它就退化成不超时（由调用方的多镜像兜底）。
 */
private fun fetchTextPromise(
    url: String,
    timeoutMs: Int,
    userAgent: String,
    accept: String,
): Promise<String?> = js(
    """
    fetch(url, {
        method: 'GET',
        headers: { 'Accept': accept, 'User-Agent': userAgent },
        cache: 'no-store',
        signal: (typeof AbortSignal !== 'undefined' && AbortSignal.timeout)
            ? AbortSignal.timeout(timeoutMs) : undefined,
    }).then(function (r) {
        if (!r.ok) return null;
        return r.text().then(function (t) { return t ? t : null; });
    }).catch(function () { return null; })
    """
)

/**
 * 网页端没有阻塞 IO 线程池：fetch 与 Web Bluetooth 都是事件驱动，
 * `Dispatchers.Default` 就是 JS 的微任务队列，够用。
 */
internal actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
    get() = kotlinx.coroutines.Dispatchers.Default