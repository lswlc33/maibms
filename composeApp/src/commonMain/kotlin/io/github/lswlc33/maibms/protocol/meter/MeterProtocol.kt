package io.github.lswlc33.maibms.protocol.meter

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily

/**
 * 电量计一次解析出的读数（增量合并：null = 本次未提供，保留旧值）。
 * 这些字段是各家电量计协议的解码结果，统一喂给 [io.github.lswlc33.maibms.data.MockBms]。
 */
data class MeterReading(
    /**
     * 总电压（V）——**只能由电量计自测的那一帧提供**。
     *
     * 两家电量计都能同时拿到"自己测的电压"和"被中继保护板自报的电压"，这是两套测量，
     * 数值本来就不一致（陆行 90 帧里专设 `volCalGain1/volCalOffset1` 给中继那一路做校准）。
     * 一旦两个来源都往这个字段写，界面（600ms 一拍写两次）就会在两个值之间来回跳——
     * 官方小程序首页的「电压」只绑 108 实时帧的 `systemVoltage`，CSV 的「总电压(V)」列同样是它。
     */
    val totalVoltage: Double? = null,
    val current: Double? = null,
    val power: Int? = null,
    val soc: Int? = null,
    val totalCapAh: Double? = null,
    val remainCapAh: Double? = null,
    val cycles: Int? = null,
    val battState: String? = null,
    val chMos: Boolean? = null,
    val disMos: Boolean? = null,
    val balancing: Boolean? = null,
    val temps: List<Pair<String, Double>>? = null,
    /** 逐串单体电压（V） */
    val cells: List<Double>? = null,
    val maxCell: Double? = null,
    val minCell: Double? = null,
    val avgCell: Double? = null,
    val deltaCell: Double? = null,
    val remainChargeMin: Int? = null,
    val remainDischargeMin: Int? = null,
    /** 被中继的保护板品牌（如"蚂蚁保护板"）；两家电量计都能报 */
    val relayBoardBrand: String? = null,
    /**
     * 被中继的保护板的蓝牙名（陆行 90 帧 `BoundBmsBtName`、蓝宝表1 `boundDeviceName`）。
     *
     * **不是本机的设备名**：官方把这两个字段都算作"中继的那块板"的信息（陆行 CSV 里叫
     * 「电池蓝牙名」列），设备自己的名字来自广告名/设备信息帧。所以它只喂「中继信息卡」，
     * 绝不能进 `BmsStatus.deviceName`——否则大卡上显示的是另一台设备的名称。
     */
    val relayBoardName: String? = null,
    /** 被中继的保护板的 MAC（蓝宝可报；陆行可能无） */
    val relayBoardMac: String? = null,
) {
    val isEmpty: Boolean
        get() = listOfNotNull(
            totalVoltage, current, power, soc, totalCapAh, remainCapAh, cycles,
            battState, chMos, disMos, balancing, temps, cells, maxCell, minCell, avgCell,
            deltaCell, remainChargeMin, remainDischargeMin, relayBoardBrand, relayBoardName,
            relayBoardMac,
        ).isEmpty()
}

/**
 * 电量计（中继器）协议：一套"轮询请求 + 通知解码"的最小接口，各品牌一个实现。
 *
 * 与保护板协议栈（`protocol/` 下的 Frame/RealtimeDecoder）并行，不共用——电量计是**另一条数据源**，
 * 由 [io.github.lswlc33.maibms.data.BmsRepository] 按家族选择。
 */
interface MeterProtocol {
    val family: DeviceFamily
    val caps: DeviceCapabilities

    /** 连接就绪后立刻发送的初始化帧（如读设备信息/读表头） */
    fun initialFrames(): List<ByteArray> = emptyList()

    /** 每拍轮询要发送的帧（实现可内部轮换，一次给多条也行） */
    fun nextPollFrames(): List<ByteArray>

    /** 处理一条通知字节流；返回读数（无法解析/非本协议返回 null） */
    fun onData(bytes: ByteArray): MeterReading?

    /** 注入按设备记住的密码/匹配码（可空） */
    fun setSecret(secret: String?) {}

    companion object {
        /** 按家族取协议实现；保护板或无协议实现的家族返回 null（只连不读数） */
        fun forFamily(family: DeviceFamily, secret: String? = null): MeterProtocol? = when (family) {
            DeviceFamily.LanBao -> LanBaoProtocol(secret)
            DeviceFamily.LuXing -> LuXingProtocol()
            else -> null
        }
    }
}
