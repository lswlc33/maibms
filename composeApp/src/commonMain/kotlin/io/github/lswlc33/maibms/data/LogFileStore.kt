package io.github.lswlc33.maibms.data

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * 日志文件仓库：按天一个文件（`log-yyyy-MM-dd.txt`）、启动读回、跨天/启动时清理，
 * 只保留最近 [RETAIN_DAYS] 天。
 *
 * 文件操作走 **okio**（KMP 文件库，Android/桌面/iOS 三端都有实现）——不在公共代码里碰
 * java.io.File，也不用为 iOS 单独写 NSFileManager 互操作代码；具体实例由 [platformFileSystem] 提供。
 *
 * 目录由平台入口 [setDir] 注入（绝对路径）：
 * - Android = 外部私有 files 目录下的 maibms/logs（用户可在文件管理器看到、卸载才删）
 * - 桌面   = 用户目录 maibms-logs（与日志导出目录一致）
 * - iOS    = 沙盒 Application Support/maibms-logs
 *
 * 未注入目录或 IO 失败时全部静默降级为 no-op：内存环形缓冲照常工作，只是不落盘。
 */
object LogFileStore {
    /** 只保留最近 3 天（今天 + 前两天）；启动与跨天时清理更早的 */
    const val RETAIN_DAYS = 3

    private const val PREFIX = "log-"
    private const val SUFFIX = ".txt"

    private val fs: FileSystem get() = platformFileSystem
    private var dir: Path? = null

    /** 平台入口注入日志目录的**绝对路径**（null = 不落盘） */
    fun setDir(path: String?) {
        dir = path?.toPath()
    }

    /** 追加一条完整行（含级别/时间前缀），IO 失败静默——日志不能反过来影响业务 */
    fun appendLine(line: String) {
        val d = dir ?: return
        runCatching {
            fs.createDirectories(d)
            fs.appendingSink(d / fileName(today())).buffer().use { it.writeUtf8(line + "\n") }
        }
    }

    /** 读回最近 [RETAIN_DAYS] 天的全部行（更早的已被清理）；按文件名升序拼接 */
    fun readRecentLines(): List<String> {
        val d = dir ?: return emptyList()
        return runCatching {
            listLogFiles(d)
                .sortedBy { it.name }
                .flatMap { p -> runCatching { fs.read(p) { readUtf8() } }.getOrDefault("").split('\n') }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** 清理超出保留期的日志文件（启动、跨天时调用） */
    fun cleanup() {
        val d = dir ?: return
        runCatching {
            val keep = (0 until RETAIN_DAYS).map { fileName(dayOffset(-it)) }.toSet()
            listLogFiles(d).forEach { p -> if (p.name !in keep) runCatching { fs.delete(p) } }
        }
    }

    /** 删除全部日志文件（应用内「清空日志」时连文件一起清，否则重启又读回来） */
    fun clearAll() {
        val d = dir ?: return
        runCatching { listLogFiles(d).forEach { p -> runCatching { fs.delete(p) } } }
    }

    private fun listLogFiles(d: Path): List<Path> = runCatching {
        fs.list(d).toList().filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
    }.getOrDefault(emptyList())

    private fun fileName(day: String) = "$PREFIX$day$SUFFIX"

    /** 当天（本地时区）yyyy-MM-dd */
    private fun today(): String = dayOffset(0)

    /** 相对今天偏移 [offset] 天的日期串 */
    private fun dayOffset(offset: Int): String = localDatePlusDays(offset)
}
