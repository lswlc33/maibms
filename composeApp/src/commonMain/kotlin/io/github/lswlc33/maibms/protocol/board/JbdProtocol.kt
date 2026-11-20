package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily
import io.github.lswlc33.maibms.data.fmt
import kotlin.math.roundToInt

/**
 * 嘉佰达/JBD 经典 BLE 透传协议的只读实现。
 *
 * 只发 0xA5 读取 0x03/0x04/0x05；不进入工厂模式，不发送 0x5A、0x16 或 0x1D。
 * FF AA 15 支持已有密码直接登录；旧挑战模块才使用 17/18 和真实 MAC。
 * 普通读取优先，避免历史凭据阻挡免密或系统已配对的模块。资料见 docs/16。
 */
class JbdProtocol : ReadOnlyBoardProtocol {
    override val family = DeviceFamily.Jbd
    override val caps = DeviceCapabilities.Jbd

    private enum class AccessState { None, DirectPassword, Probe, Challenge, Login, Verify, Ready, Failed }
    private var authAttempted = false
    private var legacyAttempted = false
    private data class Pending(val command: Int, val sentAtMs: Long)

    private var pending: Pending? = null
    private var lastRequestAt = Long.MIN_VALUE / 2
    private var nextCommand = 0x03
    private var basicReceived = false
    private var cellsReceived = false
    private var cellsRequested = false
    private var modelRequested = false
    private var refreshRequested = false
    private var accessState = AccessState.None
    private var accessPassword: String? = null
    private var accessMac: ByteArray? = null
    private var challenge = 0
    private var buffer = ByteArray(INITIAL_BUFFER)
    private var buffered = 0
    private var lastError: String? = null
    private val settings = linkedMapOf<String, BoardSetting>()

    override val snapshotReady: Boolean
        get() = basicReceived && cellsReceived

    override val error: String? get() = lastError

    override fun configureAccess(password: String?, macAddress: String?) {
        val pw = password?.takeIf { it.length == 6 && it.all { c -> c.code in 0x20..0x7E } }
        val mac = parseMac(macAddress)
        accessPassword = pw
        accessMac = mac
        pending = null
        buffered = 0
        lastError = null
        authAttempted = false
        legacyAttempted = false
        accessState = AccessState.None
    }

    override fun nextRequest(nowMs: Long): ByteArray? {
        expire(nowMs)
        if (pending != null || accessState == AccessState.Failed) return null
        if (nowMs - lastRequestAt < MIN_REQUEST_GAP_MS) return null

        val frame = when (accessState) {
            AccessState.DirectPassword -> appFrame(0x15, (accessPassword ?: return null).encodeToByteArray())
            AccessState.Probe -> appFrame(0x15, byteArrayOf(0x30, 0x30, 0x30, 0x30, 0x30, 0x30))
            AccessState.Challenge -> appFrame(0x17, byteArrayOf())
            AccessState.Login -> {
                val pw = accessPassword ?: return null
                val mac = accessMac ?: return null
                appFrame(0x18, ByteArray(6) { i ->
                    ((((mac[i].toInt() and 0xFF) xor pw[i].code) + challenge) and 0xFF).toByte()
                })
            }
            AccessState.None, AccessState.Ready, AccessState.Verify -> readFrame(nextReadCommand())
            AccessState.Failed -> return null
        }
        val command = frameCommand(frame)
        pending = Pending(command, nowMs)
        lastRequestAt = nowMs
        return frame
    }

    override fun refreshSettings() {
        refreshRequested = true
        modelRequested = false
        cellsRequested = false
        basicReceived = false
        cellsReceived = false
        buffered = 0
        pending = null
    }

    override fun reset() {
        pending = null
        lastRequestAt = Long.MIN_VALUE / 2
        nextCommand = 0x03
        basicReceived = false
        cellsReceived = false
        cellsRequested = false
        modelRequested = false
        refreshRequested = false
        accessState = AccessState.None
        authAttempted = false
        legacyAttempted = false
        buffered = 0
        lastError = null
        settings.clear()
    }

    override fun onNotification(bytes: ByteArray, nowMs: Long): List<BoardReading> {
        expire(nowMs)
        append(bytes)
        val out = ArrayList<BoardReading>(2)
        drain(out)
        return out
    }

    private fun nextReadCommand(): Int {
        if (refreshRequested) {
            refreshRequested = false
            return 0x03
        }
        if (!basicReceived) return 0x03
        if (!cellsRequested) {
            cellsRequested = true
            return 0x04
        }
        if (!modelRequested) {
            modelRequested = true
            return 0x05
        }
        val result = nextCommand
        nextCommand = if (nextCommand == 0x03) 0x04 else 0x03
        return result
    }

    private fun expire(nowMs: Long) {
        val active = pending ?: return
        if (nowMs - active.sentAtMs < RESPONSE_TIMEOUT_MS) return
        pending = null
        buffered = 0
        if (active.command in listOf(0x15, 0x17, 0x18)) {
            if (accessState == AccessState.DirectPassword && accessMac != null && !legacyAttempted) {
                legacyAttempted = true
                accessState = AccessState.Probe
            } else accessState = AccessState.Failed
            lastError = "嘉佰达蓝牙登录未应答，请检查已有密码和 MAC，或先在系统蓝牙中配对后清除本机登录凭据重试"
        } else {
            if (!basicReceived && accessPassword != null && !authAttempted) {
                authAttempted = true
                accessState = AccessState.DirectPassword
            } else if (!basicReceived && accessState == AccessState.Verify && accessMac != null && !legacyAttempted) {
                legacyAttempted = true
                accessState = AccessState.Probe
            } else if (accessState == AccessState.Verify) {
                accessState = AccessState.Failed
            }
            lastError = "嘉佰达未返回有效读数；请核对所选板型、通信通道并关闭原厂 App，需要配对的模块请完成系统蓝牙配对" +
                if (accessState == AccessState.Failed && accessMac == null) "；旧挑战登录模块还需真实 MAC" else ""
        }
    }

    private fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size > MAX_BUFFER) {
            buffered = 0
            lastError = "嘉佰达通知超出缓冲上限"
            return
        }
        if (buffered + bytes.size > MAX_BUFFER) buffered = 0
        ensureCapacity(buffered + bytes.size)
        bytes.copyInto(buffer, buffered)
        buffered += bytes.size
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var size = buffer.size
        while (size < required) size *= 2
        buffer = buffer.copyOf(size)
    }

    private fun drain(out: MutableList<BoardReading>) {
        while (buffered > 0) {
            val first = u8(0)
            if (first == 0xDD) {
                if (!drainBms(out)) return
            } else if (first == 0xFF) {
                if (!drainApp(out)) return
            } else {
                discard(1)
            }
        }
    }

    private fun drainBms(out: MutableList<BoardReading>): Boolean {
        if (buffered < 4) return false
        if (u8(1) == 0xA5) {
            // A request echoed by a protected module is not a telemetry response.
            discard(1)
            return true
        }
        val command = u8(1)
        val length = u8(3)
        if (length > MAX_PAYLOAD) {
            lastError = "嘉佰达响应长度异常：$length"
            discard(1)
            return true
        }
        val total = 4 + length + 3
        if (buffered < total) return false
        if (u8(total - 1) != 0x77 || checksum(buffer, 2, 4 + length) != u16(total - 3)) {
            lastError = "嘉佰达响应 CRC 校验失败"
            discard(1)
            return true
        }
        val status = u8(2)
        val data = buffer.copyOfRange(4, 4 + length)
        discard(total)
        pending = pending?.takeIf { it.command != command }
        if (command == 0x80 || command == 0x83 || status != 0) {
            if (!basicReceived && accessPassword != null && !authAttempted && command in listOf(0x03, 0x80, 0x83)) {
                pending = null
                authAttempted = true
                accessState = AccessState.DirectPassword
            } else if (!basicReceived && accessState == AccessState.Verify && command in listOf(0x03, 0x80, 0x83)) {
                pending = null
                if (accessMac != null && !legacyAttempted) {
                    legacyAttempted = true
                    accessState = AccessState.Probe
                } else accessState = AccessState.Failed
            }
            lastError = "嘉佰达读取 0x${command.toString(16).uppercase()} 被设备拒绝（状态 0x${status.toString(16).padStart(2, '0').uppercase()}）"
            return true
        }
        val reading = when (command) {
            0x03 -> decodeBasic(data)
            0x04 -> decodeCells(data)
            0x05 -> decodeModel(data)
            else -> null
        }
        if (reading != null) {
            lastError = null
            out += reading
        }
        return true
    }

    private fun drainApp(out: MutableList<BoardReading>): Boolean {
        if (buffered < 4) return false
        if (u8(1) != 0xAA) {
            discard(1)
            return true
        }
        val command = u8(2)
        val length = u8(3)
        if (length > 32) {
            lastError = "嘉佰达蓝牙登录响应长度异常：$length"
            discard(1)
            return true
        }
        val total = 5 + length
        if (buffered < total) return false
        if ((sum(buffer, 2, 4 + length) and 0xFF) != u8(total - 1)) {
            lastError = "嘉佰达蓝牙登录校验失败"
            discard(1)
            return true
        }
        val data = buffer.copyOfRange(4, 4 + length)
        discard(total)
        val expected = pending?.command
        // 被动回显或上次登录的迟到回调不能推进另一轮登录状态。
        if (expected != command || accessState !in listOf(AccessState.DirectPassword, AccessState.Probe, AccessState.Challenge, AccessState.Login)) return true
        when (command) {
            0x15 -> {
                pending = null
                when {
                    length != 1 -> {
                        lastError = "嘉佰达模块未返回登录状态，请先在系统蓝牙设置中配对"
                        accessState = AccessState.Failed
                    }
                    data[0].toInt() and 0xFF == 0 -> accessState =
                        if (accessState == AccessState.DirectPassword) AccessState.Verify else AccessState.Challenge
                    data[0].toInt() and 0xFF == 2 -> accessState = AccessState.Verify
                    else -> {
                        if (accessState == AccessState.DirectPassword && accessMac != null) {
                            legacyAttempted = true
                            accessState = AccessState.Probe
                        } else {
                            lastError = "嘉佰达蓝牙密码登录被拒绝，请核对已有密码"
                            accessState = AccessState.Failed
                        }
                    }
                }
            }
            0x17 -> {
                pending = null
                if (length == 1) {
                    challenge = data[0].toInt() and 0xFF
                    accessState = AccessState.Login
                } else {
                    lastError = "嘉佰达蓝牙模块未返回登录随机数"
                    accessState = AccessState.Failed
                }
            }
            0x18 -> {
                pending = null
                if (length == 1 && data[0].toInt() and 0xFF == 0) {
                    accessState = AccessState.Ready
                    lastError = null
                } else {
                    lastError = "嘉佰达蓝牙密码错误；未发送修改或提权命令"
                    accessState = AccessState.Failed
                }
            }
        }
        return true
    }

    private fun decodeBasic(data: ByteArray): BoardReading? {
        if (data.size < 23) {
            lastError = "嘉佰达基本信息长度不足：${data.size}"
            return null
        }
        val status = u8(data, 20)
        val currentScale = if (status and 0x80 != 0) 0.1 else 0.01
        val totalVoltage = u16(data, 0) / 100.0
        val current = -signed16(data, 2) * currentScale
        val remaining = u16(data, 4) * currentScale
        val nominal = u16(data, 6) * currentScale
        val cycles = u16(data, 8)
        val soc = u8(data, 19).takeIf { it <= 100 }
        val cellCount = u8(data, 21)
        val ntcCount = u8(data, 22)
        if (cellCount !in 1..32 || ntcCount > 16 || data.size < 23 + ntcCount * 2) {
            lastError = "嘉佰达串数、温度探头数或基本信息长度不兼容"
            return null
        }
        val temperatures = (0 until ntcCount).mapNotNull { index ->
            val raw = u16(data, 23 + index * 2)
            if (raw == 0 || raw == 0xFFFF) null else "T${index + 1}" to (raw - 2731) / 10.0
        }
        val protection = protectionPairs(u16(data, 16))
        val balanceBits = u16(data, 12).toLong() or (u16(data, 14).toLong() shl 16)
        val charging = status and 0x01 != 0
        val discharging = status and 0x02 != 0
        val state = when {
            current < -0.01 -> "充电"
            current > 0.01 -> "放电"
            else -> "静置"
        }
        basicReceived = true
        accessState = AccessState.Ready
        val dateRaw = u16(data, 10)
        val year = 2000 + (dateRaw ushr 9)
        val month = (dateRaw ushr 5) and 15
        val day = dateRaw and 31
        val productionDate = if (month in 1..12 && day in 1..31)
            "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}" else null
        val version = versionText(u8(data, 18))
        val identity = linkedMapOf(
            "swVersion" to version,
            "cellCount" to cellCount.toString(),
            "temperatureCount" to ntcCount.toString(),
        ).apply { productionDate?.let { put("productionDate", it) } }
        val settings = listOfNotNull(
            productionDate?.let { setting("jbd.productionDate", "生产日期", it) },
            setting("jbd.firmware", "固件版本", version),
            setting("jbd.cellCount", "电芯串数", cellCount.toString(), group = "设备信息"),
            setting("jbd.temperatureCount", "温度探头数", ntcCount.toString(), group = "设备信息"),
            setting("jbd.nominalCapacity", "额定容量", "%.2f".fmt(nominal), "Ah", "容量信息"),
            setting("jbd.cycles", "循环次数", cycles.toString(), "次", "容量信息"),
        )
        return BoardReading(
            totalVoltage = totalVoltage,
            current = current,
            power = (totalVoltage * current).roundToInt(),
            soc = soc,
            totalCapAh = nominal,
            remainCapAh = remaining,
            cycles = cycles,
            battState = state,
            chMos = charging,
            disMos = discharging,
            balancing = balanceBits != 0L,
            balanceBits = balanceBits,
            temps = temperatures,
            protectPairs = protection,
            alarmPairs = emptyList(),
            statePairs = buildList {
                if (charging) add(0 to "充电 MOS 导通")
                if (discharging) add(1 to "放电 MOS 导通")
            },
            identity = identity,
            settings = updateSettings(settings),
        )
    }

    private fun decodeCells(data: ByteArray): BoardReading? {
        if (data.isEmpty() || data.size > 64 || data.size % 2 != 0) {
            lastError = "嘉佰达单体电压长度不是偶数"
            return null
        }
        val cells = List(data.size / 2) { i ->
            u16(data, i * 2).takeUnless { it == 0 || it == 0xFFFF }?.div(1000.0)
        }
        cellsReceived = true
        return BoardReading(cells = cells)
    }

    private fun decodeModel(data: ByteArray): BoardReading? {
        val model = data.decodeToString().takeWhile { it.code in 0x20..0x7E }.trim()
        modelRequested = true
        if (model.isEmpty()) return BoardReading()
        return BoardReading(
            identity = mapOf("model" to model),
            settings = updateSettings(listOf(setting("jbd.model", "设备型号", model))),
        )
    }

    private fun protectionPairs(bits: Int): List<Pair<Int, String>> = (0..15).mapNotNull { bit ->
        if (bits and (1 shl bit) == 0) null
        else bit to (PROTECTION_NAMES.firstOrNull { it.first == bit }?.second ?: "嘉佰达保护位 $bit")
    }

    private fun updateSettings(incoming: List<BoardSetting>): List<BoardSetting> {
        incoming.forEach { settings[it.key] = it }
        return settings.values.toList()
    }

    private fun setting(key: String, name: String, value: String, unit: String = "", group: String = "设备信息") =
        BoardSetting(key, name, value, unit, group)

    private fun readFrame(command: Int): ByteArray = byteArrayOf(
        0xDD.toByte(), 0xA5.toByte(), command.toByte(), 0,
        0xFF.toByte(), ((-command) and 0xFF).toByte(), 0x77,
    )

    private fun appFrame(command: Int, data: ByteArray): ByteArray {
        val out = ByteArray(5 + data.size)
        out[0] = 0xFF.toByte(); out[1] = 0xAA.toByte(); out[2] = command.toByte(); out[3] = data.size.toByte()
        data.copyInto(out, 4)
        out[out.lastIndex] = (sum(out, 2, out.lastIndex) and 0xFF).toByte()
        return out
    }

    private fun frameCommand(frame: ByteArray): Int = when {
        frame.size >= 3 && frame[0].toInt() and 0xFF == 0xDD -> frame[2].toInt() and 0xFF
        frame.size >= 3 -> frame[2].toInt() and 0xFF
        else -> -1
    }

    private fun checksum(data: ByteArray, from: Int, toExclusive: Int): Int =
        (-sum(data, from, toExclusive)) and 0xFFFF

    private fun sum(data: ByteArray, from: Int, toExclusive: Int): Int {
        var total = 0
        for (i in from until toExclusive) total = (total + (data[i].toInt() and 0xFF)) and 0xFFFF
        return total
    }

    private fun parseMac(value: String?): ByteArray? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.size != 6 || parts.any { it.length != 2 || it.toIntOrNull(16) == null }) return null
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF
    private fun u16(data: ByteArray, offset: Int): Int = (u8(data, offset) shl 8) or u8(data, offset + 1)
    private fun signed16(data: ByteArray, offset: Int): Int = u16(data, offset).toShort().toInt()
    private fun u32(data: ByteArray, offset: Int): Long =
        (u8(data, offset).toLong() shl 24) or (u8(data, offset + 1).toLong() shl 16) or
            (u8(data, offset + 2).toLong() shl 8) or u8(data, offset + 3).toLong()

    private fun u8(offset: Int): Int = buffer[offset].toInt() and 0xFF
    private fun u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

    private fun discard(count: Int) {
        buffer.copyInto(buffer, 0, count, buffered)
        buffered -= count
    }

    private fun versionText(raw: Int): String = "${raw ushr 4}.${raw and 0x0F}"

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 3_000L
        const val MIN_REQUEST_GAP_MS = 280L
        const val INITIAL_BUFFER = 96
        const val MAX_PAYLOAD = 255
        const val MAX_BUFFER = 1024
        val PROTECTION_NAMES = listOf(
            0 to "单体过压保护", 1 to "单体欠压保护", 2 to "总压过压保护", 3 to "总压欠压保护",
            4 to "充电过温保护", 5 to "充电低温保护", 6 to "放电过温保护", 7 to "放电低温保护",
            8 to "充电过流保护", 9 to "放电过流保护", 10 to "短路保护", 11 to "前端芯片故障",
            12 to "MOS 软件锁",
        )
    }
}
