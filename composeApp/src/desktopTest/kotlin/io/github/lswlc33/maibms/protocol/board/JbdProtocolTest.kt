package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JbdProtocolTest {
    // aiobmsble/tests/bms/test_jbd_bms.py 的实录向量，非用本实现反向生成。
    private val basic = hex("DD03001D0618FEE101F201F4002A2C7C00000000000080640304030B8B0B8A0B84F88477")
    private val cells = hex("DD0400080D660D610D680D59FE3C77")

    @Test fun goldenFramesDecodeAndPollingContinues() {
        val p = JbdProtocol()
        assertEquals("DDA50300FFFD77", p.nextRequest(0)!!.hex())
        val parts = basic.toList().chunked(3).flatMap { p.onNotification(it.toByteArray(), 20) }
        val r = parts.single()
        assertEquals(15.6, r.totalVoltage)
        assertEquals(2.87, r.current!!, 1e-9)
        assertEquals(4.98, r.remainCapAh)
        assertEquals(5.0, r.totalCapAh)
        assertEquals(42, r.cycles)
        assertEquals(listOf(22.4, 22.3, 21.7), r.temps!!.map { it.second })
        assertEquals("2022-03-28", r.identity["productionDate"])
        assertEquals("DDA50400FFFC77", p.nextRequest(300)!!.hex())
        assertEquals(listOf(3.430, 3.425, 3.432, 3.417), p.onNotification(cells, 320).single().cells)
        assertTrue(p.snapshotReady)
        assertEquals("DDA50500FFFB77", p.nextRequest(600)!!.hex())
        p.onNotification(response(5, "SP04S-TEST".encodeToByteArray()), 620)
        assertEquals("DDA50300FFFD77", p.nextRequest(900)!!.hex())
        assertTrue(isSafeBoardRead(DeviceFamily.Jbd, p.nextRequest(2200)!!))
        p.reset()
        assertFalse(p.snapshotReady)
        assertEquals("DDA50300FFFD77", p.nextRequest(2300)!!.hex())
    }

    @Test fun checksumNoiseAndPayloadSentinelAreHandled() {
        val p = JbdProtocol()
        p.nextRequest(0)
        val bad = basic.copyOf().also { it[5] = 0 }
        assertTrue(p.onNotification(bad, 10).isEmpty())
        assertNotNull(p.error)
        val reading = p.onNotification(byteArrayOf(0, 1, 2) + basic + cells, 20)
        assertEquals(2, reading.size)
        assertNull(p.error)
        val text = p.onNotification(response(5, "SPw77".encodeToByteArray()), 30).single()
        assertEquals("SPw77", text.identity["model"])
    }

    @Test fun unitsSignedCurrentBalanceAndInvalidTemperature() {
        val payload = basic.copyOfRange(4, basic.size - 3)
        payload[20] = 0x83.toByte()
        payload[12] = 0; payload[13] = 1
        payload[14] = 0; payload[15] = 2
        payload[2] = 0; payload[3] = 100
        payload[23] = 0xFF.toByte(); payload[24] = 0xFF.toByte()
        val r = JbdProtocol().onNotification(response(3, payload), 0).single()
        assertEquals(-10.0, r.current)
        assertEquals(50.0, r.totalCapAh)
        assertEquals(0x20001L, r.balanceBits)
        assertEquals(listOf(22.3, 21.7), r.temps!!.map { it.second })
        assertEquals("充电", r.battState)
    }

    @Test fun optionalLoginUsesCapturedGoldenVectorAndNeverChangesPassword() {
        val p = JbdProtocol()
        p.configureAccess("000000", "A4:C1:37:04:2D:BE")
        val probe = p.nextRequest(0)!!
        assertEquals("FFAA15063030303030303B", probe.hex())
        p.onNotification(hex("FFAA15010016"), 10)
        assertEquals("FFAA170017", p.nextRequest(300)!!.hex())
        p.onNotification(hex("FFAA1701627A"), 310)
        val login = p.nextRequest(600)!!
        assertEquals("FFAA1806F65369967FF0D5", login.hex())
        assertTrue(isSafeBoardRead(DeviceFamily.Jbd, probe))
        assertTrue(isSafeBoardRead(DeviceFamily.Jbd, login))
        assertFalse(boardFrameForLog(DeviceFamily.Jbd, login).contains("F6 53"))
        p.onNotification(hex("FFAA18010019"), 610)
        assertEquals("DDA50300FFFD77", p.nextRequest(900)!!.hex())
        assertFalse(isSafeBoardRead(DeviceFamily.Jbd, hex("FFAA16063030303030303C")))
        assertFalse(isSafeBoardRead(DeviceFamily.Jbd, hex("DD5A00025678FF3077")))
    }

    @Test fun failedLoginDoesNotRetryUntilUserOrReconnect() {
        val p = JbdProtocol()
        p.configureAccess("000000", "A4:C1:37:04:2D:BE")
        p.nextRequest(0)
        assertNull(p.nextRequest(1300))
        assertNotNull(p.error)
        assertNull(p.nextRequest(6000))
        p.configureAccess(null, null)
        assertNotNull(p.nextRequest(6300))
    }

    @Test fun missingCellResponseDoesNotStopBasicRefresh() {
        val p = JbdProtocol()
        p.nextRequest(0)
        p.onNotification(basic, 10)
        assertEquals("DDA50400FFFC77", p.nextRequest(300)!!.hex())
        assertEquals("DDA50500FFFB77", p.nextRequest(1600)!!.hex())
        p.onNotification(response(5, "JBD".encodeToByteArray()), 1610)
        assertEquals("DDA50300FFFD77", p.nextRequest(1900)!!.hex())
        assertFalse(p.snapshotReady)
    }

    @Test fun unsolicitedLoginCannotSendCredentials() {
        val p = JbdProtocol()
        assertTrue(p.onNotification(hex("FFAA1701627A"), 0).isEmpty())
        assertEquals("DDA50300FFFD77", p.nextRequest(300)!!.hex())
    }

    private fun response(command: Int, payload: ByteArray): ByteArray {
        val sum = payload.sumOf { it.toInt() and 255 } + payload.size
        val crc = -sum and 65535
        return byteArrayOf(0xDD.toByte(), command.toByte(), 0, payload.size.toByte()) + payload +
            byteArrayOf((crc ushr 8).toByte(), crc.toByte(), 0x77)
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0').uppercase() }
}
