package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic boundary frames, following the independently inspected Motexa A layout. No hardware claim. */
class YanYangLegacyTest {
    @Test fun aLayoutUsesOwnRequestsWordSwappedVoltageAndSplitCells() {
        val p = YanYangProtocol()
        p.nextRequest(0)
        p.onNotification(response(ByteArray(8)), 10)
        assertRequest(p.nextRequest(20)!!, 1, 1, 47)
        val main = p.onNotification(response(mainPayload(20)), 30).single()
        assertEquals(74.0, main.totalVoltage)
        assertNull(main.current)
        assertNull(main.power)
        assertEquals("-12.34", main.settings!!.single { it.key == "yy.a.currentRaw" }.value)
        assertEquals(20, main.cells!!.size)
        assertEquals(3.7, main.cells!![17])
        assertNull(main.cells!![18])
        assertNull(main.soc, "conflicting legacy SOC scale must remain a raw setting")
        assertNull(main.totalCapAh)
        assertFalse(p.caps.mos)
        assertEquals(listOf("MOS" to 28.0, "T1" to 30.0), main.temps)
        assertRequest(p.nextRequest(40)!!, 1, 48, 42)
        val extra = ByteArray(84)
        putBe(extra, 40, 3711); putBe(extra, 42, 3722)
        val cells = p.onNotification(response(extra), 50).single().cells!!
        assertEquals(3.711, cells[18]); assertEquals(3.722, cells[19])
        assertEquals(3.7, cells[0])
        assertTrue(p.snapshotReady)
        assertRequest(p.nextRequest(60)!!, 1, 90, 22)
        p.onNotification(response(ByteArray(44)), 70)
        assertNull(p.nextRequest(519))
        assertRequest(p.nextRequest(520)!!, 1, 1, 47)
    }

    @Test fun addressDiscoveryLearnsOneSlaveForAllBlocksAndResetForgetsIt() {
        val p = YanYangProtocol()
        p.nextRequest(0)
        assertRequest(p.nextRequest(3000)!!, 1, 63, 4)
        assertRequest(p.nextRequest(6000)!!, 1, 1, 79)
        val discovery = p.nextRequest(9000)!!
        assertContentEquals(hex("00030000000185DB"), discovery)
        val addressReply = response(byteArrayOf(0, 7), 7)
        val corrupt = addressReply.copyOf().also { it[it.lastIndex] = 0 }
        assertTrue(p.onNotification(corrupt, 9010).isEmpty())
        assertTrue(p.onNotification(addressReply.copyOfRange(0, 2), 9020).isEmpty())
        assertEquals("7", p.onNotification(addressReply.copyOfRange(2, addressReply.size), 9030).single().identity["modbusAddress"])
        assertRequest(p.nextRequest(9040)!!, 7, 63, 4)
        p.onNotification(response(ByteArray(8), 7), 9050)
        assertRequest(p.nextRequest(9060)!!, 7, 1, 47)
        assertTrue(p.onNotification(response(mainPayload(4), 1), 9070).isEmpty())
        assertEquals(14.8, p.onNotification(response(mainPayload(4), 7), 9080).single().totalVoltage)
        assertRequest(p.nextRequest(9090)!!, 7, 48, 42)
        val next = YanYangProtocol()
        next.configureAddress(p.modbusAddress)
        assertRequest(next.nextRequest(9100)!!, 7, 63, 4)
        next.configureAddress(2) // 不能覆盖已在途请求的地址。
        assertEquals(7, next.modbusAddress)
        p.reset()
        assertRequest(p.nextRequest(10000)!!, 1, 63, 4)
    }

    @Test fun silentProfileAndAddressDiscoveryTryOldAAtDefaultAddressOnlyAfterBProbesFail() {
        val p = YanYangProtocol()
        assertRequest(p.nextRequest(0)!!, 1, 63, 4)
        assertRequest(p.nextRequest(3000)!!, 1, 63, 4)
        assertRequest(p.nextRequest(6000)!!, 1, 1, 79)
        assertRequest(p.nextRequest(9000)!!, 0, 0, 1)
        assertRequest(p.nextRequest(12000)!!, 1, 1, 47)
        assertEquals(14.8, p.onNotification(response(mainPayload(4)), 12010).single().totalVoltage)
        assertTrue(p.snapshotReady)
    }

    @Test fun silentProfileOnDiscoveredAddressDoesNotFallBackToAddressOne() {
        val p = YanYangProtocol()
        assertRequest(p.nextRequest(0)!!, 1, 63, 4)
        assertRequest(p.nextRequest(3000)!!, 1, 63, 4)
        assertRequest(p.nextRequest(6000)!!, 1, 1, 79)
        assertRequest(p.nextRequest(9000)!!, 0, 0, 1)
        p.onNotification(response(byteArrayOf(0, 7), 7), 9010)
        assertRequest(p.nextRequest(9020)!!, 7, 63, 4)
        assertRequest(p.nextRequest(12020)!!, 7, 63, 4)
        assertRequest(p.nextRequest(15020)!!, 7, 1, 79)
        assertRequest(p.nextRequest(18020)!!, 7, 1, 47)
        assertEquals(14.8, p.onNotification(response(mainPayload(4), 7), 18030).single().totalVoltage)
        assertTrue(p.snapshotReady)
    }

    @Test fun legacyFramesSplitAtEveryBoundaryAndBadLayoutDoesNotBecomeTelemetry() {
        val frame = response(mainPayload(4))
        for (split in 0..frame.size) {
            val p = YanYangProtocol()
            p.nextRequest(0); p.onNotification(response(ByteArray(8)), 1); p.nextRequest(2)
            val a = p.onNotification(frame.copyOfRange(0, split), 3)
            val b = p.onNotification(frame.copyOfRange(split, frame.size), 4)
            assertEquals(1, a.size + b.size)
        }
        val p = YanYangProtocol()
        p.nextRequest(0); p.onNotification(response(ByteArray(8)), 1); p.nextRequest(2)
        val inconsistent = mainPayload(4).also { putBe(it, 22, 52500) }
        assertTrue(p.onNotification(response(inconsistent), 3).isEmpty())
        assertFalse(p.snapshotReady)
        assertNull(p.protocolLayoutHint)
        assertTrue(p.error?.contains("总压与单体") == true)
        assertEquals(14.8, p.onNotification(frame, 4).single().totalVoltage)
        assertTrue(p.snapshotReady)
    }

    private fun mainPayload(cells: Int) = ByteArray(94).also { data ->
        data[12] = cells.toByte(); data[13] = 2
        val millivolts = cells * 3700
        putBe(data, 22, millivolts and 0xffff); putBe(data, 24, millivolts ushr 16)
        putBe(data, 26, -1234)
        putBe(data, 38, 213); putBe(data, 40, 4567); putBe(data, 42, 8000)
        repeat(minOf(cells, 18)) { putBe(data, 44 + it * 2, 3700) }
        data[81] = 68; data[80] = 70
    }
    private fun putBe(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte(); data[offset + 1] = value.toByte()
    }
    private fun assertRequest(frame: ByteArray, slave: Int, register: Int, count: Int) {
        assertEquals(slave, frame[0].toInt() and 255)
        assertEquals(register, ((frame[2].toInt() and 255) shl 8) or (frame[3].toInt() and 255))
        assertEquals(count, ((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255))
        assertTrue(isSafeBoardRead(DeviceFamily.YanYang, frame))
    }
    private fun response(payload: ByteArray, slave: Int = 1): ByteArray {
        val frame = byteArrayOf(slave.toByte(), 3, payload.size.toByte()) + payload
        var crc = 65535
        for (b in frame) {
            crc = crc xor (b.toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return frame + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
