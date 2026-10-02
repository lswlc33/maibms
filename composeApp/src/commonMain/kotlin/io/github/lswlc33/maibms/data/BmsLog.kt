package io.github.lswlc33.maibms.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用日志：分级（V/I/W/E）、环形缓冲、可导出可清理，覆盖所有用户操作与蓝牙通讯。
 *
 * - `d` 蓝牙帧收发、扫描发现、MTU 等高频细节 —— 默认关闭，排查联调问题时打开
 * - `i` 用户可见的关键动作 —— 连接/断开/升权/读写参数/控制命令/界面操作，默认显示
 * - `w` 可自动恢复的异常 —— 重试、退避、超时后重连
 * - `e` 失败与不可恢复错误 —— 校验失败、写参数被拒、扫描失败
 *
 * 仍同步 println 到 logcat（桌面端是 stdout），真机上 adb 也能看。
 * 每条带墙钟时间戳（epoch ms，跨会话有效）：上次运行留下的日志随启动读回，
 * 文件按天一份、只保留最近 [LogFileStore.RETAIN_DAYS] 天；显示级别与帧级开关
 * 记忆在 [AppStore]，重启后沿用。
 */
object BmsLog {
    enum class Level(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    /** 环形缓冲上限：600 行约等于一次完整连接会话的帧日志量 */
    const val MAX = 600

    private const val DAY_MS = 86_400_000L

    /** 一条已格式化的日志 */
    data class Entry(val level: Level, val tag: String, val text: String, val atMs: Long) {
        /** 开发者页单行展示：级别 + 类别 + 内容（时间由列表统一显示） */
        fun render(): String = "${level.tag}/$tag $text"
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** 原始行视图（老调用方/导出兜底用） */
    val lines: StateFlow<List<String>>
        get() = _lines
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** 帧级日志（D 级）开关：默认关，避免 900ms 轮询把有用的 I/W/E 刷出缓冲；写入即持久化 */
    val frameLogOn = MutableStateFlow(false)

    /** 最低显示级别：UI 可调（默认 I，即 I/W/E）；写入即持久化 */
    val minLevel = MutableStateFlow(Level.INFO)

    /** 启动时调用：读回上次运行留下的日志（文件按天存，最多 3 天），并清理过期文件 */
    fun restore() = withLock(lock) {
        lastAppendDay = epochMillisNow() / DAY_MS
        LogFileStore.cleanup()
        val saved = LogFileStore.readRecentLines()
        if (saved.isNotEmpty()) {
            val parsed = saved.mapNotNull(::parseLine)
            _entries.value = parsed.takeLast(MAX)
            _lines.value = saved.takeLast(MAX)
        }
    }

    fun add(level: Level, tag: String, msg: String) {
        if (level == Level.DEBUG && !frameLogOn.value) {
            // D 级仍进 logcat 便于 adb 排查，只是不占应用内缓冲
            println("[ANTBMS/$tag] ${level.tag} $msg")
            return
        }
        val e = Entry(level, tag, msg, epochMillisNow())
        // 追加必须原子：BLE IO 线程与 Default 调度器会并发记日志，
        // 「读出列表 + 追加 + 写回」交错时会整行丢失（且丢的是刚发生的关键行）
        withLock(lock) {
            _entries.value = (_entries.value + e).takeLast(MAX)
            _lines.value = (_lines.value + e.render()).takeLast(MAX)
            if (e.atMs / DAY_MS != lastAppendDay) {
                // 跨天：先把旧日期的最后一批冲进旧文件再切文件，并清掉 3 天外的过期日志
                lastAppendDay = e.atMs / DAY_MS
                LogFileStore.cleanup()
            }
        }
        // 文件行带完整时间与级别（导出同款格式），放在锁外写——IO 慢，别挡内存追加
        LogFileStore.appendLine(formatAbsolute(e))
        println("[ANTBMS/$tag] ${level.tag} $msg")
    }

    fun d(tag: String, msg: String) = add(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = add(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = add(Level.ERROR, tag, msg)

    /** 兼容旧签名（原先的 add(tag, msg) 视作 INFO） */
    fun add(tag: String, msg: String) = add(Level.INFO, tag, msg)

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".fmt(it) }

    fun clear() = withLock(lock) {
        _entries.value = emptyList()
        _lines.value = emptyList()
        // 内存清了文件也得清，否则重启后上次的内容又读回来了
        LogFileStore.clearAll()
    }

    /** 导出文本：带可读时间与完整级别，供「导出日志」用 */
    fun exportText(): String {
        val list = _entries.value
        if (list.isEmpty()) return "（日志为空）"
        val sb = StringBuilder("麻衣 BMS 日志导出 · ${list.size} 条\n")
        sb.appendLine("说明：0x23 密码校验帧的数据区已遮蔽（**），不会包含密码明文。")
        var lastDay = -1L
        for (e in list) {
            val day = e.atMs / DAY_MS
            if (day != lastDay) {
                sb.appendLine("── ${dayLabel(e.atMs)} ──")
                lastDay = day
            }
            sb.appendLine(formatAbsolute(e))
        }
        return sb.toString()
    }

    /** 墙钟转可读时间：跨会话的日志必须能看出「昨天几点」，所以导出与文件都用完整日期。
     *  格式与原先 JVM 版逐字符一致（见 DateTime.kt，不做本地化） */
    private fun dayLabel(epochMs: Long): String = formatDate(epochMs)

    private fun formatAbsolute(e: Entry): String =
        "${formatDateTime(e.atMs)} ${e.level.tag}/${e.tag} ${e.text}"

    /** 文件行（导出同款格式）→ Entry；解析失败的行跳过，保证旧格式/损坏行不拖垮读回 */
    private fun parseLine(line: String): Entry? = runCatching {
        val m = lineRegex.matchEntire(line.trim()) ?: return null
        val at = parseDateTimeOrNull(m.groupValues[1]) ?: return null
        val level = Level.entries.firstOrNull { it.tag == m.groupValues[2] } ?: return null
        Entry(level, m.groupValues[3], m.groupValues[4], at)
    }.getOrNull()

    private val lineRegex = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([DEIW])/(\S+) (.*)""")

    /** 环形缓冲/文件追加的互斥（JVM=synchronized 语义，iOS=NSRecursiveLock） */
    private val lock = PlatformLock()

    /** 上次写入日志所在的「天」（epoch day）；restore 后为 restore 当天，跨天时触发一次清理 */
    @Volatile private var lastAppendDay: Long = -1
}
