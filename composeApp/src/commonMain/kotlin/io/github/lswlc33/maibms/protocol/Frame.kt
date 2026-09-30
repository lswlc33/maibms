package io.github.lswlc33.maibms.protocol

/** 协议常量（docs/03-帧格式与校验.md） */
object Proto {
    const val HEAD: Byte = 0x7E.toByte()
    const val TAIL_H: Byte = 0xAA.toByte()
    const val TAIL_L: Byte = 0x55.toByte()
    const val ADDR_MAIN: Byte = 0xA1.toByte()

    const val FC_REALTIME: Int = 0x01       // 读实时（短帧）
    const val FC_PARAM: Int = 0x02           // 读参数（短帧）
    const val FC_WRITE_PARAM: Int = 0x22     // 写参数（长帧）
    const val FC_AUTH: Int = 0x23            // 密码校验（长帧）
    const val FC_CONTROL: Int = 0x51         // 控制命令（长帧，长度 0）
    const val FC_UPGRADE: Int = 0x57          // BLE 升级（长帧，长度 0）

    const val RSP_REALTIME: Int = 0x11
    const val RSP_PARAM: Int = 0x12
    const val RSP_WRITE: Int = 0x42
    const val RSP_AUTH: Int = 0x43
    const val RSP_CONTROL: Int = 0x61

    /** 附加结果段：写参数/读写状态（docs/06 §6.5，结果码在寄存器低字节） */
    const val FC_WRITE_STATUS: Int = 0xFF
}

/** CRC16-Modbus：初值 0xFFFF，多项式 0xA001，线上低字节在前 */
object Crc16 {
    private val table = IntArray(256).also { tbl ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1
            }
            tbl[i] = c
        }
    }

    fun modbus(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = (crc ushr 8) xor table[(crc xor data[i].toInt() and 0xFF)]
        }
        return crc and 0xFFFF
    }
}

/** 组帧：短帧(功能码<0x10, 长度=请求字节数) / 长帧(≥0x10, 长度=数据区长度) */
object Frame {
    /** 构建完整帧。短帧时 length 字段 = 请求读取的字节数（寄存器字节数） */
    fun build(addr: Byte, func: Int, reg: Int, data: ByteArray, lengthField: Int): ByteArray {
        val isShort = func < 0x10
        val bodyLen = if (isShort) 0 else data.size
        val buf = ByteArray(3 + 2 + 1 + bodyLen + 2 + 2)
        var p = 0
        buf[p++] = Proto.HEAD
        buf[p++] = addr
        buf[p++] = func.toByte()
        // 寄存器字段：小端（01/02/03/04/06/07/22/23/26/51/52/53 系列全部小端；59/70/80/57 大端，本项目未用到）
        buf[p++] = (reg and 0xFF).toByte()
        buf[p++] = ((reg shr 8) and 0xFF).toByte()
        // 长度字段：短帧=请求读取字节数（调用方指定）；长帧恒为数据区实际长度
        buf[p++] = (if (isShort) lengthField else data.size).toByte()
        if (!isShort) {
            data.copyInto(buf, p)
            p += data.size
        }
        // CRC 覆盖 addr..最后一个数据字节（不含 7E / 不含 CRC 自身 / 不含 AA55）
        val crc = Crc16.modbus(buf, 1, p)
        buf[p++] = (crc and 0xFF).toByte()          // 低字节在前
        buf[p++] = ((crc shr 8) and 0xFF).toByte()
        buf[p++] = Proto.TAIL_H
        buf[p] = Proto.TAIL_L
        return buf
    }

    /** 读实时数据（245B 新版；lengthField = 请求字节数 0xF5） */
    fun readRealtime(addr: Byte = Proto.ADDR_MAIN) = build(addr, Proto.FC_REALTIME, 0, ByteArray(0), 0xF5)

    /** 读参数块（addr 起始地址，bytes 字节数） */
    fun readParam(startAddr: Int, bytes: Int, addr: Byte = Proto.ADDR_MAIN) =
        build(addr, Proto.FC_PARAM, startAddr, ByteArray(0), bytes)

    /** 写参数（地址，工程量原始值 u16，线上小端） */
    fun writeParam(reg: Int, rawValue: Int, addr: Byte = Proto.ADDR_MAIN) =
        build(addr, Proto.FC_WRITE_PARAM, reg, byteArrayOf((rawValue and 0xFF).toByte(), ((rawValue shr 8) and 0xFF).toByte()), 2)

    /** 控制命令（51 + 命令号小端） */
    fun control(cmd: Int, addr: Byte = Proto.ADDR_MAIN) =
        build(addr, Proto.FC_CONTROL, cmd, ByteArray(0), 0)

    /**
     * 密码校验（23 + 槽地址小端 + 密码字节，不足补 0x00）。
     * 槽长由调用方按等级给出（一~四级 8 字节、五级与管理员 12 字节，见 ParamTable.slotLen）——
     * 曾写死 8 字节，导致 5 级槽（@362）与管理员槽（@374）发出的帧长度不对，永远校验不过。
     */
    fun auth(slotAddr: Int, payload: ByteArray, addr: Byte = Proto.ADDR_MAIN): ByteArray =
        build(addr, Proto.FC_AUTH, slotAddr, payload, payload.size)

    /** 密码校验（按等级编码：五级/管理员槽 12 字节，管理员用点分十进制） */
    fun auth(level: Int, password: String, addr: Byte = Proto.ADDR_MAIN): ByteArray =
        auth(ParamTable.slotAddr(level), PasswordCodec.encode(level, password), addr)

    /** 保存应用参数（51/07） */
    fun saveParams(addr: Byte = Proto.ADDR_MAIN) = control(7, addr)
}

/** 解析出的段 */
class ParsedFrame(
    val addr: Int,
    val func: Int,
    val reg: Int,
    val data: ByteArray,
    val isSeg2: Boolean,   // 组合帧第二段（无帧头）
    val lengthField: Int = 0,  // 短帧=请求读取字节数；长帧=数据长度
) {
    override fun equals(other: Any?): Boolean =
        other is ParsedFrame && other.addr == addr && other.func == func && other.reg == reg &&
                other.data.contentEquals(data) && other.isSeg2 == isSeg2
    override fun hashCode(): Int = func * 31 + reg
    override fun toString(): String =
        "F(%02X reg=%04X len=%d seg2=%s)".format(func, reg, data.size, isSeg2)
}

/**
 * 流式解帧器（docs/03 §3.6、15 §15.6）。
 * 规则：数据不足 → break 保留缓冲；CRC 失败/结构非法 → 丢 1 字节滑动重找 7E；
 *       组合帧第二段无 7E 帧头（紧跟上一段之后），AA55 只在末尾出现一次。
 */
class FrameParser {
    private val buf = ArrayList<Byte>(512)

    val buffered: Int get() = buf.size

    /**
     * 段尾状态：一段解析完（CRC 已过）后，后面要么是 AA55 收尾，要么紧跟无帧头的下一段。
     * 只看 1 个字节判不出来，所以状态跨 feed 保持。
     */
    private var afterSeg = false

    /** 喂入新字节，返回本次解析出的段 */
    fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<ParsedFrame> {
        for (i in from until to) buf.add(bytes[i])
        val out = ArrayList<ParsedFrame>(2)
        while (buf.isNotEmpty()) {
            if (u8(0) == 0x7E) {
                when (val seg = tryParseAt0()) {
                    null -> break                                  // 数据不足，等待
                    INVALID -> { drop1(); afterSeg = false }        // 校验失败，滑动重找
                    else -> { out.add(seg); afterSeg = true }
                }
                continue
            }
            if (!afterSeg) { drop1(); continue }                    // 帧外噪声
            // 段后：先看收尾，再看是否还有下一段
            if (buf.size < 2) break                                 // 不足以判断，等待
            if (u8(0) == 0xAA && u8(1) == 0x55) { drop1(); drop1(); afterSeg = false; continue }
            when (val seg = tryParseSeg2At0()) {
                null -> break                                       // 可能是段2，数据不足
                INVALID -> { drop1(); afterSeg = false }
                else -> out.add(seg)                                // 还有下一段（保持 afterSeg）
            }
        }
        return out
    }

    private fun drop1() { if (buf.isNotEmpty()) buf.removeAt(0) }

    private fun u8(i: Int) = buf[i].toInt() and 0xFF

    /**
     * 位置 0 处的段1：7E 地址 功能码 寄存器(2B) 长度 数据 CRC(2B)。
     * 段体长度 = 6 + 数据长 + 2，**不含 AA55**——帧尾由 feed 的状态机判断（组合帧里 AA55 只在最末尾）。
     * null=数据不足；INVALID=校验失败
     */
    private fun tryParseAt0(): ParsedFrame? {
        if (buf.size < 6) return null
        val fc = u8(2)
        val isShort = fc < 0x10
        val len = if (isShort) 0 else u8(5)
        val body = 8 + len                      // 6 头 + len 数据 + 2 CRC
        if (buf.size < body) return null
        val bytes = ByteArray(5 + len) { buf[it + 1] }   // 地址..最后数据字节（CRC 覆盖范围）
        val expect = Crc16.modbus(bytes)
        if (expect and 0xFF != u8(body - 2) || expect shr 8 != u8(body - 1)) return INVALID
        val data = if (isShort) ByteArray(0) else ByteArray(len) { buf[6 + it] }
        val frame = ParsedFrame(u8(1), fc, u8(3) or (u8(4) shl 8), data, isSeg2 = false, lengthField = u8(5))
        repeat(body) { drop1() }
        return frame
    }

    /**
     * 组合帧后续段：功能码 regLo regHi len data crcLo crcHi（无帧头/帧尾）。
     * 功能码不限定——文档里的 0xFE 机型段是其一，真机 0x23 应答后跟的是 0xFF 段。
     */
    private fun tryParseSeg2At0(): ParsedFrame? {
        if (buf.size < 6) return null
        val len = u8(3)
        val total = 4 + len + 2
        if (buf.size < total) return null
        val bytes = ByteArray(4 + len) { buf[it] }   // 功能码 + reg(2) + len + data
        val expect = Crc16.modbus(bytes)
        if (expect and 0xFF != u8(4 + len) || expect shr 8 != u8(5 + len)) return INVALID
        val data = ByteArray(len) { buf[4 + it] }
        val frame = ParsedFrame(0, u8(0), u8(1) or (u8(2) shl 8), data, isSeg2 = true, lengthField = len)
        repeat(total) { drop1() }
        return frame
    }

    companion object {
        private val INVALID = ParsedFrame(-1, -1, -1, ByteArray(0), false)
    }
}
