package io.github.lswlc33.maibms.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * 应用日志：分级（V/I/W/E）、环形缓冲、可导出可清理，覆盖所有用户操作与蓝牙通讯。
 *
 * - `d` 蓝牙帧收发、扫描发现、MTU 等高频细节 —— 默认关闭，排查联调问题时打开
 * - `i` 用户可见的关键动作 —— 连接/断开/升权/读写参数/控制命令/界面操作，默认显示
 * - `w` 可自动恢复的异常 —— 重试、退避、超时后重连
 * - `e` 失败与不可恢复错误 —— 校验失败、写参数被拒、扫描失败
 *
 * 仍同步 println 到 logcat（桌面端是 stdout），真机上 adb 也能看。
 * 每条带单调时间戳（毫秒），导出时再换算成可读时间。
 */
object BmsLog {
    enum class Level(val tag: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    /** 环形缓冲上限：300 行约等于一次完整连接会话的帧日志量 */
    const val MAX = 600

    private val origin = TimeSource.Monotonic.markNow()

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

    /** 帧级日志（D 级）开关：默认关，避免 900ms 轮询把有用的 I/W/E 刷出缓冲 */
    val frameLogOn = MutableStateFlow(false)

    /** 最低显示级别：UI 可调（默认 I，即 I/W/E） */
    val minLevel = MutableStateFlow(Level.INFO)

    fun add(level: Level, tag: String, msg: String) {
        if (level == Level.DEBUG && !frameLogOn.value) {
            // D 级仍进 logcat 便于 adb 排查，只是不占应用内缓冲
            println("[ANTBMS/$tag] ${level.tag} $msg")
            return
        }
        val e = Entry(level, tag, msg, origin.elapsedNow().inWholeMilliseconds)
        _entries.value = (_entries.value + e).takeLast(MAX)
        _lines.value = (_lines.value + e.render()).takeLast(MAX)
        println("[ANTBMS/$tag] ${level.tag} $msg")
    }

    fun d(tag: String, msg: String) = add(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = add(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = add(Level.ERROR, tag, msg)

    /** 兼容旧签名（原先的 add(tag, msg) 视作 INFO） */
    fun add(tag: String, msg: String) = add(Level.INFO, tag, msg)

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }

    fun clear() {
        _entries.value = emptyList()
        _lines.value = emptyList()
    }

    /** 导出文本：带可读时间与完整级别，供「导出日志」用 */
    fun exportText(): String {
        val list = _entries.value
        if (list.isEmpty()) return "（日志为空）"
        val sb = StringBuilder("麻衣 BMS 日志导出 · ${list.size} 条\n")
        var lastDay = -1L
        for (e in list) {
            val day = e.atMs / 86_400_000L
            if (day != lastDay) {
                sb.appendLine("── 第 ${day + 1} 天 ──")
                lastDay = day
            }
            val sec = e.atMs / 1000
            val ms = e.atMs % 1000
            sb.appendLine("%02d:%02d:%02d.%03d %s/%s %s".format(
                sec / 3600, sec % 3600 / 60, sec % 60, ms, e.level.tag, e.tag, e.text))
        }
        return sb.toString()
    }
}
