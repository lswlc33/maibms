package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MingtangProtocolTest {
    // 按公开的报文结构构造的合成向量，未经真机捕获或验证。
    private val valid = live(tlv(10, "051E"), tlv(38, "37"), tlv(4, "0CE40CE5"))

    @Test fun fragmentsNoiseAndConcatenatedPages() {
        val p = MingtangProtocol()
        p.nextRequest(0)
        val noise = hex("E11101E01000E11100000005")
        assertTrue(p.onNotification(noise + valid.copyOfRange(0, 2), 10).isEmpty())
        assertTrue(p.onNotification(valid.copyOfRange(2, valid.lastIndex), 20).isEmpty())
        assertFalse(p.snapshotReady)
        val unrelated = page(0xE2, valid) + "[END]".encodeToByteArray() + page(0xF2, valid)
        val readings = p.onNotification(valid.takeLast(1).toByteArray() + unrelated + valid, 30)
        assertEquals(listOf(55, 55), readings.map { it.soc })
        assertTrue(readings.all { it.hasLiveReading })
        assertTrue(p.snapshotReady)
        assertNull(p.error)
    }

    @Test fun rejectsTruncatedTlvWrongLengthsAndImplausibleFields() {
        val prefix = tlv(10, "051E") + tlv(38, "37") + tlv(4, "0CE50CE6")
        val invalid = listOf(
            live(prefix, hex("00")), // 尾字节之前还有无法组成 TLV 的字节。
            live(prefix, hex("000D04FF0000")), // 声称 4 字节，实际只有 3 字节。
            live(prefix, tlv(13, "0000")),
            live(tlv(10, "051E"), tlv(38, "37"), tlv(4, "0CE500")),
            live(tlv(10, "051E"), tlv(38, "65"), tlv(4, "0CE5")),
            live(tlv(10, "0000"), tlv(38, "37"), tlv(4, "0CE5")),
            live(prefix, tlv(24, "02")),
            live(prefix, tlv(36, "FFFFFFFF")),
            live(prefix, tlv(38, "01")),
        )
        for (bytes in invalid) {
            val p = MingtangProtocol()
            assertTrue(p.onNotification(bytes, 0).isEmpty())
            assertFalse(p.snapshotReady)
            assertNotNull(p.error)
            assertEquals(55, p.onNotification(valid, 10).single().soc)
        }
    }

    @Test fun missingFieldsAndUnavailableSensorsDoNotCreateValues() {
        val p = MingtangProtocol()
        val voltage = tlv(10, "051E")
        val soc = tlv(38, "00")
        val unusable = live(voltage, soc, tlv(3, "04"), tlv(4, "0000FFFF"), tlv(27, "955C"))
        assertTrue(p.onNotification(live() + live(voltage) + live(voltage, soc) + unusable, 0).isEmpty())
        assertFalse(p.snapshotReady)

        val withTemp = p.onNotification(live(voltage, soc, tlv(29, "0929")), 10).single()
        assertEquals(0, withTemp.soc)
        assertEquals(listOf("BAT2" to 23.45), withTemp.temps)
        assertNull(withTemp.current)
        assertNull(withTemp.cells)
        assertNull(withTemp.settings)
        assertTrue(withTemp.hasLiveReading)
        assertTrue(p.snapshotReady)
        val onlyCharge = p.onNotification(live(voltage, soc, tlv(13, "0000007B")), 20).single()
        assertNull(onlyCharge.current)
        assertNull(onlyCharge.power)
    }

    @Test fun signedValuesUnitsCellSlotsAndUninterpretedSettings() {
        val voltage = tlv(10, "051E")
        val soc = tlv(38, "00")
        val p = MingtangProtocol()
        val r = p.onNotification(live(
            voltage, soc, tlv(4, "0CE500000CE6FFFF"), tlv(3, "0005"),
            tlv(15, "000009C4"), tlv(13, "FFFFFB1E"),
            tlv(36, "00018894"), tlv(37, "00000000"),
            tlv(31, "0929"), tlv(27, "FB2E"), tlv(28, "955C"), tlv(30, "7530"),
            tlv(24, "00"), tlv(25, "80000004"),
            tlv(17, "80"), tlv(19, "00"), tlv(21, "A5"), tlv(26, "010203"), tlv(170, "01"),
        ), 0).single()
        assertEquals(13.1, r.totalVoltage)
        assertEquals(3.75, r.current)
        assertEquals(0, r.soc)
        assertEquals(100.5, r.totalCapAh)
        assertEquals(0.0, r.remainCapAh)
        assertEquals(listOf(3.301, null, 3.302, null, null), r.cells)
        assertEquals(listOf("MOS" to -12.34, "EXT2" to 23.45), r.temps)
        assertEquals(false, r.balancing)
        assertEquals(0x8000_0004L, r.balanceBits)
        assertEquals(mapOf("protocol" to "MINGTANG-TLV", "cellCount" to "5"), r.identity)
        assertEquals(mapOf(
            "mingtang.cellCount" to "5", "mingtang.raw.17" to "0x80", "mingtang.raw.19" to "0x00",
            "mingtang.raw.21" to "0xA5", "mingtang.raw.26" to "0x010203",
        ), r.settings!!.associate { it.key to it.value })
        assertNull(r.cycles)
        assertNull(r.chMos)
        assertNull(r.disMos)
        assertNull(r.protectPairs)
        assertNull(r.alarmPairs)

        val charging = p.onNotification(live(
            voltage, soc, tlv(13, "000009C4"), tlv(15, "FFFFFB1E"), tlv(3, "02"),
        ), 10).single()
        assertEquals(-3.75, charging.current)
        assertEquals("2", charging.identity["cellCount"])
    }

    @Test fun oversizedNotificationsAndBadHeadersRecover() {
        val p = MingtangProtocol()
        val oversizedFrameHeader = hex("E11100001000") // 声明帧总长为 4097 字节。
        val badTlvHeader = hex("E111000000110001FF") // 长度在上限内，但首个 TLV 越界。
        val noise = ByteArray(9000) { 0x55 }
        assertTrue(p.onNotification(noise, 0).isEmpty())
        assertFalse(p.snapshotReady)
        val bytes = noise + oversizedFrameHeader + badTlvHeader + valid + noise + valid
        assertEquals(2, p.onNotification(bytes, 10).size)
        assertTrue(p.snapshotReady)
        assertNull(p.error)
    }

    @Test fun oneReadInFlightWithTimeoutCooldownAndReset() {
        val p = MingtangProtocol()
        val read = "stateread".encodeToByteArray()
        assertContentEquals(read, p.nextRequest(0))
        assertContentEquals(read, boardProbeRequest(DeviceFamily.Mingtang))
        assertTrue(MingtangProtocol.isReadRequest(read))
        assertTrue(isSafeBoardRead(DeviceFamily.Mingtang, read))
        for (cmd in listOf("stateread\n", "STATEREAD", "statereadstateread", "settingread", "settingsread", "readhis", "readevent", "write")) {
            assertFalse(isSafeBoardRead(DeviceFamily.Mingtang, cmd.encodeToByteArray()))
        }
        assertFalse(MingtangProtocol.isReadRequest(byteArrayOf()))
        MingtangProtocol.readFrame().fill(0)
        assertContentEquals(read, MingtangProtocol.readFrame())

        p.refreshSettings()
        assertNull(p.nextRequest(1199))
        assertContentEquals(read, p.nextRequest(1200))
        assertNotNull(p.error)
        assertEquals(55, p.onNotification(valid, 1300).single().soc)
        assertNull(p.error)
        p.refreshSettings()
        assertNull(p.nextRequest(1799))
        assertContentEquals(read, p.nextRequest(1800))
        assertTrue(p.onNotification(live(), 1810).isEmpty())
        assertNotNull(p.error)
        assertTrue(p.onNotification(valid.copyOf(valid.lastIndex), 1820).isEmpty())
        p.reset()
        assertFalse(p.snapshotReady)
        assertNull(p.error)
        assertTrue(p.onNotification(valid.takeLast(1).toByteArray(), 1830).isEmpty())
        assertContentEquals(read, p.nextRequest(1830))
        assertEquals(55, p.onNotification(valid, 1840).single().soc)
    }

    private fun live(vararg fields: ByteArray) = page(0xE1, fields.fold(byteArrayOf()) { data, field -> data + field })

    private fun page(kind: Int, payload: ByteArray): ByteArray {
        val lastIndex = 6 + payload.size
        return byteArrayOf(kind.toByte(), 0x11, 0, 0xA6.toByte(), (lastIndex ushr 8).toByte(), lastIndex.toByte()) +
            payload + byteArrayOf(0xA5.toByte())
    }

    private fun tlv(tag: Int, valueHex: String): ByteArray {
        val value = hex(valueHex)
        return byteArrayOf((tag ushr 8).toByte(), tag.toByte(), value.size.toByte()) + value
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
