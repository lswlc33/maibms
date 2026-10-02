package io.github.lswlc33.maibms.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 桌面：HttpURLConnection（与 Android 同一套策略） */
internal actual suspend fun httpGetText(url: String, timeoutMs: Int, userAgent: String, accept: String): String? =
    withContext(ioDispatcher) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", accept)
                conn.setRequestProperty("User-Agent", userAgent)
                if (conn.responseCode !in 200..299) return@runCatching null
                conn.inputStream.use { it.readBytes().decodeToString() }.ifBlank { null }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
