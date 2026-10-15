package io.github.lswlc33.maibms.protocol

import io.github.lswlc33.maibms.data.fmt
import io.github.lswlc33.maibms.data.formatShortDateTime

/** 一条历史事件（`0x13` 应答解析结果） */
data class EventLogRecord(
    /** 日志序号（帧头回显的请求序号，从 0 起） */
    val index: Int,
    val eventCode: Int,
    val eventName: String,
    /** 原始 u32（语义见 [EventLogCodec.formatTime]） */
    val timeRaw: Long,
    val timeText: String,
    val items: List<EventLogItem>,
)

/** 事件里的一个数据项（`[项号][符号标志][u16 值]`） */
data class EventLogItem(val id: Int, val name: String, val value: String)

/**
 * 设备历史事件日志（`0x03`→`0x13`）与保护动作计数（`0x05`→`0x15`）编解码。
 *
 * 依据：`docs/08-日志与事件.md`、`docs/附录C-事件与日志表.md`，并逐项对照官方小程序反编译结果
 * （旧版 `app.js` 的 `l()`、新版 `utils/dealBle.js` 的 `rizhiData`、新版 SDK `bms_0x13_1.0.0.js`
 * 与 `utils/dict.js` 的两张表）。
 *
 * **四处与文档不一致、按源码实现**（详见本轮 Plan 文档 §1.2，附录 E 亦有记录）：
 * 1. `0x05` 请求的长度字段是 `0x28`(40)，不是文档写的 `00`（官方 `helper.js ReadData(0,40,5)`）；
 * 2. 时间戳三个官方实现都按**时长**格式化（天/时/分/秒），没有一处转日历日期——
 *    但文档说是 Unix 秒并有样例。这里按取值判别（见 [UNIX_TIME_MIN]），两种固件都能正确显示；
 * 3. 「返回条数为 0 即停」其实判的是**帧长度字段**（官方 `e[5] != 0`）；这里长度字段为 0 或
 *    项数 K 为 0 都算到底；
 * 4. 数据项表不止 32 项：新版表给 33~37 也定了名（均衡串数/故障码/加热MOS/HDSV）。
 *
 * 另：官方两个 BLE 解析器遇到**未知数据项**只吞 1 字节项号，符号标志与 2 字节值没读，
 * 会让同一条记录后续所有项全部错位（实测为 bug）；这里一律吞满 4 字节，不错位。
 */
object EventLogCodec {

    /**
     * Unix 秒的判别下限（2020-09-13）。低于它的一律按**设备运行时长**解读：
     * 设备跑满 1.6e9 秒要 50 年，所以这个判据在两种固件行为下都不会误判。
     */
    const val UNIX_TIME_MIN = 1_600_000_000L

    /** 单条记录的数据项上限（防御脏数据：K 是设备给的字节，不能直接拿来当循环次数） */
    private const val MAX_ITEMS = 64

    /** 计数器个数（docs 附录C C.3） */
    const val COUNTER_COUNT = 20

    /** 读第 [index] 条日志的请求帧（短帧，长度字段 00） */
    fun request(index: Int): ByteArray =
        Frame.build(Proto.ADDR_MAIN, Proto.FC_LOG, index, ByteArray(0), 0)

    /** 读 20 个保护动作计数的请求帧（短帧，长度字段 40 = 20×u16，官方 ReadData(0,40,5)） */
    fun countersRequest(): ByteArray =
        Frame.build(Proto.ADDR_MAIN, Proto.FC_PROTECT_NUM, 0, ByteArray(0), COUNTER_COUNT * 2)

    /**
     * 解析一条 `0x13` 应答。
     *
     * 返回 null 表示「这条记录是空的」——官方把帧长度字段为 0 当作日志到底的判据，
     * 这里同样按**空记录**处理（调用方据此停止翻页），而不是当成解析失败。
     */
    fun parse(frame: ParsedFrame): EventLogRecord? {
        val d = frame.data
        // 空记录：长度字段 0（官方谓词）或数据区不足最基本的 8 字节头
        if (frame.lengthField == 0 || d.size < 8) return null
        val k = d[2].toInt() and 0xFF
        if (k == 0) return null
        val eventCode = d[3].toInt() and 0xFF
        val timeRaw = u32le(d, 4)
        val items = ArrayList<EventLogItem>(minOf(k, MAX_ITEMS))
        var p = 8
        // 按 K 循环，但每项都要先确认剩余长度够 4 字节：截断帧只丢尾部，不整条丢
        while (items.size < k.coerceAtMost(MAX_ITEMS) && p + 4 <= d.size) {
            val id = d[p].toInt() and 0xFF
            val signed = (d[p + 1].toInt() and 0xFF) == 0x80
            var raw = (d[p + 2].toInt() and 0xFF) or ((d[p + 3].toInt() and 0xFF) shl 8)
            if (signed && raw >= 0x8000) raw -= 0x10000
            items += item(id, raw)
            p += 4
        }
        return EventLogRecord(
            index = frame.reg,
            eventCode = eventCode,
            eventName = eventName(eventCode),
            timeRaw = timeRaw,
            timeText = formatTime(timeRaw),
            items = items,
        )
    }

    /** 解析 `0x15` 应答：数据区是连续 u16 小端。不足 40 字节时只返回解析到的前几项。 */
    fun parseCounters(data: ByteArray): List<Int> {
        val n = minOf(data.size / 2, COUNTER_COUNT)
        return (0 until n).map { u16le(data, 2 * it) }
    }

    /**
     * 事件时间：`≥ 2020-09` 的按 Unix 秒转本地时间，否则按设备**运行时长**。
     *
     * 两种都能自证：真机读一条日志，与设备当前运行时间对一下就知道了（见 Plan §6.1）。
     */
    fun formatTime(raw: Long): String = if (raw >= UNIX_TIME_MIN) {
        // 与快照记录时刻同款短标签：MM-dd HH:mm
        formatShortDateTime(raw * 1000)
    } else {
        runtimeLabel(raw)
    }

    fun isUnixTime(raw: Long): Boolean = raw >= UNIX_TIME_MIN

    /** 时长：与界面其它地方的「运行时间」同款口径（N天 HH:MM:SS），带「运行」前缀避免误读成日期 */
    private fun runtimeLabel(sec: Long): String {
        val d = sec / 86_400
        val h = sec % 86_400 / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return if (d > 0) "运行 ${d}天%02d:%02d:%02d".fmt(h, m, s)
        else "运行 %02d:%02d:%02d".fmt(h, m, s)
    }

    // ---------------- 事件类别名（docs 附录C C.1，取新版 dict.js 的权威写法） ----------------

    private val events: List<String> = listOf(
        "电池类型未设置", "单体过压保护", "单体过压恢复", "单体2级过压保护", "单体2级过压恢复",
        "总压过压保护", "总压过压恢复", "单体欠压保护", "单体欠压恢复", "单体2级欠压保护",
        "单体2级欠压恢复", "总压欠压保护", "总压欠压恢复", "单体压差保护", "单体压差恢复",
        "电池高温充电保护", "电池高温充电恢复", "电池高温放电保护", "电池高温放电恢复",
        "MOS高温保护", "MOS高温恢复", "电池低温充电保护", "电池低温充电恢复",
        "电池低温放电保护", "电池低温放电恢复", "充电过流保护", "充电过流恢复",
        "放电过流保护", "放电过流恢复", "放电二级过流保护", "放电二级过流恢复",
        "短路保护", "短路恢复",
        "控制放电开关1", "控制放电开关2", "控制放电开关3", "控制放电开关4",
        "控制充电开关1", "控制充电开关2", "控制充电开关3", "控制充电开关4",
        "均衡线掉串", "电流异常", "放电MOS异常", "充电MOS异常", "内部通信异常",
        "单体过压告警", "单体欠压告警", "单体压差告警", "总压过压告警", "总压欠压告警",
        "电池高温充电告警", "电池高温放电告警", "电池低温充电告警", "电池低温放电告警",
        "MOS高温告警", "充电过流告警", "放电过流告警", "SOC一级告警", "SOC二级告警",
        "单体效验异常", "总压单体异常", "预充失败", "电池充满",
        "电池充电中", "电池放电中", "充电器连接C", "充电器连接R", "充电器连接S",
        "充电MOS开", "放电MOS开", "均衡开", "进入待机",
        "均衡极限", "均衡压差", "自动均衡", "均衡过温",
        "复位BMS", "升级APP", "升级BOOT", "关闭电源", "BMS启动", "充电结束",
        "模组失联", "验证9级权限", "验证可用时间", "恢复参数设置", "恢复厂家参数", "保存应用参数",
        "一键控制功能", "均衡故障", "预放电MOS异常", "预充电MOS异常", "加热异常保护",
    )

    /** 事件名：1 基；超出已知表或占位码时给出可排查的写法，不编含义 */
    fun eventName(code: Int): String =
        events.getOrNull(code - 1)?.takeIf { it.isNotBlank() } ?: "未知事件($code)"

    // ---------------- 数据项表（docs 附录C C.2 ＋ 新版表补齐 33~37） ----------------

    /** 数据项定义：名称、倍率、单位；枚举项用 [label] 把码值换成文案 */
    private class ItemDef(
        val name: String,
        val ratio: Int = 1,
        val unit: String = "",
        val label: ((Int) -> String)? = null,
    )

    private val items: Map<Int, ItemDef> = mapOf(
        1 to ItemDef("总压", 10, "V"),
        2 to ItemDef("电流", 10, "A"),
        3 to ItemDef("SOC", 1, "%"),
        4 to ItemDef("功率温度", 1, "℃"),
        5 to ItemDef("均衡温度", 1, "℃"),
        6 to ItemDef("最大电池温度", 1, "℃"),
        7 to ItemDef("最小电池温度", 1, "℃"),
        8 to ItemDef("温度最大位置"),
        9 to ItemDef("温度最小位置"),
        10 to ItemDef("最大单体电压", 1000, "V"),
        11 to ItemDef("最大单体位置"),
        12 to ItemDef("最小单体电压", 1000, "V"),
        13 to ItemDef("最小单体位置"),
        14 to ItemDef("平均单体电压", 1000, "V"),
        15 to ItemDef("单体压差", 1000, "V"),
        16 to ItemDef("有效电池数"),
        17 to ItemDef("CV"),
        18 to ItemDef("DV"),
        19 to ItemDef("DSV"),
        // 20/21 与实时帧尾部那两个状态字节同一套码表 → 复用 MosStateDict，避免两份表漂移
        20 to ItemDef("充电MOS状态", label = { MosStateDict.chargeLabel(it) }),
        21 to ItemDef("放电MOS状态", label = { MosStateDict.dischargeLabel(it) }),
        22 to ItemDef("数值1"),
        23 to ItemDef("数值2"),
        24 to ItemDef("短路触发次数"),
        25 to ItemDef("关机原因", label = { shutdownReasons.getOrNull(it) ?: "码 $it" }),
        26 to ItemDef("重启原因", label = { rebootReasons.getOrNull(it) ?: "码 $it" }),
        27 to ItemDef("充电时间", 1, "min"),
        28 to ItemDef("最大充电电流", 10, "A"),
        29 to ItemDef("模组标记"),
        30 to ItemDef("关键数据"),
        31 to ItemDef("时间"),
        32 to ItemDef("电池类型", label = { RealtimeDecoder.batteryTypeText(it) }),
        33 to ItemDef("未跳线前均衡串数"),
        34 to ItemDef("跳线后均衡串数"),
        35 to ItemDef("均衡故障码"),
        36 to ItemDef("加热mos状态"),
        37 to ItemDef("HDSV"),
    )

    private val shutdownReasons = listOf("ant通信复位", "长按关机", "弱电开关关机", "低压自动关机", "三方协议关机")
    private val rebootReasons = listOf("ant通信复位", "升级APP复位", "升级BOOT复位", "清时间复位", "三方协议复位")

    fun itemName(id: Int): String = items[id]?.name ?: "未知数据项 $id"

    /** 数据项 → 展示文本（含单位）；未知项给原始值，便于对照固件新增项 */
    private fun item(id: Int, raw: Int): EventLogItem {
        val def = items[id] ?: return EventLogItem(id, "未知数据项 $id", raw.toString())
        val text = def.label?.invoke(raw) ?: when {
            def.ratio >= 1000 -> "%.3f".fmt(raw / def.ratio.toDouble())
            def.ratio > 1 -> "%.1f".fmt(raw / def.ratio.toDouble())
            else -> raw.toString()
        }
        return EventLogItem(id, def.name, if (def.unit.isEmpty()) text else "$text${def.unit}")
    }

    // ---------------- 保护动作计数（docs 附录C C.3） ----------------

    /** 计数器名称，下标 0~19 即数据区顺序 */
    val counterNames: List<String> = listOf(
        "单体过压保护", "总压过压保护", "单体欠压保护", "总压欠压保护", "单体压差保护",
        "电池高温充电保护", "电池高温放电恢复", "功率管高温保护", "电池低温充电保护", "电池低温放电保护",
        "充电过流保护", "放电过流保护", "短路保护", "充电次数", "循环次数",
        "低压关机次数", "电流异常次数", "手动复位次数", "看门狗复位次数", "异常复位次数",
    )

    /** 循环次数的下标（C.3 第 15 项） */
    const val CYCLE_COUNTER_INDEX = 14

    private fun u16le(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)

    private fun u32le(d: ByteArray, i: Int): Long =
        (d[i].toLong() and 0xFF) or ((d[i + 1].toLong() and 0xFF) shl 8) or
                ((d[i + 2].toLong() and 0xFF) shl 16) or ((d[i + 3].toLong() and 0xFF) shl 24)
}
