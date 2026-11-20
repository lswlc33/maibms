package io.github.lswlc33.maibms.protocol.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YadiProtocolTest {
    // 独立计算 CRC 的合成向量。@20=51.10 V，@22 故意不同，SOC=0 但两项容量均非零。
    private val first = ascii(
        "F4033C00000000A00102B3C4D5E6F70300000010325476F61362040000D204F000" +
            "0000000000000000000000000000000000000000D703E02E0000C05D00003B7A",
    )
    private val second = ascii(
        "F4033B2301001800000000480D00003F0DFFFF" + "00".repeat(38) + "8E0DF000006447",
    )
    private val last = ascii("F40305123456789A89A1")

    @Test fun onlyWhitelistedAsciiReadRequestsAreAllowed() {
        for ((address, count, expected) in listOf(
            Triple(0x1000, 60, "F40310003C9445"),
            Triple(0x103C, 59, "F403103C3BC487"),
            Triple(0x1077, 5, "F40310770573A7"),
        )) {
            val frame = YadiProtocol.readFrame(address, count)
            assertEquals(expected, frame.decodeToString())
            assertTrue(YadiProtocol.isReadRequest(frame))
            assertTrue(YadiProtocol.isReadRequest(ascii(expected.lowercase())))
        }
        for (text in listOf(
            "F40610003C9489", // 其他功能码，CRC 正确
            "F403103C3C8545", // 非白名单长度，CRC 正确
            "F403103D3C84D5", // 非白名单地址，CRC 正确
            "F40310003C9545", // CRC 错误
            "F40310003G9445",
            "F40310003C9445\n",
        )) assertFalse(YadiProtocol.isReadRequest(ascii(text)), text)
        assertFailsWith<IllegalArgumentException> { YadiProtocol.readFrame(0x103C, 60) }
    }

    @Test fun fragmentedAndCoalescedFramesPublishOnlyOneCompletePage() {
        val p = YadiProtocol()
        assertTrue(p.onNotification(first, 0).isEmpty())
        assertEquals("F40310003C9445", p.nextRequest(0)?.decodeToString())
        assertNull(p.nextRequest(300), "不得同时发出两条请求")
        val noisy = ascii("\u0000noise!F4F") + ascii(first.decodeToString().lowercase())
        noisy.toList().chunked(7).forEachIndexed { i, chunk ->
            assertTrue(p.onNotification(chunk.toByteArray(), 310L + i).isEmpty())
        }
        assertEquals("F403103C3BC487", p.nextRequest(350)?.decodeToString())
        assertTrue(p.onNotification(first + last + second + last, 370).isEmpty())
        assertFalse(p.snapshotReady)
        assertNull(p.nextRequest(629))
        assertEquals("F40310770573A7", p.nextRequest(630)?.decodeToString())
        assertTrue(p.onNotification(last.copyOfRange(0, 9), 650).isEmpty())
        val r = p.onNotification(last.copyOfRange(9, last.size), 660).single()
        assertTrue(p.snapshotReady)
        assertTrue(r.hasLiveReading)
        assertEquals(51.10, r.totalVoltage)
        assertEquals(0, r.soc)
        assertEquals(0x123, r.cycles)
        val cells = assertNotNull(r.cells)
        assertEquals(24, cells.size)
        assertEquals(listOf(3.400, null, 3.391, null), cells.take(4))
        assertEquals(3.470, cells.last())
        assertEquals(mapOf("protocol" to "YADI-F403", "cellCount" to "24"), r.identity)
        assertNull(r.runtimeSec)
        assertNull(r.current)
        assertNull(r.soh)
        assertNull(r.totalCapAh)
        assertEquals("0xA00102B3", r.settings?.find { it.key == "yadi.protection" }?.value)
        assertEquals("0xE02E0000C05D0000", r.settings?.find { it.key == "yadi.capacity" }?.value)
        assertEquals("F40310003C9445", p.nextRequest(930)?.decodeToString())
    }

    @Test fun lateNotificationsAfterTimeoutCannotCompleteAbandonedPage() {
        val freshFirst = ascii(
            "F4033C00000000A00102B3C4D5E6F70300000010325476871362040000D204F000" +
                "0000000000000000000000000000000000000000D703E02E0000C05D0000D428",
        )
        val p = YadiProtocol()
        p.nextRequest(0)
        p.onNotification(first, 10)
        p.nextRequest(300)
        p.onNotification(second, 310)
        p.nextRequest(600)
        p.onNotification(last.copyOfRange(0, 10), 610)
        assertTrue(p.onNotification(last.copyOfRange(10, last.size), 1900).isEmpty())
        assertNotNull(p.error)
        assertFalse(p.snapshotReady)
        assertTrue(p.onNotification(first + second + last, 4800).isEmpty())
        assertNull(p.nextRequest(4899))
        assertEquals("F40310003C9445", p.nextRequest(4900)?.decodeToString())
        assertTrue(p.onNotification(last + freshFirst, 4910).isEmpty())
        assertEquals("F403103C3BC487", p.nextRequest(5200)?.decodeToString())
        assertTrue(p.onNotification(second, 5210).isEmpty())
        assertEquals("F40310770573A7", p.nextRequest(5500)?.decodeToString())
        assertEquals(49.99, p.onNotification(last, 5510).single().totalVoltage)
        assertTrue(p.snapshotReady)
        assertNull(p.error)
    }

    @Test fun badCrcAbandonsWholePage() {
        val p = YadiProtocol()
        p.nextRequest(0)
        p.onNotification(first, 10)
        p.nextRequest(300)
        val bad = second.copyOf().also { it[it.lastIndex] = '0'.code.toByte() }
        assertTrue(p.onNotification(bad + second, 320).isEmpty())
        assertFalse(p.snapshotReady)
        assertTrue(p.error.orEmpty().contains("CRC"))
        assertNull(p.nextRequest(3319))
        assertEquals("F40310003C9445", p.nextRequest(3320)?.decodeToString())
    }

    @Test fun unexpectedLengthCannotAdvanceAndResetDropsFragments() {
        val p = YadiProtocol()
        p.nextRequest(0)
        assertTrue(p.onNotification(ascii("F40302ABCD8AF4"), 10).isEmpty())
        assertNotNull(p.error)
        p.reset()
        assertNull(p.error)
        assertEquals("F40310003C9445", p.nextRequest(20)?.decodeToString())
        assertTrue(p.onNotification(first.copyOfRange(0, 7), 21).isEmpty())
        p.reset()
        assertFalse(p.snapshotReady)
        assertEquals("F40310003C9445", p.nextRequest(30)?.decodeToString())
        assertTrue(p.onNotification(first.copyOfRange(7, first.size), 31).isEmpty())
        assertNull(p.nextRequest(310))
        assertTrue(p.onNotification(first, 320).isEmpty())
        assertEquals("F403103C3BC487", p.nextRequest(330)?.decodeToString())
    }

    private fun ascii(value: String) = value.encodeToByteArray()
}
