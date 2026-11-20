package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily

/**
 * 明唐 BLE 只读实时页。报文资料来自 esp32BMSGPS 的明唐协议说明，尾字节含义与校验算法未确认。
 * tag 10 固定按参考解码结果的 0.01 V/计数处理，与头文件写的 0.001 V 有冲突，尚未经真机验证。
 */
class MingtangProtocol : ReadOnlyBoardProtocol {
    override val family = DeviceFamily.Mingtang
    override val caps = DeviceCapabilities.Mingtang

    private val buffer = ByteArray(MAX_BUFFER)
    private var buffered = 0
    private var pendingAt: Long? = null
    private var lastLiveAt: Long? = null
    private var lastError: String? = null

    override val snapshotReady: Boolean get() = lastLiveAt != null
    override val error: String? get() = lastError

    override fun nextRequest(nowMs: Long): ByteArray? {
        expire(nowMs)
        if (pendingAt != null || lastLiveAt?.let { nowMs - it < MIN_LIVE_GAP_MS } == true) return null
        pendingAt = nowMs
        return readFrame()
    }

    override fun refreshSettings() {
        // 参数随下一次实时读取更新，沿用同一轮询和节流。
    }

    override fun reset() {
        buffered = 0
        pendingAt = null
        lastLiveAt = null
        lastError = null
    }

    override fun onNotification(bytes: ByteArray, nowMs: Long): List<BoardReading> {
        expire(nowMs)
        val readings = mutableListOf<BoardReading>()
        var offset = 0
        while (offset < bytes.size) {
            // 按缓冲余量分段取数据，通知大小可以超过单帧上限。
            val count = minOf(bytes.size - offset, buffer.size - buffered)
            bytes.copyInto(buffer, buffered, offset, offset + count)
            buffered += count
            offset += count
            drain(readings, nowMs)
        }
        return readings
    }

    private fun expire(nowMs: Long) {
        val sentAt = pendingAt ?: return
        if (nowMs - sentAt < RESPONSE_TIMEOUT_MS) return
        pendingAt = null
        buffered = 0
        lastError = "明唐读取超时，未收到有效实时数据"
    }

    private fun drain(readings: MutableList<BoardReading>, nowMs: Long) {
        var start = 0
        while (start < buffered) {
            val kind = u8(start)
            if (kind != LIVE && kind != 0xE2 && kind !in 0xF1..0xF3) {
                start++
                continue
            }
            val available = buffered - start
            if ((available >= 2 && u8(start + 1) != 0x11) ||
                (available >= 3 && u8(start + 2) != 0x00)) {
                start++
                continue
            }
            if (available < HEADER_SIZE) break
            val size = u16(start + 4) + 1
            if (size !in (HEADER_SIZE + 1)..buffer.size) {
                lastError = "明唐响应长度异常：$size"
                start++
                continue
            }

            val firstTlv = start + HEADER_SIZE
            val tail = start + size - 1
            if (kind == LIVE && !tlvsCanFit(firstTlv, tail)) {
                lastError = "明唐实时页 TLV 越过帧边界"
                start++
                continue
            }
            if (available < size) break
            if (kind == LIVE) {
                val reading = decodeLive(firstTlv, tail)
                if (reading == null) {
                    lastError = "明唐实时页字段缺失或无效"
                } else {
                    pendingAt = null
                    lastLiveAt = nowMs
                    lastError = null
                    readings += reading
                }
            }
            // 其他页只按长度跳过，尾字节以及 E2 之后的 [END] 均无须解码。
            start += size
        }
        if (start != 0) {
            buffer.copyInto(buffer, 0, start, buffered)
            buffered -= start
        }
    }

    /** 在等分片时也能丢弃已可判定越界的 TLV，尾字节始终在 TLV 区间之外。 */
    private fun tlvsCanFit(firstTlv: Int, tail: Int): Boolean {
        var offset = firstTlv
        while (offset < tail) {
            if (tail - offset < TLV_HEADER_SIZE) return false
            if (buffered - offset < TLV_HEADER_SIZE) return true
            offset += TLV_HEADER_SIZE + u8(offset + 2)
        }
        return offset == tail
    }

    private fun decodeLive(firstTlv: Int, tail: Int): BoardReading? {
        var cellCount: Int? = null
        var cells: List<Double?>? = null
        var voltage: Double? = null
        var chargeMa: Int? = null
        var dischargeMa: Int? = null
        var balancing: Boolean? = null
        var balanceBits: Long? = null
        var totalCap: Double? = null
        var remainCap: Double? = null
        var soc: Int? = null
        val temperatures = arrayOfNulls<Double>(TEMPERATURE_NAMES.size)
        val settings = mutableListOf<BoardSetting>()
        val seen = BooleanArray(TAG_SOC + 1)
        var offset = firstTlv
        while (offset < tail) {
            val tag = u16(offset)
            val length = u8(offset + 2)
            val value = offset + TLV_HEADER_SIZE
            liveFieldLengthMatches(tag, length)?.let { matches ->
                if (!matches || seen[tag]) return null
                seen[tag] = true
            }
            when (tag) {
                3 -> {
                    // 公开资料未定宽，只接收可解释为合理串数的 1B / BE16。
                    val count = if (length == 1) u8(value) else u16(value)
                    if (count !in 1..caps.maxCells) return null
                    cellCount = count
                    settings += BoardSetting("mingtang.cellCount", "电芯串数", count.toString(), "串")
                }
                4 -> cells = List(length / 2) { index ->
                    when (val mv = u16(value + index * 2)) {
                        0, 0xFFFF -> null
                        in 1..MAX_CELL_MV -> mv / 1000.0
                        else -> return null
                    }
                }
                5, 6, 7 -> {
                    val mv = u16(value)
                    if (mv > MAX_CELL_MV && mv != 0xFFFF) return null
                }
                10 -> {
                    val raw = u16(value)
                    if (raw !in 1..(caps.maxCells * MAX_CELL_MV / 10)) return null
                    voltage = raw / 100.0
                }
                13, 15 -> {
                    val ma = i32(value)
                    if (ma !in -MAX_CURRENT_MA..MAX_CURRENT_MA) return null
                    if (tag == 13) chargeMa = ma else dischargeMa = ma
                }
                17, 19, 21, 26 -> settings += rawSetting(tag, value, length)
                24 -> balancing = when (u8(value)) {
                    0 -> false
                    1 -> true
                    else -> return null
                }
                25 -> balanceBits = i32(value).toLong() and 0xFFFF_FFFFL
                in 27..31 -> when (val raw = u16(value).toShort().toInt()) {
                    -27300, 30000 -> Unit
                    in -10000..20000 -> temperatures[tag - 27] = raw / 100.0
                    else -> return null
                }
                36, 37 -> {
                    val mah = i32(value)
                    if (mah !in 0..MAX_CAPACITY_MAH || (tag == 36 && mah == 0)) return null
                    if (tag == 36) totalCap = mah / 1000.0 else remainCap = mah / 1000.0
                }
                TAG_SOC -> soc = u8(value).takeIf { it <= 100 } ?: return null
            }
            offset = value + length
        }

        if (voltage == null || soc == null) return null
        // 额外需要至少一项实测数据。串数、原始标志和无效探头不能单独确认实时页。
        if (chargeMa == null && dischargeMa == null && totalCap == null && remainCap == null &&
            temperatures.all { it == null } && cells?.any { it != null } != true) return null
        val current = if (chargeMa != null && dischargeMa != null) (dischargeMa.toLong() - chargeMa) / 1000.0 else null
        val temps = temperatures.mapIndexedNotNull { index, temp -> temp?.let { TEMPERATURE_NAMES[index] to it } }
        return BoardReading(
            totalVoltage = voltage,
            current = current,
            soc = soc,
            totalCapAh = totalCap,
            remainCapAh = remainCap,
            balancing = balancing,
            balanceBits = balanceBits,
            temps = temps.takeIf { it.isNotEmpty() },
            cells = cells?.let { values ->
                // 串数可补齐尾部缺串，但不能改变按 tag 4 原顺序读到的槽位。
                List(maxOf(values.size, cellCount ?: 0)) { values.getOrNull(it) }
            },
            identity = buildMap {
                put("protocol", "MINGTANG-TLV")
                cellCount?.let { put("cellCount", it.toString()) }
            },
            settings = settings.takeIf { it.isNotEmpty() },
        )
    }

    /** 未确认的 tag 只按 TLV 边界跳过。 */
    private fun liveFieldLengthMatches(tag: Int, length: Int): Boolean? = when (tag) {
        3 -> length == 1 || length == 2
        4 -> length in 2..(caps.maxCells * 2) && length % 2 == 0
        5, 6, 7, 10, in 27..31 -> length == 2
        13, 15, 25, 36, 37 -> length == 4
        17, 19, 21, 24, TAG_SOC -> length == 1
        26 -> length > 0 // 循环次数的字长未确认，仅展示原始字节。
        else -> null
    }

    private fun rawSetting(tag: Int, value: Int, length: Int): BoardSetting {
        val name = when (tag) {
            17 -> "充电保护原始字节"
            19 -> "放电保护原始字节"
            21 -> "短路保护原始字节"
            else -> "循环次数原始字节"
        }
        val hex = (value until value + length).joinToString("", prefix = "0x") {
            u8(it).toString(16).padStart(2, '0').uppercase()
        }
        return BoardSetting("mingtang.raw.$tag", name, hex, group = "实时页原始字段")
    }

    private fun u8(at: Int): Int = buffer[at].toInt() and 0xFF
    private fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
    private fun i32(at: Int): Int = (u16(at) shl 16) or u16(at + 2)

    companion object {
        private const val HEADER_SIZE = 6
        private const val TLV_HEADER_SIZE = 3
        private const val LIVE = 0xE1
        private const val TAG_SOC = 38
        private const val MAX_BUFFER = 4096
        private const val RESPONSE_TIMEOUT_MS = 1200L
        private const val MIN_LIVE_GAP_MS = 500L
        // 宽松的车用电池读数上限，用于排除明显不合理的数值。
        private const val MAX_CELL_MV = 6000
        private const val MAX_CURRENT_MA = 10_000_000
        private const val MAX_CAPACITY_MAH = 10_000_000
        private val TEMPERATURE_NAMES = listOf("MOS", "BAT1", "BAT2", "EXT1", "EXT2")
        private val READ = "stateread".encodeToByteArray()

        fun readFrame(): ByteArray = READ.copyOf()
        fun isReadRequest(frame: ByteArray): Boolean = READ.contentEquals(frame)
    }
}
