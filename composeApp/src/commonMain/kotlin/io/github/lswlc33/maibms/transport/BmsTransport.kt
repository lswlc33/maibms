package io.github.lswlc33.maibms.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** 扫描发现的设备 */
data class ScanDevice(val name: String, val address: String, val rssi: Int)

/** 连接状态 */
enum class LinkState { Idle, Connecting, Connected, Disconnected }

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

    /** 扫描（虚拟设备返回内置假列表） */
    suspend fun scan(onFound: (ScanDevice) -> Unit) {}

    /** 停止扫描（重复调用安全） */
    fun stopScan() {}

    /** 是否支持真实扫描（决定 UI 是否启用） */
    val supportsScan: Boolean get() = false

    /** 连接过程中的提示/失败原因（null=无）；用于把重连进度反馈到界面 */
    val connectHint: Flow<String?> get() = flowOf(null)
}

/** 无 BLE 后端的环境（桌面端等）：全部操作安全空转，链路恒为 Idle */
object NoopTransport : BmsTransport {
    private val _linkState = MutableStateFlow(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState
    override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 0)
    override suspend fun connect(address: String?) {}
    override suspend fun disconnect() {}
    override suspend fun write(frame: ByteArray) {}
}
