package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.DeviceFamily

/**
 * 设备能力位：某设备（家族）能提供哪些数据。
 *
 * 首页/详情按它**显隐**卡片与字段——有能力才显示真实值，没能力整块隐藏，
 * 避免出现一堆读不到的 "--" 或误导性的空卡（电量计三家都没有保护/告警位）。
 *
 * 取值依据官方小程序解包（见 [Plan/电量计兼容-开发计划.md] §6）。
 */
data class DeviceCapabilities(
    /** 保护/告警位域（蚂蚁实时帧才有；两家电量计都没有） */
    val protectAlarm: Boolean = true,
    /** 充电/放电 MOS 状态 */
    val mos: Boolean = true,
    /** 均衡状态/位图 */
    val balance: Boolean = true,
    /** 逐串单体电压 */
    val perCellVoltage: Boolean = true,
    /** 多路温度（MOS/均衡/多探头） */
    val multiTemp: Boolean = true,
    /** 扩展时间字段（充电/放电剩余、本次充电时长、上次充电间隔、运行时间） */
    val extTimes: Boolean = true,
    /** 权限等级（蚂蚁专有校验） */
    val permission: Boolean = true,
    val controlCharge: Boolean = true,
    val controlDischarge: Boolean = true,
    val controlBalance: Boolean = true,
    /** 逐串单体最多支持的串数（0 = 不支持逐串） */
    val maxCells: Int = 32,
) {
    companion object {
        /** 蚂蚁保护板：全能力（与现状一致） */
        val Board = DeviceCapabilities()

        /** 蓝宝电量计：无保护/告警、无 MOS/均衡、无逐串、仅 1 路温度、无扩展时间、无权限、无控制 */
        val LanBao = DeviceCapabilities(
            protectAlarm = false, mos = false, balance = false,
            perCellVoltage = false, multiTemp = false, extTimes = false,
            permission = false, controlCharge = false, controlDischarge = false,
            controlBalance = false, maxCells = 0,
        )

        /** 陆行电量计（EM2APP）：有逐串单体(≤30)与多路温度；无保护/告警、无 MOS/均衡、无扩展时间、无权限、无控制 */
        val LuXing = DeviceCapabilities(
            protectAlarm = false, mos = false, balance = false,
            perCellVoltage = true, multiTemp = true, extTimes = false,
            permission = false, controlCharge = false, controlDischarge = false,
            controlBalance = false, maxCells = 30,
        )

        fun forFamily(f: DeviceFamily): DeviceCapabilities = when (f) {
            DeviceFamily.LanBao -> LanBao
            DeviceFamily.LuXing -> LuXing
            else -> Board
        }
    }
}
