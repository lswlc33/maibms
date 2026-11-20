package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.math.roundToInt

/** JK02 24S / 32S 与 JK04 浮点均衡板，只发送 97 设备信息与 96 实时读取。 */
class JikongProtocol : ReadOnlyBoardProtocol {
    override val family = DeviceFamily.Jikong
    override val caps: DeviceCapabilities
        get() = if (layout == Layout.Jk04) DeviceCapabilities.Jikong.copy(
            protectAlarm = false, mos = false, maxCells = 24, multiTemp = false,
            runtime = true, cycleCount = false, capacity = false, stateOfHealth = false,
        ) else DeviceCapabilities.Jikong.copy(runtime = true)
    private val buffer = ByteArray(600)
    private var buffered = 0
    private var started = false
    private var version: Int? = null
    private enum class Layout { Unknown, Jk02, Jk04 }
    private var layout = Layout.Unknown
    private var jk04CellCount: Int? = null
    private var identity = emptyMap<String, String>()
    private var pendingAt: Long? = null
    private var lastRequestAt = Long.MIN_VALUE / 2
    private var lastLiveAt: Long? = null
    private var infoAt: Long? = null
    private var moduleReady = false
    private var lastError: String? = null
    private var settings = emptyList<BoardSetting>()
    override val protocolLayoutHint: String? get() = layout.takeUnless { it == Layout.Unknown }?.name
    override fun configureLayout(layout: String) {
        if (this.layout == Layout.Unknown) this.layout = Layout.values().firstOrNull { it.name == layout } ?: Layout.Unknown
    }
    override val snapshotReady: Boolean get() = lastLiveAt != null
    override val error: String? get() = lastError
    override val modbusAddress: Int? get() = null

    override fun reset() {
        buffered = 0
        started = false
        version = null
        layout = Layout.Unknown
        jk04CellCount = null
        identity = emptyMap()
        pendingAt = null
        lastRequestAt = Long.MIN_VALUE / 2
        lastLiveAt = null
        infoAt = null
        moduleReady = false
        lastError = null
        settings = emptyList()
    }

    override fun refreshSettings() {
        if (pendingAt == null) version = null
    }

    override fun nextRequest(nowMs: Long): ByteArray? {
        pendingAt?.let {
            if (nowMs - it < 3_000) return null
            pendingAt = null
            buffered = 0
            lastError = "极空读取超时，请确认选择了极空板型、关闭其他连接此板的 App"
        }
        if (version != null && !moduleReady && nowMs - (infoAt ?: nowMs) < 1_000) return null
        if (nowMs - lastRequestAt < 600 || lastLiveAt?.let { nowMs - it < 600 } == true) return null
        started = true
        pendingAt = nowMs
        lastRequestAt = nowMs
        return readFrame(if (version == null) 0x97 else 0x96)
    }

    override fun onNotification(bytes: ByteArray, nowMs: Long): List<BoardReading> {
        if (!started) return emptyList()
        val out = mutableListOf<BoardReading>()
        for (byte in bytes) {
            if (buffered == buffer.size) discard(1)
            buffer[buffered++] = byte
            while (buffered >= 4) {
                val response = matches(RESPONSE_HEADER)
                val command = matches(COMMAND_HEADER)
                if (!response && !command) { discard(1); continue }
                if (buffered < 5) break
                val length = if (command) 20 else 300
                if (buffered < length) break
                if (sum(buffer, length - 1) != u8(length - 1)) {
                    lastError = "极空响应校验失败"
                    discard(1)
                    continue
                }
                val frame = buffer.copyOfRange(0, length)
                discard(length)
                if (command) {
                    if (frame[4] == 0xC8.toByte()) moduleReady = true
                    continue
                }
                when (frame[4].toInt() and 255) {
                    3 -> decodeInfo(frame, nowMs)?.let { out += it }
                    2 -> if (version != null) decodeLive(frame, nowMs)?.let { out += it }
                    1 -> if (version != null && looksLikeJk04Settings(frame)) {
                        jk04CellCount = frame[34].toInt() and 255
                    }
                }
            }
        }
        return out
    }

    private fun decodeInfo(data: ByteArray, nowMs: Long): BoardReading? {
        val model = text(data, 6, 16)
        val software = text(data, 30, 8)
        val major = software.removePrefix("V").removePrefix("v").substringBefore('.').toIntOrNull()
        if (major == null || major !in 1..99 || !model.startsWith("JK", ignoreCase = true)) {
            pendingAt = null
            lastError = "极空设备信息不兼容（$model / $software），请核对蓝牙设备"
            return null
        }
        version = major
        infoAt = nowMs
        pendingAt = null
        lastError = null
        identity = linkedMapOf(
            "protocol" to when (layout) {
                Layout.Jk04 -> "JK04"
                Layout.Jk02 -> if (major < 11) "JK02 24S" else "JK02 32S"
                Layout.Unknown -> "JK（等待实时报文确认布局）"
            },
            "model" to model,
            "hwVersion" to text(data, 22, 8),
            "swVersion" to software,
            "deviceName" to text(data, 46, 16),
            "serialNo" to text(data, 86, 11),
        ).filterValues { it.isNotBlank() }
        val labels = mapOf("protocol" to "协议", "model" to "设备型号", "hwVersion" to "硬件版本",
            "swVersion" to "软件版本", "deviceName" to "设备名称", "serialNo" to "序列号")
        settings = identity.map { (key, value) -> BoardSetting("jk.$key", labels.getValue(key), value) }
        return BoardReading(identity = identity, settings = settings)
    }

    private fun decodeLive(data: ByteArray, nowMs: Long): BoardReading? {
        val major = version ?: return null
        if (layout == Layout.Unknown) {
            val floatCells = List(24) { float32(data, 6 + it * 4) }
            val floatLayout = floatCells.count { it in 0.5..6.0 } >= 2 &&
                floatCells.all { it == 0.0 || it in 0.5..6.0 }
            val offset = if (major < 11) -32 else 0
            val enabled = u32(data, 70 + offset / 2)
            val integerLayout = enabled != 0L && (0 until (if (major < 11) 24 else 32))
                .filter { enabled and (1L shl it) != 0L }.all { u16(data, 6 + it * 2) in 500..6000 }
            layout = when {
                floatLayout && !integerLayout -> Layout.Jk04
                integerLayout && !floatLayout -> Layout.Jk02
                else -> {
                    lastError = "极空实时报文无法确认 JK02 / JK04 布局"
                    return null
                }
            }
            identity = identity + ("protocol" to if (layout == Layout.Jk04) "JK04" else if (major < 11) "JK02 24S" else "JK02 32S")
            settings = settings.filterNot { it.key == "jk.protocol" } + BoardSetting("jk.protocol", "协议", identity.getValue("protocol"))
        }
        if (layout == Layout.Jk04) return decodeJk04(data, nowMs)
        val offset = if (major < 11) -32 else 0
        val cellMask = u32(data, 70 + offset / 2)
        val slots = if (major < 11) 24 else 32
        val count = (0 until slots).lastOrNull { cellMask and (1L shl it) != 0L }?.plus(1)
        if (count == null) {
            lastError = "极空响应没有有效的电芯串数"
            return null
        }
        val voltage = u32(data, 150 + offset) / 1000.0
        val current = -i32(data, 158 + offset) / 1000.0
        val nominal = u32(data, 178 + offset) / 1000.0
        val remaining = u32(data, 174 + offset) / 1000.0
        if (voltage <= 0 || voltage > 200 || !current.isFinite()) {
            lastError = "极空实时数据不兼容，请核对 JK02 协议版本"
            return null
        }
        val mask = data[214 + offset].toInt() and 255
        val tempSlots = if (major < 11) listOf("T1" to 130, "T2" to 132, "MOS" to 134)
            else listOf("MOS" to 144, "T1" to 162, "T2" to 164, "MOS2" to 254) +
                if (major >= 14) listOf("T3" to 256, "T4" to 258) else emptyList()
        val temps = tempSlots.mapIndexedNotNull { index, (name, position) ->
            val raw = i16(data, position)
            if (mask and (1 shl index) == 0 || raw == -2000) null else name to raw / 10.0
        }
        val faults = u32(data, 166 + offset).let { if (offset != 0) it ushr 16 else it }
        val charging = data[198 + offset].toInt() != 0
        val discharging = data[199 + offset].toInt() != 0
        val balancing = data[172 + offset].toInt() != 0
        pendingAt = null
        lastLiveAt = nowMs
        lastError = null
        return BoardReading(
            totalVoltage = voltage, current = current, power = (voltage * current).roundToInt(),
            soc = (data[173 + offset].toInt() and 255).takeIf { it <= 100 },
            soh = (data[190 + offset].toInt() and 255).takeIf { it <= 100 },
            totalCapAh = nominal.takeIf { it > 0 }, remainCapAh = remaining,
            cycles = u32(data, 182 + offset).takeIf { it <= Int.MAX_VALUE }?.toInt(),
            battState = when { current > 0.05 -> "放电"; current < -0.05 -> "充电"; else -> "静置" },
            chMos = charging, disMos = discharging, balancing = balancing,
            temps = temps,
            cells = List(count) { index ->
                val raw = u16(data, 6 + index * 2)
                if (cellMask and (1L shl index) == 0L || raw !in 1..5000) null else raw / 1000.0
            },
            protectPairs = (0..31).mapNotNull { bit ->
                if (faults and (1L shl bit) == 0L || bit in listOf(4, 19)) null
                else bit to (PROTECTION_NAMES[bit] ?: "极空保护位 $bit")
            },
            alarmPairs = if (faults and (1L shl 19) != 0L) listOf(19 to "设备提示更新访问密码") else emptyList(),
            statePairs = buildList {
                if (charging) add(0 to "充电 MOS 导通")
                if (discharging) add(1 to "放电 MOS 导通")
                if (faults and (1L shl 4) != 0L) add(4 to "充满标志")
            },
            identity = identity,
            settings = settings,
        )
    }

    private fun decodeJk04(data: ByteArray, nowMs: Long): BoardReading? {
        val slots = List(24) { index ->
            val raw = float32(data, 6 + index * 4)
            raw.takeIf { it.isFinite() && it in 0.5..6.0 }
        }
        val count = jk04CellCount ?: slots.indexOfLast { it != null }.plus(1)
        if (count !in 1..24 || slots.take(count).count { it != null } < 2) {
            lastError = "极空 JK04 没有足够有效单体电压"
            return null
        }
        val cells = slots.take(count)
        if (cells.any { it == null }) {
            lastError = "极空 JK04 部分单体缺失，不能生成完整总压"
            return null
        }
        val balance = data[220].toInt() and 255
        if (balance !in 0..2) {
            lastError = "极空 JK04 均衡状态不兼容"
            return null
        }
        pendingAt = null
        lastLiveAt = nowMs
        lastError = null
        val readingSettings = settings + listOf(
            BoardSetting("jk.voltageSource", "总压来源", "有效单体电压之和", group = "读取说明"),
            BoardSetting("jk.readSupport", "设备数据范围", "JK04 均衡板未提供总电流、SOC、容量、MOS 或温度", group = "读取说明"),
        )
        return BoardReading(
            totalVoltage = cells.filterNotNull().sum(),
            cells = cells, balancing = balance != 0,
            battState = if (balance == 0) "均衡待机" else "均衡中",
            runtimeSec = u32(data, 286),
            identity = identity + mapOf("cellCount" to count.toString()),
            settings = readingSettings,
        )
    }

    private fun looksLikeJk04Settings(data: ByteArray): Boolean = float32(data, 6) == 1.0 &&
        (data[34].toInt() and 255) in 1..24 && data[35] == 0.toByte() && data[36] == 0.toByte() && data[37] == 0.toByte()

    private fun float32(data: ByteArray, index: Int): Double = Float.fromBits(i32(data, index)).toDouble()

    private fun matches(header: ByteArray): Boolean = header.indices.all { buffer[it] == header[it] }
    private fun discard(count: Int) { buffer.copyInto(buffer, 0, count, buffered); buffered -= count }
    private fun u8(index: Int) = buffer[index].toInt() and 255
    private fun u16(data: ByteArray, index: Int) = (data[index].toInt() and 255) or ((data[index + 1].toInt() and 255) shl 8)
    private fun i16(data: ByteArray, index: Int) = u16(data, index).toShort().toInt()
    private fun i32(data: ByteArray, index: Int) = u16(data, index) or (u16(data, index + 2) shl 16)
    private fun u32(data: ByteArray, index: Int) = i32(data, index).toLong() and 0xFFFFFFFFL
    private fun text(data: ByteArray, index: Int, size: Int) = data.copyOfRange(index, index + size)
        .takeWhile { it != 0.toByte() }.filter { it.toInt() in 32..126 }.toByteArray().decodeToString().trim()

    companion object {
        fun readFrame(command: Int): ByteArray {
            require(command == 0x96 || command == 0x97)
            return ByteArray(20).also {
                COMMAND_HEADER.copyInto(it)
                it[4] = command.toByte()
                it[19] = sum(it, 19).toByte()
            }
        }
        private fun sum(data: ByteArray, length: Int): Int = (0 until length).sumOf { data[it].toInt() and 255 } and 255
        private val RESPONSE_HEADER = byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte())
        private val COMMAND_HEADER = byteArrayOf(0xAA.toByte(), 0x55, 0x90.toByte(), 0xEB.toByte())
        private val PROTECTION_NAMES = mapOf(
            0 to "采样线阻异常", 1 to "MOS 过温保护", 2 to "串数与设置不符",
            5 to "总压过压保护", 6 to "充电过流保护", 7 to "充电短路保护",
            8 to "充电过温保护", 9 to "充电低温保护", 10 to "通信异常",
            11 to "单体欠压保护", 12 to "总压欠压保护", 13 to "放电过流保护", 14 to "放电短路保护",
            15 to "放电过温保护", 16 to "充电 MOS 异常", 17 to "放电 MOS 异常",
            18 to "GPS 连接异常", 20 to "放电开启失败", 21 to "电池过温保护", 22 to "温度采样异常",
            23 to "PL 模块异常", 24 to "短路保护解除失败", 25 to "二级放电过流保护", 26 to "三级放电过流保护",
            27 to "放电低温保护", 28 to "GPS 远程锁定",
        )
    }
}
