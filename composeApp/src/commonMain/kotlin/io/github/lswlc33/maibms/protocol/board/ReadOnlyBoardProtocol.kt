package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.protocol.Crc16
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlinx.serialization.Serializable

/** 新接入保护板的参数只保存展示值，没有写入地址或写入方法。 */
@Serializable
data class BoardSetting(
    val key: String,
    val name: String,
    val value: String,
    val unit: String = "",
    val group: String = "设备信息",
)

/** null 表示本帧未提供该字段，空列表表示设备明确没有该项。 */
data class BoardReading(
    val totalVoltage: Double? = null,
    /** 与现有仪表一致：放电为正，充电为负。 */
    val current: Double? = null,
    val power: Int? = null,
    val soc: Int? = null,
    val totalCapAh: Double? = null,
    val remainCapAh: Double? = null,
    val soh: Int? = null,
    val cycles: Int? = null,
    val totalCycleAh: Int? = null,
    val runtimeSec: Long? = null,
    val remainChargeMin: Int? = null,
    val remainDischargeMin: Int? = null,
    val battState: String? = null,
    val chMos: Boolean? = null,
    val disMos: Boolean? = null,
    val balancing: Boolean? = null,
    val balanceBits: Long? = null,
    val temps: List<Pair<String, Double>>? = null,
    /** 保留原始串号；null 格代表该串未接。 */
    val cells: List<Double?>? = null,
    val protectPairs: List<Pair<Int, String>>? = null,
    val alarmPairs: List<Pair<Int, String>>? = null,
    val statePairs: List<Pair<Int, String>>? = null,
    val identity: Map<String, String> = emptyMap(),
    val settings: List<BoardSetting>? = null,
) {
    val hasLiveReading: Boolean get() = totalVoltage != null &&
        (current != null || power != null || cells?.any { it != null } == true ||
            (identity["protocol"] == "MINGTANG-TLV" && soc != null))
}

/** 每次只保留一条在途读取，Modbus 应答不带寄存器地址，不能并发猜测归属。 */
interface ReadOnlyBoardProtocol {
    val family: DeviceFamily
    val caps: DeviceCapabilities
    fun nextRequest(nowMs: Long): ByteArray?
    fun onNotification(bytes: ByteArray, nowMs: Long): List<BoardReading>
    fun refreshSettings()
    /** 可选 BLE 会话登录，只使用用户已有凭据，不修改模块密码。 */
    fun configureAccess(password: String?, macAddress: String?) {}
    /** Optional connection-detected layout that should seed a fresh poller for the same device. */
    val protocolLayoutHint: String? get() = null
    fun configureLayout(layout: String) {}
    /** 已由同一连接探测确认的 Modbus 地址，不在设备间记忆。 */
    val modbusAddress: Int? get() = null
    fun configureAddress(address: Int) {}
    fun reset()
    val snapshotReady: Boolean
    val error: String? get() = null

    companion object {
        fun forFamily(family: DeviceFamily): ReadOnlyBoardProtocol? = when (family) {
            DeviceFamily.YanYang -> YanYangProtocol()
            DeviceFamily.Jbd -> JbdProtocol()
            DeviceFamily.Jikong -> JikongProtocol()
            DeviceFamily.Yadi -> YadiProtocol()
            DeviceFamily.Mingtang -> MingtangProtocol()
            else -> null
        }
    }
}

/** 传输层也使用同一白名单，误入旧控制入口时仍发不出修改命令。 */
fun isSafeBoardRead(family: DeviceFamily, frame: ByteArray): Boolean {
    fun u8(i: Int) = frame[i].toInt() and 0xFF
    return when (family) {
        DeviceFamily.YanYang -> {
            if (frame.size != 8 || u8(0) !in 0..247 || u8(1) != 3) false
            else {
                val reg = (u8(2) shl 8) or u8(3)
                val count = (u8(4) shl 8) or u8(5)
                val allowed = if (u8(0) == 0) reg == 0 && count == 1 else
                    (reg to count) in listOf(63 to 4, 1 to 79, 80 to 80, 160 to 81, 240 to 20,
                        1 to 47, 48 to 42, 90 to 22)
                allowed && Crc16.modbus(frame, 0, 6) == (u8(6) or (u8(7) shl 8))
            }
        }
        DeviceFamily.Jbd -> if (frame.size >= 5 && u8(0) == 0xFF && u8(1) == 0xAA) {
            val command = u8(2)
            val size = u8(3)
            frame.size == size + 5 &&
                ((command == 0x15 && size == 6 && (4 until 10).all { u8(it) in 0x20..0x7E }) ||
                    (command == 0x17 && size == 0) || (command == 0x18 && size == 6)) &&
                (frame.drop(2).dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF) == u8(frame.lastIndex)
        } else frame.size == 7 && u8(0) == 0xDD && u8(1) == 0xA5 &&
            u8(2) in 3..5 && u8(3) == 0 && u8(6) == 0x77 &&
            ((u8(4) shl 8) or u8(5)) == ((-u8(2)) and 0xFFFF)
        DeviceFamily.Jikong -> frame.size == 20 && u8(0) == 0xAA && u8(1) == 0x55 &&
            u8(2) == 0x90 && u8(3) == 0xEB && u8(4) in 0x96..0x97 &&
            (5 until 19).all { u8(it) == 0 } &&
            (frame.take(19).sumOf { it.toInt() and 255 } and 255) == u8(19)
        DeviceFamily.Yadi -> YadiProtocol.isReadRequest(frame)
        DeviceFamily.Mingtang -> MingtangProtocol.isReadRequest(frame)
        else -> false
    }
}

fun boardFrameForLog(family: DeviceFamily, frame: ByteArray): String {
    if (family == DeviceFamily.Jbd && frame.size >= 4 && frame[0] == 0xFF.toByte() &&
        frame[1] == 0xAA.toByte() && (frame[2] == 0x18.toByte() || frame[2] == 0x15.toByte())) {
        val command = (frame[2].toInt() and 255).toString(16).uppercase()
        return "FF AA $command ${frame[3].toInt() and 0xFF} [登录数据已隐藏]"
    }
    return frame.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}

fun boardProbeRequest(family: DeviceFamily): ByteArray? = when (family) {
    DeviceFamily.YanYang -> byteArrayOf(1, 3, 0, 0x3F, 0, 4, 0x74, 5)
    DeviceFamily.Jikong -> JikongProtocol.readFrame(0x97)
    DeviceFamily.Jbd -> byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 3, 0, 0xFF.toByte(), 0xFD.toByte(), 0x77)
    DeviceFamily.Yadi -> YadiProtocol.readFrame(0x1000, 60)
    DeviceFamily.Mingtang -> MingtangProtocol.readFrame()
    else -> null
}
