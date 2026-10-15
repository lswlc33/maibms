package io.github.lswlc33.maibms.protocol.meter

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 电量计协议：组帧结构 + 应答解码（合成帧自洽性；量纲以 EM02.js 的换算为准）。 */
class MeterProtocolTest {

    // ---- 蓝宝 FA50 ----

    @Test
    fun lanbao_read_frame_structure() {
        val p = LanBaoProtocol()
        val f = p.nextPollFrames().first()
        assertEquals(0xFA, f[0].toInt() and 0xFF)
        assertEquals(0x50, f[1].toInt() and 0xFF)
        assertEquals(0x28, f[2].toInt() and 0xFF)
        assertEquals(0xA0, f[3].toInt() and 0xFF)
        assertEquals(0x15, f[4].toInt() and 0xFF)
        assertEquals(9, f[6].toInt() and 0xFF)
        assertEquals(listOf(6, 6, 6, 6, 6, 6), (7..12).map { f[it].toInt() and 0xFF })
        assertEquals(1, f[13].toInt() and 0xFF)
        assertEquals(0xE5, f[18].toInt() and 0xFF)
    }

    @Test
    fun lanbao_decodes_table1() {
        val p = LanBaoProtocol()
        val data = ByteArray(111)
        putBE(data, 0, 2, 4800)
        putBE(data, 2, 4, 500)
        putBE(data, 6, 2, 800)
        data[14] = 25
        putBE(data, 19, 4, 240)
        putBE(data, 82, 2, 4200)
        putBE(data, 84, 2, 4180)
        putBE(data, 86, 2, 20)
        data[50] = 2
        listOf(0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33).forEachIndexed { i, v -> data[51 + i] = v.toByte() }
        // 57 = 表1「名称」：被中继保护板的蓝牙名（不是本机设备名，见 MeterReading.relayBoardName）
        "LB-PACK".forEachIndexed { i, c -> data[57 + i] = c.code.toByte() }
        val r = p.onData(lanbaoFrame(1, data))!!
        assertEquals(48.0, r.totalVoltage)
        assertEquals(5.0, r.current)
        assertEquals(80, r.soc)
        assertEquals(240, r.power)
        assertEquals(4.2, r.maxCell)
        assertEquals(4.18, r.minCell)
        assertEquals(0.02, r.deltaCell)
        assertEquals("彦阳保护板", r.relayBoardBrand)
        assertEquals("LB-PACK", r.relayBoardName)
        assertEquals("AA:BB:CC:11:22:33", r.relayBoardMac)
    }

    @Test
    fun lanbao_rejects_bad_crc_or_foreign_frame() {
        val p = LanBaoProtocol()
        assertNull(p.onData(byteArrayOf(0x7E, 0x01, 0x11)))
        // 正确帧但故意破坏 CRC → 必须拒绝
        val data = ByteArray(111)
        val bad = lanbaoFrame(1, data).also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertNull(p.onData(bad))
    }

    // ---- 陆行 EM2APP ----

    @Test
    fun luxing_poll_frames_match_em02_commands() {
        val p = LuXingProtocol()
        // 官方 EM02.js：40 00/89、40 92/54、42 00/45、41 52/48（每拍 4 条）
        val expected = listOf(
            byteArrayOf(0x01, 0x03, 0x0F, 0xA0.toByte(), 0x00, 0x59, 0x86.toByte(), 0xC6.toByte()),
            byteArrayOf(0x01, 0x03, 0x0F, 0xFC.toByte(), 0x00, 0x36, 0x06, 0xF8.toByte()),
            byteArrayOf(0x01, 0x03, 0x10, 0x68, 0x00, 0x2D, 0x00, 0xCB.toByte()),
            byteArrayOf(0x01, 0x03, 0x10, 0x38, 0x00, 0x30, 0xC0.toByte(), 0xD3.toByte()),
        )
        assertEquals(4, p.nextPollFrames().size)
        expected.forEachIndexed { i, e -> assertTrue(p.nextPollFrames()[i].contentEquals(e)) }
    }

    @Test
    fun luxing_decodes_realtime_len108() {
        val p = LuXingProtocol()
        val d = ByteArray(108)
        var i = 0
        fun u8(v: Int) { d[i++] = v.toByte() }
        fun i8(v: Int) { d[i++] = v.toByte() }
        fun u16(v: Int) { d[i++] = v.toByte(); d[i++] = (v ushr 8).toByte() }
        fun i16(v: Int) = u16(if (v < 0) v + 0x10000 else v)
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        u8(4)                     // cellNum
        u8(1)                     // batType
        u8(1); i8(32)             // mosTemp
        u8(0); i8(-40)            // 均衡温度 0 路
        u8(0)                     // rsv
        u8(2); i8(20); i8(21); repeat(6) { i8(0) }   // batTemps[8]
        u8(0); u8(1)              // min/max temp idx
        u8(0); i8(0)              // validMaxTemp/minTemp
        i16(240)                  // power
        u32(48000)                // 系统电压 mV → 48.0V
        u32(12345)                // 系统电流 /100 → 123.45A
        u16(4200); u16(4190); u16(4180); u16(4210)   // 单体 /1000
        repeat(60 - 2 * 4) { u8(0) }
        u8(3); u8(2)              // max/min 电压下标
        u16(4210); u16(4180)      // validMax/Min 电压
        u16(0); u16(0)            // 最大充/放电流
        u8(0); u8(0)              // 死区
        u16(1000)                 // 额定容量 /10 → 100.0Ah
        u16(500)                  // actualCapacity：官方界面/CSV 都不用，解析层也不采用（见下）
        u8(80)                    // SOC
        u8(100)                   // SOH
        assertEquals(108, i)
        val r = p.onData(modbusResp(d))!!
        assertEquals(48.0, r.totalVoltage)
        assertEquals(123.45, r.current)
        assertEquals(240, r.power)
        assertEquals(80, r.soc)
        assertEquals(100.0, r.totalCapAh)
        // 剩余容量 = SOC × 额定容量（官方 EM02.js `(q/100*j).toFixed(1)`），**不是**帧里的
        // actualCapacity——真机那个寄存器恒等于额定值，照搬会让「剩余容量」永远等于「总容量」
        assertEquals(80.0, r.remainCapAh)
        assertEquals(4, r.cells!!.size)
        assertEquals(4.21, r.maxCell)
        assertEquals(4.18, r.minCell)
        assertTrue(r.temps!!.any { it.first == "MOS" && it.second == 32.0 })
    }

    @Test
    fun luxing_realtime_sentinels_become_null() {
        val p = LuXingProtocol()
        val d = ByteArray(108)
        var i = 0
        fun u8(v: Int) { d[i++] = v.toByte() }
        fun i8(v: Int) { d[i++] = v.toByte() }
        fun u16(v: Int) { d[i++] = v.toByte(); d[i++] = (v ushr 8).toByte() }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        u8(1); u8(0)                 // cellNum=1, batType
        u8(0); i8(0)                 // mosTemp 0 路
        u8(0); i8(0)                 // balaTemp 0 路
        u8(0); u8(0)                 // rsv / batTempNum=0
        repeat(8) { i8(0) }          // batTemps
        u8(0); u8(0); u8(0); i8(0)   // 温度下标/限值
        u16(0x7FFF)                  // power 未上报
        u32(0xFFFFFFFFL)             // 电压未上报
        u32(0x7FFFFFFFL)             // 电流未上报（哨兵是 0x7FFFFFFF）
        u16(0xFFFF)                  // 单体未接
        repeat(60 - 2 * 1) { u8(0) }
        u8(0); u8(0)                 // max/min 下标
        u16(0xFFFF); u16(0xFFFF)     // 最高/最低单体未上报
        u16(0); u16(0)               // 充/放电流
        u8(0); u8(0)                 // 死区
        u16(0xFFFF); u16(0xFFFF)     // 容量未上报
        u8(0); u8(0)                 // soc / soh
        assertEquals(108, i)
        val r = p.onData(modbusResp(d))!!
        assertNull(r.totalVoltage)
        assertNull(r.current)
        assertNull(r.power)
        assertNull(r.totalCapAh)
        assertNull(r.remainCapAh)
        assertNull(r.maxCell)
        assertNull(r.cells)
    }

    @Test
    fun luxing_decodes_bms_data_len90() {
        val p = LuXingProtocol()

        val d = ByteArray(90)
        var i = 0
        fun u8(v: Int) { d[i++] = v.toByte() }
        fun u16(v: Int) { d[i++] = v.toByte(); d[i++] = (v ushr 8).toByte() }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        u8(1); u8(1)              // bmsBound / bmsConnected
        u16(0); u16(0)            // Tx/Rx
        u16(0); u16(0)            // rpm/speed
        u16(0); u16(0)            // tempIn/ntc
        u16(0); u16(0)            // cal
        u32(48000)                // 中继板自报 48.0V——**不采用**（见下）
        u8(0); u8(0)              // localSoc/skip
        u16(0)                    // localcap
        u8(0); u8(0)              // localCellNum/BatType
        repeat(11) { u16(0) }     // ocv
        u8(2)                     // BoundBmsVendorID = 蚂蚁
        "ANT-BLE".forEach { u8(it.code) }; repeat(33 - 7) { u8(0) }
        listOf(0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33).forEach { u8(it) }
        assertEquals(90, i)
        val r = p.onData(modbusResp(d))!!
        // 总电压只认 108 实时帧：90 帧的 batTotalVolt 是中继那块保护板自报的数，
        // 两套测量不一致，写进去会让界面在 600ms 一拍里来回跳（官方首页/CSV 也都不用这一路）
        assertNull(r.totalVoltage)
        assertEquals("蚂蚁保护板", r.relayBoardBrand)
        assertEquals("AA:BB:CC:11:22:33", r.relayBoardMac)
        assertEquals("ANT-BLE", r.relayBoardName)
    }

    // ---- 家族/能力 ----

    @Test
    fun protocol_selection_and_caps() {
        assertTrue(MeterProtocol.forFamily(DeviceFamily.LanBao) is LanBaoProtocol)
        assertTrue(MeterProtocol.forFamily(DeviceFamily.LuXing) is LuXingProtocol)
        assertNull(MeterProtocol.forFamily(DeviceFamily.Ant))
        assertEquals(DeviceCapabilities.LanBao, DeviceCapabilities.forFamily(DeviceFamily.LanBao))
        assertTrue(DeviceCapabilities.LuXing.perCellVoltage)
    }

    // ---- helpers ----

    /**
     * 真机实录（EM2APP-A581，2026-10-15 19:41，整车静置）：
     * 额定容量与 actualCapacity 两处**都是 0x046A = 113.0Ah**，SOC 83%。
     * 官方口径的剩余容量 = 113.0 × 83% = 93.79Ah；若照搬 actualCapacity 就会显示 113.0。
     */
    @Test
    fun luxing_real_frame_remaining_capacity_is_soc_times_rated() {
        val frame = hexStr(
            "01 03 6C 14 00 00 00 00 00 00 04 40 40 40 40 00 00 00 00 00 00 40 40 07 00 36 37 01 00 0A 00 00 00 8D 0F 93 0F " +
            "8E 0F 8E 0F 8E 0F 8F 0F 93 0F 94 0F 8F 0F 8E 0F 8E 0F 90 0F 92 0F 8F 0F 90 0F 8E 0F 8F 0F 8E 0F 8E 0F 93 0F 00 " +
            "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 07 94 0F 8D 0F 00 00 00 00 00 00 6A 04 6A 04 53 64 " +
            "96 0F"
        )
        val r = LuXingProtocol().onData(frame)!!
        assertEquals(83, r.soc)
        val total = r.totalCapAh!!
        val remain = r.remainCapAh!!
        assertEquals(113.0, total, 1e-9, "额定容量 0x046A/10")
        assertEquals(93.79, remain, 1e-9, "剩余容量必须是 SOC×额定容量，不是帧里恒等于额定值的 actualCapacity")
        assertTrue(remain < total, "车辆静置、SOC 83% 时剩余容量不该等于总容量")
        assertEquals(79.67, r.totalVoltage!!, 1e-9)
    }

    // ---- 通知分片 / 有符号值 / 快照就绪 ----

    @Test
    fun default_notification_api_keeps_whole_frame_callers() {
        val reading = MeterReading(totalVoltage = 48.0)
        val protocol = object : MeterProtocol {
            override val family = DeviceFamily.LuXing
            override val caps = DeviceCapabilities.LuXing
            override fun nextPollFrames(): List<ByteArray> = emptyList()
            override fun onData(bytes: ByteArray): MeterReading? = if (bytes.isEmpty()) null else reading
        }
        assertEquals(listOf(reading), protocol.onNotification(byteArrayOf(1)))
        assertTrue(protocol.onNotification(byteArrayOf()).isEmpty())
        protocol.reset()
        assertFalse(protocol.snapshotReady)
    }

    @Test
    fun luxing_signed_current_and_power_keep_sentinels() {
        for (rawCurrent in listOf(-12345, -100, -1, Int.MIN_VALUE, 0, Int.MAX_VALUE - 1)) {
            val reading = LuXingProtocol().onData(luxingRealtime(rawCurrent = rawCurrent, rawPower = -240))!!
            assertEquals(rawCurrent / 100.0, reading.current, "current raw=$rawCurrent")
            assertEquals(-240, reading.power)
        }
        for (power in listOf(-1, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt() - 1)) {
            assertEquals(power, LuXingProtocol().onData(luxingRealtime(rawPower = power))!!.power)
        }
        val sentinel = LuXingProtocol().onData(luxingRealtime(rawCurrent = Int.MAX_VALUE, rawPower = 32767))!!
        assertNull(sentinel.current)
        assertNull(sentinel.power)
        assertEquals("放电", LuXingProtocol().onData(luxingRealtime(rawCurrent = -100))!!.battState)
    }

    @Test
    fun lanbao_signed_current_power_and_temperature_extrema() {
        for (raw in listOf(-12345, -100, -1, Int.MIN_VALUE, 0, Int.MAX_VALUE)) {
            val reading = LanBaoProtocol().onData(lanbaoTable1(rawCurrent = raw, rawPower = raw))!!
            assertEquals(raw / 100.0, reading.current, "current raw=$raw")
            assertEquals(raw, reading.power, "power raw=$raw")
        }
        for (temp in listOf(-1, -128, 127)) {
            val reading = LanBaoProtocol().onData(lanbaoTable1(rawTemp = temp))!!
            assertEquals(listOf("温度" to temp.toDouble()), reading.temps)
        }
        assertEquals("放电", LanBaoProtocol().onData(lanbaoTable1(rawCurrent = -100))!!.battState)
    }

    @Test
    fun notifications_split_at_every_boundary() {
        for (frames in listOf(luxingReadingFrames(), lanbaoReadingFrames())) {
            val luxing = (frames.first()[0].toInt() and 0xFF) == 1
            for (frame in frames) {
                val expected = newProtocol(luxing).onData(frame)!!
                for (split in 0..frame.size) {
                    val protocol = newProtocol(luxing)
                    val first = protocol.onNotification(frame.copyOfRange(0, split))
                    if (split < frame.size) assertTrue(first.isEmpty(), "size=${frame.size}, split=$split")
                    val second = protocol.onNotification(frame.copyOfRange(split, frame.size))
                    assertEquals(listOf(expected), first + second, "size=${frame.size}, split=$split")
                }
            }
        }
    }

    @Test
    fun notifications_in_twenty_byte_chunks_emit_all_tables() {
        for (luxing in listOf(true, false)) {
            val frames = if (luxing) luxingReadingFrames() else lanbaoReadingFrames()
            val stream = joinFrames(frames)
            val protocol = newProtocol(luxing)
            val actual = ArrayList<MeterReading>()
            for (offset in stream.indices step 20) {
                actual.addAll(protocol.onNotification(stream.copyOfRange(offset, minOf(offset + 20, stream.size))))
            }
            val expected = frames.map { newProtocol(luxing).onData(it)!! }
            assertEquals(expected, actual)
            assertTrue(protocol.snapshotReady)
        }
    }

    @Test
    fun coalesced_notifications_emit_ordered_separate_patches_and_keep_suffix() {
        for (luxing in listOf(true, false)) {
            val frames = if (luxing) luxingReadingFrames() else lanbaoReadingFrames()
            val expected = frames.map { newProtocol(luxing).onData(it)!! }
            val protocol = newProtocol(luxing)
            val stream = joinFrames(frames)
            assertEquals(expected, protocol.onNotification(stream))
            assertTrue(protocol.snapshotReady)
            assertTrue(protocol.onNotification(byteArrayOf()).isEmpty())
            protocol.reset()
            assertFalse(protocol.snapshotReady)
            assertEquals(expected, protocol.onNotification(stream + frames.first().copyOfRange(0, 2)))
            assertEquals(listOf(expected.first()), protocol.onNotification(frames.first().copyOfRange(2, frames.first().size)))
            // A single oversized notification must not discard later complete frames.
            assertEquals(expected + expected, newProtocol(luxing).onNotification(stream + stream))
        }
    }

    @Test
    fun noise_is_dropped_and_reset_discards_partial_prefixes() {
        for (luxing in listOf(true, false)) {
            val frames = if (luxing) luxingReadingFrames() else lanbaoReadingFrames()
            val expected = frames.map { newProtocol(luxing).onData(it)!! }
            val noise = ByteArray(4096) { 0x55.toByte() }
            val rejectedHeader = if (luxing) byteArrayOf(1, 3, 7)
                else byteArrayOf(0xFA.toByte(), 0x50, 0, 0, 0x18)
            val protocol = newProtocol(luxing)
            assertTrue(protocol.onNotification(noise + rejectedHeader + frames.first().copyOfRange(0, 1)).isEmpty())
            assertEquals(listOf(expected.first()), protocol.onNotification(frames.first().copyOfRange(1, frames.first().size)))
            protocol.reset()
            assertTrue(protocol.onNotification(frames.last().copyOfRange(0, 2)).isEmpty())
            protocol.reset()
            assertTrue(protocol.onNotification(frames.last().copyOfRange(2, frames.last().size)).isEmpty())
            assertFalse(protocol.snapshotReady)
            assertEquals(expected, protocol.onNotification(joinFrames(frames)))
            assertTrue(protocol.snapshotReady)
        }
    }

    @Test
    fun luxing_consumes_ignored_information_and_exceptions_without_crc_rejection() {
        val info = modbusResp(ByteArray(178))
        val exception = byteArrayOf(1, 0x83.toByte(), 2, 0, 0)
        assertEquals(183, info.size)
        for (ignored in listOf(info, exception)) {
            for (split in 0..ignored.size) {
                val protocol = LuXingProtocol()
                assertTrue(protocol.onNotification(ignored.copyOfRange(0, split)).isEmpty())
                assertTrue(protocol.onNotification(ignored.copyOfRange(split, ignored.size)).isEmpty())
                assertFalse(protocol.snapshotReady)
                assertEquals(listOf(LuXingProtocol().onData(luxingRealtime())!!), protocol.onNotification(luxingRealtime()))
            }
        }
        val frames = luxingReadingFrames()
        val expected = frames.map { LuXingProtocol().onData(it)!! }
        val badCrcRealtime = frames.first().copyOf().also {
            it[it.size - 1] = (it.last().toInt() xor 1).toByte()
        }
        val protocol = LuXingProtocol()
        assertEquals(expected, protocol.onNotification(info + exception + badCrcRealtime + info + exception + frames[1] + frames[2]))
        assertTrue(protocol.snapshotReady)
        assertEquals(expected.first(), LuXingProtocol().onData(badCrcRealtime))
    }

    @Test
    fun luxing_readiness_requires_valid_voltage_bms_and_cycles_since_reset() {
        val protocol = LuXingProtocol()
        assertFalse(protocol.snapshotReady)
        assertNull(protocol.onData(luxingRealtime().copyOfRange(0, 112)))
        protocol.onData(luxingBms())
        protocol.onData(luxingCycle())
        protocol.onData(luxingRealtime(rawVoltage = 0xFFFFFFFFL))
        assertFalse(protocol.snapshotReady)
        protocol.onData(luxingRealtime(rawVoltage = 0))
        assertTrue(protocol.snapshotReady, "零电压是有效读数，不增设官方没有的非零判据")
        protocol.onData(luxingRealtime())
        assertTrue(protocol.snapshotReady)
        protocol.reset()
        assertFalse(protocol.snapshotReady)
        protocol.onData(luxingRealtime())
        protocol.onData(luxingCycle())
        assertFalse(protocol.snapshotReady)
        protocol.onData(luxingBms())
        assertTrue(protocol.snapshotReady)
    }

    @Test
    fun lanbao_accepts_complete_lengths_despite_reflected_id_or_unvalidated_header_fields() {
        for (frame in listOf(lanbaoTable1(), lanbaoTable2())) {
            val expected = LanBaoProtocol().onData(frame)!!
            for (reflected in listOf(0, 1, 2, 255)) {
                val modified = withLanbaoCrc(frame.copyOf().also {
                    it[2] = 0x7E; it[3] = 0x33
                    it[5] = 0x12; it[6] = 0x34
                    it[13] = reflected.toByte()
                })
                assertEquals(expected, LanBaoProtocol().onData(modified))
                assertEquals(listOf(expected), LanBaoProtocol().onNotification(modified))
                val split = minOf(130, modified.size - 1)
                val protocol = LanBaoProtocol()
                assertTrue(protocol.onNotification(modified.copyOfRange(0, split)).isEmpty())
                assertEquals(listOf(expected), protocol.onNotification(modified.copyOfRange(split, modified.size)))
            }
        }
    }

    @Test
    fun lanbao_rejects_crc_header_command_tail_and_unsupported_lengths() {
        val frame = lanbaoTable1()
        val invalid = listOf(
            frame.copyOf().also { it[0] = 0 },
            withLanbaoCrc(frame.copyOf().also { it[0] = 0 }),
            withLanbaoCrc(frame.copyOf().also { it[1] = 0 }),
            withLanbaoCrc(frame.copyOf().also { it[4] = 0x18 }),
            frame.copyOf().also { it[it.size - 1] = 0 },
            frame.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() },
            frame.copyOfRange(0, frame.size - 1),
            lanbaoFrame(1, ByteArray(110)),
            lanbaoFrame(2, ByteArray(133)),
            lanbaoFrame(1, ByteArray(135)),
        )
        for (bad in invalid) {
            val protocol = LanBaoProtocol()
            assertNull(protocol.onData(bad), "bad size=${bad.size}")
            assertTrue(protocol.onNotification(bad).isEmpty())
            assertFalse(protocol.snapshotReady)
        }
        val table2 = lanbaoTable2()
        val protocol = LanBaoProtocol()
        val badCrc = frame.copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 1).toByte() }
        assertEquals(listOf(LanBaoProtocol().onData(table2)!!), protocol.onNotification(badCrc + table2))
        assertFalse(protocol.snapshotReady)
        assertEquals(listOf(LanBaoProtocol().onData(frame)!!), protocol.onNotification(frame))
        assertTrue(protocol.snapshotReady)
    }

    @Test
    fun lanbao_crc_framing_does_not_split_at_payload_e5() {
        val frame = withLanbaoCrc(lanbaoTable1().also { it[16 + 60] = 0xE5.toByte() })
        val protocol = LanBaoProtocol()
        assertTrue(protocol.onNotification(frame.copyOfRange(0, 77)).isEmpty())
        assertEquals(listOf(LanBaoProtocol().onData(frame)!!), protocol.onNotification(frame.copyOfRange(77, frame.size)))
    }

    @Test
    fun lanbao_reset_preserves_secret_poll_schedule_but_requires_both_tables_again() {
        val protocol = LanBaoProtocol("123456")
        val requests = List(4) { protocol.nextPollFrames().single() }
        assertEquals(listOf(1, 1, 1, 1), requests.map { it[13].toInt() and 0xFF })
        protocol.onData(lanbaoTable2())
        assertFalse(protocol.snapshotReady)
        protocol.onData(lanbaoTable1())
        assertTrue(protocol.snapshotReady)
        protocol.reset()
        assertFalse(protocol.snapshotReady)
        val fifth = protocol.nextPollFrames().single()
        assertEquals(2, fifth[13].toInt() and 0xFF)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), (7..12).map { fifth[it].toInt() and 0xFF })
        assertEquals(1, protocol.nextPollFrames().single()[13].toInt() and 0xFF)
        protocol.onData(lanbaoTable1())
        assertFalse(protocol.snapshotReady)
        protocol.onData(lanbaoTable2())
        assertTrue(protocol.snapshotReady)
        protocol.setSecret("654321")
        assertTrue(protocol.snapshotReady)
    }

    private fun newProtocol(luxing: Boolean): MeterProtocol = if (luxing) LuXingProtocol() else LanBaoProtocol()

    private fun luxingReadingFrames() = listOf(luxingRealtime(), luxingBms(), luxingCycle())
    private fun lanbaoReadingFrames() = listOf(lanbaoTable1(), lanbaoTable2())
    private fun joinFrames(frames: List<ByteArray>): ByteArray = frames.fold(byteArrayOf()) { bytes, frame -> bytes + frame }

    private fun luxingRealtime(
        rawCurrent: Int = 500,
        rawPower: Int = 240,
        rawVoltage: Long = 48000L,
    ): ByteArray {
        val data = ByteArray(108)
        data[0] = 1
        putLE(data, 20, 2, rawPower.toLong())
        putLE(data, 22, 4, rawVoltage)
        putLE(data, 26, 4, rawCurrent.toLong())
        putLE(data, 30, 2, 4200)
        putLE(data, 92, 2, 4200)
        putLE(data, 94, 2, 4180)
        putLE(data, 102, 2, 1000)
        data[106] = 80
        return modbusResp(data)
    }

    private fun luxingBms(): ByteArray {
        val data = ByteArray(90)
        data[50] = 2
        "ANT-BLE".forEachIndexed { i, c -> data[51 + i] = c.code.toByte() }
        return modbusResp(data)
    }

    private fun luxingCycle(): ByteArray {
        val data = ByteArray(96)
        putLE(data, 0, 2, 123)
        return modbusResp(data)
    }

    private fun lanbaoTable1(rawCurrent: Int = 500, rawPower: Int = 240, rawTemp: Int = 25): ByteArray {
        val data = ByteArray(111)
        putBE(data, 0, 2, 4800)
        putBE(data, 2, 4, rawCurrent)
        putBE(data, 6, 2, 800)
        data[14] = rawTemp.toByte()
        putBE(data, 19, 4, rawPower)
        putBE(data, 82, 2, 4200)
        putBE(data, 84, 2, 4180)
        putBE(data, 86, 2, 20)
        return lanbaoFrame(1, data)
    }

    private fun lanbaoTable2(): ByteArray {
        val data = ByteArray(134)
        putBE(data, 48, 4, 100000)
        putBE(data, 57, 2, 123)
        return lanbaoFrame(2, data)
    }

    private fun withLanbaoCrc(frame: ByteArray): ByteArray {
        val crc = lanbaoCrc(frame.copyOfRange(0, frame.size - 3))
        frame[frame.size - 3] = (crc ushr 8).toByte()
        frame[frame.size - 2] = crc.toByte()
        return frame
    }

    private fun putLE(data: ByteArray, offset: Int, length: Int, value: Long) {
        for (i in 0 until length) data[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun hexStr(s: String) =
        s.trim().split(' ', '\n').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun lanbaoFrame(dataNumber: Int, data: ByteArray): ByteArray {
        val head = ByteArray(16)
        head[0] = 0xFA.toByte(); head[1] = 0x50.toByte(); head[4] = 0x15
        head[13] = dataNumber.toByte()
        val body = head + data
        val crc = lanbaoCrc(body)
        return body + byteArrayOf((crc ushr 8).toByte(), (crc and 0xFF).toByte(), 0xE5.toByte())
    }

    private fun lanbaoCrc(b: ByteArray): Int {
        var v = 0xFFFF
        for (x in b) {
            v = v xor (x.toInt() and 0xFF)
            repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 49960 else v ushr 1 }
        }
        return v and 0xFFFF
    }

    private fun modbusResp(data: ByteArray): ByteArray {
        val f = byteArrayOf(0x01, 0x03, data.size.toByte()) + data
        val crc = modbusCrc16(f)
        return f + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    private fun putBE(d: ByteArray, off: Int, len: Int, v: Int) {
        for (i in 0 until len) d[off + i] = ((v shr (8 * (len - 1 - i))) and 0xFF).toByte()
    }
}
