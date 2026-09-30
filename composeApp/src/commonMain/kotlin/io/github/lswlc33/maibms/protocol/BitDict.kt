package io.github.lswlc33.maibms.protocol

/** 保护/告警位域字典（docs/13-位域与枚举字典.md §13.1/§13.2） */
object BitDict {
    val protectNames: Map<Int, String> = mapOf(
        0 to "电芯类型设置", 1 to "单体过压保护", 2 to "单体2级过压", 3 to "总压过压保护",
        4 to "单体欠压保护", 5 to "单体2级欠压", 6 to "总压欠压保护", 7 to "单体压差保护",
        8 to "电池高温充电", 9 to "电池高温放电", 10 to "MOS高温保护", 11 to "电池低温充电",
        12 to "电池低温放电", 13 to "充电过流保护", 14 to "放电过流保护", 15 to "放电二级过流",
        16 to "短路保护", 38 to "请升级固件", 40 to "继电器粘连", 41 to "放电保险异常",
        42 to "充电保险异常", 43 to "模组失联", 44 to "可用时间到达",
    )

    val warnNames: Map<Int, String> = mapOf(
        0 to "单体过压告警", 1 to "总压过压告警", 2 to "单体欠压告警", 3 to "总压欠压告警",
        4 to "单体压差告警", 5 to "电池充电高温", 6 to "电池放电高温", 7 to "电池充电低温",
        8 to "电池放电低温", 9 to "MOS高温告警", 10 to "充电过流告警", 11 to "放电过流告警",
        12 to "SOC一级告警", 13 to "SOC二级告警", 18 to "充电MOS开", 19 to "放电MOS开",
        32 to "温度保护", 33 to "系统错误", 36 to "电加热开启", 37 to "强制输出中",
        38 to "蓝牙关闭", 41 to "充电继电器开", 42 to "放电继电器开", 44 to "强制开启充电",
    )

    fun decode(bits: ULong, dict: Map<Int, String>): List<String> =
        dict.filter { (bits shr it.key) and 1UL == 1UL }.values.toList()

    /** 带位号解码：详情弹窗要显示真实 bit 号（用列表下标会张冠李戴） */
    fun decodePairs(bits: ULong, dict: Map<Int, String>): List<Pair<Int, String>> =
        dict.filter { (bits shr it.key) and 1UL == 1UL }.map { it.key to it.value }.sortedBy { it.first }

    /** 告警卡展示用：过滤“状态类”位（MOS 开关/继电器等），只留告警语义 */
    private val stateBits = setOf(18, 19, 35, 36, 37, 39, 40, 41, 42)
    fun decodeForDisplay(bits: ULong, dict: Map<Int, String>): List<String> =
        dict.filter { it.key !in stateBits && (bits shr it.key) and 1UL == 1UL }.values.toList()

    /** 同上但带位号，供详情弹窗逐条列出 */
    fun decodeForDisplayPairs(bits: ULong, dict: Map<Int, String>): List<Pair<Int, String>> =
        dict.filter { it.key !in stateBits && (bits shr it.key) and 1UL == 1UL }
            .map { it.key to it.value }.sortedBy { it.first }
}
