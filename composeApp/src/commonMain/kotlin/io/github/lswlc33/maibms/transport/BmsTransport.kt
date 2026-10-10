package io.github.lswlc33.maibms.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** 扫描发现的设备 */
data class ScanDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    /** 按广播名判定的家族；识别不出时 Unknown */
    val family: DeviceFamily = DeviceFamily.Unknown,
)

/** 连接状态 */
enum class LinkState { Idle, Connecting, Connected, Disconnected }

/**
 * BLE 通道：一组「写特征 + 通知特征」的配对（docs/02-蓝牙链路.md §2.2）。
 *
 * 三组通道是同一颗 BLE 模组上的三组特征值，背后复用的是不同的物理接口：
 * 备用 A 占用**蓝牙屏**那一路、备用 B 占用**充电器**那一路 —— 占用期间对应外设无法与保护板通信，
 * 而且官方只在默认通道上放开固件升级。
 */
enum class BleChannel(
    val id: String,
    val label: String,
    /** 通道标签（特征值名），界面上直接显示 */
    val tag: String,
    val writeUuid: String,
    val notifyUuid: String,
    /** 备用通道的代价（卡片警示语）；默认通道为 null */
    val warn: String? = null,
) {
    Default("ffe1", "默认通道", "ffe1收发", "FFE1", "FFE1"),
    BackupA(
        "fff3-fff4", "备用通道 A", "fff3发-fff4收", "FFF3", "FFF4",
        "使用期间蚂蚁蓝牙屏将无法与保护板通信，且不支持固件升级",
    ),
    BackupB(
        "fff5-fff6", "备用通道 B", "fff5发-fff6收", "FFF5", "FFF6",
        "使用期间蚂蚁充电器将无法与保护板通信，且不支持固件升级",
    ),
    /**
     * 电量计（中继器）通道：蓝宝/陆行两家都是 `FFE0` 服务 + `FFE2` 写 / `FFE1` 通知
     * （陆行 `utils/bluetoothService.js`、蓝宝 `utils/btls/bleHandler.js`）。
     * 与保护板的三条通道不是一套；只有家族为电量计时才会用到。
     */
    Meter(
        "meter", "电量计通道", "ffe2发-ffe1收", "FFE2", "FFE1",
    );

    companion object {
        fun byId(id: String?): BleChannel? = values().firstOrNull { it.id == id }
    }
}

/**
 * 通道候选的生成规则（与官方实现一致，见 docs/02-蓝牙链路.md §2.2）：
 * **只有暴露了可写 `FFF5` 特征的硬件才有备用通道**；只提供 `FFE1` 的设备只试默认通道，
 * 官方在这种设备上连自动轮切都不会启动（日志「设备不支持备用通道，跳过自动轮切」）。
 */
fun bleChannelCandidates(hasWritableFff5: Boolean): List<BleChannel> =
    if (hasWritableFff5) listOf(BleChannel.Default, BleChannel.BackupA, BleChannel.BackupB)
    else listOf(BleChannel.Default)

/**
 * 传输抽象：BLE 实际实现（Android）/ 虚拟 BMS（全平台演示）共用。
 * 真实与虚拟实现都只依赖 core 协议层，互不感知。
 */
interface BmsTransport {
    val linkState: Flow<LinkState>

    /** 收到的字节流（可能分片，交给 FrameParser 重组） */
    val incoming: Flow<ByteArray>

    suspend fun connect(address: String? = null)
    suspend fun disconnect()
    suspend fun write(frame: ByteArray)

    /**
     * 设置本次连接的目标家族（由上层在 [connect] 前调用；决定服务/通道/握手策略）。
     * 默认实现忽略——只有真实 BLE 后端需要它。
     */
    fun setTargetFamily(family: DeviceFamily) {}

    /** 当前连接设备的家族（未连接/未知 = Unknown）；用于界面按电量计/保护板分支 */
    val currentFamily: StateFlow<DeviceFamily> get() = DEFAULT_FAMILY

    /** 扫描（虚拟设备返回内置假列表） */
    suspend fun scan(onFound: (ScanDevice) -> Unit) {}

    /** 停止扫描（重复调用安全） */
    fun stopScan() {}

    /** 是否支持真实扫描（决定 UI 是否启用） */
    val supportsScan: Boolean get() = false

    /** 连接过程中的提示/失败原因（null=无）；用于把重连进度反馈到界面 */
    val connectHint: Flow<String?> get() = flowOf(null)

    // ---- 通信通道（docs/02 §2.2）----

    /** 当前正在使用的通道（null = 未连接或尚未探明） */
    val activeChannel: StateFlow<BleChannel?> get() = DEFAULT_ACTIVE_CHANNEL

    /** 本设备提供且可用的通道（真实 BLE 在发现服务后得出）；**空 = 还没探测过**（未连接） */
    val availableChannels: StateFlow<List<BleChannel>> get() = DEFAULT_AVAILABLE_CHANNELS

    /** 是否支持手动切换通道（只有真实 BLE 后端为 true） */
    val supportsChannelSwitch: Boolean get() = false

    /** 手动切换通道：断开当前链路并按新通道重连；不支持切换的后端忽略 */
    suspend fun switchChannel(channel: BleChannel) {}

    /** 设置下次连接优先尝试的通道（仅本进程有效，持久化由上层负责） */
    fun preferChannel(channel: BleChannel?) {}
}

private val DEFAULT_ACTIVE_CHANNEL = MutableStateFlow<BleChannel?>(null)
private val DEFAULT_AVAILABLE_CHANNELS = MutableStateFlow<List<BleChannel>>(emptyList())
private val DEFAULT_FAMILY = MutableStateFlow(DeviceFamily.Unknown)

/** 无 BLE 后端的环境（桌面端等）：全部操作安全空转，链路恒为 Idle */
object NoopTransport : BmsTransport {
    private val _linkState = MutableStateFlow(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState
    override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 0)
    override suspend fun connect(address: String?) {}
    override suspend fun disconnect() {}
    override suspend fun write(frame: ByteArray) {}
}
