package io.github.lswlc33.maibms.protocol.meter

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily

/**
 * 蓝宝电量计（`BlueBabe`）：自定义 `FA 50` 帧 + 私有 CRC（`utils/modbus/modebusRtu.js` `_execute`）。
 *
 * 读帧：`FA 50 <dst=0x28> <src=0xA0> <cmd=0x15> 00 09 <匹配码6> <数据号> <起始> <长度> <CRC2> E5`
 * （匹配码默认 `06 06 06 06 06 06`，6 位数字密码）。应答：`<16 字节头> <数据区> <CRC2> E5`，
 * 字段按 `pages/index/createParamNew.js` 的 `startAddress/readLength/decimalPlaces` 在数据区里取。
 *
 * 表1（dataNumber=1）给：总压/总流/SOC/温度/剩余容量/功率/最高·最低单串/压差/绑定保护板名称·MAC·类型。
 * 表2（dataNumber=2）给：设备序列号/版本/电池类型/串数/容量/循环次数。
 *
 * ⚠️ 字段的量纲（decimalPlaces）取自反编译常量；多字节字段按**大端**解析（`hexToDecimalism` 未反转）。
 * 上线前需真机核对（见 Plan §11）。
 */
class LanBaoProtocol(secret: String? = null) : MeterProtocol {
    override val family = DeviceFamily.LanBao
    override val caps = DeviceCapabilities.LanBao

    /** 6 字节匹配码；默认全 6（官方 `globalData.matchCode`） */
    private var match = byteArrayOf(6, 6, 6, 6, 6, 6)
    private var index = 0

    init {
        setSecret(secret)
    }

    override fun setSecret(secret: String?) {
        val s = secret?.trim()?.replace(" ", "") ?: return
        when {
            // 12 位十六进制 = 6 字节（官方"固定匹配码"就是这种形态，如 18 21 A3 3B 45 56）
            s.length == 12 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                match = ByteArray(6) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            // 6 位数字 = 逐位字节（官方默认 06 06 06 06 06 06）
            s.length == 6 && s.all { it.isDigit() } ->
                match = ByteArray(6) { i -> s[i].digitToInt().toByte() }
        }
    }

    override fun initialFrames(): List<ByteArray> = emptyList()

    /**
     * 官方每轮读**两张表**（`getInfo()` = getTableInfoOne → getTableInfoTwo，逐条等应答）。
     * 这里做分频：表1（总压/电流/SOC/温度/单体，仪表盘主数据）**每拍**读；
     * 表2（容量/循环/设备信息）**每 5 拍**读一次——既保证主数据跟手，又避免一拍照发两帧。
     */
    override fun nextPollFrames(): List<ByteArray> {
        index++
        return if (index % 5 == 0) listOf(readFrame(dataNumber = 2, start = 0, len = 134))
        else listOf(readFrame(dataNumber = 1, start = 0, len = 111))
    }

    override fun onData(bytes: ByteArray): MeterReading? {
        if (bytes.size < 19) return null
        if ((bytes[bytes.size - 1].toInt() and 0xFF) != 0xE5) return null
        if ((bytes[4].toInt() and 0xFF) != 0x15) return null
        // CRC 校验：覆盖除「CRC 2 字节 + E5」外的全部字节（crc.js crcCheck，增强校验关闭时）
        val calc = crcCheck(bytes, 0, bytes.size - 3)
        val stored = ((bytes[bytes.size - 3].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 2].toInt() and 0xFF)
        if (calc != stored) return null
        val data = bytes.copyOfRange(16, bytes.size - 3)
        // 优先按数据区长度分派（表1=111 / 表2=134），长度不符再回退到回显的数据号
        return when (data.size) {
            111 -> decodeTable1(data)
            134 -> decodeTable2(data)
            else -> when (bytes[13].toInt() and 0xFF) {
                1 -> decodeTable1(data)
                2 -> decodeTable2(data)
                else -> null
            }
        }
    }

    /** 表1：见 createParamNew.js createTableOne */
    private fun decodeTable1(d: ByteArray): MeterReading {
        val voltage = raw(d, 0, 2) / 100.0                 // Total_Voltage (dec 2)
        val current = raw(d, 2, 4, signed = true) / 100.0  // Total_Current (dec 2, signed)
        val soc = raw(d, 6, 2) / 10                        // SOC_Estimate (dec 1)
        val temp = raw(d, 14, 1, signed = true)            // Current_Temperature
        val remainCap = raw(d, 15, 4) / 100000.0           // Remaining_Capacity (dec 5, mAh)
        val power = raw(d, 19, 4, signed = true)           // Realtime_Power (w)
        val hiCell = raw(d, 82, 2) / 1000.0                // highestVoltage (dec 3)
        val loCell = raw(d, 84, 2) / 1000.0                // lowestVoltage
        val diff = raw(d, 86, 2) / 1000.0                  // voltageDifference
        val boundName = ascii(d, 57, 25)                   // 被中继保护板的蓝牙名
        val boardBrand = boardBrandName(raw(d, 50, 1))     // 电池保护板类型
        val boardMac = macString(d, 51, 6)                 // 被中继保护板的 MAC
        return MeterReading(
            deviceName = boundName.takeIf { it.isNotBlank() },
            totalVoltage = voltage,
            current = current,
            power = power,
            soc = soc,
            remainCapAh = remainCap / 1000.0,
            temps = listOf("温度" to temp.toDouble()),
            maxCell = hiCell.takeIf { hiCell > 0 },
            minCell = loCell.takeIf { loCell > 0 },
            deltaCell = diff.takeIf { diff > 0 },
            battState = when {
                current > 0.3 -> "充电"
                current < -0.3 -> "放电"
                else -> "静置"
            },
            relayBoardBrand = boardBrand,
            relayBoardMac = boardMac,
        )
    }

    /** 表1 `communicationMethod`：1=蚂蚁保护板 2=彦阳保护板 3=极空保护板 4=嘉佰达保护板 */
    private fun boardBrandName(code: Int): String? = when (code) {
        1 -> "蚂蚁保护板"
        2 -> "彦阳保护板"
        3 -> "极空保护板"
        4 -> "嘉佰达保护板"
        else -> null
    }

    private fun macString(d: ByteArray, off: Int, n: Int): String? {
        val parts = (0 until n).map { i ->
            val b = if (off + i < d.size) d[off + i].toInt() and 0xFF else 0
            b.toString(16).padStart(2, '0').uppercase()
        }
        return if (parts.all { it == "00" }) null else parts.joinToString(":")
    }

    /** 表2：设备序列号(0,16 ascii) 硬件版本(16,8) 软件版本(24,8) 电池类型(46,1) 串数(47,1) 容量(48,4 dec3) 循环(57,2) */
    private fun decodeTable2(d: ByteArray): MeterReading {
        val cap = raw(d, 48, 4) / 1000.0
        val cycles = raw(d, 57, 2)
        return MeterReading(
            totalCapAh = cap.takeIf { cap > 0 },
            cycles = cycles,
        )
    }

    // ---- 组帧 ----
    private fun readFrame(dataNumber: Int, start: Int, len: Int): ByteArray {
        val f = ArrayList<Int>(22)
        f += 0xFA; f += 0x50; f += 0x28; f += 0xA0; f += 0x15; f += 0x00; f += 0x09
        match.forEach { f += (it.toInt() and 0xFF) }
        f += (dataNumber and 0xFF); f += (start and 0xFF); f += (len and 0xFF)
        val crc = crcCheck(f)
        f += ((crc ushr 8) and 0xFF); f += (crc and 0xFF)
        f += 0xE5
        return ByteArray(f.size) { (f[it] and 0xFF).toByte() }
    }

    /**
     * 私有 CRC：`crc.js crcCheck`（init 0xFFFF，多项式 0xC328 的反射形式 / 0x49960）。
     */
    private fun crcCheck(bytes: List<Int>): Int {
        var v = 0xFFFF
        for (b in bytes) {
            v = v xor (b and 0xFF)
            repeat(8) {
                v = if (v and 1 != 0) (v ushr 1) xor 49960 else v ushr 1
            }
        }
        return v and 0xFFFF
    }

    /** 对 ByteArray 的 [from, to) 段做同样的 CRC（应答校验用） */
    private fun crcCheck(bytes: ByteArray, from: Int, to: Int): Int {
        var v = 0xFFFF
        for (i in from until to) {
            v = v xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                v = if (v and 1 != 0) (v ushr 1) xor 49960 else v ushr 1
            }
        }
        return v and 0xFFFF
    }

    // ---- 数据区取值（大端，按 startAddress/readLength） ----
    private fun raw(d: ByteArray, off: Int, len: Int, signed: Boolean = false): Int {
        var v = 0
        for (i in 0 until len) {
            v = (v shl 8) or (if (off + i < d.size) d[off + i].toInt() and 0xFF else 0)
        }
        if (signed && len in 1..4) {
            val bits = len * 8
            val signBit = 1 shl (bits - 1)
            if (v and signBit != 0) v -= (1 shl bits)
        }
        return v
    }

    private fun ascii(d: ByteArray, off: Int, n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until n) {
            val c = if (off + i < d.size) d[off + i].toInt() and 0xFF else 0
            if (c == 0) break
            if (c in 32..126) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
}
