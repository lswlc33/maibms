package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.transport.BoardConnectionProbe
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JikongProtocolTest {
    private fun fixture(model: String, field: String): ByteArray {
        val resource = requireNotNull(javaClass.getResourceAsStream("/jikong-frames.json"))
        val root = resource.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        return hex(root.getValue("samples").jsonObject.getValue(model).jsonObject.getValue(field).jsonPrimitive.content)
    }

    @Test fun recorded24And32CellVersionsUseTheirOwnLayoutsAndCurrentDirection() {
        data class Expected(val model: String, val voltage: Double, val current: Double, val count: Int, val soc: Int)
        for ((model, voltage, current, count, soc) in listOf(
            Expected("JK02_24S", 52.971, -2.329, 16, 56),
            Expected("JK02_32S", 26.509, 7.063, 8, 68),
            Expected("JK02_32S_v15", 53.224, -31.881, 16, 25),
        )) {
            val p = JikongProtocol()
            assertContentEquals(hex("AA5590EB97000000000000000000000000000011"), p.nextRequest(0))
            val identity = p.onNotification(fixture(model, "dev"), 10).single()
            assertNotNull(identity.identity["model"])
            assertFalse(p.snapshotReady)
            assertContentEquals(hex("AA5590EB96000000000000000000000000000010"), p.nextRequest(1_010))
            val readings = fixture(model, "cell").toList().chunked(20)
                .flatMap { p.onNotification(it.toByteArray(), 1_020) }
            val reading = readings.single()
            assertEquals(voltage as Double, reading.totalVoltage!!, 1e-8, model)
            assertEquals(current as Double, reading.current!!, 1e-8, model)
            assertEquals(count, reading.cells!!.size)
            assertEquals(soc, reading.soc)
            assertTrue(reading.temps!!.isNotEmpty())
            assertEquals(true, reading.chMos)
            assertEquals(true, reading.disMos)
            assertTrue(p.snapshotReady)
            assertNull(p.nextRequest(1_100))
            assertTrue(isSafeBoardRead(DeviceFamily.Jikong, p.nextRequest(1_700)!!))
        }
    }

    @Test fun probeRequiresValidatedLiveFrameAndSurvivesNoiseCrcFailureAndAttachedAck() {
        val probe = BoardConnectionProbe(DeviceFamily.Jikong, null, null)
        probe.nextRequest(0)
        val info = fixture("JK02_32S", "dev")
        val ack = hex("AA5590EBC801010000000000000000000000004441540D0A")
        assertFalse(probe.onNotification(info + ack, 20))
        assertFalse(probe.ready)
        probe.nextRequest(620)
        val live = fixture("JK02_32S", "cell")
        val corrupt = live.copyOf().also { it[299] = 0 }
        assertFalse(probe.onNotification(corrupt, 630))
        assertFalse(probe.onNotification(byteArrayOf(0, 9, 0x55) + live.copyOfRange(0, 11), 640))
        assertTrue(probe.onNotification(live.copyOfRange(11, live.size), 650))
        assertTrue(probe.ready)
    }

    @Test fun everySplitPointAndResetPreserveBoundaries() {
        val info = fixture("JK02_24S", "dev")
        val live = fixture("JK02_24S", "cell")
        for (split in 0..live.size) {
            val p = JikongProtocol()
            p.nextRequest(0)
            p.onNotification(info, 10)
            p.nextRequest(1_020)
            val a = p.onNotification(live.copyOfRange(0, split), 1_030)
            val b = p.onNotification(live.copyOfRange(split, live.size), 1_040)
            assertEquals(1, a.size + b.size, "split $split")
            p.reset()
            assertFalse(p.snapshotReady)
            assertTrue(p.onNotification(info + live, 2_000).isEmpty())
            assertEquals(0x97, p.nextRequest(2_010)!![4].toInt() and 255)
        }
        val illegal = JikongProtocol.readFrame(0x96).also { it[4] = 1 }
        assertFalse(isSafeBoardRead(DeviceFamily.Jikong, illegal))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
