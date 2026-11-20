package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.protocol.Crc16
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.math.roundToInt

/** Nordic UART 上的 F403 只读协议。整页通过校验后才发布读数。 */
class YadiProtocol : ReadOnlyBoardProtocol {
    override val family = DeviceFamily.Yadi
    override val caps = DeviceCapabilities.Yadi
    override var snapshotReady = false
        private set
    override val error: String? get() = lastError

    private data class Block(val offset: Int, val count: Int) {
        val address: Int get() = PAGE_ADDRESS + offset
    }

    private val page = ByteArray(PAGE_SIZE)
    private val nibbles = ByteArray((RESPONSE_HEADER_SIZE + 0xFF + CRC_SIZE) * 2)
    private var buffered = 0
    private var blockIndex = 0
    private var pendingAt: Long? = null
    private var lastRequestAt: Long? = null
    private var retryAt: Long? = null
    private var lastError: String? = null

    override fun nextRequest(nowMs: Long): ByteArray? {
        expire(nowMs)
        if (pendingAt != null || retryAt?.let { nowMs < it } == true ||
            lastRequestAt?.let { nowMs - it < MIN_REQUEST_GAP_MS } == true) return null
        val block = BLOCKS[blockIndex]
        retryAt = null
        pendingAt = nowMs
        lastRequestAt = nowMs
        return readFrame(block.address, block.count)
    }

    override fun onNotification(bytes: ByteArray, nowMs: Long): List<BoardReading> {
        expire(nowMs)
        if (pendingAt == null) return emptyList()

        val block = BLOCKS[blockIndex]
        for (byte in bytes) {
            val digit = hexDigit(byte)
            if (digit < 0) {
                buffered = 0
                continue
            }
            if (buffered < HEADER.size && digit != HEADER[buffered]) {
                buffered = 0
                if (digit != HEADER[0]) continue
            }
            nibbles[buffered++] = digit.toByte()
            if (buffered < RESPONSE_HEADER_SIZE * 2) continue
            val count = decodedByte(HEADER.size)
            val crcOffset = RESPONSE_HEADER_SIZE + count
            val frameSize = crcOffset + CRC_SIZE
            if (buffered < frameSize * 2) continue

            val frame = ByteArray(frameSize) { decodedByte(it * 2).toByte() }
            buffered = 0
            if (Crc16.modbus(frame, 0, crcOffset) != u16(frame, crcOffset)) {
                fail(nowMs, "亚迪响应 CRC 校验失败")
                return emptyList()
            }
            if (count != block.count) {
                // 其他块有不同的长度，可能是重复通知。仍等待当前块的应答。
                if (BLOCKS.any { it.count == count }) continue
                fail(nowMs, "亚迪响应长度异常：$count")
                return emptyList()
            }

            frame.copyInto(page, block.offset, RESPONSE_HEADER_SIZE, crcOffset)
            pendingAt = null
            blockIndex++
            // 每个请求最多消费一帧。剩下的通知不能作为未来请求的应答。
            if (blockIndex < BLOCKS.size) return emptyList()
            return finishPage(nowMs)
        }
        return emptyList()
    }

    // 每一页都包含设置。保留在途读取，以免取消后的迟到应答被当作新请求的响应。
    override fun refreshSettings() {
        snapshotReady = false
    }

    override fun reset() {
        discardPage()
        lastRequestAt = null
        retryAt = null
        lastError = null
        snapshotReady = false
    }

    private fun expire(nowMs: Long) {
        val sentAt = pendingAt ?: return
        if (nowMs - sentAt >= RESPONSE_TIMEOUT_MS) fail(nowMs, "亚迪读取超时")
    }

    private fun fail(nowMs: Long, message: String) {
        discardPage()
        // 应答没有地址或事务号。同长度的跨轮应答在线上无法区分，留出时间排空迟到通知。
        retryAt = nowMs + RECOVERY_DRAIN_MS
        lastError = message
        snapshotReady = false
    }

    private fun discardPage() {
        pendingAt = null
        blockIndex = 0
        buffered = 0
        page.fill(0)
    }

    private fun finishPage(nowMs: Long): List<BoardReading> {
        val cellCount = u8(page, 63)
        val voltageCv = u16(page, 20)
        if (cellCount !in 1..MAX_CELLS || voltageCv !in 1..MAX_PACK_CENTIVOLTS) {
            fail(nowMs, "亚迪响应的总压或串数异常")
            return emptyList()
        }
        val reading = BoardReading(
            totalVoltage = voltageCv / 100.0,
            soc = u16(page, 48).takeIf { it <= 1000 }?.let { (it / 10.0).roundToInt() },
            cycles = u16(page, 60),
            cells = List(cellCount) { index ->
                u16(page, 68 + index * 2).takeIf { it in 1..MAX_CELL_MILLIVOLTS }?.div(1000.0)
            },
            identity = mapOf("protocol" to "YADI-F403", "cellCount" to cellCount.toString()),
            settings = listOf(
                rawSetting("protection", "保护原始值", 4, 8),
                rawSetting("fault", "故障原始值", 8, 9),
                rawSetting("warning", "告警原始值", 9, 12),
                rawSetting("capacity", "容量寄存器原始值", 52, 60),
            ),
        )
        blockIndex = 0
        snapshotReady = true
        lastError = null
        return listOf(reading)
    }

    private fun rawSetting(key: String, name: String, from: Int, to: Int) =
        BoardSetting("yadi.$key", name, "0x${encodeHex(page, from, to)}", group = "原始寄存器")

    private fun decodedByte(index: Int): Int = (nibbles[index].toInt() shl 4) or nibbles[index + 1].toInt()

    companion object {
        private const val PAGE_ADDRESS = 0x1000
        private const val PAGE_SIZE = 124
        private const val REQUEST_BODY_SIZE = 5
        private const val RESPONSE_HEADER_SIZE = 3
        private const val CRC_SIZE = 2
        private const val MAX_CELLS = 24
        private const val MAX_PACK_CENTIVOLTS = 20_000
        private const val MAX_CELL_MILLIVOLTS = 6_000
        private const val MIN_REQUEST_GAP_MS = 280L
        private const val RESPONSE_TIMEOUT_MS = 1_250L
        private const val RECOVERY_DRAIN_MS = 3_000L
        private const val HEX_DIGITS = "0123456789ABCDEF"
        private val HEADER = intArrayOf(0xF, 4, 0, 3)
        private val BLOCKS = listOf(Block(0, 60), Block(60, 59), Block(119, 5))

        fun readFrame(address: Int, count: Int): ByteArray {
            require(BLOCKS.any { it.address == address && it.count == count })
            val frame = byteArrayOf(0xF4.toByte(), 3, (address ushr 8).toByte(), address.toByte(), count.toByte(), 0, 0)
            val crc = Crc16.modbus(frame, 0, REQUEST_BODY_SIZE)
            frame[REQUEST_BODY_SIZE] = crc.toByte()
            frame[REQUEST_BODY_SIZE + 1] = (crc ushr 8).toByte()
            return encodeHex(frame, 0, frame.size).encodeToByteArray()
        }

        /** 传输层白名单：仅放行本协议轮询所用的三条 F403 请求。 */
        fun isReadRequest(frame: ByteArray): Boolean {
            val size = REQUEST_BODY_SIZE + CRC_SIZE
            if (frame.size != size * 2) return false
            val decoded = ByteArray(size) { index ->
                val hi = hexDigit(frame[index * 2])
                val lo = hexDigit(frame[index * 2 + 1])
                if (hi < 0 || lo < 0) return false
                ((hi shl 4) or lo).toByte()
            }
            if (u8(decoded, 0) != 0xF4 || u8(decoded, 1) != 3) return false
            val address = (u8(decoded, 2) shl 8) or u8(decoded, 3)
            val count = u8(decoded, 4)
            return BLOCKS.any { it.address == address && it.count == count } &&
                Crc16.modbus(decoded, 0, REQUEST_BODY_SIZE) == u16(decoded, REQUEST_BODY_SIZE)
        }

        private fun hexDigit(byte: Byte): Int = when (val ascii = byte.toInt()) {
            in '0'.code..'9'.code -> ascii - '0'.code
            in 'A'.code..'F'.code -> ascii - 'A'.code + 10
            in 'a'.code..'f'.code -> ascii - 'a'.code + 10
            else -> -1
        }

        private fun encodeHex(data: ByteArray, from: Int, to: Int): String = buildString((to - from) * 2) {
            for (index in from until to) {
                val byte = u8(data, index)
                append(HEX_DIGITS[byte ushr 4])
                append(HEX_DIGITS[byte and 0xF])
            }
        }

        private fun u8(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF
        private fun u16(data: ByteArray, index: Int): Int = u8(data, index) or (u8(data, index + 1) shl 8)
    }
}
