package io.github.lswlc33.maibms.data

import java.io.File

/** 桌面文件后端：用户目录 maibms-logs（由 Main.kt 注入，与日志导出目录一致） */
internal actual object LogFileBackend {
    private var dir: File? = null

    actual fun setDir(path: String?) {
        dir = path?.let { File(it) }
    }

    private fun file(name: String): File? =
        dir?.let { d -> runCatching { d.mkdirs(); File(d, name) }.getOrNull() }

    actual fun appendLine(fileName: String, text: String) {
        file(fileName)?.appendText(text)
    }

    actual fun readLines(fileName: String): List<String> =
        file(fileName)?.takeIf { it.isFile }?.readLines() ?: emptyList()

    actual fun listFiles(): List<String> = dir?.listFiles()?.map { it.name } ?: emptyList()

    actual fun delete(fileName: String) {
        file(fileName)?.delete()
    }
}
