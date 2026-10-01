package io.github.lswlc33.maibms.data

import java.io.File

/**
 * 日志文件仓库：按天一个文件、启动读回、跨天/启动时清理，只保留最近 [RETAIN_DAYS] 天。
 *
 * 两端都是 JVM（Android / 桌面），直接共享一份实现；只有日志目录由平台入口注入：
 * Android = 外部私有 files 父目录下的 maibms/logs（用户可在文件管理器看到、卸载才删），
 * 桌面 = 用户目录 maibms-logs（与日志导出目录一致）。
 * 注入失败（测试、只读环境）时退化为 no-op：内存环形缓冲照常工作，只是不落盘。
 */
object LogFileStore {
    /** 只保留最近 3 天（今天 + 前两天）；启动与跨天时清理更早的 */
    const val RETAIN_DAYS = 3

    /** 平台入口注入；null = 不落盘（no-op） */
    var dir: File? = null

    private const val PREFIX = "log-"
    private const val SUFFIX = ".txt"

    /** 当天文件；目录建不出来或未注入时返回 null（写操作全部静默放弃） */
    private fun fileFor(day: String): File? =
        dir?.let { d -> runCatching { File(d.apply { mkdirs() }, "$PREFIX$day$SUFFIX") }.getOrNull() }

    /** 追加一条完整行（含级别/时间前缀），IO 失败静默——日志不能反过来影响业务 */
    fun appendLine(line: String) {
        runCatching {
            fileFor(today())?.appendText(line + "\n")
        }
    }

    /** 读回最近 [RETAIN_DAYS] 天的全部行（今天 + 前两天，更早的已被清理）；按文件名升序拼接 */
    fun readRecentLines(): List<String> {
        val d = dir ?: return emptyList()
        return runCatching {
            listLogFiles(d)
                .sortedBy { it.name }
                .flatMap { f -> f.readLines().filter { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    /** 清理超出保留期的日志文件（启动、跨天时调用） */
    fun cleanup() {
        val d = dir ?: return
        runCatching {
            val keep = (0 until RETAIN_DAYS).map { "log-${dayOffset(-it)}.txt" }.toSet()
            listLogFiles(d).forEach { f -> if (f.name !in keep) f.delete() }
        }
    }

    /** 删除全部日志文件（应用内「清空日志」时连文件一起清，否则重启又读回来） */
    fun clearAll() {
        val d = dir ?: return
        runCatching { listLogFiles(d).forEach { it.delete() } }
    }

    private fun listLogFiles(d: File): List<File> =
        d.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }?.toList() ?: emptyList()

    /** 当天（本地时区）yyyy-MM-dd */
    private fun today(): String = dayOffset(0)

    /** 相对今天偏移 [offset] 天的日期串 */
    private fun dayOffset(offset: Int): String =
        java.time.LocalDate.now().plusDays(offset.toLong()).toString()
}
