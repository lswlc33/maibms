package io.github.lswlc33.maibms.protocol

/**
 * 结果码两套表，别混用（docs/06 §6.5、附录B B.2/B.3）：
 * - 写参数结果 → 0x42 同帧追加的 0xFF 段（低字节），见 [writeResult]
 * - 控制命令结果 → 0x61 应答，见 [controlResult]
 */
object ResultCodes {
    /** 0xFF 附加段结果码（写参数/读写状态） */
    fun writeResult(code: Int): String = when (code) {
        0 -> "正常"
        1 -> "权限不够"
        2 -> "数据小于最小值"
        3 -> "数据大于最大值"
        4 -> "地址超范围"
        5 -> "电池类型错误 / 密码不正确"
        6 -> "固件升级成功（BMS）"
        7 -> "固件升级成功（GPRS）"
        8, 9 -> "固件升级失败"
        10 -> "设置成功（需保存后生效）"
        11 -> "读取成功"
        16 -> "BOOT 固件升级成功"
        17 -> "固件不匹配"
        else -> "未知结果码 $code"
    }

    /** 0x61 控制应答结果码 */
    fun controlResult(code: Int): String = when (code) {
        0 -> "无提示"
        1 -> "成功"
        2 -> "失败"
        3 -> "权限不足"
        4 -> "地址超限"
        5 -> "电池类型错误"
        6 -> "电池信息错误"
        7 -> "固件不匹配"
        else -> "未知结果码 $code"
    }

    /** 写参数是否判定为成功（0xFF 段的 10=设置成功、0=正常、11=读取成功） */
    fun writeOk(code: Int): Boolean = code == 10 || code == 0 || code == 11
}

/** 控制命令号（docs/07 §7.1、附录B B.1） */
object ControlCmd {
    const val DISCHARGE_OFF = 1
    const val DISCHARGE_ON = 3
    const val CHARGE_OFF = 4
    const val CHARGE_ON = 6
    const val SAVE_PARAMS = 7
    const val CURRENT_ZERO = 8
    const val RESTART = 9
    const val POWER_OFF = 11
    const val FACTORY_RESET = 12
    const val BALANCE_ON = 13
    const val BALANCE_OFF = 14
    const val CLEAR_LOG = 15
    const val BT_INIT = 16
    const val BT_CLOSE = 28
    const val BT_OPEN = 29
    const val BUZZER_ON = 30
    const val BUZZER_OFF = 31
    const val CLEAR_DISCHARGE_CAP = 32
    const val CLEAR_CHARGE_CAP = 33
    const val CLEAR_DISCHARGE_TIME = 34
    const val CLEAR_CHARGE_TIME = 35
    const val CLEAR_RUNTIME = 36
    const val CLEAR_PROTECT_COUNT = 37
    const val PRESET_TITANATE = 38
    const val PRESET_TERNARY = 39
    const val PRESET_LIFEPO4 = 40
    const val FACTORY_VENDOR_RESET = 42
    const val SAVE_USER_DATA = 44
    const val FORCE_CHARGE = 52
    const val PRESET_SODIUM = 53

    fun name(cmd: Int): String = when (cmd) {
        DISCHARGE_OFF -> "关闭放电"; DISCHARGE_ON -> "打开放电"
        CHARGE_OFF -> "关闭充电"; CHARGE_ON -> "打开充电"
        SAVE_PARAMS -> "保存应用参数"; CURRENT_ZERO -> "电流归零"; RESTART -> "重启系统"
        POWER_OFF -> "关闭系统"; FACTORY_RESET -> "恢复出厂设置"; BALANCE_ON -> "打开均衡"
        BALANCE_OFF -> "关闭均衡"; CLEAR_LOG -> "清除系统日志"; BT_INIT -> "蓝牙初始化"
        BT_CLOSE -> "蓝牙关闭"; BT_OPEN -> "蓝牙打开"
        BUZZER_ON -> "蜂鸣器开"; BUZZER_OFF -> "蜂鸣器关"
        CLEAR_DISCHARGE_CAP -> "清总放电容量"; CLEAR_CHARGE_CAP -> "清总充电容量"
        CLEAR_DISCHARGE_TIME -> "清总放电时间"; CLEAR_CHARGE_TIME -> "清总充电时间"
        CLEAR_RUNTIME -> "清零运行时间"; CLEAR_PROTECT_COUNT -> "清零保护次数"
        PRESET_TITANATE -> "钛锂预设"; PRESET_TERNARY -> "三元预设"
        PRESET_LIFEPO4 -> "铁锂预设"; PRESET_SODIUM -> "钠电预设"
        FACTORY_VENDOR_RESET -> "恢复厂家设置"; SAVE_USER_DATA -> "保存用户数据"
        FORCE_CHARGE -> "强制开启充电"
        else -> "命令 $cmd"
    }

    /** 危险命令（红色主题 + 警告文案）：清零/复位/重启/预设这类会改变设备状态或有数据损失 */
    fun isDangerous(cmd: Int): Boolean = cmd in setOf(
        POWER_OFF, FACTORY_RESET, FACTORY_VENDOR_RESET, BT_CLOSE, RESTART, CURRENT_ZERO,
        CLEAR_LOG, CLEAR_DISCHARGE_CAP, CLEAR_CHARGE_CAP, CLEAR_DISCHARGE_TIME,
        CLEAR_CHARGE_TIME, CLEAR_RUNTIME, CLEAR_PROTECT_COUNT,
        PRESET_TITANATE, PRESET_TERNARY, PRESET_LIFEPO4, PRESET_SODIUM,
    )

    /**
     * 最高危：需手工输入「确认」才能执行（防误触）。
     * 这四个要么让设备停机、要么抹掉全部配置，点错一次无法就地撤销。
     */
    fun requiresTypedConfirm(cmd: Int): Boolean =
        cmd in setOf(POWER_OFF, FACTORY_RESET, FACTORY_VENDOR_RESET, BT_CLOSE)

    /** 开关类命令：结果由实时帧就地反映，不必再弹 0x61 结果横幅 */
    fun isToggle(cmd: Int): Boolean = cmd in setOf(
        CHARGE_ON, CHARGE_OFF, DISCHARGE_ON, DISCHARGE_OFF, BALANCE_ON, BALANCE_OFF,
    )

    /** 会重写参数区的命令：成功后要强制重读参数，界面才不会停在旧值 */
    fun rewritesParams(cmd: Int): Boolean = cmd in setOf(
        PRESET_TITANATE, PRESET_TERNARY, PRESET_LIFEPO4, PRESET_SODIUM,
        FACTORY_RESET, FACTORY_VENDOR_RESET, SAVE_USER_DATA,
    )

    /** 确认弹窗里的后果说明（高危命令必须写清楚会失去什么） */
    fun warning(cmd: Int): String? = when (cmd) {
        POWER_OFF -> "设备将立即断电关机，需要人工重新上电才能恢复"
        FACTORY_RESET -> "会抹掉全部用户参数并恢复默认阈值配置"
        FACTORY_VENDOR_RESET -> "恢复厂家设置，比恢复出厂更彻底（含厂家参数区）"
        BT_CLOSE -> "关闭后无法再通过蓝牙连接本设备，需重新上电或改用有线方式恢复"
        BT_INIT -> "蓝牙模块复位，连接会短暂中断并自动重连"
        RESTART -> "设备将重启，期间实时数据中断，权限可能回落"
        CURRENT_ZERO -> "电流传感器零点校准：必须在无电流状态下执行，有电流时执行会校错"
        CLEAR_LOG -> "设备内的历史记录将被清空，无法恢复"
        CLEAR_DISCHARGE_CAP, CLEAR_CHARGE_CAP -> "累计充放电容量清零，无法恢复（不影响当前电量）"
        CLEAR_DISCHARGE_TIME, CLEAR_CHARGE_TIME -> "累计充放电时长清零，无法恢复"
        CLEAR_RUNTIME -> "累计运行时间清零，无法恢复"
        CLEAR_PROTECT_COUNT -> "保护次数统计清零，无法恢复"
        PRESET_TITANATE, PRESET_TERNARY, PRESET_LIFEPO4, PRESET_SODIUM ->
            "会用一整套预设阈值覆盖当前保护参数（电压/温度/电流），请先确认电池化学体系匹配"
        else -> null
    }
}

/** 电池类型枚举（0xFAFx 编码，docs 13.5；2026-09-30 实测 0xFAF1=三元锂） */
val CELL_TYPE: Map<Long, String> = mapOf(
    0xFAF1L to "三元锂", 0xFAF2L to "磷酸铁锂", 0xFAF3L to "钛酸锂",
    0xFAF6L to "钠电", 0xFAF7L to "磷酸锰铁锂",
)

/** 参数寄存器定义（docs/附录A；倍率 = 工程量→原始值） */
class ParamDef(
    val name: String,
    val addr: Int,          // 字节地址
    val scale: Double,      // 原始值 = 工程量 × scale
    val unit: String,
    val min: Double,
    val max: Double,
    val step: Double = 0.0,
    val dict: Map<Long, String>? = null,   // 枚举值→显示文字（未命中回落数字）
    /** 设备状态量/累计量：由设备自行维护，只能通过清零类控制命令归零，不给输入框 */
    val readOnly: Boolean = false,
    /** 只读原因（编辑弹窗里展示给用户） */
    val note: String? = null,
)

object ParamTable {
    val groups: List<Pair<String, IntRange>> = listOf(
        "电压保护" to 0..50,
        "温度保护" to 56..98,
        "电流保护" to 104..138,
        "均衡参数" to 140..150,
        "电池组与容量" to 152..196,
        "系统参数" to 298..374,
        "其他参数" to 378..406,
        "厂家参数" to 592..706,
    )

    /** 常用参数子集（UI 编辑 + Mock 引擎校验共用）；地址/倍率见 docs/附录A */
    val defs: List<ParamDef> = listOf(
        // A.1 电压保护 / 告警（0 ~ 50）
        ParamDef("单体过压保护电压", 0, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("单体过压恢复", 2, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("单体二级过压保护", 4, 1000.0, "V", 2.0, 4.6, 0.001),
        ParamDef("单体二级过压恢复", 6, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("总压过压保护电压", 8, 10.0, "V", 40.0, 95.0, 0.1),
        ParamDef("总压过压恢复", 10, 10.0, "V", 40.0, 95.0, 0.1),
        ParamDef("单体欠压保护电压", 12, 1000.0, "V", 2.0, 3.65, 0.001),
        ParamDef("单体欠压恢复", 14, 1000.0, "V", 2.0, 3.65, 0.001),
        ParamDef("单体二级欠压保护", 16, 1000.0, "V", 1.5, 3.6, 0.001),
        ParamDef("单体二级欠压恢复", 18, 1000.0, "V", 1.5, 3.6, 0.001),
        ParamDef("总压欠压保护电压", 20, 10.0, "V", 20.0, 80.0, 0.1),
        ParamDef("总压欠压恢复", 22, 10.0, "V", 20.0, 80.0, 0.1),
        ParamDef("单体压差保护", 24, 1000.0, "V", 0.01, 1.0, 0.001),
        ParamDef("单体压差恢复", 26, 1000.0, "V", 0.01, 1.0, 0.001),
        ParamDef("单体过压告警电压", 32, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("单体过压告警恢复", 34, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("总压过压告警", 36, 10.0, "V", 40.0, 95.0, 0.1),
        ParamDef("总压过压告警恢复", 38, 10.0, "V", 40.0, 95.0, 0.1),
        ParamDef("单体欠压告警电压", 40, 1000.0, "V", 2.0, 3.65, 0.001),
        ParamDef("单体欠压告警恢复", 42, 1000.0, "V", 2.0, 3.65, 0.001),
        ParamDef("总压欠压告警", 44, 10.0, "V", 20.0, 80.0, 0.1),
        ParamDef("总压欠压告警恢复", 46, 10.0, "V", 20.0, 80.0, 0.1),
        ParamDef("单体压差告警", 48, 1000.0, "V", 0.01, 1.0, 0.001),
        ParamDef("单体压差告警恢复", 50, 1000.0, "V", 0.01, 1.0, 0.001),

        // A.2 温度保护 / 告警（56 ~ 98），负值按 s16 解析
        ParamDef("电池高温充电保护", 56, 1.0, "℃", -20.0, 85.0),
        ParamDef("电池高温充电恢复", 58, 1.0, "℃", -20.0, 85.0),
        ParamDef("电池高温放电保护", 60, 1.0, "℃", -20.0, 100.0),
        ParamDef("电池高温放电恢复", 62, 1.0, "℃", -20.0, 100.0),
        ParamDef("功率管高温保护", 64, 1.0, "℃", 30.0, 120.0),
        ParamDef("功率管高温恢复", 66, 1.0, "℃", 30.0, 120.0),
        ParamDef("电池低温充电保护", 68, 1.0, "℃", -40.0, 20.0),
        ParamDef("电池低温充电恢复", 70, 1.0, "℃", -40.0, 20.0),
        ParamDef("电池低温放电保护", 72, 1.0, "℃", -40.0, 20.0),
        ParamDef("电池低温放电恢复", 74, 1.0, "℃", -40.0, 20.0),
        ParamDef("电池充电高温告警", 80, 1.0, "℃", -20.0, 85.0),
        ParamDef("电池充电高温告警恢复", 82, 1.0, "℃", -20.0, 85.0),
        ParamDef("电池放电高温告警", 84, 1.0, "℃", -20.0, 100.0),
        ParamDef("电池放电高温告警恢复", 86, 1.0, "℃", -20.0, 100.0),
        ParamDef("MOS 高温告警", 88, 1.0, "℃", 30.0, 120.0),
        ParamDef("MOS 高温告警恢复", 90, 1.0, "℃", 30.0, 120.0),
        ParamDef("电池充电低温告警", 92, 1.0, "℃", -40.0, 20.0),
        ParamDef("电池充电低温告警恢复", 94, 1.0, "℃", -40.0, 20.0),
        ParamDef("电池放电低温告警", 96, 1.0, "℃", -40.0, 20.0),
        ParamDef("电池放电低温告警恢复", 98, 1.0, "℃", -40.0, 20.0),

        // A.3 电流保护 / 告警（104 ~ 138）
        ParamDef("充电过流保护", 104, 10.0, "A", 1.0, 200.0, 0.1),
        ParamDef("充电过流保护延时", 106, 1.0, "s", 1.0, 60.0),
        ParamDef("放电过流保护", 108, 10.0, "A", 1.0, 300.0, 0.1),
        ParamDef("放电过流保护延时", 110, 1.0, "s", 1.0, 60.0),
        ParamDef("2 级放电过流保护", 112, 10.0, "A", 1.0, 500.0, 0.1),
        ParamDef("2 级放电过流延时", 114, 1.0, "ms", 100.0, 60000.0),
        ParamDef("短路保护电流", 116, 1.0, "A", 100.0, 1000.0),
        ParamDef("短路保护延时", 118, 1.0, "μs", 1.0, 1000.0),
        ParamDef("充电过流告警", 124, 10.0, "A", 1.0, 200.0, 0.1),
        ParamDef("充电过流告警恢复", 126, 10.0, "A", 1.0, 200.0, 0.1),
        ParamDef("放电过流告警", 128, 10.0, "A", 1.0, 300.0, 0.1),
        ParamDef("放电过流告警恢复", 130, 10.0, "A", 1.0, 300.0, 0.1),
        ParamDef("SOC 一级告警", 132, 1.0, "%", 0.0, 100.0),
        ParamDef("SOC 二级告警", 134, 1.0, "%", 0.0, 100.0),

        // A.4 均衡参数（140 ~ 150）
        ParamDef("均衡极限电压", 140, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("充电均衡起控电压", 142, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("均衡启动压差", 144, 1000.0, "V", 0.01, 0.5, 0.001),
        ParamDef("均衡结束压差", 146, 1000.0, "V", 0.01, 0.45, 0.001),
        ParamDef("均衡电流", 148, 1.0, "", 0.0, 65535.0),   // docs 单位空缺，官方按原始值显示（实测 180）
        ParamDef("均衡充电电流", 150, 10.0, "A", 0.1, 20.0, 0.1),

        // A.5 电池组配置（152 ~ 196）
        ParamDef("电池类型选择", 152, 1.0, "", 0.0, 65535.0, dict = CELL_TYPE),
        ParamDef("电池串数", 154, 1.0, "串", 4.0, 24.0),
        ParamDef("欠压内阻补偿", 156, 10.0, "mΩ", 0.0, 500.0, 0.1),
        ParamDef("自动关机电压", 158, 1000.0, "V", 0.0, 4.5, 0.001),
        ParamDef("最大充电请求电流", 160, 10.0, "A", 1.0, 200.0, 0.1),
        ParamDef("电池物理容量", 162, 1_000_000.0, "Ah", 1.0, 2000.0),
        ParamDef("剩余容量", 166, 1_000_000.0, "Ah", 0.0, 2000.0, readOnly = true,
            note = "设备状态量，随充放电自动更新；校准请用「电流归零」等控制命令"),
        ParamDef("总共循环容量", 170, 1_000_000.0, "Ah", 0.0, 4294.9, readOnly = true,
            note = "累计量，只能通过「清总充/放电容量」控制命令归零"),
        ParamDef("100% 单体电压", 174, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("90% 单体电压", 176, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("80% 单体电压", 178, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("70% 单体电压", 180, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("60% 单体电压", 182, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("50% 单体电压", 184, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("40% 单体电压", 186, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("30% 单体电压", 188, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("20% 单体电压", 190, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("10% 单体电压", 192, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("0% 单体电压", 194, 1000.0, "V", 2.0, 4.5, 0.001),
        ParamDef("SOC 校准方式", 196, 1.0, "", 0.0, 65535.0),   // docs 单位 N，官方按原始值显示

        // A.7 系统配置（298 ~ 328；330 起是密码区，不列也不读）
        ParamDef("电流传感器量程", 298, 10.0, "A", 10.0, 500.0, 0.1),
        ParamDef("无电流自动待机", 300, 1.0, "s", 10.0, 3600.0),
        ParamDef("蓝牙地址编码", 302, 1.0, "", 0.0, 65535.0),
        ParamDef("静态消耗电流", 304, 10.0, "mA", 0.0, 1000.0, 0.1),
        ParamDef("电池温度传感器屏蔽", 306, 1.0, "", 0.0, 255.0),
        ParamDef("启动电流", 308, 1.0, "A", 0.0, 500.0),
        ParamDef("系统基准电压", 310, 1000.0, "V", 0.0, 5.0, 0.001),
        ParamDef("总压转换参数", 312, 1.0, "", 0.0, 65535.0),
        ParamDef("系统运行时间", 314, 1.0, "", 0.0, 65535.0, readOnly = true,
            note = "累计运行时长，只能通过「清零运行时间」控制命令归零"),
        ParamDef("禁止放电时长", 316, 1.0, "min", 0.0, 65535.0),
        ParamDef("禁止充电时长", 318, 1.0, "min", 0.0, 65535.0),
        ParamDef("允许放电时长", 320, 1.0, "min", 0.0, 65535.0),
        ParamDef("允许充电时长", 322, 1.0, "min", 0.0, 65535.0),
        ParamDef("跳线配置L", 324, 1.0, "", 0.0, 65535.0),
        ParamDef("跳线配置H", 326, 1.0, "", 0.0, 65535.0),
        ParamDef("静态关机延时", 328, 1.0, "H", 0.0, 65535.0),
        ParamDef("系统基准电压偏移", 374, 1.0, "mV", 0.0, 65535.0),

        // A.6 其他参数（378 ~ 406）；官方页把 384~394 显示成「附加参数1~6」
        ParamDef("轮胎长度", 378, 1.0, "mm", 0.0, 65535.0),
        ParamDef("一周脉冲次数", 380, 1.0, "", 0.0, 65535.0),
        ParamDef("从机数", 382, 1.0, "", 0.0, 65535.0),
        ParamDef("最大回馈电流", 384, 1.0, "", 0.0, 65535.0),
        ParamDef("最大放电电流", 386, 1.0, "", 0.0, 65535.0),
        ParamDef("最长预充时间", 388, 1.0, "s", 0.0, 65535.0),
        ParamDef("预充百分比", 390, 1.0, "", 0.0, 65535.0),
        ParamDef("附加参数5", 392, 1.0, "", 0.0, 65535.0),
        ParamDef("附加参数6", 394, 1.0, "", 0.0, 65535.0),
        ParamDef("强充时间间隔最大值", 396, 1.0, "", 0.0, 65535.0),
        ParamDef("SOC保持值最大值", 398, 1.0, "", 0.0, 65535.0),
        ParamDef("强充充电SOC最大值", 400, 1.0, "", 0.0, 65535.0),
        ParamDef("停止充电SOC最大值", 402, 1.0, "", 0.0, 65535.0),
        ParamDef("充电限流电流最大值", 404, 1.0, "", 0.0, 65535.0),
        ParamDef("充电限流时间最大值", 406, 1.0, "", 0.0, 65535.0),

        // A.8 厂家数值区（592 ~ 618 / 700 ~ 706）；65535=未披露，读回需权限（实测待验证）
        ParamDef("串口1波特率", 592, 1.0, "", 0.0, 65535.0),
        ParamDef("串口2波特率", 594, 1.0, "", 0.0, 65535.0),
        ParamDef("串口3波特率", 596, 1.0, "", 0.0, 65535.0),
        ParamDef("串口4波特率", 598, 1.0, "", 0.0, 65535.0),
        ParamDef("CAN波特率", 600, 1.0, "", 0.0, 65535.0),
        ParamDef("功能配置1", 602, 1.0, "", 0.0, 65535.0),
        ParamDef("功能配置2", 604, 1.0, "", 0.0, 65535.0),
        ParamDef("BMS电流信息", 606, 1.0, "", 0.0, 65535.0),
        ParamDef("系统电池串数", 608, 1.0, "", 0.0, 65535.0),
        ParamDef("温度检测个数", 610, 1.0, "", 0.0, 65535.0),
        ParamDef("校准基准电压", 612, 1000.0, "V", 0.0, 5.0, 0.001),
        ParamDef("校准总压参数", 614, 1.0, "", 0.0, 65535.0),
        ParamDef("校准电流量程", 616, 10.0, "A", 0.0, 5000.0, 0.1),
        ParamDef("DTU所在串口", 618, 1.0, "", 0.0, 65535.0),
        ParamDef("出厂短路电流", 700, 1.0, "A", 0.0, 65535.0),
        ParamDef("出厂短路延时", 702, 1.0, "μs", 0.0, 65535.0),
        ParamDef("MODBUS通信地址", 704, 1.0, "", 0.0, 65535.0),
        ParamDef("ANT通信地址", 706, 1.0, "", 0.0, 65535.0),
    )

    fun byAddr(addr: Int): ParamDef? = defs.firstOrNull { it.addr == addr }

    /** 跨两个寄存器（低字 @addr、高字 @addr+2）的 u32 参数：容量类倍率 1e6，单寄存器放不下 */
    val u32Addrs: Set<Int> = setOf(162, 166, 170)

    /** 参数区原始值 → 工程量显示；未读到返回 "--" */
    fun format(params: Map<Int, Int>, def: ParamDef): String {
        val raw: Long = if (def.addr in u32Addrs) {
            val lo = params[def.addr]?.toLong() ?: return "--"
            val hi = params[def.addr + 2]?.toLong() ?: 0L
            (hi shl 16) or (lo and 0xFFFF)
        } else {
            val v = params[def.addr]?.toLong() ?: return "--"
            // 真机实测：权限不足时整块返回 0xFFFF（未披露），不是真实读数
            if (v == 0xFFFFL) return "--"
            // 温度类参数允许负值，寄存器按 s16 存（真机实测 -2℃ = FE FF）
            if (def.min < 0) (v and 0xFFFF).let { if (it >= 0x8000) it - 0x10000 else it } else v
        }
        val v = raw / def.scale
        def.dict?.get(raw)?.let { return it }
        return when {
            def.scale >= 1_000_000 -> "%.1f".format(v)
            def.scale >= 1000 -> "%.3f".format(v)
            def.scale >= 10 -> "%.1f".format(v)
            else -> "%.0f".format(v)
        }
    }

    /** 显示范围文本（编辑弹窗用） */
    fun rangeText(def: ParamDef): String {
        fun f(v: Double) = when {
            def.scale >= 1000 -> "%.3f".format(v)
            def.scale >= 10 -> "%.1f".format(v)
            else -> "%.0f".format(v)
        }
        return "${f(def.min)}~${f(def.max)} ${def.unit}"
    }

    /** 密码槽（docs/09 §9.1）：1~4 级 8 字节 @330+8(n-1)，5 级 12 字节 @362，管理员 12 字节 @374 */
    val passwordSlots: List<Triple<Int, Int, String>> = listOf(
        Triple(1, 330, "一级"), Triple(2, 338, "二级"), Triple(3, 346, "三级"),
        Triple(4, 354, "四级"), Triple(5, 362, "五级"), Triple(9, 374, "管理员"),
    )

    fun slotAddr(level: Int): Int = when (level) {
        1 -> 330; 2 -> 338; 3 -> 346; 4 -> 354; 5 -> 362; 9 -> 374
        else -> 330
    }

    fun slotLen(level: Int): Int = if (level <= 4) 8 else 12
}
