package io.github.lswlc33.maibms.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.setValue
import kotlin.coroutines.resume

/**
 * iOS：NSURLSession 包成挂起函数。请求超时与重定向（默认跟随 302）与 JVM 侧策略一致，
 * 非 2xx / 出错 / 空 body 一律回 null——调用方按「这个源失败」处理，继续试下一个镜像。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun httpGetText(url: String, timeoutMs: Int, userAgent: String, accept: String): String? =
    suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setTimeoutInterval(timeoutMs / 1000.0)
            setValue(accept, forHTTPHeaderField = "Accept")
            setValue(userAgent, forHTTPHeaderField = "User-Agent")
        }
        var done = false
        fun finish(value: String?) {
            if (!done) { done = true; cont.resume(value) }
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            val body = data?.let { NSString.create(it, NSUTF8StringEncoding) as String? }
            finish(if (error != null || status !in 200..299 || body.isNullOrBlank()) null else body)
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

/** iOS 线程模型没有单独的 IO 池，用 Dispatchers.Default */
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
