package io.github.lswlc33.maibms.protocol

/**
 * MOS / 均衡状态码字典（docs/13-位域与枚举字典.md §13.3）。
 *
 * 实时帧尾部相对偏移 12（放电 MOS）、13（充电 MOS）、14（均衡状态）各 1 字节。
 * 这个字节的语义不是「开 / 关」那么简单：**值 ≥ 2 表示「被某个原因断开了」**，
 * 是诊断故障的关键信息（例如「开启失败」= BMS 想开但没开成，通常是 MOS 损坏或线路问题）。
 *
 * 表来源：旧版 `AntDict.CH_MOS_State` / `DIS_MOS_State` / `Balance_State`
 * ＋新版 `utils/dict.js` 的 `SyslogDataItemEn` 第 20/21 项（含 `list` 枚举与单位）——
 * 两份在 0~20 段逐项一致，本表取并集并按新版补齐末尾（`与控制开关2关联` = 23）。
 * 表中标注为「保留」的码位在官方表里就是占位数字（`"11"`、`"19"`……），不参与展示，
 * 只在详情弹窗里以「状态码 N（未定义）」呈现，便于固件扩展时对照。
 */
object MosStateDict {

    /** 充电 MOS 状态（偏移 13）。0=关闭、1=开启，≥2 为断开原因 */
    private val charge: Map<Int, String> = mapOf(
        2 to "单体过压", 3 to "过流保护", 4 to "电池充满", 5 to "总压过压",
        6 to "电池过温", 7 to "功率过温", 8 to "电流异常", 9 to "均衡线掉串",
        10 to "主板过温", 12 to "开启失败", 13 to "放电管异常", 14 to "等待",
        15 to "手动关闭", 16 to "二级过压", 17 to "低温保护", 18 to "压差超限",
        20 to "自检异常", 23 to "与控制开关2关联",
    )

    /** 放电 MOS 状态（偏移 12）。0=关闭、1=开启，≥2 为断开原因 */
    private val discharge: Map<Int, String> = mapOf(
        2 to "单体欠压", 3 to "过流保护", 4 to "二级过流", 5 to "总压欠压",
        6 to "电池过温", 7 to "功率过温", 8 to "电流异常", 9 to "均衡线掉串",
        10 to "主板过温", 11 to "充电开启", 12 to "短路保护", 13 to "放电管异常",
        14 to "开启失败", 15 to "手动关闭", 16 to "二级欠压", 17 to "低温保护",
        18 to "压差超限", 19 to "自检异常", 23 to "与控制开关2关联",
    )

    /** 均衡状态（偏移 14）。0=关闭，≥1 为均衡模式 */
    private val balance: Map<Int, String> = mapOf(
        1 to "极限均衡", 2 to "充电压差", 3 to "均衡过温", 4 to "自动均衡", 5 to "手动平衡",
    )

    /** 该 MOS 类别的断开原因；未定义/保留码与 0/1 一律返回 null（界面只显示开/关） */
    fun chargeReason(code: Int): String? = if (code >= 2) charge[code] else null
    fun dischargeReason(code: Int): String? = if (code >= 2) discharge[code] else null

    /** 均衡模式名；0（关闭）与保留码返回 null */
    fun balanceMode(code: Int): String? = if (code >= 1) balance[code] else null

    /** 详情弹窗用的完整描述：开关读数 + 原因 / 原始码，保留码也不丢信息 */
    fun chargeText(code: Int): String = text(code, charge)
    fun dischargeText(code: Int): String = text(code, discharge)
    fun balanceText(code: Int): String = when {
        code == 0 -> "关闭"
        balance[code] != null -> balance.getValue(code)
        else -> "状态码 $code（未定义）"
    }

    /**
     * 纯文案（不带「关闭 · …（状态码 N）」包装）。历史事件里的数据项 20/21 与实时帧尾部那两个
     * 状态字节**共用同一套码表**（官方也是同一份 `list`），那边一行里塞不下完整描述，用这个。
     */
    fun chargeLabel(code: Int): String = plain(code, charge)
    fun dischargeLabel(code: Int): String = plain(code, discharge)

    private fun plain(code: Int, table: Map<Int, String>): String = when {
        code == 1 -> "开启"
        code == 0 -> "关闭"
        table[code] != null -> table.getValue(code)
        else -> "状态码 $code"
    }

    private fun text(code: Int, table: Map<Int, String>): String = when {
        code == 1 -> "开启"
        code == 0 -> "关闭"
        table[code] != null -> "关闭 · ${table.getValue(code)}（状态码 $code）"
        else -> "关闭 · 状态码 $code（未定义）"
    }
}
