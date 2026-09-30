package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 虚拟 BMS 引擎：接收解析后的请求帧，按协议编码应答，并以 20 字节分片模拟 BLE 通知。
 * 所有应答均通过 Frame.build 组帧（与 docs 样例逐字节一致），数据回填走完整协议路径。
 */
class MockBmsEngine(private val scope: CoroutineScope) {

    // ---- 模拟状态 ----
    var soc: Double = 76.0
    var current: Double = -12.6           // A（负=放电）
    var battStateCode = 4                 // 4=放电 2=充电 1=静止
    var chMosOn = false
    var disMosOn = true
    var balanceState = 0
    var permissionSlots = mutableMapOf(1 to "12345678", 2 to "12345678", 3 to "12345678", 4 to "12345678", 5 to "12345678", 9 to "12345678")
    var currentPermission = 1
    var protectBits: ULong = 0uL
    var warnBits: ULong = (1uL shl 19)   // 放电 MOS 开
    var runtimeSec: Long = 14L * 3600 + 22 * 60 + 5   // 真机字段单位是秒
    var tick = 0
    val cellCount = 16
    val tempCount = 4
    val cells = MutableList(cellCount) { 3.30 + (it % 5) * 0.003 - (it % 3) * 0.002 }
    val temps = MutableList(tempCount) { 28.4 + it * 0.2 }
    var totalCapAh = 100.0
    var remainCapAh = 76.0
    var cycleCapAh = 1860.0
    var batteryType = 0xFAF2.toInt()      // 磷酸铁锂

    /** 参数区（原始 u16 值按字节地址）；容量类是 u32，拆成低字@addr / 高字@addr+2 */
    val paramStore = mutableMapOf<Int, Int>().also { m ->
        // 16S 磷酸铁锂 / 100Ah 的一套整机默认值（与 docs 附录A 的默认一致）
        m[0] = 3650; m[2] = 3450; m[4] = 3750; m[6] = 3550
        m[8] = 584; m[10] = 560; m[12] = 2800; m[14] = 2900
        m[16] = 2500; m[18] = 2600; m[20] = 480; m[22] = 500
        m[24] = 80; m[26] = 50; m[32] = 3550; m[34] = 3500
        m[36] = 570; m[38] = 560; m[40] = 3000; m[42] = 3100
        m[44] = 500; m[46] = 510; m[48] = 100; m[50] = 80
        // 温度保护 / 告警全组（低温为 s16 负值）
        m[56] = 55; m[58] = 50; m[60] = 60; m[62] = 55
        m[64] = 75; m[66] = 70; m[68] = 0; m[70] = 5; m[72] = -20; m[74] = -15
        m[80] = 50; m[82] = 45; m[84] = 55; m[86] = 50
        m[88] = 70; m[90] = 65; m[92] = 0; m[94] = 5; m[96] = -20; m[98] = -15
        // 电流保护 / 告警
        m[104] = 500; m[106] = 3; m[108] = 800; m[110] = 3
        m[112] = 1200; m[114] = 2; m[116] = 300; m[118] = 200
        m[124] = 450; m[126] = 400; m[128] = 750; m[130] = 700
        m[132] = 20; m[134] = 10
        // 均衡
        m[140] = 3400; m[142] = 3350; m[144] = 30; m[146] = 15; m[148] = 2; m[150] = 500
        // 电池组
        m[152] = batteryType; m[154] = cellCount; m[156] = 10; m[158] = 2500; m[160] = 500
        putU32(m, 162, (totalCapAh * 1_000_000).toLong())
        putU32(m, 166, (remainCapAh * 1_000_000).toLong())
        putU32(m, 170, (cycleCapAh * 1_000_000).toLong())
        m[174] = 3400; m[176] = 3350; m[178] = 3300; m[180] = 3250; m[182] = 3200
        m[184] = 3150; m[186] = 3100; m[188] = 3000; m[190] = 2900; m[192] = 2800; m[194] = 2750
        // 系统
        m[298] = 1500; m[300] = 300; m[304] = 100; m[306] = 0; m[308] = 100; m[310] = 3300
        m[316] = 0; m[318] = 0; m[320] = 0; m[322] = 0; m[328] = 1800
    }

    /** u32 参数落两格：低字 @addr，高字 @addr+2 */
    private fun putU32(m: MutableMap<Int, Int>, addr: Int, v: Long) {
        m[addr] = (v and 0xFFFF).toInt()
        m[addr + 2] = ((v shr 16) and 0xFFFF).toInt()
    }

    /** 写参数落库：容量类按 u32 两格写 */
    private fun storeRaw(addr: Int, raw: Int) {
        if (addr in ParamTable.u32Addrs) paramStore[addr] = raw
        else paramStore[addr] = raw and 0xFFFF
    }

    // ---- 场景切换（设置-连接-模拟场景） ----
    fun applyScene(scene: String) {
        when (scene) {
            "正常充电" -> { battStateCode = 2; current = 12.4; chMosOn = true; disMosOn = true; protectBits = 0uL; warnBits = 1uL shl 18 }
            "正常放电" -> { battStateCode = 4; current = -12.6; chMosOn = false; disMosOn = true; protectBits = 0uL; warnBits = 1uL shl 19 }
            "静止" -> { battStateCode = 1; current = 0.0; chMosOn = true; disMosOn = true; protectBits = 0uL; warnBits = (1uL shl 18) or (1uL shl 19) }
            "保护触发" -> {
                battStateCode = 5; current = 0.0; chMosOn = false; disMosOn = false
                protectBits = 1uL shl 4                       // 单体欠压保护
                warnBits = (1uL shl 2) or (1uL shl 12)        // 欠压告警 + SOC 一级告警
                cells[8] = 2.641; soc = 9.0; remainCapAh = 9.0
            }
            "告警" -> { warnBits = (1uL shl 4) or (1uL shl 6); protectBits = 0uL }
            "主动均衡" -> { balanceState = 4; warnBits = warnBits or (1uL shl 35) }
        }
    }

    /** 周期性推进模拟（由每次 0x01 轮询驱动） */
    private fun tickSim() {
        tick++
        runtimeSec += 1
        // 电流微扰
        val base = when (battStateCode) { 2 -> 12.4; 4 -> -12.6; else -> 0.0 }
        current = base + kotlin.math.sin(tick / 18.0) * 0.6
        // SOC 缓慢变化
        soc = (soc + current / 3600.0 * 0.5 * 100.0 / totalCapAh * 10).coerceIn(0.0, 100.0)
        remainCapAh = totalCapAh * soc / 100.0
        // 单体电压微扰
        for (i in cells.indices) {
            cells[i] += kotlin.math.sin((tick + i * 7) / 23.0) * 0.0006
            cells[i] = cells[i].coerceIn(2.5, 3.65)
        }
        if (battStateCode == 5) cells[8] = 2.641
        // 温度漂移
        for (i in temps.indices) temps[i] += kotlin.math.sin((tick + i * 11) / 40.0) * 0.05
    }

    /** 处理一条请求帧，返回应答帧字节（null=无需应答） */
    fun handle(frame: ParsedFrame): ByteArray? {
        return when (frame.func) {
            Proto.FC_REALTIME -> {
                tickSim()
                Frame.build(Proto.ADDR_MAIN, Proto.RSP_REALTIME, 0, encodeRealtime(), 0)
            }
            Proto.FC_PARAM -> {
                val start = frame.reg
                val bytes = frame.lengthField.takeIf { it > 0 } ?: 52   // 短帧：长度字段=请求字节数
                encodeParamBlock(start, bytes)
            }
            Proto.FC_WRITE_PARAM -> {
                val addr = frame.reg
                val raw = (frame.data.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) or
                          ((frame.data.getOrNull(1)?.toInt()?.and(0xFF) ?: 0) shl 8)
                val def = ParamTable.byAddr(addr)
                // 与真机一致：写被拒时 0x42 照样回显参数块，结论在同帧追加的 0xFF 段里
                val (code, limit) = when {
                    currentPermission < 3 -> 1 to 0                       // 权限不够
                    def == null -> { storeRaw(addr, raw); 10 to 0 }
                    raw < (def.min * def.scale).toInt() -> 2 to (def.min * def.scale).toInt()
                    raw > (def.max * def.scale).toInt() -> 3 to (def.max * def.scale).toInt()
                    else -> { storeRaw(addr, raw); 10 to 0 }
                }
                if (code == 10) {
                    if (addr == 162) totalCapAh = raw / 1_000_000.0
                    if (addr == 154) { /* 串数变更演示 */ }
                }
                val echo = raw.takeIf { code == 10 } ?: (paramStore[addr] ?: 0)
                buildWriteReply(addr, echo, code, limit)
            }
            Proto.FC_AUTH -> auth(frame)
            Proto.FC_CONTROL -> control(frame)
            else -> null
        }
    }

    private fun auth(frame: ParsedFrame): ByteArray {
        val addr = frame.reg
        val slot = ParamTable.passwordSlots.firstOrNull { it.first.let { lv -> ParamTable.slotAddr(lv) == addr } }
        var level = 0
        if (slot != null) {
            val level_ = slot.first
            val expected = permissionSlots[level_] ?: "12345678"
            val got = frame.data.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            if (got == expected) { level = level_; currentPermission = level }
        }
        // 应答 0x43：数据区前 2 字节 = 校验后运行权限（u16 小端）
        return Frame.build(Proto.ADDR_MAIN, Proto.RSP_AUTH, addr,
            byteArrayOf((level and 0xFF).toByte(), ((level shr 8) and 0xFF).toByte()), 2)
    }

    private fun control(frame: ParsedFrame): ByteArray {
        val cmd = frame.reg
        var code = 1
        when (cmd) {
            ControlCmd.DISCHARGE_ON -> disMosOn = true
            ControlCmd.DISCHARGE_OFF -> disMosOn = false
            ControlCmd.CHARGE_ON -> chMosOn = true
            ControlCmd.CHARGE_OFF -> chMosOn = false
            ControlCmd.BALANCE_ON -> balanceState = 5
            ControlCmd.BALANCE_OFF -> balanceState = 0
            ControlCmd.FORCE_CHARGE -> { chMosOn = true; battStateCode = 2; current = 6.0
                warnBits = warnBits or (1uL shl 44) }
            ControlCmd.PRESET_LIFEPO4 -> { m5() }
            ControlCmd.SAVE_PARAMS -> { /* 内存实现，直接成功 */ }
            else -> code = 1
        }
        return Frame.build(Proto.ADDR_MAIN, Proto.RSP_CONTROL, cmd, byteArrayOf(code.toByte()), 1)
    }

    private fun m5() { /* 预设施加电压阈值 */ paramStore[0] = 3650; paramStore[12] = 2500; paramStore[154] = 16 }

    /**
     * 0x42 写应答：段1 = 参数块回显（与 0x12 同布局），失败时同帧追加 0xFF 结果段
     * （格式见 docs/附录B B.3：FF 码 长度 地址u16 限值u16 CRC）。
     */
    private fun buildWriteReply(addr: Int, echoedRaw: Int, code: Int, limit: Int): ByteArray {
        val seg1 = Frame.build(
            Proto.ADDR_MAIN, Proto.RSP_WRITE, addr,
            byteArrayOf((echoedRaw and 0xFF).toByte(), ((echoedRaw shr 8) and 0xFF).toByte()), 2
        )
        // 去掉段1 的 AA55 尾部
        val head = seg1.copyOfRange(0, seg1.size - 2)
        if (code == 10 || code == 0 || code == 11) return seg1
        val body = byteArrayOf(
            0xFF.toByte(), code.toByte(), 0x00, 0x04,
            (addr and 0xFF).toByte(), ((addr shr 8) and 0xFF).toByte(),
            (limit and 0xFF).toByte(), ((limit shr 8) and 0xFF).toByte(),
        )
        val crc = Crc16.modbus(body)
        return head + body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte(),
            Proto.TAIL_H, Proto.TAIL_L)
    }

    // ---- 实时数据编码（解码的逆，186B 布局：28 + 2N + 2M + 78 + 24） ----
    fun encodeRealtime(): ByteArray {
        val n = cellCount; val m = tempCount
        val t0 = 28 + 2 * n + 2 * m
        val size = t0 + 78 + 24
        val d = ByteArray(size)
        fun putU8(i: Int, v: Int) { d[i] = v.toByte() }
        fun putU16(i: Int, v: Int) { d[i] = (v and 0xFF).toByte(); d[i + 1] = ((v shr 8) and 0xFF).toByte() }
        fun putS16(i: Int, v: Int) = putU16(i, v and 0xFFFF)
        fun putU32(i: Int, v: Long) {
            d[i] = (v and 0xFF).toByte(); d[i + 1] = ((v shr 8) and 0xFF).toByte()
            d[i + 2] = ((v shr 16) and 0xFF).toByte(); d[i + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putU64(i: Int, v: ULong) { for (k in 0 until 8) d[i + k] = ((v shr (8 * k)) and 0xFFu).toByte() }

        putU8(0, currentPermission)
        putU8(1, battStateCode)
        putU8(2, m); putU8(3, n)
        putU64(4, protectBits)
        putU64(12, warnBits)
        putU64(20, 0uL)
        for (i in 0 until n) putU16(28 + 2 * i, (cells[i] * 1000).toInt() and 0x1FFF)
        for (i in 0 until m) putS16(28 + 2 * n + 2 * i, temps[i].toInt())

        val maxIdx = (1..n).maxByOrNull { cells[it - 1] } ?: 1
        val minIdx = (1..n).minByOrNull { cells[it - 1] } ?: 1
        val maxV = cells[maxIdx - 1]; val minV = cells[minIdx - 1]
        val avgV = cells.average()
        putS16(t0, 31)   // MOS 温度（协议整数 ℃）
        putS16(t0 + 2, 30)
        putU16(t0 + 4, (cells.sum() * 100).toInt())
        putS16(t0 + 6, (current * 10).toInt())
        putU16(t0 + 8, soc.toInt())
        putU16(t0 + 10, 98)
        putU8(t0 + 12, if (disMosOn) 1 else 12)
        putU8(t0 + 13, if (chMosOn) 1 else 0)
        putU8(t0 + 14, balanceState)
        putU8(t0 + 15, 0)
        putU32(t0 + 16, (totalCapAh * 1_000_000).toLong())
        putU32(t0 + 20, (remainCapAh * 1_000_000).toLong())
        putU32(t0 + 24, (cycleCapAh * 1000).toLong())
        putU32(t0 + 28, ((cells.sum()) * current).toLong())     // 功率 = 总压×电流（s32）
        putU32(t0 + 32, runtimeSec)
        putU32(t0 + 36, 0)                                     // 均衡位图/结构体
        putU16(t0 + 40, (maxV * 1000).toInt()); putU16(t0 + 42, maxIdx)
        putU16(t0 + 44, (minV * 1000).toInt()); putU16(t0 + 46, minIdx)
        putU16(t0 + 48, ((maxV - minV) * 1000).toInt())
        putU16(t0 + 50, (avgV * 1000).toInt())
        putS16(t0 + 52, 0); putU16(t0 + 54, 0); putU16(t0 + 56, 0); putU16(t0 + 58, 0)
        putU16(t0 + 60, batteryType)
        putU32(t0 + 62, 0); putU32(t0 + 66, 0)
        putU32(t0 + 70, 0); putU32(t0 + 74, 0)

        // 扩展段（24B，相对 t0+78）：本次充电时长 / 上次间隔 / 充放剩余 / 高速电流 / 有效期 / 采集故障 / 充电器输出 / 充电器状态
        val e = t0 + 78
        putU32(e, 0); putU32(e + 4, 0)
        val remainMin = if (current < -0.1) (remainCapAh / -current * 60).toInt().coerceIn(0, 65535) else 0
        putU16(e + 8, remainMin); putU16(e + 10, 0)
        putU16(e + 12, 0)
        putU16(e + 14, 0)
        putU16(e + 16, 0)
        putU16(e + 18, 5283); putU16(e + 20, 0)   // 充电器输出占位
        putU16(e + 22, 0)
        return d
    }

    /** 参数块编码（0x12 应答：reg + len + u16 数据） */
    private fun encodeParamBlock(startAddr: Int, bytes: Int): ByteArray {
        val count = (bytes / 2).coerceAtLeast(1)
        val data = ByteArray(count * 2)
        for (i in 0 until count) {
            val addr = startAddr + i * 2
            val v = paramStore[addr] ?: when {
                addr in 330..361 -> 0x00            // 密码区不读出
                else -> 0
            }
            data[2 * i] = (v and 0xFF).toByte()
            data[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return Frame.build(Proto.ADDR_MAIN, Proto.RSP_PARAM, startAddr, data, data.size)
    }
}

/**
 * 虚拟传输：实现 BmsTransport，把写收到的帧交给引擎，应答按 20 字节分片投递（模拟 BLE 通知）。
 */
class MockBmsTransport(
    private val engine: MockBmsEngine,
    private val scope: CoroutineScope,
) : BmsTransport {

    private val _linkState = MutableStateFlow(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming
    override val supportsScan: Boolean get() = true

    private val parser = FrameParser()

    override suspend fun connect(address: String?) {
        _linkState.value = LinkState.Connecting
        delay(300)                                  // 模拟握手时延
        _linkState.value = LinkState.Connected
    }

    override suspend fun disconnect() {
        _linkState.value = LinkState.Disconnected
    }

    override suspend fun write(frame: ByteArray) {
        if (_linkState.value != LinkState.Connected) return
        val requests = parser.feed(frame)
        for (req in requests) {
            val resp = engine.handle(req) ?: continue
            // 20 字节分片投递，间隔 6ms（模拟 BLE 通知节奏与粘包）
            var offset = 0
            while (offset < resp.size) {
                val len = minOf(20, resp.size - offset)
                _incoming.emit(resp.copyOfRange(offset, offset + len))
                offset += len
                delay(6)
            }
        }
    }

    override suspend fun scan(onFound: (ScanDevice) -> Unit) {
        delay(200)
        onFound(ScanDevice("ANT-BMS-16S", "AA:BB:CC:0E:56:C2", -52))
        delay(150)
        onFound(ScanDevice("ANT-BMS-24S", "AA:BB:CC:11:9A:07", -78))
    }
}
