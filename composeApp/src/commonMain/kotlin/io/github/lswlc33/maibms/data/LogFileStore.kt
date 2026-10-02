package io.github.lswlc33.maibms.data

/**
 * 日志文件仓库：按天一个文件（`log-yyyy-MM-dd.txt`）、启动读回、跨天/启动时清理，
 * 只保留最近 [RETAIN_DAYS] 天。文件系统操作交给平台后端 [LogFileBackend]：
 * JVM 两端是 java.io.File，iOS 是 NSFileManager。
 *
 * 目录由平台入口注入（[setDir]）：
 * - Android = 外部私有 files 父目录下的 maibms/logs（用户可在文件管理器看到、卸载才删）
 * - 桌面   = 用户目录 maibms-logs（与日志导出目录一致）
 * - iOS    = 应用沙盒的 Application Support/maibms-logs
 *
 * 未注入或 IO 失败时全部静默降级为 no-op：内存环形缓冲照常工作，只是不落盘。
 */
object LogFileStore {
    /** 只保留最近 3 天（今天 + 前两天）；启动与跨天时清理更早的 */
    const val RETAIN_DAYS = 3

    private const val PREFIX = "log-"
    private const val SUFFIX = ".txt"

    /** 平台入口注入日志目录的**绝对路径**（null = 不落盘） */
    fun setDir(path: String?) = LogFileBackend.setDir(path)

    /** 追加一条完整行（含级别/时间前缀），IO 失败静默——日志不能反过来影响业务 */
    fun appendLine(line: String) {
        runCatching { LogFileBackend.appendLine(fileName(today()), line + "\n") }
    }

    /** 读回最近 [RETAIN_DAYS] 天的全部行（更早的已被清理）；按文件名升序拼接 */
    fun readRecentLines(): List<String> = runCatching {
        LogFileBackend.listFiles()
            .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .sorted()
            .flatMap { LogFileBackend.readLines(it).filter { line -> line.isNotBlank() } }
    }.getOrDefault(emptyList())

    /** 清理超出保留期的日志文件（启动、跨天时调用） */
    fun cleanup() {
        runCatching {
            val keep = (0 until RETAIN_DAYS).map { fileName(dayOffset(-it)) }.toSet()
            LogFileBackend.listFiles()
                .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
                .forEach { if (it !in keep) LogFileBackend.delete(it) }
        }
    }

    /** 删除全部日志文件（应用内「清空日志」时连文件一起清，否则重启又读回来） */
    fun clearAll() {
        runCatching { LogFileBackend.listFiles().forEach { LogFileBackend.delete(it) } }
    }

    private fun fileName(day: String) = "$PREFIX$day$SUFFIX"

    /** 当天（本地时区）yyyy-MM-dd */
    private fun today(): String = dayOffset(0)

    /** 相对今天偏移 [offset] 天的日期串 */
    private fun dayOffset(offset: Int): String = localDatePlusDays(offset)
}

/** 平台文件操作（只认文件名，目录由 [LogFileStore.setDir] 注入）；未注入时全部 no-op */
internal expect object LogFileBackend {
    fun setDir(path: String?)
    fun appendLine(fileName: String, text: String)
    fun readLines(fileName: String): List<String>
    fun listFiles(): List<String>
    fun delete(fileName: String)
}
