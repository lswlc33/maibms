package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.transport.BoardConnectionProbe
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cell/settings bytes are published captures; password regions in device-info are redacted. */
class Jk04ProtocolTest {
    private fun fixture(key: String): ByteArray {
        val resource = requireNotNull(javaClass.getResourceAsStream("/jk04-frames.json"))
        val root = resource.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        return root.getValue("frames").jsonObject.getValue(key).jsonPrimitive.content
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test fun captureAutomaticallySelectsFloatLayoutAndDoesNotInventBmsReadings() {
        val p = JikongProtocol()
        p.nextRequest(0)
        assertEquals("8.0.3M", p.onNotification(fixture("JK04_device_info"), 10).single().identity["swVersion"])
        assertFalse(p.snapshotReady)
        assertEquals(0x96, p.nextRequest(1010)!![4].toInt() and 255)
        val reading = fixture("JK04_cell").toList().chunked(20)
            .flatMap { p.onNotification(it.toByteArray(), 1030) }.single()
        assertEquals("JK04", reading.identity["protocol"])
        assertEquals(16, reading.cells!!.size)
        assertEquals(53.166, reading.totalVoltage!!, 0.002)
        assertEquals(591338, reading.runtimeSec!!.toInt())
        assertEquals(true, reading.balancing)
        assertNull(reading.current)
        assertNull(reading.power)
        assertNull(reading.soc)
        assertNull(reading.totalCapAh)
        assertNull(reading.chMos)
        assertNull(reading.temps)
        assertFalse(p.caps.mos)
        assertFalse(p.caps.capacity)
        assertFalse(p.caps.stateOfHealth)
        assertTrue(p.caps.runtime)
        assertTrue(reading.hasLiveReading)
        assertTrue(p.snapshotReady)
        assertTrue(reading.settings!!.any { it.key == "jk.voltageSource" })
        assertNotNull(p.nextRequest(1700))
    }

    @Test fun v3DeviceInfoIsAcceptedAndType1OnlyProvidesCellCountHint() {
        val p = JikongProtocol()
        p.nextRequest(0)
        assertEquals("3.3.0", p.onNotification(fixture("JK04_device_info_B2A16S"), 10).single().identity["swVersion"])
        assertTrue(p.onNotification(fixture("JK04_settings_B2A16S"), 20).isEmpty())
        assertFalse(p.snapshotReady)
        p.nextRequest(1010)
        val reading = p.onNotification(fixture("JK04_cell"), 1020).single()
        assertEquals("JK04", reading.identity["protocol"])
        assertEquals(16, reading.cells!!.size)
        val probe = BoardConnectionProbe(DeviceFamily.Jikong, null, null)
        probe.nextRequest(0)
        assertFalse(probe.onNotification(fixture("JK04_device_info"), 10))
        probe.nextRequest(1010)
        assertTrue(probe.onNotification(fixture("JK04_cell"), 1020))
    }

    @Test fun everySplitCrcFailureAndReconnectKeepLayoutIsolated() {
        val info = fixture("JK04_device_info")
        val live = fixture("JK04_cell")
        for (split in 0..live.size) {
            val p = JikongProtocol()
            p.nextRequest(0); p.onNotification(info, 10); p.nextRequest(1010)
            val a = p.onNotification(live.copyOfRange(0, split), 1020)
            val b = p.onNotification(live.copyOfRange(split, live.size), 1021)
            assertEquals(1, a.size + b.size, "split $split")
        }
        val p = JikongProtocol()
        p.nextRequest(0); p.onNotification(info, 10); p.nextRequest(1010)
        assertTrue(p.onNotification(live.copyOf().also { it[299] = 0 }, 1020).isEmpty())
        assertFalse(p.snapshotReady)
        assertTrue(p.onNotification(live, 1030).single().hasLiveReading)
        p.reset()
        assertTrue(p.caps.mos)
        assertTrue(p.onNotification(info + live, 1100).isEmpty())
        assertEquals(0x97, p.nextRequest(1200)!![4].toInt() and 255)
    }
}
