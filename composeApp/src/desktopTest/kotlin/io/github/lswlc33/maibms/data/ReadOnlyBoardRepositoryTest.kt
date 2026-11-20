package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.ControlCmd
import io.github.lswlc33.maibms.protocol.Frame
import io.github.lswlc33.maibms.protocol.FrameParser
import io.github.lswlc33.maibms.protocol.Proto
import io.github.lswlc33.maibms.protocol.board.BoardReading
import io.github.lswlc33.maibms.protocol.board.BoardSetting
import io.github.lswlc33.maibms.protocol.board.isSafeBoardRead
import io.github.lswlc33.maibms.transport.BmsTransport
import io.github.lswlc33.maibms.transport.DeviceFamily
import io.github.lswlc33.maibms.transport.LinkState
import io.github.lswlc33.maibms.transport.MockBmsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 彦阳/嘉佰达只读板的真实仓库与数据合并回归。 */
class ReadOnlyBoardRepositoryTest {

    /** 每个用例独立的 KV，避免覆盖桌面测试进程的共享 MemoryStore。 */
    private class TestStore : KeyValueStore {
        private val values = ConcurrentHashMap<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private data class Sent(val address: String?, val frame: ByteArray)

    /**
     * 只负责模拟 GATT 的连接、写入和通知分片；请求编排、协议解析和 UI 合并全部走生产代码。
     */
    private class RecordingBoardTransport(
        val antEngines: MutableMap<String, MockBmsEngine> = mutableMapOf(),
    ) : BmsTransport {
        override val linkState = MutableStateFlow(LinkState.Idle)
        override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
        override val currentFamily = MutableStateFlow(DeviceFamily.Unknown)

        private val sent = mutableListOf<Sent>()
        private val targetFamilies = mutableListOf<DeviceFamily>()
        @Volatile private var address: String? = null

        /** 切设备用：让新设备首个请求停住，先观察仓库是否已清掉旧 UI。 */
        @Volatile var blockAddress: String? = null
        @Volatile var blockNextWrite: Boolean = false
        val blockEntered = CompletableDeferred<Unit>()
        val releaseBlock = CompletableDeferred<Unit>()

        override fun setTargetFamily(family: DeviceFamily) {
            currentFamily.value = family
            synchronized(targetFamilies) { targetFamilies += family }
        }

        override suspend fun connect(address: String?) {
            this.address = address
            linkState.value = LinkState.Connecting
            linkState.value = LinkState.Connected
        }

        override suspend fun disconnect() {
            linkState.value = LinkState.Disconnected
        }

        override suspend fun write(frame: ByteArray) {
            if (linkState.value != LinkState.Connected) return
            val target = address
            synchronized(sent) { sent += Sent(target, frame.copyOf()) }

            if (target == blockAddress && blockNextWrite) {
                blockNextWrite = false
                blockEntered.complete(Unit)
                releaseBlock.await()
            }

            when (currentFamily.value) {
                DeviceFamily.Jbd -> {
                    val command = frame.getOrNull(2)?.toInt()?.and(0xFF) ?: return
                    val response = when (command) {
                        3 -> JBD_BASIC_INFO
                        4 -> JBD_CELL_INFO
                        5 -> jbdResponse(5, "JBD-TEST".encodeToByteArray())
                        else -> null
                    }
                    response?.let { emitFragments(it) }
                }

                DeviceFamily.Ant -> {
                    val request = FrameParser().feed(frame).firstOrNull() ?: return
                    val response = target?.let { antEngines[it]?.handle(request) }
                    response?.let { emitFragments(it) }
                }

                else -> Unit
            }
        }

        fun writes(): List<Sent> = synchronized(sent) { sent.toList() }

        fun clearWrites() = synchronized(sent) { sent.clear() }

        fun requestCommands(): List<Int> = writes().mapNotNull { sent ->
            sent.frame.getOrNull(2)?.toInt()?.and(0xFF)
                ?.takeIf { sent.frame.getOrNull(0)?.toInt()?.and(0xFF) == 0xDD }
        }

        fun configuredFamilies(): List<DeviceFamily> = synchronized(targetFamilies) { targetFamilies.toList() }

        private suspend fun emitFragments(frame: ByteArray) {
            // 非固定通知边界，专门覆盖分片、粘包状态机不能依赖 MTU 的回归。
            val sizes = intArrayOf(1, 2, 5, 3, 7, 11)
            var offset = 0
            var pattern = 0
            while (offset < frame.size) {
                val end = minOf(frame.size, offset + sizes[pattern % sizes.size])
                incoming.emit(frame.copyOfRange(offset, end))
                offset = end
                pattern++
                yield()
            }
        }
    }

    private fun withRepository(
        body: suspend (CoroutineScope, BmsRepository, RecordingBoardTransport, MockBmsEngine) -> Unit,
    ) = runBlocking {
        val oldStore = AppStore.store
        val oldForeground = AppForeground.active.value
        val oldSavedAddress = MockBms.savedAddress
        val oldConnectedName = MockBms.connectedDeviceName.value
        val oldUsingRealBle = MockBms.usingRealBle.value
        val worker = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        var repo: BmsRepository? = null
        try {
            AppStore.store = TestStore()
            DeviceProfiles.resetForTest()
            AppStore.snapshotEnabled = false
            AppStore.autoReconnect = false
            AppStore.backgroundMonitor = false
            AppStore.pollIntervalMs = 300
            AppForeground.setActive(true)
            MockBms.clearForRealDevice()
            MockBms.savedAddress = null
            MockBms.plainPasswords.value = emptyMap()
            MockBms.modifying.value = false

            val antEngine = MockBmsEngine(worker)
            val transport = RecordingBoardTransport()
            val created = BmsRepository(worker, antEngine)
            repo = created
            created.setRealTransport(transport)
            created.start()
            assertTrue(
                awaitTrue(5_000) { transport.incoming.subscriptionCount.value > 0 },
                "仓库通知订阅应就绪",
            )
            body(worker, created, transport, antEngine)
        } finally {
            runCatching { repo?.disconnect() }
            worker.coroutineContext[Job]?.cancelAndJoin()
            DeviceProfiles.resetForTest()
            AppStore.store = oldStore
            AppForeground.setActive(oldForeground)
            MockBms.clearForRealDevice()
            MockBms.savedAddress = oldSavedAddress
            MockBms.connectedDeviceName.value = oldConnectedName
            MockBms.usingRealBle.value = oldUsingRealBle
            MockBms.plainPasswords.value = emptyMap()
            MockBms.modifying.value = false
        }
    }

    private fun withIsolatedState(body: () -> Unit) {
        val oldStore = AppStore.store
        val oldSavedAddress = MockBms.savedAddress
        val oldConnectedName = MockBms.connectedDeviceName.value
        val oldUsingRealBle = MockBms.usingRealBle.value
        try {
            AppStore.store = TestStore()
            DeviceProfiles.resetForTest()
            MockBms.clearForRealDevice()
            MockBms.savedAddress = null
            MockBms.connectedDeviceName.value = null
            MockBms.plainPasswords.value = emptyMap()
            body()
        } finally {
            DeviceProfiles.resetForTest()
            AppStore.store = oldStore
            MockBms.clearForRealDevice()
            MockBms.savedAddress = oldSavedAddress
            MockBms.connectedDeviceName.value = oldConnectedName
            MockBms.usingRealBle.value = oldUsingRealBle
            MockBms.plainPasswords.value = emptyMap()
            MockBms.modifying.value = false
        }
    }

    private suspend fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            delay(20)
        }
        return condition()
    }

    @Test
    fun unknownDeviceMustSelectBrandAndNeverFallsBackToAnt() =
        withRepository { _, repo, transport, _ ->
            repo.connectTo("custom-address")
            assertTrue(transport.configuredFamilies().isEmpty())
            assertTrue(transport.writes().isEmpty())
            assertTrue(repo.connectHint.value?.contains("品牌") == true)
            assertNull(DeviceProfiles.find("custom-address"))
            repo.connectTo("custom-address", DeviceFamily.Jbd)
            assertTrue(awaitTrue(5_000) { MockBms.status.value.hasReading("voltage") })
            assertEquals(DeviceFamily.Jbd, repo.currentFamily.value)
            assertEquals(DeviceFamily.Jbd, DeviceProfiles.find("custom-address")?.family)
        }

    @Test
    fun brandsWithoutVerifiedBleProtocolDoNotConnectOrSendAntFrames() =
        withRepository { _, repo, transport, _ ->
            for (family in listOf(DeviceFamily.Xingheng, DeviceFamily.Zhiba, DeviceFamily.Shanchong, DeviceFamily.Xiaoha)) {
                val address = "unverified-${family.name}"
                repo.connectTo(address, family)
                assertTrue(transport.configuredFamilies().isEmpty())
                assertTrue(transport.writes().isEmpty())
                assertEquals(family.connectionSupportHint, repo.connectHint.value)
                assertNull(DeviceProfiles.find(address))
            }
        }

    @Test
    fun jbdGoldenFramesAreFragmentedIntoUiAndPollingContinuesReadOnly() =
        withRepository { _, repo, transport, _ ->
            val address = "JBD:00:00:00:00:01"
            DeviceProfiles.upsert(DeviceProfile(address, "JBD-TEST", family = DeviceFamily.Jbd))
            repo.connectTo(address, DeviceFamily.Jbd)

            assertTrue(
                awaitTrue(5_000) {
                    MockBms.status.value.hasReading("voltage") &&
                        MockBms.status.value.cells.size == 4
                },
                "JBD 03/04 黄金帧应经真实仓库分片回填仪表",
            )
            val status = MockBms.status.value
            assertEquals(15.6, status.totalVoltage, 1e-9)
            assertEquals(2.87, status.current, 1e-9, "JBD 放电负号应转换为本项目的放电正号")
            assertEquals(100, status.soc)
            assertEquals(42, status.cycles)
            assertEquals(5.0, status.totalCapAh, 1e-9)
            assertEquals(4.98, status.remainCapAh, 1e-9)
            assertEquals(
                listOf(3.430, 3.425, 3.432, 3.417),
                status.cells.map { it.volt },
            )
            assertEquals(listOf(1, 2, 3, 4), status.cells.map { it.index })
            assertEquals(listOf(22.4, 22.3, 21.7), status.temps.map { it.second })
            assertTrue(status.hasReading("capacity"))

            assertTrue(
                awaitTrue(5_000) { transport.requestCommands().count { it == 3 } >= 2 },
                "JBD 首轮后仍应继续刷新基础读数",
            )

            // 只读家族误入旧命令入口时，任何新增写入都必须被仓库门禁挡住。
            transport.clearWrites()
            assertEquals(BmsRepository.WRITE_READ_ONLY, repo.writeParam(0, 4300))
            assertEquals(BmsRepository.WRITE_READ_ONLY, repo.control(ControlCmd.CHARGE_ON))
            assertEquals(BmsRepository.WRITE_READ_ONLY, repo.saveAllParams())
            assertEquals(0, repo.auth(3, "12345678"))
            assertNull(
                repo.requestAndAwait(
                    Frame.control(ControlCmd.CHARGE_ON),
                    Proto.RSP_CONTROL,
                    expectedReg = ControlCmd.CHARGE_ON,
                ),
            )

            assertTrue(
                awaitTrue(3_000) { transport.writes().isNotEmpty() },
                "只读板的轮询应继续运行",
            )
            assertTrue(
                transport.writes().all { isSafeBoardRead(DeviceFamily.Jbd, it.frame) },
                "只读板轮询只能发送 DD A5 03/04/05 读取帧",
            )
        }

    @Test
    fun boardBasicAndCellReadingsRetainBalanceBitsAndCellSlots() = withIsolatedState {
        MockBms.applyBoardReading(
            BoardReading(
                totalVoltage = 48.0,
                current = 0.0,
                soc = 0,
                balanceBits = 0b1010L,
            ),
        )
        MockBms.applyBoardReading(
            BoardReading(cells = listOf(3.400, null, 0.0, 3.300)),
        )

        val status = MockBms.status.value
        assertEquals(listOf(1, 2, 3, 4), status.cells.map { it.index })
        assertFalse(status.cells[0].balancing)
        assertTrue(status.cells[1].balancing, "基础帧的第 2 串均衡位不能被单体帧清掉")
        assertFalse(status.cells[2].balancing)
        assertTrue(status.cells[3].balancing)
        assertTrue(status.cells[1].missing, "未接串必须保留原始串号并显式标记")
        assertEquals(0.0, status.cells[2].volt, 1e-9, "有效的 0.0 不能被当成缺失")
        assertTrue(status.hasReading("voltage"))
        assertTrue(status.hasReading("current"))
        assertTrue(status.hasReading("soc"))
        assertFalse(status.hasReading("capacity"), "未收到容量时不能用默认 0 冒充读数")
    }

    @Test
    fun zeroBoardFieldsAreAvailableWhenActuallyReceived() = withIsolatedState {
        MockBms.applyBoardReading(
            BoardReading(
                totalVoltage = 0.0,
                current = 0.0,
                power = 0,
                soc = 0,
                totalCapAh = 0.0,
                remainCapAh = 0.0,
                soh = 0,
                cycles = 0,
                totalCycleAh = 0,
                chMos = false,
                disMos = false,
                balancing = false,
                balanceBits = 0L,
            ),
        )

        val status = MockBms.status.value
        assertTrue(status.hasData)
        assertTrue(status.hasReading("voltage"))
        assertTrue(status.hasReading("current"))
        assertTrue(status.hasReading("power"))
        assertTrue(status.hasReading("soc"))
        assertTrue(status.hasReading("capacity"))
        assertTrue(status.hasReading("cycles"))
        assertEquals(0.0, status.totalVoltage, 1e-9)
        assertEquals(0.0, status.current, 1e-9)
        assertEquals(0, status.soc)
        assertEquals(0.0, status.totalCapAh, 1e-9)
        assertEquals(0, status.cycles)
    }

    @Test
    fun snapshotRestoresReadOnlyMetadataAndLegacySnapshotDefaults() = withIsolatedState {
        val setting = BoardSetting("jbd.model", "型号", "JBD-TEST")
        val snapshot = BmsSnapshot(
            id = 1001L,
            deviceAddress = "JBD:SNAPSHOT",
            deviceName = "JBD-TEST",
            timeLabel = "11-16 12:00",
            status = BmsStatus(
                deviceName = "JBD-TEST",
                connected = false,
                hasData = true,
                totalVoltage = 15.6,
                current = 2.87,
                availableFields = setOf("voltage", "current", "capacity"),
                cells = listOf(CellV(1, 3.430, balancing = true)),
            ),
            liveParams = emptyMap(),
            identity = mapOf("serialNo" to "SNAP-01"),
            family = DeviceFamily.Jbd,
            capabilities = DeviceCapabilities.Jbd,
            boardSettings = listOf(setting),
        )

        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        MockBms.restoreSnapshot(decoded!!)
        assertEquals(DeviceFamily.Jbd, MockBms.family.value)
        assertEquals(DeviceCapabilities.Jbd, MockBms.caps.value)
        assertEquals(listOf(setting), MockBms.boardSettings.value)
        assertTrue(MockBms.status.value.hasReading("capacity"))

        // 旧版本没有 family/capabilities/boardSettings/availableFields 字段。
        val legacy = SnapshotCodec.decode(
            """
            {"id":7,"deviceAddress":"ANT:OLD","deviceName":"ANT-OLD","timeLabel":"10-01 10:00",
             "status":{},"liveParams":{},"identity":{}}
            """.trimIndent(),
        )
        assertNotNull(legacy)
        assertEquals(DeviceFamily.Unknown, legacy.family)
        assertNull(legacy.capabilities)
        assertTrue(legacy.boardSettings.isEmpty())
        assertNull(legacy.status.availableFields)
        MockBms.restoreSnapshot(legacy)
        assertEquals(DeviceFamily.Ant, MockBms.family.value, "旧 ANT 快照仍按设备名回退")
        assertEquals(DeviceCapabilities.Board, MockBms.caps.value)
        assertTrue(MockBms.boardSettings.value.isEmpty())
    }

    @Test
    fun manualFamilyOverrideIsSavedAndUsedByReconnect() = withRepository { _, repo, transport, _ ->
        val address = "UNKNOWN:BOARD:01"
        DeviceProfiles.upsert(DeviceProfile(address, "generic-bms", family = DeviceFamily.Unknown))

        repo.connectTo(address, DeviceFamily.Jbd)
        assertTrue(awaitTrue(5_000) { transport.requestCommands().contains(3) })
        assertEquals(DeviceFamily.Jbd, DeviceProfiles.find(address)?.family)
        assertEquals(DeviceFamily.Jbd, repo.currentFamily.value)
        repo.disconnect()

        transport.clearWrites()
        repo.connectTo(address)
        assertTrue(
            awaitTrue(5_000) {
                repo.currentFamily.value == DeviceFamily.Jbd &&
                    transport.configuredFamilies().count { it == DeviceFamily.Jbd } >= 2 &&
                    transport.requestCommands().isNotEmpty()
            },
            "手动选择的家族应随设备档案用于重连",
        )
        assertTrue(transport.writes().all { isSafeBoardRead(DeviceFamily.Jbd, it.frame) })
    }

    @Test
    fun switchingFromNewJbdToOldAntClearsUiBeforeNewFramesArrive() = withRepository { worker, repo, transport, _ ->
        val newAddress = "JBD:NEW:01"
        val oldAddress = "ANT:OLD:01"
        val oldEngine = MockBmsEngine(worker)
        transport.antEngines[oldAddress] = oldEngine
        DeviceProfiles.upsert(DeviceProfile(newAddress, "JBD-NEW", family = DeviceFamily.Jbd))
        DeviceProfiles.upsert(DeviceProfile(oldAddress, "ANT-OLD", family = DeviceFamily.Ant))

        repo.connectTo(newAddress, DeviceFamily.Jbd)
        assertTrue(
            awaitTrue(5_000) {
                MockBms.family.value == DeviceFamily.Jbd &&
                    MockBms.status.value.cells.size == 4
            },
        )

        transport.blockAddress = oldAddress
        transport.blockNextWrite = true
        repo.connectTo(oldAddress, DeviceFamily.Ant)
        assertTrue(awaitTrue(5_000) { transport.blockEntered.isCompleted }, "旧板首个请求应进入屏障")

        assertFalse(MockBms.status.value.hasData, "切设备后新帧到达前不能继续显示旧板数据")
        assertTrue(MockBms.status.value.cells.isEmpty())
        assertEquals(DeviceFamily.Ant, MockBms.family.value)
        assertEquals(oldAddress, MockBms.savedAddress)

        transport.releaseBlock.complete(Unit)
        assertTrue(
            awaitTrue(5_000) {
                MockBms.status.value.hasData &&
                    MockBms.status.value.deviceName == "ANT-OLD" &&
                    MockBms.status.value.cells.size == oldEngine.cellCount
            },
            "旧蚂蚁板首帧应在真实仓库路径回填自己的 UI",
        )
        assertEquals(DeviceCapabilities.Board, MockBms.caps.value)
        assertTrue(MockBms.status.value.cells.size != 4)
    }

    private companion object {
        /** aiobmsble tests/bms/test_jbd_bms.py 的固定 JBD 03 黄金帧。 */
        val JBD_BASIC_INFO = hexStr(
            "DD 03 00 1D 06 18 FE E1 01 F2 01 F4 00 2A 2C 7C " +
                "00 00 00 00 00 00 80 64 03 04 03 0B 8B 0B 8A 0B 84 F8 84 77",
        )

        /** aiobmsble tests/bms/test_jbd_bms.py 的固定 JBD 04 单体帧。 */
        val JBD_CELL_INFO = hexStr(
            "DD 04 00 08 0D 66 0D 61 0D 68 0D 59 FE 3C 77",
        )

        fun hexStr(text: String): ByteArray = text.trim()
            .split(Regex("\\s+"))
            .map { it.toInt(16).toByte() }
            .toByteArray()

        /** JBD 响应校验覆盖 status + length + payload，线上为高字节在前。 */
        fun jbdResponse(command: Int, payload: ByteArray): ByteArray {
            val body = ByteArray(payload.size + 4)
            body[0] = 0xDD.toByte()
            body[1] = command.toByte()
            body[2] = 0
            body[3] = payload.size.toByte()
            payload.copyInto(body, 4)
            val sum = (2 until body.size).sumOf { body[it].toInt() and 0xFF }
            val checksum = (-sum) and 0xFFFF
            return body + byteArrayOf(
                (checksum ushr 8).toByte(),
                checksum.toByte(),
                0x77,
            )
        }
    }
}
