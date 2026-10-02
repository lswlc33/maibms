package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.lswlc33.maibms.data.epochMillisNow
import io.github.lswlc33.maibms.data.iosDocumentsDir
import io.github.lswlc33.maibms.data.platformFileSystem
import okio.Path.Companion.toPath

/**
 * iOS：把日志文本写进沙盒的 Documents（「文件」App 里可见，可通过访达/iTunes 取走），
 * 返回写入路径；失败返回 null，界面据此提示。
 * 写文件走 okio（与日志落盘同一套实现），不碰 ObjC 文件 API。
 */
@Composable
actual fun rememberLogExporter(): (String) -> String? = remember {
    { text ->
        runCatching {
            val docs = iosDocumentsDir() ?: error("Documents 目录不可用")
            val path = "$docs/maibms-log-${epochMillisNow()}.txt".toPath()
            platformFileSystem.write(path) { writeUtf8(text) }
            path.toString()
        }.getOrNull()
    }
}
