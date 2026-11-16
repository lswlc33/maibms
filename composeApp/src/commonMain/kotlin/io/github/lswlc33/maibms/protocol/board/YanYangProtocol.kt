package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.protocol.Crc16
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 彦阳 YYBMS B，只发 slave 1 / FC03 的五种白名单读取。
 *
 * 偏移均相对响应 payload，小端；不能套用旧 A/C 代寄存器图。
 * 核对来源（固定版本，下载代码未执行）：
 * - xiaodl-top/NiuHelper@6ebda6869211ac94a45d4ca837a294669a8319b0：
 *   docs/yybms-bms-ble-protocol.md §7、BmsDecoder.kt、BmsDecoderTest.kt，含 NIU-M 实录。
 * - guilhem/yanyang-bms-web@b5e39c5eae1ab738bb8976743905fbbc20f7d4c4：
 *   web/protocol.js、web/ble.js、docs/protocol.md、tests/fixtures/read-requests.json。
 *   NIU-N 的测试周期明确标记 synthetic，不当作硬件实录。
 * - gunnicom/NIUyy@eac15734d58a5b7198bb10008436b14adeae2c4a/readvalues.py：
 *   i32 电流 /100、放电正充电负；与 M 型实录一致。
 *
 * N 型合成夹具的电流符号和运行状态相反，因此有明确充放状态时统一按状态定向。
 * HB/O2 的热敏换算未得到可靠样本验证，按来源拒绝这些 SN 子型，不猜测温度。
 * 调用者应串行调用；换连接时必须 reset，连接代际由仓库/传输层隔离。
 */
class YanYangProtocol : ReadOnlyBoardProtocol {
    override val family = DeviceFamily.YanYang
    override val caps = DeviceCapabilities.YanYang

    private enum class Block(val register: Int, val count: Int) {
        Profile(63, 4), Summary(1, 79), Status(80, 80), Settings(160, 81), Identity(240, 20);

        val payloadSize: Int get() = count * 2

        fun request(): ByteArray {
            val frame = byteArrayOf(
                1, 3, (register ushr 8).toByte(), register.toByte(),
                (count ushr 8).toByte(), count.toByte(), 0, 0,
            )
            val crc = Crc16.modbus(frame, 0, 6)
            frame[6] = crc.toByte()
            frame[7] = (crc ushr 8).toByte()
            return frame
        }
    }

    private data class Pending(val block: Block, val sentAtMs: Long, val isRetry: Boolean)

    private var pending: Pending? = null
    private var retryBlock: Block? = null
    private val requested = mutableSetOf<Block>()
    private var profileReceived = false
    private var summaryReceived = false
    private var statusReceived = false
    private var settingsReceived = false
    private var identityReceived = false
    private var settingsRequested = false
    private var identityRequested = false
    private var settingsRefresh = false
    private var pollStatusNext = false
    private var nextPollAtMs: Long? = null
    private var halted = false
    private var lastError: String? = null

    // 固定上限：最大帧 = 162 字节 payload + 5。逐字节排出帧，粘包不会无限积累。
    private val buffer = ByteArray(167)
    private var buffered = 0
    private var cellCount = 0
    private var batterySensorCount = 0
    private var mosSensorCount = 0
    private var balanceSensorCount = 0
    private var voltage: Double? = null
    private var rawCurrent: Double? = null
    private var runState: Int? = null
    private val settingBlocks = mutableMapOf<Block, List<BoardSetting>>()

    override val snapshotReady: Boolean
        get() = !halted && profileReceived && summaryReceived && statusReceived && settingsReceived && identityReceived

    override val error: String? get() = lastError

    override fun nextRequest(nowMs: Long): ByteArray? {
        if (halted) return null
        expire(nowMs)
        if (pending != null) return null
        val retry = retryBlock
        val initializing = !profileReceived || !summaryReceived || !statusReceived || !settingsRequested || !identityRequested
        // 仓库每 80ms 调用一次；初始化/重试/手动刷新可紧接应答，稳定整轮至少 500ms。
        if (retry == null && !initializing && !settingsRefresh && !pollStatusNext &&
            nextPollAtMs?.let { nowMs < it } == true) return null
        retryBlock = null
        val block = retry ?: when {
            !profileReceived -> Block.Profile
            !summaryReceived -> Block.Summary
            !statusReceived -> Block.Status
            !settingsRequested -> Block.Settings
            !identityRequested -> Block.Identity
            settingsRefresh -> Block.Settings
            pollStatusNext -> Block.Status
            else -> Block.Summary
        }
        when (block) {
            Block.Settings -> { settingsRequested = true; settingsRefresh = false }
            Block.Identity -> identityRequested = true
            Block.Summary -> {
                pollStatusNext = true
                nextPollAtMs = nowMs + POLL_CYCLE_INTERVAL_MS
            }
            Block.Status -> pollStatusNext = false
            Block.Profile -> Unit
        }
        // 只清未完成碎片，不让上一请求的截断 payload 接到本次响应前面。
        buffered = 0
        requested.add(block)
        pending = Pending(block, nowMs, retry != null)
        return block.request()
    }

    override fun refreshSettings() {
        settingsRefresh = true
    }

    override fun reset() {
        pending = null
        retryBlock = null
        requested.clear()
        profileReceived = false
        summaryReceived = false
        statusReceived = false
        settingsReceived = false
        identityReceived = false
        settingsRequested = false
        identityRequested = false
        settingsRefresh = false
        pollStatusNext = false
        nextPollAtMs = null
        halted = false
        lastError = null
        buffered = 0
        cellCount = 0
        batterySensorCount = 0
        mosSensorCount = 0
        balanceSensorCount = 0
        voltage = null
        rawCurrent = null
        runState = null
        settingBlocks.clear()
    }

    private fun expire(nowMs: Long) {
        val active = pending ?: return
        if (nowMs - active.sentAtMs < RESPONSE_TIMEOUT_MS) return
        pending = null
        buffered = 0
        lastError = "彦阳读取寄存器 ${active.block.register} 超时（1.2 秒）"
        // 可选块最多立即重试一次，丢包不应永远卡住实时轮询；缺少必需块仍会补读。
        retryBlock = if (active.isRetry) null else active.block
    }

    override fun onNotification(bytes: ByteArray, nowMs: Long): List<BoardReading> {
        if (halted) return emptyList()
        expire(nowMs)
        val readings = mutableListOf<BoardReading>()
        for (byte in bytes) {
            if (halted) break
            buffer[buffered++] = byte
            drain(readings)
        }
        return readings
    }

    private fun drain(readings: MutableList<BoardReading>) {
        while (buffered > 0 && !halted) {
            if (u8(buffer, 0) != 1) { discard(1); continue }
            if (buffered < 2) return
            val function = u8(buffer, 1)
            if (function != 3 && function != 0x83) { discard(1); continue }
            if (buffered < 3) return
            val block = if (function == 3) BLOCK_BY_PAYLOAD[u8(buffer, 2)] else null
            if (function == 3 && block == null) { discard(1); continue }
            val length = if (function == 0x83) 5 else block!!.payloadSize + 5
            if (buffered < length) {
                // 噪声/截断旧包可能伪装成长帧头；后方完整且 CRC 正确的帧可以重新同步。
                val offset = nextCompleteFrameOffset()
                if (offset < 0) return
                discard(offset)
                continue
            }
            if (Crc16.modbus(buffer, 0, length - 2) != u16(buffer, length - 2)) {
                lastError = "彦阳响应 CRC 校验失败"
                discard(1)
                continue
            }
            val frame = buffer.copyOfRange(0, length)
            discard(length)
            if (function == 0x83) {
                val active = pending ?: continue
                pending = null
                val code = u8(frame, 2)
                lastError = "彦阳读取寄存器 ${active.block.register} 异常 0x${code.toString(16).padStart(2, '0').uppercase()}"
                // 异常应答没有寄存器地址，只归属当前 pending；忙碌可以稍后重读。
                retryBlock = if (code == 6 && !active.isRetry) active.block else null
                continue
            }
            val decodedBlock = block!!
            if (decodedBlock !in requested) continue
            if (decodedBlock != Block.Profile && !profileReceived) continue
            if (decodedBlock != Block.Profile && decodedBlock != Block.Summary && !summaryReceived) continue
            val reading = decode(decodedBlock, frame.copyOfRange(3, length - 2)) ?: continue
            // Modbus 无事务号。完整迟到帧可更新同设备快照，不能解除其他长度的 pending。
            // 同块重试时旧完整帧与新帧不可区分，均视为该块的最近有效只读结果。
            if (pending?.block == decodedBlock) pending = null
            if (retryBlock == decodedBlock) retryBlock = null
            lastError = null
            readings.add(reading)
        }
    }

    private fun nextCompleteFrameOffset(): Int {
        for (offset in 1 until buffered - 4) {
            if (u8(buffer, offset) != 1) continue
            val function = u8(buffer, offset + 1)
            val length = when (function) {
                3 -> BLOCK_BY_PAYLOAD[u8(buffer, offset + 2)]?.let { it.payloadSize + 5 } ?: continue
                0x83 -> 5
                else -> continue
            }
            if (offset + length <= buffered &&
                Crc16.modbus(buffer, offset, offset + length - 2) == u16(buffer, offset + length - 2)) return offset
        }
        return -1
    }

    private fun discard(count: Int) {
        buffer.copyInto(buffer, 0, count, buffered)
        buffered -= count
    }

    private fun incompatible(message: String): BoardReading? {
        halted = true
        pending = null
        retryBlock = null
        buffered = 0
        lastError = message
        return null
    }

    private fun decode(block: Block, data: ByteArray): BoardReading? = when (block) {
        Block.Profile -> {
            if (u32(data, 0) != MAGIC) incompatible("彦阳协议不兼容：需要 YYBMS B 魔数 0x4D444253，旧 A/C 代不支持")
            else {
                profileReceived = true
                BoardReading(identity = mapOf("protocol" to "YYBMS B"))
            }
        }
        Block.Summary -> decodeSummary(data)
        Block.Status -> decodeStatus(data)
        Block.Settings -> decodeSettings(data)
        Block.Identity -> {
            identityReceived = true
            // 来源的 @20 文本与低压/延时字段重叠，含义未确认，不当作厂商 SN 展示。
            BoardReading()
        }
    }

    private fun decodeSummary(data: ByteArray): BoardReading? {
        if (u32(data, 124) != MAGIC) return incompatible("彦阳基础块魔数不匹配，停止按 YYBMS B 解析")
        val serial = data.copyOfRange(2, 10).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
        if (serial.take(4) in UNSUPPORTED_THERMAL_PREFIXES) {
            return incompatible("彦阳 HB/O2 子型号 ${serial.take(4)} 的温度协议尚不支持")
        }
        val cells = u8(data, 148)
        val batterySensors = u8(data, 103)
        val mosSensors = u8(data, 104)
        val balanceSensors = u8(data, 105)
        if (cells !in 1..25 || batterySensors > 8 || mosSensors > 1 || balanceSensors > 1) {
            return incompatible("彦阳 YYBMS B 串数或温度探头数不兼容")
        }
        cellCount = cells
        batterySensorCount = batterySensors
        mosSensorCount = mosSensors
        balanceSensorCount = balanceSensors
        voltage = i32(data, 150) / 1000.0
        rawCurrent = i32(data, 154) / 100.0
        summaryReceived = true
        val identity = linkedMapOf(
            "protocol" to "YYBMS B",
            "model" to text(data, 12, 26),
            "productName" to text(data, 38, 36),
            "serialNo" to serial,
            "deviceName" to text(data, 80, 12),
            "hwVersion" to version(u16(data, 74)),
            "swVersion" to version(u8(data, 123)),
            "boot" to version(u8(data, 122)),
            "batteryType" to when (val code = u8(data, 149)) {
                0 -> "三元锂"; 1 -> "磷酸铁锂"; 2 -> "钛酸锂"; 3 -> "钠离子"; else -> "未知（$code）"
            },
            "cellCount" to cells.toString(),
            "temperatureCount" to (batterySensors + mosSensors + balanceSensors).toString(),
        )
        val names = listOf("协议", "硬件型号", "产品型号", "序列号", "设备名称", "硬件版本", "软件版本", "Boot 版本", "电芯类型", "电芯串数", "温度探头数")
        val settings = identity.entries.mapIndexedNotNull { index, entry ->
            entry.value.takeIf { it.isNotEmpty() }?.let { BoardSetting("yy.${entry.key}", names[index], it) }
        }
        return BoardReading(
            totalVoltage = voltage,
            current = normalizedCurrent(),
            power = calculatedPower(),
            runtimeSec = u32(data, 140),
            identity = identity,
            settings = updateSettings(Block.Summary, settings),
        )
    }

    private fun normalizedCurrent(): Double? = rawCurrent?.let { raw ->
        when (runState) { 1 -> -abs(raw); 2 -> abs(raw); else -> raw }
    }

    private fun calculatedPower(): Int? = voltage?.let { v -> normalizedCurrent()?.let { (v * it).roundToInt() } }

    private fun decodeStatus(data: ByteArray): BoardReading {
        val control = u32(data, 144)
        runState = ((control ushr 26) and 3).toInt()
        val faults = u32(data, 152)
        val balance = u32(data, 118) and ((1L shl cellCount) - 1)
        val cells = List(cellCount) { index ->
            val raw = u16(data, 2 + index * 2)
            if (raw in INVALID_CELLS || raw > 5000) null else raw / 1000.0
        }
        val temperatures = buildList {
            fun addSensor(name: String, offset: Int) {
                val raw = u8(data, offset)
                if (raw != 0 && raw != 166) add(name to (raw - 40).toDouble())
            }
            if (mosSensorCount > 0) addSensor("MOS", 64)
            if (balanceSensorCount > 0) addSensor("均衡", 65)
            repeat(batterySensorCount) { addSensor("T${it + 1}", 66 + it) }
        }
        // 来源只提供一张状态/故障字，没有独立的预告警寄存器：保护位保留原位号，
        // 电量低/未知位列告警，充满/放空单列运行状态，不能套用蚂蚁位表。
        val protection = PROTECTION_NAMES.filter { (bit, _) -> faults and (1L shl bit) != 0L }
        val alarms = buildList {
            if (faults and (1L shl 14) != 0L) add(14 to "电量过低告警")
            for (bit in 19..24) if (faults and (1L shl bit) != 0L) add(bit to "未知彦阳告警位 $bit")
        }
        val states = buildList {
            // 0/1/2 来自 runState1，15/16 来自告警字，35 = runState2 bit3（锁）。
            if (control and 1L != 0L) add(0 to "放电回路吸合")
            if (control and 2L != 0L) add(1 to "充电回路吸合")
            if (control and 4L != 0L) add(2 to "预充回路吸合")
            if (faults and (1L shl 15) != 0L) add(15 to "充满标志")
            if (faults and (1L shl 16) != 0L) add(16 to "放空标志")
            if (u32(data, 148) and 8L != 0L) add(35 to "设备已锁定")
        }
        statusReceived = true
        return BoardReading(
            current = normalizedCurrent(),
            power = calculatedPower(),
            soc = u8(data, 80).takeIf { it <= 100 },
            soh = u8(data, 81).takeIf { it <= 100 },
            totalCapAh = u16(data, 76) / 10.0,
            remainCapAh = u16(data, 78) / 10.0,
            cycles = u8(data, 95),
            remainChargeMin = u16(data, 86),
            remainDischargeMin = u16(data, 88),
            battState = when (runState) { 0 -> "静置"; 1 -> "充电"; 2 -> "放电"; else -> "未知" },
            chMos = control and 2L != 0L,
            disMos = control and 1L != 0L,
            balancing = balance != 0L,
            balanceBits = balance,
            temps = temperatures,
            cells = cells,
            protectPairs = protection,
            alarmPairs = alarms,
            statePairs = states,
            settings = updateSettings(Block.Status, listOf(
                BoardSetting("yy.balanceVoltage", "均衡启动电压", i16(data, 122).toString(), "mV", "均衡参数"),
                BoardSetting("yy.balanceDifference", "均衡启动压差", i16(data, 124).toString(), "mV", "均衡参数"),
            )),
        )
    }

    private fun decodeSettings(data: ByteArray): BoardReading {
        val settings = buildList {
            fun setting(key: String, name: String, value: String, unit: String, group: String) {
                add(BoardSetting("yy.$key", name, value, unit, group))
            }
            listOf(
                Triple(18, "cellOvervoltage", "单体过压保护"), Triple(20, "cellOvervoltageRecovery", "单体过压恢复"),
                Triple(24, "cellUndervoltage", "单体欠压保护"), Triple(26, "cellUndervoltageRecovery", "单体欠压恢复"),
            ).forEach { (offset, key, name) -> setting(key, name, i16(data, offset).toString(), "mV", "单体保护") }
            listOf(
                Triple(36, "packOvervoltage", "总压过压保护"), Triple(38, "packOvervoltageRecovery", "总压过压恢复"),
                Triple(42, "packUndervoltage", "总压欠压保护"), Triple(44, "packUndervoltageRecovery", "总压欠压恢复"),
            ).forEach { (offset, key, name) -> setting(key, name, (u16(data, offset) / 100.0).toString(), "V", "总压保护") }
            setting("chargeOvercurrent", "充电过流保护", (u16(data, 62) / 100.0).toString(), "A", "电流保护")
            setting("dischargeOvercurrent", "放电过流保护", (u16(data, 68) / 100.0).toString(), "A", "电流保护")
            val thermalNames = listOf("充电过温保护", "充电过温恢复", "充电低温保护", "充电低温恢复", "放电过温保护", "放电过温恢复", "放电低温保护", "放电低温恢复")
            thermalNames.forEachIndexed { index, name ->
                setting("thermal$index", name, (data[80 + index].toInt() - 40).toString(), "℃", "温度保护")
            }
        }
        settingsReceived = true
        val packSerial = text(data, 94, 8)
        return BoardReading(
            identity = if (packSerial.isEmpty()) emptyMap() else mapOf("packSerial" to packSerial),
            settings = updateSettings(Block.Settings, settings),
        )
    }

    private fun updateSettings(block: Block, settings: List<BoardSetting>): List<BoardSetting>? {
        if (settingBlocks[block] == settings) return null
        settingBlocks[block] = settings
        // BoardReading.settings 供仓库整体替换，保留之前读到的其他块；未变化不重复推送。
        return Block.values().flatMap { settingBlocks[it].orEmpty() }
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF
    private fun u16(data: ByteArray, offset: Int): Int = u8(data, offset) or (u8(data, offset + 1) shl 8)
    private fun i16(data: ByteArray, offset: Int): Int = u16(data, offset).toShort().toInt()
    private fun i32(data: ByteArray, offset: Int): Int = u16(data, offset) or (u16(data, offset + 2) shl 16)
    private fun u32(data: ByteArray, offset: Int): Long = i32(data, offset).toLong() and 0xFFFFFFFFL
    private fun version(raw: Int): String = "${raw / 100}.${(raw % 100).toString().padStart(2, '0')}"
    private fun text(data: ByteArray, offset: Int, length: Int): String = data.copyOfRange(offset, offset + length)
        .takeWhile { it != 0.toByte() }.toByteArray().decodeToString().filter { it >= ' ' && it != '\u007F' }.trim()

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 1_200L
        const val POLL_CYCLE_INTERVAL_MS = 500L
        const val MAGIC = 0x4D444253L
        val BLOCK_BY_PAYLOAD = Block.values().associateBy { it.payloadSize }
        val UNSUPPORTED_THERMAL_PREFIXES = setOf("0001", "5948", "0008", "0009", "000A", "010A")
        val INVALID_CELLS = setOf(0, 20, 35, 36)
        val PROTECTION_NAMES = listOf(
            0 to "单体过压保护", 1 to "单体欠压保护", 2 to "欠压关断（储运模式）",
            3 to "总压过压保护", 4 to "总压欠压保护", 5 to "充电过流保护",
            6 to "放电过流1保护", 7 to "放电过流2（硬件过流）", 8 to "短路保护",
            9 to "充电过温保护", 10 to "充电低温保护", 11 to "放电过温保护", 12 to "放电低温保护",
            13 to "MOS 过温保护", 17 to "硬件检测单体过压", 18 to "硬件检测单体欠压",
            25 to "硬件检测失效", 26 to "读写失效（EEPROM）", 27 to "电压检测失效",
            28 to "温度检测失效", 29 to "电流检测失效", 30 to "放电输出失效", 31 to "充电输出失效",
        )
    }
}
