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
        assertEquals("7ea1234a01083132333435363738db50aa55", Frame.auth(330, "12345678").toHex())
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
        // 一致性自检
        assertTrue(kotlin.math.abs(r.avgCellV * r.cellCount - r.totalVoltage) < 0.1)
        assertTrue(kotlin.math.abs((r.maxCellV - r.minCellV) - r.deltaCellV) < 1e-9)
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
