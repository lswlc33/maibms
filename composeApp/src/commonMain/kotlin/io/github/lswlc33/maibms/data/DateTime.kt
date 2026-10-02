package io.github.lswlc33.maibms.data

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 公共代码里的时间/日期工具——**不能用 JVM 的 `SimpleDateFormat` / `Date` / `java.time`**
 * （iOS/Native 上都不存在）。这里基于 kotlinx-datetime（KMP 库，原生支持 iOS）自己拼格式串，
 * 输出与原先 JVM 版本逐字符一致，日志文件与导出文本的格式因此完全不变。
 *
 * 语义固定：
 * - 月/日/时/分/秒补零两位，毫秒补三位；
 * - 不做本地化（原先也是固定 Locale.US：某些语言会把数字换成非 ASCII 字形，破坏文件解析）；
 * - [local] = true 用系统时区（日志与界面显示），false 用 UTC（留给需要稳定基准的场景）。
 */

private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()
private fun pad3(v: Int): String = v.toString().padStart(3, '0')

private fun localAt(epochMs: Long, local: Boolean): LocalDateTime {
    val tz = if (local) TimeZone.currentSystemDefault() else TimeZone.UTC
    return Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
}

/** 当前墙钟时间（epoch 毫秒） */
fun epochMillisNow(): Long = Clock.System.now().toEpochMilliseconds()

/** 本地时区当天日期 `yyyy-MM-dd`；[daysOffset] 相对今天偏移（负数=过去） */
fun localDatePlusDays(daysOffset: Int): String =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.plus(DatePeriod(days = daysOffset)).toString()

/** `yyyy-MM-dd HH:mm:ss.SSS`；[withMillis] = false 时省去 `.SSS` */
fun formatDateTime(epochMs: Long, withMillis: Boolean = true, local: Boolean = true): String {
    val t = localAt(epochMs, local)
    val base = "${t.year}-${pad2(t.monthNumber)}-${pad2(t.dayOfMonth)} " +
            "${pad2(t.hour)}:${pad2(t.minute)}:${pad2(t.second)}"
    return if (withMillis) "$base.${pad3(t.nanosecond / 1_000_000)}" else base
}

/** `yyyy-MM-dd` */
fun formatDate(epochMs: Long, local: Boolean = true): String {
    val t = localAt(epochMs, local)
    return "${t.year}-${pad2(t.monthNumber)}-${pad2(t.dayOfMonth)}"
}

/** `MM-dd HH:mm`（快照记录时刻、配置缓存时间的短标签） */
fun formatShortDateTime(epochMs: Long, local: Boolean = true): String {
    val t = localAt(epochMs, local)
    return "${pad2(t.monthNumber)}-${pad2(t.dayOfMonth)} ${pad2(t.hour)}:${pad2(t.minute)}"
}

/** `HH:mm:ss.SSS`（日志列表的时间列；跨会话日志要靠墙钟对齐） */
fun formatTimeOfDay(epochMs: Long, local: Boolean = true): String {
    val t = localAt(epochMs, local)
    return "${pad2(t.hour)}:${pad2(t.minute)}:${pad2(t.second)}.${pad3(t.nanosecond / 1_000_000)}"
}

/**
 * 解析 `yyyy-MM-dd HH:mm:ss.SSS` 回 epoch 毫秒（日志文件读回用）。
 * 固定宽度格式手工切段，比引日期解析器更稳；形状不符或值非法返回 null。
 */
fun parseDateTimeOrNull(text: String, local: Boolean = true): Long? {
    val t = text.trim()
    if (t.length < 19) return null
    if (t[4] != '-' || t[7] != '-' || t[10] != ' ' || t[13] != ':' || t[16] != ':') return null
    val year = t.substring(0, 4).toIntOrNull() ?: return null
    val month = t.substring(5, 7).toIntOrNull() ?: return null
    val day = t.substring(8, 10).toIntOrNull() ?: return null
    val hour = t.substring(11, 13).toIntOrNull() ?: return null
    val minute = t.substring(14, 16).toIntOrNull() ?: return null
    val second = t.substring(17, 19).toIntOrNull() ?: return null
    val millis = if (t.length >= 23 && t[19] == '.') (t.substring(20, 23).toIntOrNull() ?: 0) else 0
    return runCatching {
        val tz = if (local) TimeZone.currentSystemDefault() else TimeZone.UTC
        LocalDateTime(year, month, day, hour, minute, second, millis * 1_000_000)
            .toInstant(tz).toEpochMilliseconds()
    }.getOrNull()
}
