package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.protocol.Crc16
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 彦阳 YYBMS，只发 FC03 的地址发现、代际探测和已确认读取块。
 *
 * B 代偏移相对 payload、小端；旧 A 兼容布局单独解析，C 代不能仅凭非 B 签名确认。
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
 * HB/O2 的热敏换算缺少可靠样本，仅隐藏这些 SN 子型的温度，不影响其他 B 代读数。
 * 调用者应串行调用；换连接时必须 reset，连接代际由仓库/传输层隔离。
 */
class YanYangProtocol : ReadOnlyBoardProtocol {
    override val family = DeviceFamily.YanYang
    override val caps: DeviceCapabilities
        get() = if (layout == Layout.LegacyA) DeviceCapabilities.YanYang.copy(
            protectAlarm = false, mos = false, balance = false, extTimes = false,
            runtime = false, cycleCount = false, capacity = false, stateOfHealth = false,
        ) else DeviceCapabilities.YanYang

    private enum class Block(val register: Int, val count: Int) {
        Profile(63, 4), Summary(1, 79), Status(80, 80), Settings(160, 81), Identity(240, 20),
        Address(0, 1), LegacyMain(1, 47), LegacyExtra(48, 42), LegacyState(90, 22);

        val payloadSize: Int get() = count * 2

        fun request(slave: Int): ByteArray {
            val frame = byteArrayOf(
                slave.toByte(), 3, (register ushr 8).toByte(), register.toByte(),
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
    private enum class Layout { Unknown, LegacyA, B }
    private var layout = Layout.Unknown
    private var slaveAddress = 1
    private var addressRequested = false
    private var addressPending = false
    private var legacyExtraRequested = false
    private var legacyStateRequested = false
    private var legacyPollExtra = false
    private var legacyPollState = false
    private var legacyCells: List<Double?> = emptyList()
    private var profileAttempts = 0
    private var thermalSupported = true
    private var lastFragmentAt = 0L
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
        get() = !halted && profileReceived && summaryReceived &&
            (statusReceived || (layout == Layout.LegacyA && cellCount <= 18))

    override val error: String? get() = lastError
    override val modbusAddress: Int get() = slaveAddress
    override val protocolLayoutHint: String? get() = layout.takeIf { summaryReceived }?.name

    override fun configureLayout(layout: String) {
        val detected = Layout.values().firstOrNull { it != Layout.Unknown && it.name == layout } ?: return
        if (this.layout != Layout.Unknown || pending != null) return
        this.layout = detected
        profileReceived = true
        profileAttempts = 2
        if (detected == Layout.LegacyA) pollStatusNext = false
    }

    override fun configureAddress(address: Int) {
        require(address in 1..247)
        if (pending != null || requested.isNotEmpty()) return
        slaveAddress = address
    }

    override fun nextRequest(nowMs: Long): ByteArray? {
        if (halted) return null
        expire(nowMs)
        if (pending != null) return null
        val retry = retryBlock
        val initializing = !profileReceived || !summaryReceived ||
            (if (layout == Layout.LegacyA) !legacyExtraRequested || !legacyStateRequested
             else !statusReceived || !settingsRequested || !identityRequested)
        // 仓库每 80ms 调用一次；初始化/重试/手动刷新可紧接应答，稳定整轮至少 500ms。
        if (retry == null && !initializing && !settingsRefresh && !pollStatusNext &&
            !legacyPollExtra && !legacyPollState && nextPollAtMs?.let { nowMs < it } == true) return null
        retryBlock = null
        val block = retry ?: when {
            addressPending -> Block.Address
            layout == Layout.Unknown && profileAttempts < 2 -> Block.Profile
            layout == Layout.LegacyA -> when {
                !summaryReceived -> Block.LegacyMain
                !legacyExtraRequested -> Block.LegacyExtra
                !legacyStateRequested -> Block.LegacyState
                settingsRefresh -> Block.LegacyMain
                legacyPollExtra -> Block.LegacyExtra
                legacyPollState -> Block.LegacyState
                else -> Block.LegacyMain
            }
            !summaryReceived -> Block.Summary
            !statusReceived -> Block.Status
            !settingsRequested -> Block.Settings
            !identityRequested -> Block.Identity
            settingsRefresh -> Block.Settings
            pollStatusNext -> Block.Status
            else -> Block.Summary
        }
        when (block) {
            Block.Profile -> profileAttempts++
            Block.Settings -> { settingsRequested = true; settingsRefresh = false }
            Block.Identity -> identityRequested = true
            Block.Summary -> {
                pollStatusNext = true
                nextPollAtMs = nowMs + POLL_CYCLE_INTERVAL_MS
            }
            Block.Status -> pollStatusNext = false
            Block.Address -> { addressPending = false; addressRequested = true }
            Block.LegacyMain -> {
                settingsRefresh = false
                legacyPollExtra = true
                legacyPollState = false
                nextPollAtMs = nowMs + POLL_CYCLE_INTERVAL_MS
            }
            Block.LegacyExtra -> {
                legacyExtraRequested = true
                legacyPollExtra = false
                legacyPollState = true
            }
            Block.LegacyState -> { legacyStateRequested = true; legacyPollState = false }
        }
        // 只清未完成碎片，不让上一请求的截断 payload 接到本次响应前面。
        buffered = 0
        requested.add(block)
        pending = Pending(block, nowMs, retry != null)
        return block.request(if (block == Block.Address) 0 else slaveAddress)
    }

    override fun refreshSettings() {
        settingsRefresh = true
    }

    override fun reset() {
        pending = null
        retryBlock = null
        requested.clear()
        profileReceived = false
        layout = Layout.Unknown
        slaveAddress = 1
        addressRequested = false
        addressPending = false
        legacyExtraRequested = false
        legacyStateRequested = false
        legacyPollExtra = false
        legacyPollState = false
        legacyCells = emptyList()
        profileAttempts = 0
        thermalSupported = true
        lastFragmentAt = 0
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
        if (nowMs - active.sentAtMs < RESPONSE_TIMEOUT_MS ||
            (buffered > 0 && nowMs - lastFragmentAt < RESPONSE_TIMEOUT_MS && nowMs - active.sentAtMs < 10_000)) return
        pending = null
        buffered = 0
        lastError = "彦阳读取寄存器 ${active.block.register} 超时"
        if (advanceLayoutProbe(active.block)) return
        // 签名小块不可用时改读基础块；B 代仍须校验基础块魔数。
        retryBlock = if (active.isRetry || (active.block == Block.Profile && profileAttempts >= 2)) null else active.block
    }

    private fun advanceLayoutProbe(block: Block): Boolean {
        if (layout != Layout.Unknown || (block != Block.Summary && block != Block.Address)) return false
        if (block == Block.Summary && !addressRequested) {
            addressPending = true
        } else {
            // 地址发现只尝试一次；拿到从机地址后，即使代际探测无回应也保留该地址。
            layout = Layout.LegacyA
            pollStatusNext = false
        }
        retryBlock = null
        return true
    }

    override fun onNotification(bytes: ByteArray, nowMs: Long): List<BoardReading> {
        if (halted) return emptyList()
        expire(nowMs)
        if (bytes.isNotEmpty()) lastFragmentAt = nowMs
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
            if (u8(buffer, 0) !in 1..247) { discard(1); continue }
            if (pending?.block != Block.Address && u8(buffer, 0) != slaveAddress) { discard(1); continue }
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
                if (retryBlock == null) advanceLayoutProbe(active.block)
                continue
            }
            val decodedBlock = block!!
            if (decodedBlock !in requested) continue
            if (decodedBlock == Block.Profile && layout != Layout.Unknown && pending?.block != Block.Profile) continue
            if (decodedBlock == Block.Address && pending?.block != Block.Address) continue
            if (decodedBlock in listOf(Block.LegacyMain, Block.LegacyExtra, Block.LegacyState)) {
                if (layout != Layout.LegacyA) continue
            } else if (layout == Layout.LegacyA && decodedBlock != Block.Profile && decodedBlock != Block.Address) continue
            if (decodedBlock !in listOf(Block.Profile, Block.Summary, Block.Address, Block.LegacyMain) && !profileReceived) continue
            if (decodedBlock !in listOf(Block.Profile, Block.Summary, Block.Address, Block.LegacyMain) && !summaryReceived) continue
            val reading = if (decodedBlock == Block.Address) {
                slaveAddress = u8(frame, 0)
                layout = Layout.Unknown
                profileAttempts = 0
                profileReceived = false
                summaryReceived = false
                statusReceived = false
                pollStatusNext = false
                nextPollAtMs = null
                requested.clear()
                BoardReading(identity = mapOf("modbusAddress" to slaveAddress.toString()))
            } else decode(decodedBlock, frame.copyOfRange(3, length - 2)) ?: continue
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
            if (u8(buffer, offset) !in 1..247 ||
                (pending?.block != Block.Address && u8(buffer, offset) != slaveAddress)) continue
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
            if (u32(data, 0) == MAGIC) {
                layout = Layout.B
                profileReceived = true
                BoardReading(identity = mapOf("protocol" to "YYBMS B"))
            } else {
                // 非 B 签名只用于选择下一次读取，不能独自证明是旧 A。
                layout = Layout.LegacyA
                pollStatusNext = false
                BoardReading()
            }
        }
        Block.Summary -> decodeSummary(data)
        Block.Status -> decodeStatus(data)
        Block.Settings -> decodeSettings(data)
        Block.LegacyMain -> decodeLegacyMain(data)
        Block.LegacyExtra -> decodeLegacyExtra(data)
        Block.LegacyState -> BoardReading(settings = updateSettings(Block.LegacyState,
            listOf(BoardSetting("yy.a.stateRaw", "旧代状态字（原始）", wordSwapped32(data, 40).toString(16).uppercase(), group = "原始状态"))))
        Block.Address -> null
        Block.Identity -> {
            identityReceived = true
            // 来源的 @20 文本与低压/延时字段重叠，含义未确认，不当作厂商 SN 展示。
            BoardReading()
        }
    }

    /** 旧 A 布局只解析有独立参考的基础量；不把 A2 的冲突电流/SOC 覆盖回 A1。 */
    private fun decodeLegacyMain(data: ByteArray): BoardReading? {
        val count = u8(data, 12)
        val sensors = u8(data, 13)
        val packVoltage = wordSwapped32(data, 22) / 1000.0
        if (count !in 1..25 || sensors !in 0..4 || packVoltage !in 1.0..200.0) {
            lastError = "彦阳旧代基础块不符合 A 布局，请核对型号；不会套用 B 代或猜测 C 代字段"
            return null
        }
        val mainCells = List(minOf(count, 18)) { be16(data, 44 + it * 2) }
        val mainVoltage = mainCells.sum() / 1000.0
        val extraCount = count - mainCells.size
        // A1 只返回前 18 串；为未到的单体与采样误差留余量。
        val tolerance = maxOf(1.0, packVoltage * 0.10)
        if (mainCells.any { it !in 500..6000 } ||
            packVoltage < mainVoltage + extraCount * 0.5 - tolerance ||
            packVoltage > mainVoltage + extraCount * 6.0 + tolerance) {
            lastError = "彦阳旧代基础块的总压与单体电压不一致，未确认 A 布局"
            return null
        }
        val previous = legacyCells
        legacyCells = List(count) { index -> mainCells.getOrNull(index)?.div(1000.0) ?: previous.getOrNull(index) }
        cellCount = count
        voltage = packVoltage
        rawCurrent = be16(data, 26).toShort().toInt() / 100.0
        summaryReceived = true
        profileReceived = true
        val serial = data.copyOfRange(0, 12).toList().chunked(2)
            .flatMap { it.reversed() }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0').uppercase() }
        thermalSupported = serial.take(4) !in UNSUPPORTED_THERMAL_PREFIXES
        val positions = listOf("MOS" to 81, "T1" to 80, "T2" to 83, "探头 4" to 82)
        val temperatures = if (!thermalSupported) emptyList() else positions.take(sensors).mapNotNull { (label, position) ->
            val raw = u8(data, position)
            raw.takeIf { it in 1..127 }?.let { label to (it - 40).toDouble() }
        }
        val modelSettings = listOf(
            BoardSetting("yy.protocol", "协议", "YYBMS 旧 A 兼容布局"),
            BoardSetting("yy.legacySupport", "兼容验证", "按公开资料适配旧 A 布局，仍待实物验证；未确认 C 代"),
            BoardSetting("yy.modbusAddress", "Modbus 地址", slaveAddress.toString()),
            BoardSetting("yy.cellCount", "电芯串数", count.toString()),
            BoardSetting("yy.a.socRaw", "SOC 原始值", be16(data, 38).toString(), group = "旧代原始字段"),
            BoardSetting("yy.a.capacityRaw1", "容量字段 1（原始）", be16(data, 40).toString(), group = "旧代原始字段"),
            BoardSetting("yy.a.capacityRaw2", "容量字段 2（原始）", be16(data, 42).toString(), group = "旧代原始字段"),
            BoardSetting("yy.a.currentRaw", "电流（来源符号）", rawCurrent.toString(), "A", "旧代原始字段"),
            BoardSetting("yy.a.currentDirection", "电流方向", "方向未获实录确认，不生成充放电状态或功率", group = "兼容说明"),
        ) + if (!thermalSupported) listOf(BoardSetting("yy.thermalSupport", "温度读数", "此子型号的温度布局尚未确认，已隐藏")) else emptyList()
        return BoardReading(
            totalVoltage = voltage, battState = "方向待确认",
            cells = legacyCells, temps = temperatures,
            identity = mapOf("protocol" to "YYBMS 旧 A 兼容布局", "cellCount" to count.toString(),
                "temperatureCount" to sensors.toString(), "modbusAddress" to slaveAddress.toString()),
            settings = updateSettings(Block.LegacyMain, modelSettings),
        )
    }

    private fun decodeLegacyExtra(data: ByteArray): BoardReading {
        // A2 第 19..25 串的端序按参考 APK 的 BE16，不能照搬另一份冲突文档的 LE16。
        legacyCells = List(cellCount) { index ->
            if (index < 18) legacyCells.getOrNull(index)
            else be16(data, 40 + (index - 18) * 2).takeIf { it in 500..6000 }?.div(1000.0)
        }
        statusReceived = true
        return BoardReading(
            cells = legacyCells,
            settings = updateSettings(Block.LegacyExtra, listOf(
                BoardSetting("yy.a.alarmRaw", "告警字（原始）", be16(data, 36).toString(16).uppercase(), group = "旧代原始字段"),
                BoardSetting("yy.a.contactRaw", "接点状态（原始）", u8(data, 38).toString(16).uppercase(), group = "旧代原始字段"),
            )),
        )
    }

    private fun be16(data: ByteArray, offset: Int): Int = (u8(data, offset) shl 8) or u8(data, offset + 1)
    private fun wordSwapped32(data: ByteArray, offset: Int): Long =
        be16(data, offset).toLong() or (be16(data, offset + 2).toLong() shl 16)

    private fun decodeSummary(data: ByteArray): BoardReading? {
        if (u32(data, 124) != MAGIC) return incompatible("彦阳基础块魔数不匹配，停止按 YYBMS B 解析")
        val serial = data.copyOfRange(2, 10).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
        profileReceived = true
        layout = Layout.B
        thermalSupported = serial.take(4) !in UNSUPPORTED_THERMAL_PREFIXES
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
        } + if (!thermalSupported) listOf(BoardSetting("yy.thermalSupport", "温度读数", "此子型号的温度布局尚未确认，已隐藏")) else emptyList()
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
            if (thermalSupported && mosSensorCount > 0) addSensor("MOS", 64)
            if (thermalSupported && balanceSensorCount > 0) addSensor("均衡", 65)
            if (thermalSupported) repeat(batterySensorCount) { addSensor("T${it + 1}", 66 + it) }
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
            if (thermalSupported) thermalNames.forEachIndexed { index, name ->
                setting("thermal$index", name, (u8(data, 80 + index) - 40).toString(), "℃", "温度保护")
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
        const val RESPONSE_TIMEOUT_MS = 3_000L
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
