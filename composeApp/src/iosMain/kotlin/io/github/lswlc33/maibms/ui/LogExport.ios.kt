package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * iOS：把日志文本写进沙盒的 Documents（「文件」App 里可见，可通过访达/iTunes 取走），
 * 返回写入路径；失败返回 null，界面据此提示。编码走 NSData，与文件后端同一套做法。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberLogExporter(): (String) -> String? = remember {
    { text ->
        runCatching {
            val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: error("Documents 目录不可用")
            val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
            val path = "$docs/maibms-log-$stamp.txt"
            val bytes = text.encodeToByteArray()
            bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
                .writeToFile(path, atomically = true)
            path
        }.getOrNull()
    }
}
