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
        lastAppendDay = formatDate(epochMillisNow())
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
            // 按**本地日期**跨天（文件命名和导出标题也是本地日期；用 UTC 天会在非 UTC
            // 时区把凌晨的日志分到前一天，清理也会错开几个小时）
            val day = formatDate(e.atMs)
            if (day != lastAppendDay) {
                lastAppendDay = day
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

    /**
     * 帧级转储（收发报文）：**开关打开时才拼十六进制**，关着只输出「多少字节」。
     *
     * 为什么要有这个重载：帧十六进制是这条路径上最贵的一次拼串——一帧 245 字节 ≈ 245 次
     * 字符串分配，而它发生在 **BLE 回调线程**上（Android 的通知回调），每拍都跑；
     * 关着开关时这些字符串只进 logcat，用户根本不会看。留一行「← 245B」则 adb 仍能看出
     * "有没有在收、收多长"，需要看报文时打开开关即可（开关会落盘记住）。
     *
     * @param label 前缀（方向箭头 / 功能码与长度等）
     * @param size 字节数（开关关着时输出的就是它）
     * @param hex 只在开关打开时调用——**别把 hex() 写在调用点外面**，那就白省了
     */
    fun frame(tag: String, label: String, size: Int, hex: () -> String) {
        if (frameLogOn.value) add(Level.DEBUG, tag, "$label ${hex()}")
        else add(Level.DEBUG, tag, "$label ${size}B")
    }

    /** 兼容旧签名（原先的 add(tag, msg) 视作 INFO） */
    fun add(tag: String, msg: String) = add(Level.INFO, tag, msg)

    /**
     * 字节数组 → 空格分隔的十六进制串。
     *
     * 用 256 项查表而不是逐字节 `"%02X".fmt(it)`：后者每个字节都要起一个 StringBuilder，
     * 而这条路径上（每拍收发 + 开发者页导出）长度动辄两三百字节。
     */
    fun hex(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val sb = StringBuilder(b.size * 3 - 1)
        for (i in b.indices) {
            if (i > 0) sb.append(' ')
            val v = b[i].toInt() and 0xFF
            sb.append(HEX_TABLE[v ushr 4]).append(HEX_TABLE[v and 0x0F])
        }
        return sb.toString()
    }

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
        var lastDay: String? = null
        for (e in list) {
            val day = formatDate(e.atMs)
            if (day != lastDay) {
                sb.appendLine("── $day ──")
                lastDay = day
            }
            sb.appendLine(formatAbsolute(e))
        }
        return sb.toString()
    }

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

    /** 十六进制半字节查表（见 [hex]：避免逐字节起 StringBuilder） */
    private val HEX_TABLE = "0123456789ABCDEF".toCharArray()

    /** 上次写入日志所在的本地日期（yyyy-MM-dd）；restore 后为当天，跨天时触发一次清理 */
    @Volatile private var lastAppendDay: String? = null
}
