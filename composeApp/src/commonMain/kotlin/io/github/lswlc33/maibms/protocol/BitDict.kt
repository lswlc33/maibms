package io.github.lswlc33.maibms.protocol

/**
 * 保护/告警位域字典（docs/13-位域与枚举字典.md §13.1/§13.2）。
 *
 * 2026-10-01 对照旧版 AntDict.ProtectMackString / WarningMackString 逐位校对补全：
 * - 告警域 bit18/19 是「充电中/放电中」标志，真正的「充电/放电 MOS 开」在 bit23/24
 *   （此前一对位号错位）；真机常置的 23/24/26 即 MOS 开×2 + 待机，与旧版字典吻合。
 * - 此前两张表缺了大量高位条目（保护 17~37/39/45~48/50、告警 14~17/20~31/34/35/
 *   39/40/43/45~53）：板子真触发「电流异常/预充失败/内部通信异常/即将低压关机」等
 *   会被静默吞掉，保护卡照常显示「无保护动作 ✓」。
 */
object BitDict {
    val protectNames: Map<Int, String> = mapOf(
        0 to "设置电芯类型", 1 to "单体过压保护", 2 to "单体2级过压", 3 to "总压过压保护",
        4 to "单体欠压保护", 5 to "单体2级欠压", 6 to "总压欠压保护", 7 to "单体压差保护",
        8 to "电池高温充电", 9 to "电池高温放电", 10 to "MOS高温保护", 11 to "电池低温充电",
        12 to "电池低温放电", 13 to "充电过流保护", 14 to "放电过流保护", 15 to "放电二级过流",
        16 to "短路保护",
        17 to "控制放电开关1", 18 to "控制放电开关2", 19 to "控制放电开关3", 20 to "控制放电开关4",
        21 to "控制充电开关1", 22 to "控制充电开关2", 23 to "控制充电开关3", 24 to "控制充电开关4",
        25 to "均衡线掉串", 26 to "电流异常", 27 to "放电MOS异常", 28 to "充电MOS异常",
        29 to "内部通信异常", 30 to "预充失败", 31 to "BMS初始化",
        32 to "自检1", 33 to "自检2", 34 to "充电通信检测", 35 to "GPS丢失保护",
        36 to "防打火工作", 37 to "自检3", 38 to "请升级固件", 39 to "继电器预充失败",
        40 to "继电器粘连", 41 to "放电保险异常", 42 to "充电保险异常", 43 to "模组失联",
        44 to "可用时间到达", 45 to "预放MOS异常", 46 to "预充MOS异常", 47 to "负载锁定开启",
        48 to "采集接收模式", 50 to "采集Boot模式",
    )

    val warnNames: Map<Int, String> = mapOf(
        0 to "单体过压告警", 1 to "总压过压告警", 2 to "单体欠压告警", 3 to "总压欠压告警",
        4 to "单体压差告警", 5 to "电池充电高温", 6 to "电池放电高温", 7 to "电池充电低温",
        8 to "电池放电低温", 9 to "MOS高温告警", 10 to "充电过流告警", 11 to "放电过流告警",
        12 to "SOC一级告警", 13 to "SOC二级告警", 14 to "单体效验异常", 15 to "总压效验异常",
        16 to "预充失败", 17 to "电池充满",
        18 to "电池充电中", 19 to "电池放电中",
        20 to "CAN充电连接", 21 to "485充电连接", 22 to "充电连接CUR",
        23 to "充电MOS开", 24 to "放电MOS开", 25 to "均衡开", 26 to "待机中",
        27 to "均衡极限", 28 to "均衡压差", 29 to "均衡自动", 30 to "均衡过温",
        31 to "电压保护",
        32 to "温度保护", 33 to "系统错误", 34 to "GPS通信丢失", 35 to "均衡检测",
        36 to "电加热开启", 37 to "强制输出中", 38 to "蓝牙关闭",
        39 to "继电器预充中", 40 to "MOS预充中", 41 to "充电继电器开", 42 to "放电继电器开",
        43 to "时钟异常", 44 to "强制开启充电",
        45 to "调试模式", 46 to "可用时间到达", 47 to "可用即将到达", 48 to "防打火中",
        49 to "即将低压关机", 50 to "蚂蚁充电连接", 51 to "均衡等待", 52 to "测试模式",
        53 to "内部通信不稳定",
    )

    fun decode(bits: ULong, dict: Map<Int, String>): List<String> =
        decodePairs(bits, dict).map { it.second }

    /** 带位号解码：详情弹窗要显示真实 bit 号（用列表下标会张冠李戴），按位号升序 */
    fun decodePairs(bits: ULong, dict: Map<Int, String>): List<Pair<Int, String>> =
        dict.filter { (bits shr it.key) and 1UL == 1UL }.map { it.key to it.value }.sortedBy { it.first }

    /**
     * 告警域里的「状态类」位（docs/13.2 注意项：17 起多为运行状态）：
     * MOS/继电器开关、充放电标志、各种连接/模式/均衡过程位 —— 不进告警卡，
     * 只留真正的告警语义（0~16、31 电压保护、32/33、34、38、43、44、46/47、49、53）。
     */
    private val stateBits = setOf(
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        35, 36, 37, 39, 40, 41, 42, 45, 48, 50, 51, 52,
    )
    fun decodeForDisplay(bits: ULong, dict: Map<Int, String>): List<String> =
        decodeForDisplayPairs(bits, dict).map { it.second }

    /** 同上但带位号，供详情弹窗逐条列出 */
    fun decodeForDisplayPairs(bits: ULong, dict: Map<Int, String>): List<Pair<Int, String>> =
        decodePairs(bits, dict).filter { it.first !in stateBits }
}
