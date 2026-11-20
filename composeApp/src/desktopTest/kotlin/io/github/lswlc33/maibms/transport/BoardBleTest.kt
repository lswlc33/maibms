package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.protocol.Frame
import io.github.lswlc33.maibms.protocol.ControlCmd
import io.github.lswlc33.maibms.protocol.board.isSafeBoardRead
import io.github.lswlc33.maibms.protocol.board.boardProbeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardBleTest {
    @Test fun namesAndAdvertisedServicesAreHints() {
        assertEquals(DeviceFamily.Jbd, advertisedFamily("custom", listOf("ff00")))
        assertEquals(DeviceFamily.YanYang, advertisedFamily("custom", listOf("0000fee9-0000-1000-8000-00805f9b34fb")))
        assertEquals(DeviceFamily.Unknown, advertisedFamily("custom", listOf("FFE0")))
        assertEquals(DeviceFamily.Jikong, advertisedFamily("custom", listOf("FFE0"), listOf(0x0B65)))
        assertEquals(DeviceFamily.Jikong, advertisedFamily("custom", listOf("FFE0"), listOf(0x4B4A)))
        assertEquals(DeviceFamily.LanBao, advertisedFamily("BlueBabe-1", listOf("FFE0"), listOf(0x0B65)))
        assertEquals(DeviceFamily.Jbd, advertisedFamily("JBD-SP04", listOf("FEE9")))
        assertEquals(DeviceFamily.YanYang, advertisedFamily("YY-123", listOf("FF00")))
        assertEquals(DeviceFamily.Yadi, advertisedFamily("FDA90705", listOf(BleChannel.Yadi.serviceUuid)))
        assertEquals(DeviceFamily.Unknown, advertisedFamily("custom UART", listOf(BleChannel.Yadi.serviceUuid)))
        assertEquals(DeviceFamily.Mingtang, advertisedFamily("custom", listOf("fa00")))
        assertEquals(DeviceFamily.Xiaoha, advertisedFamily("HBB8909006196SOC000", emptyList()))
        assertTrue(BleChannel.Mingtang.discoverByProperties)
        assertEquals(listOf("FA00"), DeviceFamily.Mingtang.serviceUuids)
        assertTrue(BleChannel.YanYangFfe1 in boardBleChannels(DeviceFamily.YanYang))
        assertNull(advertisedFamily("CJ01-EM02", listOf("FFE0")))
        assertNull(advertisedFamily("headphones", emptyList()))
        assertEquals(listOf("FF00", "FFE0"), DeviceFamily.Jbd.serviceUuids)
        assertTrue(sameBleUuid("ffe1", "0000ffe1-0000-1000-8000-00805f9b34fb"))
        assertEquals("D44BC439-ABFD-45A2-B575-925416129600", bleUuid(BleChannel.YanYangFee9.writeUuid))
    }

    @Test fun newBoardsOnlyHaveTheirOwnChannelsAndSafeProbes() {
        for (family in listOf(DeviceFamily.Jbd, DeviceFamily.YanYang, DeviceFamily.Jikong, DeviceFamily.Yadi, DeviceFamily.Mingtang)) {
            assertTrue(boardBleChannels(family).isNotEmpty())
            assertTrue(boardBleChannels(family).none { it == BleChannel.BackupA || it == BleChannel.BackupB })
            assertTrue(isSafeBoardRead(family, boardProbeRequest(family)!!))
            assertFalse(isSafeBoardRead(family, Frame.readRealtime()))
            assertFalse(isSafeBoardRead(family, Frame.control(ControlCmd.CHARGE_ON)))
            assertFalse(isSafeBoardRead(family, Frame.writeParam(0, 4200)))
        }
    }
}
