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
    /**
     * 剩余容量（Ah）。**不一定有专用寄存器**：陆行帧里那个 `actualCapacity` 真机恒等于额定值
     * （照搬会让「剩余容量」永远等于「总容量」），官方是按 `SOC × 额定容量` 算出来显示/导出的，
     * 所以陆行这一格是算出来的（见 LuXingProtocol.decodeRealtime）；蓝宝是设备自报。
     */
    val remainCapAh: Double? = null,
    val cycles: Int? = null,
    val battState: String? = null,
    val chMos: Boolean? = null,
    val disMos: Boolean? = null,
    val balancing: Boolean? = null,
    val temps: List<Pair<String, Double>>? = null,
    /** 逐串单体电压（V） */
    val cells: List<Double>? = null,
    /**
     * 与 [cells] 一一对应的 1 起串号。null = 按顺序 1..N。
     *
     * 陆行 108 帧用 `0xFFFF` 表示该路未接；官方数组保留 `"--"` 占位，串号不能压缩。
     * 这里只携带**有效**单体的原始串号，供合并层还原跳号（例如 `[1, 2, 4]`）。
     */
    val cellIndices: List<Int>? = null,
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

    // ---- 电量计**自身**的设备信息（蓝宝表2 的设备序列号/版本/电池类型/串数）----
    // 只进「设置 → 关于（身份区）」，不进主读数：这些是设备档案，不是实时量。
    // 键名与保护板身份区保持一致（serialNo/hwVersion/swVersion/batteryType），
    // 界面复用同一批行，不需要为电量计再写一套展示。
    /** 设备序列号（蓝宝表2 偏移 0，16 字节 ASCII） */
    val serialNo: String? = null,
    val hwVersion: String? = null,
    val swVersion: String? = null,
    /**
     * 电池类型**文案**。
     *
     * ⚠️ 保护板与电量计的码表不同：保护板实时帧是 `0xFAFx` 编码（docs/13 §13.5），
     * 蓝宝表2 是 1=三元锂 / 2=磷酸铁锂 / 3=铅酸电池 的小整数（`createParamNew.js`
     * `Battery_Type.options`）。换算在各协议实现里做，这里只传最终中文。
     */
    val batteryType: String? = null,
    /**
     * 设备/帧声明的单体总数：蓝宝表2 偏移 47、陆行 108 帧的 `cellNum`。
     * 身份区用它显示串数；合并层用陆行这个值给 0xFFFF 的通道补 "--" 占位格。
     */
    val cellCount: Int? = null,
) {
    val isEmpty: Boolean
        get() = listOfNotNull(
            totalVoltage, current, power, soc, totalCapAh, remainCapAh, cycles,
            battState, chMos, disMos, balancing, temps, cells, maxCell, minCell, avgCell,
            deltaCell, remainChargeMin, remainDischargeMin, relayBoardBrand, relayBoardName,
            relayBoardMac, serialNo, hwVersion, swVersion, batteryType, cellCount,
        ).isEmpty()

    /**
     * 本帧是否包含**实时量**（电压/电流/功率/SOC）。
     *
     * 电量计的 90/96/表2 这类增量帧只带中继信息、循环或容量；它们到达时不能把
     * `BmsStatus.hasData` 置真，否则界面会在真正的实时帧到达前显示 0.0 V / 0%。
     */
    val hasLiveReading: Boolean
        get() = totalVoltage != null || current != null || power != null || soc != null
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

    /** 处理一条完整应答；返回读数（无法解析/非本协议返回 null） */
    fun onData(bytes: ByteArray): MeterReading?

    /** 处理通知字节流；可保留分片，并按接收顺序返回每个完整帧的增量读数。 */
    fun onNotification(bytes: ByteArray): List<MeterReading> = listOfNotNull(onData(bytes))

    /** 本次连接所需的各张表已收到；不等同于某个增量读数非空。 */
    val snapshotReady: Boolean get() = false

    /** 清空本次连接的接收分片与就绪状态；保留密码和轮询状态。 */
    fun reset() {}

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
