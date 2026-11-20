package io.github.lswlc33.maibms.protocol.board

import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.protocol.Crc16
import io.github.lswlc33.maibms.transport.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 来源黄金向量直接内嵌，不通过待测解析器/组帧器生成期望值。
 * M 型两帧是 NiuHelper BmsDecoderTest.kt 的官方 App 实录；N 型五帧明确为上游 synthetic。
 * 固定提交与完整 URL 见本文件末尾，变异帧仅用于故障/边界测试，不声称真机验证。
 */
class YanYangProtocolTest {
    @Test
    fun requestsMatchPublishedWhitelistAndPollAtMostOneCyclePer500ms() {
        val p = YanYangProtocol()
        assertEquals(DeviceFamily.YanYang, p.family)
        assertEquals(DeviceCapabilities.YanYang, p.caps)
        DEFAULT_FRAMES.forEachIndexed { index, frame ->
            if (index < 3) assertFalse(p.snapshotReady)
            exchange(p, index, index * 2L, frame)
        }
        assertTrue(p.snapshotReady)
        assertNull(p.nextRequest(501))
        exchange(p, 1, 502, REAL_SUMMARY)
        exchange(p, 2, 504, REAL_STATUS)
        assertNull(p.nextRequest(505))
        assertNull(p.nextRequest(1_001))
        exchange(p, 1, 1_002, REAL_SUMMARY)
        exchange(p, 2, 1_004, REAL_STATUS)
        assertNull(p.error)
    }

    @Test
    fun returnedRequestMutationCannotIntroduceWrites() {
        val p = YanYangProtocol()
        val first = assertNotNull(p.nextRequest(0))
        first[1] = 0x11
        assertFalse(isSafeBoardRead(p.family, withCrc(first)))
        assertEquals(1, p.onNotification(PROFILE, 1).size)
        p.reset()
        assertContentEquals(REQUESTS[0], p.nextRequest(2))
        assertNull(p.nextRequest(3))
    }

    @Test
    fun officialMTypeCaptureDecodesKnownOffsetsAndProducesDeltas() {
        val p = afterProfile()
        val base = exchange(p, 1, 2, REAL_SUMMARY)
        assertEquals(49.506, base.totalVoltage)
        assertEquals(0.57, base.current)
        assertEquals(28, base.power)
        assertEquals(43_790_273L, base.runtimeSec)
        assertEquals("YY-BCU13-NIU-M", base.identity["model"])
        assertEquals("YY-BCU13-NIU-M", base.identity["productName"])
        assertEquals("YY-NiuM-73A3", base.identity["deviceName"])
        assertEquals("5911010167E273A3", base.identity["serialNo"])
        assertEquals("1.01", base.identity["hwVersion"])
        assertEquals("1.42", base.identity["swVersion"])
        assertEquals("1.09", base.identity["boot"])
        assertEquals("三元锂", base.identity["batteryType"])
        assertEquals("13", base.identity["cellCount"])
        assertEquals("3", base.identity["temperatureCount"])
        assertTrue(base.hasLiveReading)
        assertNull(base.cells)
        assertNull(base.protectPairs)
        val detail = exchange(p, 2, 4, REAL_STATUS)
        assertNull(detail.totalVoltage, "状态块不伪造一份本次电压")
        assertEquals(0.57, detail.current)
        assertEquals("放电", detail.battState)
        assertEquals(72, detail.soc)
        assertEquals(97, detail.soh)
        assertEquals(68.0, detail.totalCapAh)
        assertEquals(64.6, detail.remainCapAh)
        assertEquals(63, detail.cycles)
        assertEquals(true, detail.chMos)
        assertEquals(true, detail.disMos)
        assertEquals(13, detail.cells!!.size)
        assertEquals(3.808, detail.cells!!.first())
        assertEquals(3.809, detail.cells!!.filterNotNull().max())
        assertEquals(listOf("MOS" to 23.0, "T1" to 22.0, "T2" to 21.0), detail.temps)
        assertEquals(false, detail.balancing)
        assertEquals(0L, detail.balanceBits)
        assertEquals(emptyList(), detail.protectPairs)
        assertEquals(emptyList(), detail.alarmPairs)
        assertTrue(detail.identity.isEmpty())
        assertNull(detail.totalCycleAh, "累计电量单位未确认，不伪造 Ah")
        assertTrue(p.snapshotReady, "实时数据不应等待可选参数/身份响应")
    }

    @Test
    fun nTypePublishedSyntheticFixtureUsesSeparateSensorsAndStateBasedCurrentSign() {
        val p = afterProfile()
        val summary = exchange(p, 1, 2, SYNTHETIC_SUMMARY)
        assertEquals(-5.25, summary.current, "未收到状态字时保留带符号来源值")
        assertEquals(74.0, summary.totalVoltage)
        assertEquals(123_456L, summary.runtimeSec)
        assertEquals("YY-BCU13-NIU-N", summary.identity["model"])
        val status = exchange(p, 2, 4, SYNTHETIC_STATUS)
        assertEquals("放电", status.battState)
        assertEquals(5.25, status.current, "上游 N 型 synthetic 的电流为负，但明确状态为放电")
        assertEquals(389, status.power)
        assertEquals(84, status.soc)
        assertEquals(99, status.soh)
        assertEquals(44.0, status.totalCapAh)
        assertEquals(35.2, status.remainCapAh)
        assertEquals(123, status.cycles)
        assertEquals(42, status.remainChargeMin)
        assertEquals(180, status.remainDischargeMin)
        assertEquals(20, status.cells!!.size)
        assertEquals(3.695, status.cells!![0])
        assertEquals(3.705, status.cells!![19])
        assertEquals((1L shl 19) or 1L, status.balanceBits)
        assertEquals(true, status.balancing)
        assertEquals(listOf("MOS" to 31.0, "均衡" to 30.0, "T1" to 25.0, "T2" to 27.0, "T3" to 28.0, "T4" to 29.0), status.temps)
    }

    @Test
    fun chargeStateSetsNegativeCurrentAndUnknownStateDoesNotGuessDirection() {
        for ((code, expectedCurrent, expectedState) in listOf(
            Triple(0, -5.25, "静置"), Triple(1, -5.25, "充电"),
            Triple(2, 5.25, "放电"), Triple(3, -5.25, "未知"),
        )) {
            val p = afterSummary(SYNTHETIC_SUMMARY)
            val frame = changed(SYNTHETIC_STATUS) { put32(it, 144, (code.toLong() shl 26) or 3L) }
            val reading = exchange(p, 2, 4, frame)
            assertEquals(expectedCurrent, reading.current)
            assertEquals(expectedState, reading.battState)
        }
        val p = afterSummary()
        val charging = exchange(p, 2, 4, changed(REAL_STATUS) { put32(it, 144, (1L shl 26) or 2L) })
        assertEquals(-0.57, charging.current)
        assertEquals(-28, charging.power)
        assertEquals(true, charging.chMos)
        assertEquals(false, charging.disMos)
    }

    @Test
    fun allFivePublishedResponsesAssembleAtEverySplitPoint() {
        DEFAULT_FRAMES.forEachIndexed { index, frame ->
            for (split in 0..frame.size) {
                val p = YanYangProtocol()
                repeat(index) { previous -> exchange(p, previous, previous * 2L, DEFAULT_FRAMES[previous]) }
                assertContentEquals(REQUESTS[index], p.nextRequest(index * 2L))
                val first = p.onNotification(frame.copyOfRange(0, split), index * 2L + 1)
                val second = p.onNotification(frame.copyOfRange(split, frame.size), index * 2L + 1)
                assertEquals(1, first.size + second.size, "块 $index 分割 $split")
                if (split < frame.size) assertTrue(first.isEmpty())
                assertNull(p.error)
            }
        }
    }

    @Test
    fun mtu20FragmentsAndCoalescedFramesKeepTheirOwnBlockIdentity() {
        val p = initialized()
        assertContentEquals(REQUESTS[1], p.nextRequest(502))
        val stream = hex("55 00 01 11 00") + REAL_SUMMARY + REAL_STATUS + REAL_SUMMARY
        val results = stream.toList().chunked(20).flatMap { p.onNotification(it.toByteArray(), 503) }
        assertEquals(3, results.size)
        assertEquals(49.506, results[0].totalVoltage)
        assertNull(results[1].totalVoltage)
        assertEquals(72, results[1].soc)
        assertEquals(49.506, results[2].totalVoltage)
        assertNull(p.error)
        // 下一块的部分响应可以和上一块粘在一起，后续通知续上。
        assertTrue(p.onNotification(REAL_STATUS.copyOfRange(0, 3), 504).isEmpty())
        assertEquals(1, p.onNotification(REAL_STATUS.copyOfRange(3, REAL_STATUS.size), 505).size)
    }

    @Test
    fun crcFailureDoesNotCompletePendingAndNextValidFrameRecovers() {
        val p = afterSummary()
        assertContentEquals(REQUESTS[2], p.nextRequest(4))
        val corrupt = REAL_STATUS.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(p.onNotification(corrupt, 5).isEmpty())
        assertTrue(p.error?.contains("CRC") == true)
        assertNull(p.nextRequest(6))
        assertEquals(1, p.onNotification(REAL_STATUS, 7).size)
        assertNull(p.error)
        assertContentEquals(REQUESTS[3], p.nextRequest(8))
    }

    @Test
    fun noiseForeignAddressFunctionAndFalseLongHeaderCannotBecomeReadings() {
        val p = YanYangProtocol()
        assertContentEquals(REQUESTS[0], p.nextRequest(0))
        val foreign = changed(PROFILE) { it[0] = 2 }
        val foreignFunction = changed(PROFILE) { it[1] = 4 }
        assertTrue(p.onNotification(ByteArray(2_048) { 0x55 } + foreign + foreignFunction + response(ByteArray(9)), 1).isEmpty())
        assertNull(p.nextRequest(2))
        assertEquals(1, p.onNotification(hex("01 03 a2") + PROFILE, 3).size, "CRC 正确的完整帧能从截断的伪长头后重新同步")
        assertNull(p.error)
    }

    @Test
    fun timeoutClearsFragmentsBeforeSameLengthRetry() {
        val p = YanYangProtocol()
        assertContentEquals(REQUESTS[0], p.nextRequest(0))
        assertTrue(p.onNotification(PROFILE.copyOfRange(0, 7), 100).isEmpty())
        assertNull(p.nextRequest(3_099))
        assertContentEquals(REQUESTS[0], p.nextRequest(3_100))
        assertTrue(p.error?.contains("超时") == true)
        assertTrue(p.onNotification(PROFILE.copyOfRange(7, PROFILE.size), 3_101).isEmpty())
        assertNull(p.nextRequest(3_102))
        assertEquals(1, p.onNotification(PROFILE, 3_103).size)
        assertNull(p.error)
        assertContentEquals(REQUESTS[1], p.nextRequest(3_104))
    }

    @Test
    fun lateNotificationAlsoExpiresPendingAndDoesNotSpliceOldFragment() {
        val p = YanYangProtocol()
        p.nextRequest(0)
        assertTrue(p.onNotification(PROFILE.copyOfRange(0, 8), 1).isEmpty())
        assertTrue(p.onNotification(PROFILE.copyOfRange(8, PROFILE.size), 3_001).isEmpty())
        assertTrue(p.error?.contains("超时") == true)
        assertContentEquals(REQUESTS[0], p.nextRequest(3_002))
        assertEquals(1, p.onNotification(PROFILE, 3_003).size)
        assertNull(p.error)
    }

    @Test
    fun lateDifferentLengthBlockCannotReleaseCurrentPending() {
        val p = afterSummary()
        assertContentEquals(REQUESTS[2], p.nextRequest(4))
        assertEquals(49.506, p.onNotification(REAL_SUMMARY, 5).single().totalVoltage)
        assertNull(p.nextRequest(6), "基础迟到帧不能完成状态块请求")
        assertEquals(72, p.onNotification(REAL_STATUS, 7).single().soc)
        assertContentEquals(REQUESTS[3], p.nextRequest(8))
    }

    @Test
    fun completeLateSameBlockIsARecentReadAndPollingContinues() {
        val p = initialized()
        assertContentEquals(REQUESTS[1], p.nextRequest(502))
        assertContentEquals(REQUESTS[1], p.nextRequest(3_502))
        assertTrue(p.error?.contains("超时") == true)
        assertEquals(49.506, p.onNotification(REAL_SUMMARY, 3_503).single().totalVoltage)
        assertNull(p.error)
        assertContentEquals(REQUESTS[2], p.nextRequest(3_504))
        // 同块重试的第二份完整回复仍按长度解析，不得解除状态块 pending。
        assertEquals(1, p.onNotification(REAL_SUMMARY, 3_505).size)
        assertNull(p.nextRequest(3_506))
        assertEquals(1, p.onNotification(REAL_STATUS, 3_507).size)
    }

    @Test
    fun optionalBlockTimeoutRetriesOnceThenLeavesRealtimeUsable() {
        val p = afterSummary()
        exchange(p, 2, 4, REAL_STATUS)
        assertContentEquals(REQUESTS[3], p.nextRequest(6))
        assertContentEquals(REQUESTS[3], p.nextRequest(3_006))
        assertContentEquals(REQUESTS[4], p.nextRequest(6_006))
        assertEquals(1, p.onNotification(IDENTITY, 6_007).size)
        assertTrue(p.snapshotReady)
        exchange(p, 1, 6_008, REAL_SUMMARY)
        exchange(p, 2, 6_010, REAL_STATUS)
        p.refreshSettings()
        exchange(p, 3, 6_012, SETTINGS)
        assertTrue(p.snapshotReady)
        assertNull(p.error)
    }

    @Test
    fun validModbusExceptionReportsCodeAndRecoversWhileBadCrcDoesNotReleasePending() {
        val p = afterSummary()
        p.nextRequest(4)
        val exception = hex("01 83 02 c0 f1") // 标准 Modbus：非法数据地址。
        val bad = exception.copyOf().also { it[4] = 0 }
        assertTrue(p.onNotification(bad, 5).isEmpty())
        assertTrue(p.error?.contains("CRC") == true)
        assertNull(p.nextRequest(6))
        assertTrue(p.onNotification(exception.copyOfRange(0, 2), 7).isEmpty())
        assertTrue(p.onNotification(exception.copyOfRange(2, 5), 8).isEmpty())
        assertTrue(p.error?.contains("0x02") == true)
        exchange(p, 2, 9, REAL_STATUS)
        assertNull(p.error)
    }

    @Test
    fun legacyProbeUsesOwnLayoutAndBadBSignatureStillHalts() {
        val p = YanYangProtocol()
        p.nextRequest(0)
        assertTrue(p.onNotification(changed(PROFILE) { it[3] = 0 }, 1).single().identity.isEmpty())
        assertNull(p.protocolLayoutHint)
        assertEquals(47, p.nextRequest(2)!![5].toInt() and 255)
        assertTrue(p.onNotification(REAL_SUMMARY, 3).isEmpty())
        p.reset()
        exchange(p, 0, 3_000, PROFILE)
        assertContentEquals(REQUESTS[1], p.nextRequest(3_002))
        assertTrue(p.onNotification(changed(REAL_SUMMARY) { it[3 + 124] = 0 }, 3_003).isEmpty())
        assertTrue(p.error?.contains("魔数") == true)
        assertNull(p.nextRequest(5_000))
        for (prefix in listOf("0001", "5948", "0008", "0009", "000A", "010A")) {
            val variant = afterProfile()
            variant.nextRequest(2)
            val frame = changed(REAL_SUMMARY) { hex(prefix).copyInto(it, 5) }
            assertEquals(49.506, variant.onNotification(frame, 3).single().totalVoltage)
            assertNull(variant.error)
            val status = exchange(variant, 2, 4, REAL_STATUS)
            assertTrue(status.temps!!.isEmpty(), "未确认温度布局不影响其他数据")
            assertEquals(72, status.soc)
            assertTrue(variant.snapshotReady)
        }
    }

    @Test
    fun incompatibleCellAndSensorCountsNeverGetClampedIntoAnotherModel() {
        for ((offset, value) in listOf(148 to 0, 148 to 26, 103 to 9, 104 to 2, 105 to 2)) {
            val p = afterProfile()
            p.nextRequest(2)
            assertTrue(p.onNotification(changed(REAL_SUMMARY) { it[3 + offset] = value.toByte() }, 3).isEmpty())
            assertTrue(p.error?.contains("不兼容") == true)
            assertNull(p.nextRequest(2_000))
        }
    }

    @Test
    fun invalidCellsKeepSlotsAndTemperatureSentinelsKeepProbeNames() {
        val p = afterSummary(SYNTHETIC_SUMMARY)
        val frame = changed(SYNTHETIC_STATUS) {
            listOf(0, 20, 35, 36, 5_001, 65_535, 5_000).forEachIndexed { index, raw -> put16(it, 2 + index * 2, raw) }
            it[3 + 64] = 166.toByte(); it[3 + 65] = 0
            it[3 + 66] = 166.toByte(); it[3 + 67] = 40
            it[3 + 68] = 1; it[3 + 69] = 167.toByte()
        }
        val result = exchange(p, 2, 4, frame)
        assertEquals(20, result.cells!!.size)
        assertEquals(List<Double?>(6) { null }, result.cells!!.take(6))
        assertEquals(5.0, result.cells!![6])
        assertEquals(3.7, result.cells!![7])
        assertEquals(listOf("T2" to 0.0, "T3" to -39.0, "T4" to 127.0), result.temps)
    }

    @Test
    fun explicitlyAbsentSensorsClearOldTemperaturesAndUnsignedRuntimeIsPreserved() {
        val noSensors = changed(REAL_SUMMARY) {
            it[3 + 103] = 0; it[3 + 104] = 0; it[3 + 105] = 0
            put32(it, 140, 0xFFFFFFFFL)
        }
        val p = afterProfile()
        assertEquals(4_294_967_295L, exchange(p, 1, 2, noSensors).runtimeSec)
        assertEquals(emptyList(), exchange(p, 2, 4, REAL_STATUS).temps)
    }

    @Test
    fun protectionAlarmAndStateFlagsUsePublishedBitsIncludingUpperFaults() {
        val p = afterSummary()
        val all = changed(REAL_STATUS) { put32(it, 152, 0xFFFFFFFFL); put32(it, 144, 7L); put32(it, 148, 8L) }
        val reading = exchange(p, 2, 4, all)
        assertEquals((0..13).toList() + listOf(17, 18) + (25..31).toList(), reading.protectPairs!!.map { it.first })
        assertEquals(listOf(14) + (19..24).toList(), reading.alarmPairs!!.map { it.first })
        assertEquals(listOf(0, 1, 2, 15, 16, 35), reading.statePairs!!.map { it.first })
        val names = reading.protectPairs!!.toMap()
        assertEquals("充电过流保护", names[5])
        assertEquals("短路保护", names[8])
        assertEquals("充电过温保护", names[9])
        assertEquals("充电低温保护", names[10])
        assertEquals("放电过温保护", names[11])
        assertEquals("放电低温保护", names[12])
        assertEquals("MOS 过温保护", names[13])
        assertEquals("读写失效（EEPROM）", names[26])
        assertEquals("充电输出失效", names[31])
        assertEquals("电量过低告警", reading.alarmPairs!!.first().second)
        val cleared = p.onNotification(changed(REAL_STATUS) {
            put32(it, 152, 0); put32(it, 144, 0); put32(it, 148, 0)
        }, 6).single()
        assertEquals(emptyList(), cleared.protectPairs)
        assertEquals(emptyList(), cleared.alarmPairs)
        assertEquals(emptyList(), cleared.statePairs)
        assertEquals(false, cleared.chMos)
        assertEquals(false, cleared.disMos)
    }

    @Test
    fun settingsOnlyExposeConfirmedThresholdsAndMergeWithoutDuplicateKeys() {
        val p = afterSummary(SYNTHETIC_SUMMARY)
        exchange(p, 2, 4, SYNTHETIC_STATUS)
        val params = exchange(p, 3, 6, SETTINGS)
        val settings = assertNotNull(params.settings).associateBy { it.key }
        assertEquals("4200", settings["yy.cellOvervoltage"]?.value)
        assertEquals("mV", settings["yy.cellOvervoltage"]?.unit)
        assertEquals("84.0", settings["yy.packOvervoltage"]?.value)
        assertEquals("V", settings["yy.packOvervoltage"]?.unit)
        assertEquals("10.0", settings["yy.chargeOvercurrent"]?.value)
        assertEquals("60.0", settings["yy.dischargeOvercurrent"]?.value)
        assertEquals(listOf("60", "50", "-10", "0", "70", "60", "-15", "-5"), (0..7).map { settings["yy.thermal$it"]?.value })
        assertEquals("4000", settings["yy.balanceVoltage"]?.value)
        assertEquals("10", settings["yy.balanceDifference"]?.value)
        assertEquals("YY-BCU13-NIU-N", settings["yy.model"]?.value)
        assertEquals("DEMO0001", params.identity["packSerial"])
        assertTrue(params.current == null && params.totalVoltage == null && params.cells == null)
        val tail = exchange(p, 4, 8, IDENTITY)
        assertTrue(tail.identity.isEmpty(), "重叠且解释不明的厂商文本不当作 SN 展示")
        assertNull(tail.settings)
        assertTrue(p.snapshotReady)
        p.refreshSettings()
        val updated = exchange(p, 3, 10, changed(SETTINGS) { put16(it, 18, 4_250) })
        val merged = assertNotNull(updated.settings)
        assertEquals(merged.size, merged.distinctBy { it.key }.size)
        assertEquals("4250", merged.single { it.key == "yy.cellOvervoltage" }.value)
        assertEquals("YY-BCU13-NIU-N", merged.single { it.key == "yy.model" }.value)
        assertFalse(merged.any { "manufacturer" in it.key || "learned" in it.key || "energy" in it.key || "password" in it.key })
    }

    @Test
    fun refreshDoesNotCancelPendingAndStablePollingOnlyAdds160OnDemand() {
        val p = initialized()
        assertContentEquals(REQUESTS[1], p.nextRequest(502))
        p.refreshSettings(); p.refreshSettings()
        assertNull(p.nextRequest(503))
        assertEquals(1, p.onNotification(REAL_SUMMARY, 504).size)
        exchange(p, 3, 505, SETTINGS)
        exchange(p, 2, 507, REAL_STATUS)
        assertNull(p.nextRequest(509))
        exchange(p, 1, 1_002, REAL_SUMMARY)
        exchange(p, 2, 1_004, REAL_STATUS)
    }

    @Test
    fun resetClearsPendingSnapshotSettingsAndPreviousCurrentDirection() {
        val p = initialized()
        p.nextRequest(502)
        p.onNotification(REAL_SUMMARY.copyOfRange(0, 50), 503)
        p.reset()
        assertFalse(p.snapshotReady)
        assertNull(p.error)
        assertTrue(p.onNotification(REAL_SUMMARY + REAL_STATUS, 504).isEmpty())
        exchange(p, 0, 600, PROFILE)
        val summary = exchange(p, 1, 602, SYNTHETIC_SUMMARY)
        assertEquals(-5.25, summary.current)
        assertFalse(summary.settings.orEmpty().any { it.key == "yy.cellOvervoltage" })
        assertFalse(p.snapshotReady)
    }

    @Test
    fun literalGoldenVectorsHaveTheirPublishedLengthAndCrc() {
        (DEFAULT_FRAMES + SYNTHETIC_SUMMARY + SYNTHETIC_STATUS).forEach { frame ->
            assertEquals((frame[2].toInt() and 0xFF) + 5, frame.size)
            val expected = (frame[frame.size - 2].toInt() and 0xFF) or ((frame.last().toInt() and 0xFF) shl 8)
            assertEquals(expected, Crc16.modbus(frame, 0, frame.size - 2))
            assertContentEquals(frame, withCrc(frame.copyOf()))
        }
        assertEquals(listOf(13, 163, 165, 167, 45), DEFAULT_FRAMES.map { it.size })
    }

    @Test
    fun missingOrUnsupportedProfileFallsBackToSignedSummaryAndSlowFragmentsFinish() {
        for (exception in listOf(false, true)) {
            val p = YanYangProtocol()
            assertContentEquals(REQUESTS[0], p.nextRequest(0))
            if (exception) {
                p.onNotification(hex("01 83 02 c0 f1"), 1)
                assertContentEquals(REQUESTS[0], p.nextRequest(2))
                p.onNotification(hex("01 83 02 c0 f1"), 3)
            } else {
                assertContentEquals(REQUESTS[0], p.nextRequest(3_000))
            }
            assertContentEquals(REQUESTS[1], p.nextRequest(6_000))
            val readings = REAL_SUMMARY.toList().chunked(20).flatMapIndexed { i, part ->
                p.onNotification(part.toByteArray(), 6_100 + i * 450L)
            }
            assertEquals(49.506, readings.single().totalVoltage)
            assertNull(p.error)
            assertContentEquals(REQUESTS[2], p.nextRequest(10_200))
            assertEquals(72, p.onNotification(REAL_STATUS, 10_210).single().soc)
            assertTrue(p.snapshotReady)
        }
    }

    private fun exchange(p: YanYangProtocol, request: Int, now: Long, response: ByteArray): BoardReading {
        val frame = assertNotNull(p.nextRequest(now), "期待寄存器 ${REQUESTS[request][3].toInt() and 0xFF}")
        assertContentEquals(REQUESTS[request], frame)
        assertTrue(isSafeBoardRead(DeviceFamily.YanYang, frame))
        assertEquals(3, frame[1].toInt())
        return p.onNotification(response, now + 1).single()
    }

    private fun afterProfile(): YanYangProtocol = YanYangProtocol().also { exchange(it, 0, 0, PROFILE) }
    private fun afterSummary(summary: ByteArray = REAL_SUMMARY): YanYangProtocol = afterProfile().also { exchange(it, 1, 2, summary) }
    private fun initialized(): YanYangProtocol = YanYangProtocol().also { p ->
        DEFAULT_FRAMES.forEachIndexed { index, frame -> exchange(p, index, index * 2L, frame) }
    }

    private fun changed(frame: ByteArray, block: (ByteArray) -> Unit): ByteArray = withCrc(frame.copyOf().also(block))
    private fun put16(frame: ByteArray, offset: Int, raw: Int) {
        frame[offset + 3] = raw.toByte(); frame[offset + 4] = (raw ushr 8).toByte()
    }
    private fun put32(frame: ByteArray, offset: Int, raw: Long) {
        repeat(4) { frame[offset + 3 + it] = (raw ushr (it * 8)).toByte() }
    }
    private fun response(payload: ByteArray): ByteArray = withCrc(byteArrayOf(1, 3, payload.size.toByte()) + payload + byteArrayOf(0, 0))

    /** 边界用例使用独立位运算 CRC；黄金帧自身含有公开来源的固定 CRC。 */
    private fun withCrc(frame: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (index in 0 until frame.size - 2) {
            crc = crc xor (frame[index].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        frame[frame.size - 2] = crc.toByte(); frame[frame.size - 1] = (crc ushr 8).toByte()
        return frame
    }

    private companion object {
        private fun hex(text: String): ByteArray = text.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // https://github.com/guilhem/yanyang-bms-web/blob/b5e39c5eae1ab738bb8976743905fbbc20f7d4c4/tests/fixtures/read-requests.json
        val REQUESTS = listOf(
            hex("01 03 00 3f 00 04 74 05"), hex("01 03 00 01 00 4f 55 fe"),
            hex("01 03 00 50 00 50 45 e7"), hex("01 03 00 a0 00 51 84 14"), hex("01 03 00 f0 00 14 45 f6"),
        )

        // 官方 App 实录（YY-BCU13-NIU-M），逐字节保留含 CRC，未修改温度或状态。
        // https://github.com/xiaodl-top/NiuHelper/blob/6ebda6869211ac94a45d4ca837a294669a8319b0/app/src/test/java/top/xiaodl/niuhelper/bms/BmsDecoderTest.kt
        val REAL_SUMMARY = hex(
            "01039E65005911010167E273A3010059592D42435531332D4E49552D4D00000000000000000000000059592D42435531" +
                "332D4E49552D4D000000000000000000000000000000000000000000006500E907031959592D4E69754D2D3733413331" +
                "3032340100050000000002010003000601090F46000300FF07FF0000006D8E5342444D1903010E27330000C37DAD02C1" +
                "2F9C02010000000D0062C10000390000006348"
        )
        val REAL_STATUS = hex(
            "0103A01900E00EE00EE00EE00EE00EE00EE00EE10EE00EE00EE00EE00EE00E0000000000000000000000000000000000" +
                "00000000000000F401881300002D0FEB0E07013F3D3E3D0000003E3D000100A802860248611700000088010B14000000" +
                "00003F93005401F1FD000025FF000000000000000000000000000000003C0F1400041000000000000000000000070000" +
                "00E41953024008020000000000000000000000BC05"
        )

        // 以下五帧是上游明确标注 evidence=synthetic 的 NIU-N 周期，绝非真机抓包。
        // https://github.com/guilhem/yanyang-bms-web/blob/b5e39c5eae1ab738bb8976743905fbbc20f7d4c4/tests/fixtures/cycle-synthetic.json
        val PROFILE = hex("01 03 08 53 42 44 4d 00 00 00 00 91 b0")
        val SYNTHETIC_SUMMARY = hex(
            "01 03 9e 65 00 01 02 03 04 05 06 07 08 00 00 59 59 2d 42 43 55 31 33 2d 4e 49 55 2d 4e 00 00 00 00 00 00 00 00 00 00 00 00 59 59 2d 42 43 55 31 33 2d 4e 49 55 2d 4e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 c9 00 00 00 00 00 59 59 2d 4e 69 75 4e 2d 44 45 4d 4f 00 00 00 00 00 00 00 00 00 00 00 04 01 01 00 00 00 00 00 19 e0 2e 03 02 10 01 00 00 00 00 64 7e 53 42 44 4d 1a 09 09 0c 00 00 00 00 00 00 00 00 40 e2 01 00 03 00 00 00 14 00 10 21 01 00 f3 fd ff ff 0d bb"
        )
        val SYNTHETIC_STATUS = hex(
            "0103a07bfe6f0e740e740e740e740e740e740e740e740e740e740e740e740e740e740e740e740e740e740e790e000000" +
                "00000000000000000000000000000000001300474641434445000000000000b80160015463280a00002a00b4000200ae" +
                "01017b39300000d2049cff290938ff0000000000000000000001000800a00f0a00140000000000000000000000000002" +
                "014000030000080000000000000000000000009665"
        )
        val SETTINGS = hex(
            "01 03 a2 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 68 10 04 10 00 00 b8 0b 80 0c 00 00 f0 0a 00 00 00 00 d0 20 08 20 00 00 70 17 00 19 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 e8 03 00 00 00 00 70 17 64 00 00 00 00 00 00 00 00 00 64 5a 1e 28 6e 64 19 23 78 00 00 00 00 00 44 45 4d 4f 30 30 30 31 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0a 00 14 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 9d a2"
        )
        val IDENTITY = hex("01 03 28 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 53 59 4e 54 48 45 54 49 43 2d 30 30 30 30 30 31 00 00 00 00 67 99")
        val DEFAULT_FRAMES = listOf(PROFILE, REAL_SUMMARY, REAL_STATUS, SETTINGS, IDENTITY)
    }
}
