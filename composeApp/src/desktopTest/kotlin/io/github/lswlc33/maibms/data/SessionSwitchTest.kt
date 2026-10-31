package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.FrameParser
import io.github.lswlc33.maibms.protocol.ParsedFrame
import io.github.lswlc33.maibms.protocol.Proto
import io.github.lswlc33.maibms.transport.BmsTransport
import io.github.lswlc33.maibms.transport.DeviceFamily
import io.github.lswlc33.maibms.transport.LinkState
import io.github.lswlc33.maibms.transport.MockBmsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真实仓库的 A→B 读到一半切换：会话、参数与落盘缓存都必须隔离。 */
class SessionSwitchTest {
    private val addrA = "AA:00:00:00:00:0A"
    private val addrB = "BB:00:00:00:00:0B"

    private class TestStore : KeyValueStore {
        private val values = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private data class Sent(val address: String?, val request: ParsedFrame)

    /** 屏障只控制真实 Flow/传输时序，不复制仓库的刷新或会话逻辑。 */
    private class TwoDeviceTransport(
        private val engines: Map<String, MockBmsEngine>,
        private val addrA: String,
        private val addrB: String,
    ) : BmsTransport {
        private val state = MutableStateFlow(LinkState.Idle)
        private val observed = mutableListOf<LinkState>()
        val initialConnectedObserved = CompletableDeferred<Unit>()
        val releaseStateCollector = CompletableDeferred<Unit>()
        val oldReadEntered = CompletableDeferred<Unit>()
        val oldReadCancelled = CompletableDeferred<Unit>()
        val newReadEntered = CompletableDeferred<Unit>()
        val releaseNewRead = CompletableDeferred<Unit>()
        private var heldInitialConnected = false
        override val linkState: Flow<LinkState> = flow {
            state.collect { st ->
                synchronized(observed) { observed += st }
                emit(st)
                if (st == LinkState.Connected && !heldInitialConnected) {
                    heldInitialConnected = true
                    initialConnectedObserved.complete(Unit)
                    // 冻结链路收集器直到 B connect 完成，确定地让
                    // Connected→Disconnected→Connected 的中间值被 StateFlow 合并。
                    releaseStateCollector.await()
                }
            }
        }
        override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        @Volatile private var address: String? = null
        private val writes = mutableListOf<Sent>()

        override suspend fun connect(address: String?) {
            this.address = address
            state.value = LinkState.Connected
        }

        override suspend fun disconnect() { state.value = LinkState.Disconnected }

        override suspend fun write(frame: ByteArray) {
            if (state.value != LinkState.Connected) return
            val target = address
            for (req in FrameParser().feed(frame)) {
                synchronized(writes) { writes += Sent(target, req) }
                val resp = engines[target]?.handle(req) ?: continue
                if (target == addrA && req.func == Proto.FC_PARAM && req.reg == 56) {
                    oldReadEntered.complete(Unit)
                    try {
                        // A 已累计首块 3650，第二块必须被生产 invalidateSession 取消。
                        awaitCancellation()
                    } finally {
                        oldReadCancelled.complete(Unit)
                    }
                }
                if (target == addrB && req.func == Proto.FC_PARAM && req.reg == 0) {
                    newReadEntered.complete(Unit)
                    releaseNewRead.await()
                }
                // 仍走 20 字节分片重组；yield 让收集器运行，不依赖毫秒级 sleep。
                for (offset in resp.indices step 20) {
                    incoming.emit(resp.copyOfRange(offset, minOf(offset + 20, resp.size)))
                    yield()
                }
            }
        }

        fun requests(address: String, func: Int): List<ParsedFrame> = synchronized(writes) {
            writes.filter { it.address == address && it.request.func == func }.map { it.request }
        }
        fun observedStates(): List<LinkState> = synchronized(observed) { observed.toList() }
    }

    private suspend fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            delay(20)
        }
        return cond()
    }

    private suspend fun awaitSignal(signal: CompletableDeferred<Unit>, label: String) {
        assertTrue(awaitTrue(5_000) { signal.isCompleted }, "$label 屏障应已到达")
        signal.await()
    }

    @Test fun switchingDeviceAbortsTheOldReadAndKeepsOnlyTheNewDeviceData() = runBlocking {
        val oldStore = AppStore.store
        val oldForeground = AppForeground.active.value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            AppStore.store = TestStore()
            DeviceProfiles.resetForTest()
            AppStore.snapshotEnabled = false
            AppStore.autoReconnect = false
            AppStore.backgroundMonitor = false
            AppStore.pollIntervalMs = 300
            AppForeground.setActive(true)
            MockBms.clearForRealDevice()
            MockBms.plainPasswords.value = emptyMap()

            val engineA = MockBmsEngine(scope).apply { paramStore[0] = 3650; currentPermission = 5 }
            val engineB = MockBmsEngine(scope).apply { paramStore[0] = 4210; currentPermission = 5 }
            DeviceProfiles.upsert(DeviceProfile(addrA, "A 板", lastConnectedAt = epochMillisNow(), family = DeviceFamily.Ant))
            DeviceProfiles.upsert(DeviceProfile(addrB, "B 板", lastConnectedAt = epochMillisNow(), family = DeviceFamily.Ant))
            val transport = TwoDeviceTransport(mapOf(addrA to engineA, addrB to engineB), addrA, addrB)
            val repo = BmsRepository(scope, engineA)
            repo.setRealTransport(transport)
            repo.start()
            assertTrue(awaitTrue(5_000) { transport.incoming.subscriptionCount.value > 0 }, "通知订阅应就绪")
            repo.connect(addrA)
            awaitSignal(transport.initialConnectedObserved, "A Connected")
            awaitSignal(transport.oldReadEntered, "A 第二参数块")
            assertTrue(MockBms.paramsReading.value)
            assertEquals(listOf(0, 56), transport.requests(addrA, Proto.FC_PARAM).map { it.reg })
            assertNull(AppStore.loadParamsCache(addrA), "A 尚未读完，不能提前落缓存")

            repo.connectTo(addrB)
            awaitSignal(transport.oldReadCancelled, "A 读取取消")
            // 不手动 refresh：必须由生产的新会话首帧自动启动 B 的读取。
            awaitSignal(transport.newReadEntered, "B 首参数块")
            assertTrue(MockBms.paramsReading.value)
            assertTrue(MockBms.liveParams.value.isEmpty(), "B 未完成时不能发布 A 的旧 acc")
            assertEquals(addrB, MockBms.savedAddress)
            assertNull(AppStore.loadParamsCache(addrB), "B 尚未完成不能落缓存")
            assertEquals(listOf(LinkState.Connected), transport.observedStates().filter { it != LinkState.Idle },
                "确定性回归：仓库尚未收到 B 的新 Connected 事件也必须读参数")
            transport.releaseStateCollector.complete(Unit)
            transport.releaseNewRead.complete(Unit)

            val expectedBlocks = listOf(0 to 52, 56 to 44, 104 to 32, 140 to 12, 152 to 142,
                298 to 32, 374 to 66, 592 to 28, 700 to 8,
                528 to 16, 576 to 16, 620 to 16, 636 to 16, 262 to 32)
            assertTrue(awaitTrue(20_000) {
                transport.requests(addrB, Proto.FC_PARAM).size >= expectedBlocks.size && !MockBms.paramsReading.value
            }, "B 应完成整轮参数/身份读取（请求 ${transport.requests(addrB, Proto.FC_PARAM).size} 条，reading=${MockBms.paramsReading.value}）")
            assertEquals(expectedBlocks, transport.requests(addrB, Proto.FC_PARAM).map { it.reg to it.lengthField })
            assertEquals(4210, MockBms.liveParams.value[0], "B 首项必须是自己的 4210，不能回填 A 的 3650")
            assertFalse(MockBms.paramsReading.value)
            assertEquals(addrB, MockBms.savedAddress)
            assertEquals(4210, AppStore.loadParamsCache(addrB)?.params?.get(0), "B 缓存不得混入 A 的参数")
            assertNull(AppStore.loadParamsCache(addrA), "A 读到一半被取消，不得落缓存")
            assertEquals(listOf(0, 56), transport.requests(addrA, Proto.FC_PARAM).map { it.reg }, "旧任务不得继续发读取")
            assertEquals(1, transport.observedStates().count { it == LinkState.Connected }, "中间断线确实被合并")
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            DeviceProfiles.resetForTest()
            AppStore.store = oldStore
            AppForeground.setActive(oldForeground)
            MockBms.clearForRealDevice()
            MockBms.modifying.value = false
        }
    }
}
