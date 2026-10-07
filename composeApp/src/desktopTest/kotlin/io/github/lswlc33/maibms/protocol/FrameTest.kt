package io.github.lswlc33.maibms.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTest {

    // ---- CRC 与组帧（docs/15 §15.6）----
    @Test fun crcModbus() {
        assertEquals(0x6258, Crc16.modbus(hex("A1010000F5")))
    }

    @Test fun buildReadRealtime() {
        assertEquals("7ea1010000f55862aa55", Frame.readRealtime().toHex())
    }

    @Test fun buildReadParam() {
        assertEquals("7ea1026800201865aa55", Frame.readParam(104, 32).toHex())
    }

    @Test fun buildWriteParam() {
        assertEquals("7ea122040002420e4c2caa55", Frame.writeParam(4, 3650).toHex())
    }

    @Test fun buildControl() {
        assertEquals("7ea1510600006924aa55", Frame.control(6).toHex())
        assertEquals("7ea15107000038e4aa55", Frame.saveParams().toHex())
    }

    @Test fun buildAuth() {
        // 一级：槽 330、8 字节（密码用等级编码，不再手写槽地址）
        assertEquals("7ea1234a01083132333435363738db50aa55", Frame.auth(1, "12345678").toHex())
    }

    /** 五级槽（362）与管理员槽（374）是 12 字节：曾写死 8 字节，长度字段与数据都不对，永远校验不过 */
    @Test fun buildAuthLevel5Uses12ByteSlot() {
        val f = Frame.auth(5, "12345678")
        assertEquals(0x23, f[2].toInt() and 0xFF)
        assertEquals(362, (f[3].toInt() and 0xFF) or ((f[4].toInt() and 0xFF) shl 8))
        assertEquals(12, f[5].toInt() and 0xFF)          // 长度字段 = 12
        // 数据区 12 字节：8 位密码 + 4 个 0x00 补齐
        assertEquals("313233343536373800000000", f.copyOfRange(6, 18).toHex())
        // 整帧能被自己的解析器读回（长度字段 = 数据长）
        val p = FrameParser().feed(f)
        assertEquals(1, p.size)
        assertEquals(12, p[0].data.size)
    }

    /** 管理员槽：点分十进制 12 段，每段一个字节 */
    @Test fun buildAuthAdminDottedDecimal() {
        val f = Frame.auth(9, "1.2.3.4.5.6.7.8.9.10.11.12")
        assertEquals(374, (f[3].toInt() and 0xFF) or ((f[4].toInt() and 0xFF) shl 8))
        assertEquals(12, f[5].toInt() and 0xFF)
        assertEquals("0102030405060708090a0b0c", f.copyOfRange(6, 18).toHex())
        // 段数/每段范围不合法要能拦下（别把半个密码发出去）
        assertTrue(PasswordCodec.validate(9, "1.2.3") != null)
        assertEquals(null, PasswordCodec.validate(9, "1.2.3.4.5.6.7.8.9.10.11.12"))
        assertTrue(PasswordCodec.validate(9, "1.2.3.4.5.6.7.8.9.10.11.300") != null)
    }

    /** 槽长按等级取：一~四级 8 字节、五级 12 字节；超长密码本地拦下，不再静默截断 */
    @Test fun authSlotLengthByLevel() {
        assertEquals(8, Frame.auth(2, "12345678")[5].toInt() and 0xFF)
        assertEquals(12, Frame.auth(5, "12345678")[5].toInt() and 0xFF)
        val f = Frame.auth(9, "0.0.0.0.0.0.0.0.0.0.0.1")
        assertEquals(12, f[5].toInt() and 0xFF)
        assertTrue(PasswordCodec.validate(1, "123456789") != null)
        assertEquals(null, PasswordCodec.validate(1, "12345678"))
        assertEquals(null, PasswordCodec.validate(5, "123456789012"))
        assertTrue(PasswordCodec.validate(5, "1234567890123") != null)
    }

    // ---- 解帧 8 场景（docs/15 §15.6）----
    private val vec186 = hex(
        "05010418000000000000000000008001000000000000000000000000250F1B0F1B0F" +
        "F90EFB0EF10EF60EC10EFB0EFB0EFD0EFA0E090F0A0FF90E450FFB0EFB0E100F280FFF0EFF0E1C0F030F" +
        "D8FFD8FFD8FFD8FF170017000E24FFFF4900640001010000" +
        "80F0FA02425E280200000000000000008820000000000000" +
        "450F1000C10E08008400050F00007F007B00B002" +
        "F1FA0000000000000000000000000000140000000000" +
        "9B090000000000000000CDAB0000777788889999"
    )

    /** 0x11 应答段：直接用组帧器构造（build 与 docs 样例逐字节一致，可作可信输入） */
    private fun seg1Frame(): ByteArray =
        Frame.build(Proto.ADDR_MAIN, 0x11, 0, vec186, vec186.size)

    /** 组合帧里的段1：不带 AA55 收尾（AA55 只在整条报文最末尾出现一次） */
    private fun seg1NoTail(): ByteArray = seg1Frame().copyOfRange(0, seg1Frame().size - 2)

    /** 无帧头的后续段：功能码 regLo regHi len data crcLo crcHi（CRC 覆盖 功能码..data） */
    private fun seg2Frame(func: Int = 0xFE): ByteArray {
        val body = byteArrayOf(func.toByte(), 0x00, 0x01, 0x06, 1, 2, 3, 4, 5, 6)
        val c = Crc16.modbus(body)
        return body + byteArrayOf((c and 0xFF).toByte(), ((c shr 8) and 0xFF).toByte())
    }

    private val tail = byteArrayOf(0xAA.toByte(), 0x55.toByte())

    private fun combinedFrame(): ByteArray = seg1NoTail() + seg2Frame() + tail

    @Test fun scene1_singleLongFrame() {
        val p = FrameParser()
        val frames = p.feed(seg1Frame())   // 只喂第一段（完整）
        assertEquals(1, frames.size)
        assertEquals(0x11, frames[0].func)
        assertEquals(vec186.size, frames[0].data.size)
    }

    /**
     * 真机实录（2026-09-29，ANT@BLE24CBUB-3547 / F9:99:1B:2B:1B:70）：
     * 0x23 密码校验应答 —— 段1 的 CRC 后没有 AA55，紧跟一条 0xFF 开头的无帧头段，
     * AA55 只在最末尾。旧解析器强制要求每段紧跟 AA55 且第二段必须以 0xFE 开头，整帧被丢。
     */
    @Test fun realDevice_authResponseCombined() {
        val raw = hex("7EA1434A01020100" + "9D5B" + "FF050000" + "2031" + "AA55")
        val p = FrameParser()
        val frames = p.feed(raw)
        assertEquals(2, frames.size)
        assertEquals(0x43, frames[0].func)
        assertEquals(330, frames[0].reg)                          // 一级密码槽
        assertEquals(1, frames[0].data[0].toInt() and 0xFF)      // 权限 1 级
        assertTrue(frames[1].isSeg2)
        assertEquals(0xFF, frames[1].func)
        assertEquals(0, p.buffered)
    }

    /** 真机实录分段到达（BLE 通知可能把这条 18 字节切成若干片） */
    @Test fun realDevice_authResponseSplit() {
        val raw = hex("7EA1434A01020100" + "9D5B" + "FF050000" + "2031" + "AA55")
        for (cut in 1 until raw.size) {
            val p = FrameParser()
            val a = p.feed(raw, 0, cut)
            val b = p.feed(raw, cut, raw.size)
            assertEquals(2, a.size + b.size, "切在 $cut 字节处解析失败")
            assertEquals(0, p.buffered)
        }
    }

    @Test fun scene2_combinedFrame() {
        val p = FrameParser()
        val frames = p.feed(combinedFrame())
        assertEquals(2, frames.size)
        assertEquals(0x11, frames[0].func)
        assertEquals(0xFE, frames[1].func)
        assertTrue(frames[1].isSeg2)
        assertEquals(6, frames[1].data.size)
        assertEquals(0, p.buffered)
    }

    @Test fun scene3_combinedSplitTwoBatches() {
        val p = FrameParser()
        val all = combinedFrame()
        val b1 = p.feed(all, 0, 100)
        assertEquals(0, b1.size)
        val b2 = p.feed(all, 100, all.size)
        assertEquals(2, b2.size)
    }

    @Test fun scene4_shortFrame() {
        val p = FrameParser()
        val frames = p.feed(Frame.control(7))
        assertEquals(1, frames.size)
        assertTrue(frames[0].data.isEmpty())
    }

    @Test fun scene5_noisePlusFrames() {
        val p = FrameParser()
        val noise = hex("00FF12")
        val truncatedTail = combinedFrame().copyOfRange(0, 50)  // 截断的帧（50 字节残段）
        val frames = p.feed(noise + Frame.control(7) + combinedFrame() + truncatedTail)
        assertEquals(3, frames.size)   // 短帧 + 组合帧 2 段
        assertEquals(50, p.buffered)   // 截断部分保留待续
    }

    @Test fun scene6_byteByByte() {
        val p = FrameParser()
        var count = 0
        combinedFrame().forEach { b -> count += p.feed(byteArrayOf(b)).size }
        assertEquals(2, count)
        assertEquals(0, p.buffered)
    }

    @Test fun scene7_twoFramesByteByByte() {
        val p = FrameParser()
        val two = Frame.readRealtime() + Frame.readParam(104, 32)
        var count = 0
        two.forEach { b -> count += p.feed(byteArrayOf(b)).size }
        assertEquals(2, count)
    }

    @Test fun scene8_pureGarbage() {
        val p = FrameParser()
        val garbage = ByteArray(300) { 0x7F }
        val frames = p.feed(garbage)
        assertEquals(0, frames.size)
        assertEquals(0, p.buffered)
    }

    // ---- 回归：长帧长度字节必须等于数据区长度（曾因写入 lengthField 参数导致应答全被丢弃）----
    @Test fun longFrameLengthByteIsDataSize() {
        val data = ByteArray(170) { (it and 0xFF).toByte() }
        val f = Frame.build(Proto.ADDR_MAIN, 0x11, 0, data, 0)
        assertEquals(180, f.size)   // 6 + 170 + 4
        assertEquals(170, f[5].toInt() and 0xFF)
        val p = FrameParser()
        val out = p.feed(f)
        assertEquals(1, out.size)
        assertEquals(170, out[0].data.size)
    }

    // ---- 实时数据解码（docs/5 §5.8 + 15 §15.6 期望值）----
    @Test fun realtimeDecode() {
        val r = RealtimeDecoder.decode(vec186)
        assertEquals(5, r.permission)
        assertEquals(1, r.battStateCode)
        assertEquals(24, r.cellCount)
        assertEquals(4, r.tempCount)
        assertEquals(3.877, r.cells[0], 1e-9)
        assertEquals(-40.0, r.temps[0], 1e-9)
        assertEquals(92.3, r.totalVoltage, 1e-9)
        assertEquals(-0.1, r.current, 1e-9)
        assertEquals(73, r.soc)
        assertEquals(100, r.soh)
        assertEquals(3.909, r.maxCellV, 1e-9); assertEquals(16, r.maxCellIdx)
        assertEquals(3.777, r.minCellV, 1e-9); assertEquals(8, r.minCellIdx)
        assertEquals(0.132, r.deltaCellV, 1e-9)
        assertEquals(3.845, r.avgCellV, 1e-9)
        assertEquals(64241, r.batteryType)   // 三元锂 0xFAF1
        // 状态字节：充/放电 MOS 开启（新版界面判据看告警位 18/19，此向量用状态字节验证）
        assertEquals(1, r.chMos)
        assertEquals(1, r.disMos)
        // 扩展段（24B）已包含：有效期 0xABCD、充电器输出占位
        assertTrue(r.hasExt)
        // 扩展段 78~89（docs/05 §5.6）：本次充电时长 0s / 上次充电间隔 2459s / 充放剩余 0/0 min
        assertEquals(0L, r.thisChargeSec)
        assertEquals(2459L, r.lastChargeGapSec)
        assertEquals(0, r.remainChargeMin)
        assertEquals(0, r.remainDischargeMin)
        // 一致性自检
        assertTrue(kotlin.math.abs(r.avgCellV * r.cellCount - r.totalVoltage) < 0.1)
        assertTrue(kotlin.math.abs((r.maxCellV - r.minCellV) - r.deltaCellV) < 1e-9)
    }

    /** 扩展段按数据区剩余长度逐字段取：实机有只有 14B 扩展段的固件，截断不崩、缺的按 0 */
    @Test fun realtimeExtTruncatedByField() {
        val v = vec186.copyOf()
        v[170] = 0x2E; v[171] = 0x01              // 充电剩余时间（ext+8）= 302 min
        val r = RealtimeDecoder.decode(v.copyOfRange(0, 172))   // 扩展段只剩 10B：放电剩余被截
        assertTrue(r.hasExt)
        assertEquals(2459L, r.lastChargeGapSec)
        assertEquals(302, r.remainChargeMin)
        assertEquals(0, r.remainDischargeMin)
    }

    // ---- 日志遮蔽（密码不得进日志文件）----

    /** 0x23 密码帧：数据区必须打码，且任何一位密码字节都不能出现在输出里 */
    @Test fun authFrameIsRedactedInLog() {
        val frame = Frame.auth(330, "12345678".encodeToByteArray())
        val logged = Frame.hexForLog(frame)
        // 帧头保留：地址、功能码、槽地址、长度仍可见（排查需要）
        assertTrue(logged.startsWith("7E A1 23 4A 01 08"), "帧头应保留：$logged")
        assertEquals(frame.size, logged.split(' ').size, "遮蔽不能改变长度结构")
        // 密码明文的所有字节形态都不得出现
        for (b in "12345678".encodeToByteArray()) {
            val h = "%02X".format(b)
            assertTrue(!logged.contains(h), "日志里出现了密码字节 $h：$logged")
        }
        assertTrue(logged.contains("**_"), "数据区应打码：$logged")
    }

    /** 真实密码帧（一级 12345678，docs 常见报文）逐字节确认遮蔽位置正确 */
    @Test fun realAuthFrameRedaction() {
        val frame = hex("7EA1234A01083132333435363738DB50AA55")
        assertEquals("7E A1 23 4A 01 08 **_ **_ **_ **_ **_ **_ **_ **_ DB 50 AA 55", Frame.hexForLog(frame))
    }

    /** 非密码帧一律原样输出（实时/参数/控制都不含敏感信息） */
    @Test fun otherFramesNotRedacted() {
        assertEquals("7E A1 01 00 00 F5 58 62 AA 55", Frame.hexForLog(Frame.readRealtime()))
        assertEquals("7E A1 02 68 00 20 18 65 AA 55", Frame.hexForLog(Frame.readParam(104, 32)))
        assertEquals("7E A1 51 07 00 00 38 E4 AA 55", Frame.hexForLog(Frame.control(7)))
    }

    /** 长度字段与帧长不符时不能越界（宁可整串输出也不能崩） */
    @Test fun malformedAuthFrameDoesNotCrash() {
        val bad = hex("7EA1234A01FF313233")      // 声称 255 字节数据，实际只有 3 字节
        assertEquals("7E A1 23 4A 01 FF 31 32 33", Frame.hexForLog(bad))
        assertTrue(Frame.hexForLog(hex("7EA1")).isNotBlank())   // 超短帧同样安全
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
