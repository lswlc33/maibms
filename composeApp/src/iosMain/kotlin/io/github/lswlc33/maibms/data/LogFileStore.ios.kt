package io.github.lswlc33.maibms.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.posix.memcpy

/**
 * iOS 文件后端：目录由入口注入（应用沙盒的 Application Support/maibms-logs）。
 * 用 NSFileManager + NSFileHandle：追加走 seekToEndOfFile，读回整文件解 UTF-8。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object LogFileBackend {
    private var dirPath: String? = null

    actual fun setDir(path: String?) {
        dirPath = path
    }

    private fun ensureDir(): String? {
        val d = dirPath ?: return null
        NSFileManager.defaultManager.createDirectoryAtPath(
            d, withIntermediateDirectories = true, attributes = null, error = null
        )
        return d
    }

    private fun filePath(name: String): String? = dirPath?.let { "$it/$name" }

    actual fun appendLine(fileName: String, text: String) {
        val path = filePath(fileName) ?: return
        ensureDir() ?: return
        val bytes = text.encodeToByteArray()
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
            handle.seekToEndOfFile()
            bytes.usePinned { handle.writeData(NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong())) }
            handle.closeFile()
        } else {
            bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
                .writeToFile(path, atomically = true)
        }
    }

    actual fun readLines(fileName: String): List<String> {
        val path = filePath(fileName) ?: return emptyList()
        val data = NSData.dataWithContentsOfFile(path) ?: return emptyList()
        val size = data.length.toInt()
        if (size == 0) return emptyList()
        val bytes = ByteArray(size)
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes.decodeToString().split('\n').filter { it.isNotBlank() }
    }

    actual fun listFiles(): List<String> {
        val d = dirPath ?: return emptyList()
        val items = NSFileManager.defaultManager.contentsOfDirectoryAtPath(d, null) ?: return emptyList()
        return items.filterIsInstance<String>()
    }

    actual fun delete(fileName: String) {
        val path = filePath(fileName) ?: return
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
