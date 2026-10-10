package io.github.lswlc33.maibms.protocol.meter

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily

/**
 * 陆行电量计（`EM2APP` / EM02 产品）。
 *
 * **重要**：EM2APP 用的**不是** `utils/bluetoothService.js` 那套（0/52/81/130/190——那是 CJ01/ECU02 等
 * 老产品的表），而是 `pages/mianEM02/EM02/EM02.js` 自己的协议：**每拍 500ms 依次发 4 条 FC03 读**，
 * 响应按字节长度分派。真机实测：用旧表会一路收到 `01 83 02`（非法数据地址）。
 *
 * | 命令（slave 1, FC03） | 起始(hex) | 寄存器数 | 响应 len | 内容 |
 * |---|---|---|---|---|
 * | `sendCommand`  | 0x0FA0(4000) | 89 | **178** | 设备信息 |
 * | `sendCommand1` | 0x0FFC(4092) | 54 | **108** | 实时：单体/温度/电压/电流/功率/容量/SOC |
 * | `sendCommand2` | 0x1068(4200) | 45 | **90**  | BMS 数据：绑定/连接状态、中继品牌/名称/MAC |
 * | `sendCommand3` | 0x1038(4152) | 48 | **96**  | 循环次数 / 串口统计 / RSSI |
 *
 * 字段偏移取自 EM02.js 的三个解析函数（`z`/`K`/`$`），均为**小端**；量纲按官方换算
 * （电压 `/1000`、电流 `/100`、容量 `/10`、单体 `/1000`）。CRC 已用官方 4 帧逐条核对通过。
 */
class LuXingProtocol : MeterProtocol {
    override val family = DeviceFamily.LuXing
    override val caps = DeviceCapabilities.LuXing

    /** EM2APP 每拍发送的 4 条命令（与官方 `sendCommand/sendCommand1/2/3` 逐字节一致） */
    private val frames: List<ByteArray> = listOf(
        modbusReadFrame(1, 4000, 89),
        modbusReadFrame(1, 4092, 54),
        modbusReadFrame(1, 4200, 45),
        modbusReadFrame(1, 4152, 48),
    )

    /** 首拍不再单独发——`nextPollFrames()` 每拍就是这 4 条，避免连上瞬间连发两轮 */
    override fun initialFrames(): List<ByteArray> = emptyList()
    override fun nextPollFrames(): List<ByteArray> = frames

    override fun onData(bytes: ByteArray): MeterReading? {
        if (bytes.size < 5) return null
        if ((bytes[0].toInt() and 0xFF) != 0x01) return null
        if ((bytes[1].toInt() and 0xFF) != 0x03) return null
        val len = bytes[2].toInt() and 0xFF
        if (bytes.size < 3 + len + 2) return null
        return when (len) {
            108 -> decodeRealtime(bytes)   // 0x6C
            90 -> decodeBmsData(bytes)     // 0x5A
            96 -> decodeCycle(bytes)       // 0x60
            else -> null                   // 178 = 设备信息，暂不解析
        }
    }

    /** len=108：实时数据（EM02.js `z`） */
    private fun decodeRealtime(f: ByteArray): MeterReading {
        val r = LeReader(f, 3)
        val cellNum = r.u8()
        r.u8()                                   // batType（w 码表）
        val mosTempNum = r.u8(); val mosTemp = r.i8()
        val balaTempNum = r.u8(); val balaTemp = r.i8()
        r.u8()                                   // rsv95_L
        val batTempNum = r.u8()
        val batTemps = IntArray(8) { r.i8() }
        r.u8(); r.u8()                           // minTempInd / maxTempInd
        r.u8(); r.i8()                           // validMaxTemp / validMinTemp
        val powerRaw = r.i16()
        val sysVolRaw = r.u32()                  // 0xFFFFFFFF = 未上报
        val sysCurrRaw = r.u32()                 // 0x7FFFFFFF = 未上报（注意不是 0xFFFFFFFF）
        val cells = ArrayList<Double>(cellNum.coerceIn(0, 32))
        for (i in 0 until cellNum) {
            val raw = r.u16()
            if (raw != 0xFFFF) cells.add(raw / 1000.0)   // 0xFFFF = 该路未接
        }
        r.skip(60 - 2 * cellNum)
        r.u8(); r.u8()                           // max/min voltage index
        val maxVRaw = r.u16()                    // 0xFFFF = 未上报
        val minVRaw = r.u16()
        r.u16(); r.u16()                         // 最大充/放电流
        r.u8(); r.u8()                           // 电流死区
        val ratedRaw = r.u16()                   // 0xFFFF = 未上报
        val actualRaw = r.u16()
        val soc = r.u8()
        r.u8()                                   // soh

        val voltage = if (sysVolRaw == 0xFFFFFFFFL) null else sysVolRaw / 1000.0
        val current = if (sysCurrRaw == 0x7FFFFFFFL) null else sysCurrRaw / 100.0
        val temps = buildList {
            if (mosTempNum > 0) add("MOS" to mosTemp.toDouble())
            if (balaTempNum > 0) add("均衡" to balaTemp.toDouble())
            batTemps.take(batTempNum.coerceIn(0, 8)).forEachIndexed { i, t -> add("T${i + 1}" to t.toDouble()) }
        }
        val cur = current ?: 0.0
        return MeterReading(
            totalVoltage = voltage,
            current = current,
            power = if (powerRaw == 32767) null else powerRaw,
            soc = soc,
            totalCapAh = if (ratedRaw == 0xFFFF) null else ratedRaw / 10.0,
            remainCapAh = if (actualRaw == 0xFFFF) null else actualRaw / 10.0,
            temps = temps.ifEmpty { null },
            cells = cells.ifEmpty { null },
            maxCell = if (maxVRaw == 0xFFFF) null else maxVRaw / 1000.0,
            minCell = if (minVRaw == 0xFFFF) null else minVRaw / 1000.0,
            battState = when {
                cur > 0.3 -> "充电"
                cur < -0.3 -> "放电"
                else -> "静置"
            },
        )
    }

    /** len=90：BMS 数据（EM02.js `K`）——绑定/连接状态 + 中继的保护板品牌/名称/MAC */
    private fun decodeBmsData(f: ByteArray): MeterReading {
        val r = LeReader(f, 3)
        r.u8()                                   // bmsBound
        r.u8()                                   // bmsConnected
        r.u16(); r.u16()                         // Tx/Rx BmsPackets
        r.u16(); r.u16()                         // hallRPM / hallSpeed
        r.u16(); r.u16()                         // tempIn / ntcTemp
        r.u16(); r.u16()                         // volCalGain1 / volCalOffset1
        val batTotalVolt = r.u32()
        r.u8()                                   // localSoc
        r.u8()                                   // skip
        r.u16()                                  // localcap
        r.u8(); r.u8()                           // localCellNum / localBatType
        repeat(11) { r.u16() }                   // ocvConfig0..10
        val vendorId = r.u8()
        val name = r.ascii(33)
        val mac = r.mac(6)
        val voltage = if (batTotalVolt == 0xFFFFFFFFL) null else batTotalVolt / 1000.0
        return MeterReading(
            deviceName = name.takeIf { it.isNotBlank() },
            totalVoltage = voltage,
            relayBoardBrand = vendorName(vendorId),
            relayBoardMac = mac,
        )
    }

    /** len=96：循环/串口（EM02.js `$`），绝对偏移 */
    private fun decodeCycle(f: ByteArray): MeterReading {
        fun u16at(o: Int): Int = if (o + 1 < f.size) (f[o].toInt() and 0xFF) or ((f[o + 1].toInt() and 0xFF) shl 8) else 0
        return MeterReading(
            cycles = u16at(3),   // cycleCount
        )
    }

    /** 中继保护板品牌码表（EM02.js `m`） */
    private fun vendorName(id: Int): String? = when (id) {
        1 -> "彦阳保护板"
        2 -> "蚂蚁保护板"
        3 -> "嘉佰达保护板"
        4 -> "极空保护板"
        5 -> "超力源保护板"
        6 -> "达理保护板"
        7 -> "小哈个人"
        8 -> "陆行电量计"
        9 -> "蓝宝电量计"
        10 -> "蓝宝电量计（带天线）"
        11 -> "黑蛇精灵"
        12 -> "明唐"
        13 -> "小哈换电"
        14 -> "电岭"
        15 -> "锋锐"
        else -> null
    }
}

/** 小端读取器（EM02.js 的解析函数都用 `getUint16(...,true)` / `getInt16(...,true)`） */
private class LeReader(private val d: ByteArray, private var p: Int) {
    fun u8(): Int = if (p < d.size) (d[p++].toInt() and 0xFF) else 0
    fun i8(): Int = if (p < d.size) d[p++].toInt() else 0
    fun u16(): Int {
        val v = (u8At(p) or (u8At(p + 1) shl 8)); p += 2; return v
    }
    fun i16(): Int { val v = u16(); return if (v >= 0x8000) v - 0x10000 else v }
    fun u32(): Long {
        val lo = u16().toLong() and 0xFFFF
        val hi = u16().toLong() and 0xFFFF
        return (hi shl 16) or lo
    }
    fun skip(n: Int) { p += n }
    fun ascii(n: Int): String {
        val sb = StringBuilder()
        repeat(n) { val c = u8(); if (c != 0 && c in 32..126) sb.append(c.toChar()) }
        return sb.toString().trim()
    }
    fun mac(n: Int): String? {
        val parts = ArrayList<String>(n)
        repeat(n) { parts.add(u8().toString(16).padStart(2, '0').uppercase()) }
        return if (parts.all { it == "00" }) null else parts.joinToString(":")
    }
    private fun u8At(o: Int): Int = if (o in d.indices) d[o].toInt() and 0xFF else 0
}

/** Modbus RTU 读保持寄存器（FC03）请求帧：[unit, 03, startHi, startLo, cntHi, cntLo, crcLo, crcHi] */
internal fun modbusReadFrame(unit: Int, start: Int, count: Int): ByteArray {
    val f = byteArrayOf(
        (unit and 0xFF).toByte(), 0x03,
        (start ushr 8).toByte(), (start and 0xFF).toByte(),
        (count ushr 8).toByte(), (count and 0xFF).toByte(),
    )
    val crc = modbusCrc16(f)
    return f + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
}

/** Modbus CRC16（poly 0xA001，init 0xFFFF） */
internal fun modbusCrc16(data: ByteArray): Int {
    var crc = 0xFFFF
    for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
        }
    }
    return crc and 0xFFFF
}
