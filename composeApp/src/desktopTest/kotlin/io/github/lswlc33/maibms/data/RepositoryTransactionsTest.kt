package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.*
import io.github.lswlc33.maibms.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/** 屏障只控制传输交付顺序，所有事务、会话、缓存与通知判定均走真实仓库。 */
class RepositoryTransactionsTest {
    private val addressA = "TEST:CORE:A"
    private val addressB = "TEST:CORE:B"

    private class TestStore : KeyValueStore {
        private val values = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun stop() { entered.complete(Unit); release.await() }
    }

    private data class Sent(val address: String?, val request: ParsedFrame)

    private class ScriptedTransport(val engine: MockBmsEngine) : BmsTransport {
        override val linkState = MutableStateFlow(LinkState.Idle)
        override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val activeChannel = MutableStateFlow<BleChannel?>(BleChannel.Default)
        override val availableChannels = MutableStateFlow(listOf(BleChannel.Default, BleChannel.BackupA, BleChannel.BackupB))
        override val supportsChannelSwitch = true
        private val sent = mutableListOf<Sent>()
        private val switched = mutableListOf<BleChannel>()
        var address: String? = null
        var script: suspend (ParsedFrame, String?) -> ByteArray? = { req, _ -> engine.handle(req) }
        override suspend fun connect(address: String?) {
            this.address = address
            linkState.value = LinkState.Connected
        }
        override suspend fun disconnect() { linkState.value = LinkState.Disconnected }
        override suspend fun write(frame: ByteArray) {
            val req = FrameParser().feed(frame).single()
            val target = address
            synchronized(sent) { sent += Sent(target, req) }
            script(req, target)?.let { incoming.emit(it) }
        }
        override suspend fun switchChannel(channel: BleChannel) {
            synchronized(switched) { switched += channel }
            linkState.value = LinkState.Disconnected
            // 给 StateFlow 收集器机会观察断线，模拟真实异步重连。
            delay(80)
            activeChannel.value = channel
            linkState.value = LinkState.Connected
        }
        fun writes(): List<Sent> = synchronized(sent) { sent.toList() }
        fun switches(): List<BleChannel> = synchronized(switched) { switched.toList() }
    }

    private class RecordingNotifier : Notifier {
        private val updates = mutableListOf<String?>()
        override fun updateMonitoring(text: String?) { synchronized(updates) { updates += text } }
        override fun alert(id: Int, title: String, text: String) {}
        override fun ensurePermission() {}
        fun texts(): List<String?> = synchronized(updates) { updates.toList() }
    }

    private suspend fun waitUntil(ms: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            delay(20)
        }
        return condition()
    }

    private suspend fun Gate.awaitEntered() {
        assertTrue(waitUntil { entered.isCompleted }, "脚本屏障应已到达")
        entered.await()
    }

    private suspend fun <T> Deferred<T>.result(): T {
        assertTrue(waitUntil { isCompleted }, "仓库事务应完成")
        return await()
    }

    private fun withRepo(body: suspend CoroutineScope.(BmsRepository, ScriptedTransport) -> Unit) = runBlocking {
        val oldStore = AppStore.store
        val oldNotifier = Notifications.impl
        val oldForeground = AppForeground.active.value
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            AppStore.store = TestStore()
            DeviceProfiles.resetForTest()
            AppStore.snapshotEnabled = false
            AppStore.autoReconnect = false
            AppStore.backgroundMonitor = false
            AppStore.pollIntervalMs = 300
            AppForeground.setActive(false) // 排除自动轮询/连后读，脚本显式开启需要的路径。
            MockBms.clearForRealDevice()
            MockBms.plainPasswords.value = emptyMap()
            val engine = MockBmsEngine(worker).apply { currentPermission = 5 }
            val transport = ScriptedTransport(engine)
            val repo = BmsRepository(worker, engine)
            repo.setRealTransport(transport)
            repo.start()
            assertTrue(waitUntil { transport.incoming.subscriptionCount.value > 0 })
            repo.connect(addressA)
            assertTrue(waitUntil { repo.linkState.value == LinkState.Connected })
            body(worker, repo, transport)
        } finally {
            worker.coroutineContext[Job]?.cancelAndJoin()
            // 缓存复位在独立测试 KV 内做，绝不清掉原 MemoryStore 的档案。
            DeviceProfiles.resetForTest()
            AppStore.store = oldStore
            Notifications.impl = oldNotifier
            AppForeground.setActive(oldForeground)
            MockBms.clearForRealDevice()
            MockBms.modifying.value = false
        }
    }

    @Test fun u32HalvesExcludeSaveAndQueuedRead() = withRepo { repo, transport ->
        val low = Gate()
        transport.script = { req, _ ->
            val reply = transport.engine.handle(req)
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 162) low.stop()
            reply
        }
        val write = async { repo.writeParam(162, 113_000_000) }
        low.awaitEntered()
        val save = async(start = CoroutineStart.UNDISPATCHED) { repo.saveAllParams() }
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            repo.requestAndAwait(Frame.readParam(0, 2), Proto.RSP_PARAM, expectedReg = 0)
        }
        assertTrue(MockBms.modifying.value)
        low.release.complete(Unit)
        assertEquals(10, write.result())
        assertEquals(1, save.result())
        assertNotNull(read.result())
        val sent = transport.writes().map { it.request }
        val lo = sent.indexOfFirst { it.func == Proto.FC_WRITE_PARAM && it.reg == 162 }
        assertEquals(Proto.FC_WRITE_PARAM, sent[lo + 1].func)
        assertEquals(164, sent[lo + 1].reg, "u32 两半之间不能插入读/保存")
        assertEquals(2, sent.count { it.func == Proto.FC_CONTROL && it.reg == ControlCmd.SAVE_PARAMS })
        assertFalse(MockBms.modifying.value)
    }

    @Test fun u32SecondHalfRejectedNeverAutoSaves() = withRepo { repo, transport ->
        val highBefore = transport.engine.paramStore[164]
        val low = Gate()
        transport.script = { req, _ ->
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 164) transport.engine.currentPermission = 2
            val response = transport.engine.handle(req)
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 162) low.stop()
            response
        }
        val write = async { repo.writeParam(162, 113_000_000) }
        low.awaitEntered()
        low.release.complete(Unit)
        assertEquals(1, write.result())
        assertEquals(highBefore, transport.engine.paramStore[164])
        assertTrue(transport.writes().none { it.request.func == Proto.FC_CONTROL })
        assertEquals(1, MockBms.lastWriteResult.value)
        assertFalse(MockBms.modifying.value)
        assertTrue(MockBms.unsavedWriteError.value?.contains("0xA2") == true)
        assertTrue(MockBms.lastWriteDetail.value?.contains("重新写入") == true)
        transport.engine.currentPermission = 5
        transport.script = { req, _ -> transport.engine.handle(req) }
        assertBlockedSavesUntilCompleteRewrite(repo, transport)
    }

    /** busy 结束后也不能用手动保存、control 直调或其它参数的自动保存固化半值。 */
    private suspend fun assertBlockedSavesUntilCompleteRewrite(repo: BmsRepository, transport: ScriptedTransport) {
        assertEquals(BmsRepository.WRITE_SAVE_BLOCKED, repo.saveAllParams())
        assertEquals(BmsRepository.WRITE_SAVE_BLOCKED, repo.control(ControlCmd.SAVE_PARAMS))
        assertEquals(BmsRepository.WRITE_SAVE_BLOCKED, repo.writeParam(0, 4300))
        assertEquals(4300, transport.engine.paramStore[0], "单参数本身写入了临时区，但不能保存整区")
        assertEquals(ControlCmd.SAVE_PARAMS to BmsRepository.WRITE_SAVE_BLOCKED, MockBms.lastControlResult.value)
        assertTrue(MockBms.lastWriteDetail.value?.contains("重新写入") == true)
        assertTrue(transport.writes().none { it.request.func == Proto.FC_CONTROL })
        repo.refreshParams(force = true)
        assertTrue(waitUntil(8_000) { !MockBms.paramsReading.value })
        assertNotNull(MockBms.unsavedWriteError.value, "普通刷新不能猜测用户原本期望值，不能解除保护")
        assertEquals(BmsRepository.WRITE_SAVE_BLOCKED, repo.saveAllParams())
        assertTrue(transport.writes().none { it.request.func == Proto.FC_CONTROL })
        // 明确重写原始完整值，且四字节回读一致后，才允许自动保存。
        assertEquals(10, repo.writeParam(162, 100_000_000))
        assertNull(MockBms.unsavedWriteError.value)
        assertEquals(1, transport.writes().count { it.request.func == Proto.FC_CONTROL && it.request.reg == ControlCmd.SAVE_PARAMS })
        assertEquals(1, repo.saveAllParams())
        assertEquals(2, transport.writes().count { it.request.func == Proto.FC_CONTROL && it.request.reg == ControlCmd.SAVE_PARAMS })
    }

    @Test fun u32SecondHalfTimeoutBlocksLaterSavesUntilCompleteRewrite() = withRepo { repo, transport ->
        val low = Gate()
        val highBefore = transport.engine.paramStore[164]
        transport.script = { req, _ ->
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 164) null
            else {
                val response = transport.engine.handle(req)
                if (req.func == Proto.FC_WRITE_PARAM && req.reg == 162) low.stop()
                response
            }
        }
        val write = async { repo.writeParam(162, 113_000_000) }
        low.awaitEntered()
        low.release.complete(Unit)
        assertEquals(-2, write.result())
        assertEquals(113_000_000 and 0xFFFF, transport.engine.paramStore[162])
        assertEquals(highBefore, transport.engine.paramStore[164])
        assertFalse(MockBms.modifying.value)
        transport.script = { req, _ -> transport.engine.handle(req) }
        assertBlockedSavesUntilCompleteRewrite(repo, transport)
    }

    @Test fun unresolvedU32SurvivesSameDeviceReconnectWithoutLeakingToOtherDevice() = withRepo { repo, transport ->
        transport.script = { req, _ ->
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 164) null else transport.engine.handle(req)
        }
        assertEquals(-2, repo.writeParam(162, 113_000_000))
        transport.script = { req, _ -> transport.engine.handle(req) }
        repo.connectTo(addressB)
        assertTrue(waitUntil { repo.linkState.value == LinkState.Connected })
        assertNull(MockBms.unsavedWriteError.value, "A 的告警不能串到 B")
        assertEquals(1, repo.saveAllParams())
        repo.connectTo(addressA)
        assertTrue(waitUntil { repo.linkState.value == LinkState.Connected })
        assertTrue(MockBms.unsavedWriteError.value?.contains("0xA2") == true)
        assertEquals(BmsRepository.WRITE_SAVE_BLOCKED, repo.saveAllParams())
        assertEquals(1, transport.writes().count { it.request.func == Proto.FC_CONTROL })
        assertEquals(10, repo.writeParam(162, 100_000_000))
        assertNull(MockBms.unsavedWriteError.value)
        assertEquals(2, transport.writes().count { it.request.func == Proto.FC_CONTROL })
    }

    @Test fun saveFailurePreservesRealControlCodeAndWriteFailure() = withRepo { repo, transport ->
        transport.script = { req, _ ->
            if (req.func == Proto.FC_CONTROL) Frame.build(Proto.ADDR_MAIN, Proto.RSP_CONTROL, req.reg, byteArrayOf(2), 1)
            else transport.engine.handle(req)
        }
        assertEquals(BmsRepository.WRITE_SAVE_FAILED, repo.writeParam(0, 4300))
        assertEquals(ControlCmd.SAVE_PARAMS to 2, MockBms.lastControlResult.value)
        assertEquals(4300, transport.engine.paramStore[0])
        assertTrue(MockBms.lastWriteDetail.value?.contains("保存失败") == true)
    }

    @Test fun initialWriteThrowPublishesFailureAndReleasesBusy() = withRepo { repo, transport ->
        val before = transport.engine.paramStore[0]
        transport.script = { _, _ -> throw java.io.IOException("initial write failed") }
        assertEquals(-2, repo.writeParam(0, 4300))
        assertEquals(-2, MockBms.lastWriteResult.value)
        assertTrue(MockBms.lastWriteDetail.value?.contains("initial write failed") == true)
        assertEquals(before, transport.engine.paramStore[0])
        assertFalse(MockBms.modifying.value)
        assertNull(MockBms.unsavedWriteError.value)
    }

    @Test fun secondHalfThrowPreservesSaveBlockAndCanBeRepaired() = withRepo { repo, transport ->
        val low = Gate()
        transport.script = { req, _ ->
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 164) throw IllegalStateException("high write failed")
            val response = transport.engine.handle(req)
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 162) low.stop()
            response
        }
        val write = async { repo.writeParam(162, 113_000_000) }
        low.awaitEntered()
        low.release.complete(Unit)
        assertEquals(-2, write.result())
        assertTrue(MockBms.lastWriteDetail.value?.contains("high write failed") == true)
        assertNotNull(MockBms.unsavedWriteError.value)
        assertFalse(MockBms.modifying.value)
        transport.script = { req, _ -> transport.engine.handle(req) }
        assertBlockedSavesUntilCompleteRewrite(repo, transport)
    }

    @Test fun autoSaveThrowDoesNotLeaveSuccessfulWriteResult() = withRepo { repo, transport ->
        transport.script = { req, _ ->
            if (req.func == Proto.FC_CONTROL) throw java.io.IOException("auto save failed")
            transport.engine.handle(req)
        }
        assertEquals(BmsRepository.WRITE_SAVE_FAILED, repo.writeParam(0, 4300))
        assertEquals(BmsRepository.WRITE_SAVE_FAILED, MockBms.lastWriteResult.value)
        assertEquals(ControlCmd.SAVE_PARAMS to 0, MockBms.lastControlResult.value)
        assertEquals(4300, transport.engine.paramStore[0])
        assertTrue(MockBms.lastWriteDetail.value?.contains("临时区") == true)
        assertTrue(MockBms.lastWriteDetail.value?.contains("auto save failed") == true)
        assertFalse(MockBms.modifying.value)
    }

    @Test fun manualSaveThrowReturnsFailureInsteadOfEscaping() = withRepo { repo, transport ->
        transport.script = { _, _ -> throw IllegalStateException("manual save failed") }
        assertEquals(0, repo.saveAllParams())
        assertEquals(ControlCmd.SAVE_PARAMS to 0, MockBms.lastControlResult.value)
        assertEquals(BmsRepository.WRITE_SAVE_FAILED, MockBms.lastWriteResult.value)
        assertTrue(MockBms.lastWriteDetail.value?.contains("manual save failed") == true)
        assertFalse(MockBms.modifying.value)
    }

    @Test fun controlThrowReturnsFailureAndDoesNotApplySwitch() = withRepo { repo, transport ->
        MockBms.chargeSwitch.value = false
        transport.script = { _, _ -> throw java.io.IOException("control failed") }
        assertEquals(0, repo.control(ControlCmd.CHARGE_ON))
        assertEquals(ControlCmd.CHARGE_ON to 0, MockBms.lastControlResult.value)
        assertEquals(-2, MockBms.lastWriteResult.value)
        assertTrue(MockBms.lastWriteDetail.value?.contains("control failed") == true)
        assertFalse(MockBms.chargeSwitch.value)
        assertFalse(MockBms.modifying.value)
    }

    @Test fun newRepositoryRestoresPersistentPartialWriteBlock() = withRepo { repo, transport ->
        transport.script = { req, _ ->
            if (req.func == Proto.FC_WRITE_PARAM && req.reg == 164) null else transport.engine.handle(req)
        }
        assertEquals(-2, repo.writeParam(162, 113_000_000))
        assertNotNull(AppStore.get("write.unconfirmedU32.$addressA"))
        repo.disconnect()
        val nextWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val nextEngine = MockBmsEngine(nextWorker).apply { currentPermission = 5 }
            val nextTransport = ScriptedTransport(nextEngine)
            val next = BmsRepository(nextWorker, nextEngine)
            next.setRealTransport(nextTransport)
            next.start()
            assertTrue(waitUntil { nextTransport.incoming.subscriptionCount.value > 0 })
            next.connect(addressA)
            assertTrue(waitUntil { next.linkState.value == LinkState.Connected })
            assertEquals(BmsRepository.WRITE_SAVE_BLOCKED, next.saveAllParams())
            assertTrue(nextTransport.writes().isEmpty())
            assertNotNull(MockBms.unsavedWriteError.value)
            assertEquals(10, next.writeParam(162, 100_000_000))
            assertNull(AppStore.get("write.unconfirmedU32.$addressA"))
            assertNull(MockBms.unsavedWriteError.value)
        } finally {
            nextWorker.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun cancelledQueuedSaveKeepsExecutingWriteBusy() = withRepo { repo, transport ->
        val low = Gate()
        transport.script = { req, _ ->
            val response = transport.engine.handle(req)
            if (req.func == Proto.FC_WRITE_PARAM) low.stop()
            response
        }
        val write = async { repo.writeParam(0, 4300) }
        low.awaitEntered()
        val save = async(start = CoroutineStart.UNDISPATCHED) { repo.saveAllParams() }
        save.cancelAndJoin()
        assertTrue(MockBms.modifying.value, "取消排队保存不能关掉执行中的写 busy")
        low.release.complete(Unit)
        assertEquals(10, write.result())
        assertFalse(MockBms.modifying.value)
    }

    @Test fun rawValidationRejectsBeforeSending() = withRepo { repo, transport ->
        assertEquals(BmsRepository.WRITE_INVALID_RAW, repo.writeParam(0, Int.MAX_VALUE))
        assertTrue(transport.writes().isEmpty())
        assertNotNull(MockBms.lastWriteDetail.value)
        assertFalse(MockBms.modifying.value)
    }

    @Test fun oldAccumulatorCannotOverwriteWriteAndForceIsMerged() = withRepo { repo, transport ->
        val first = Gate()
        var initial = true
        transport.script = { req, _ ->
            val response = transport.engine.handle(req) // 在屏障前冻结旧值。
            if (req.func == Proto.FC_PARAM && req.reg == 0 && req.lengthField == 52 && initial) {
                initial = false
                first.stop()
            }
            response
        }
        repo.refreshParams(force = true)
        first.awaitEntered()
        val write = async(start = CoroutineStart.UNDISPATCHED) { repo.writeParam(0, 4300) }
        repeat(3) { repo.refreshParams(force = true) }
        first.release.complete(Unit)
        assertEquals(10, write.result())
        assertTrue(waitUntil(8_000) { !MockBms.paramsReading.value })
        assertEquals(4300, MockBms.liveParams.value[0], "旧 acc 的 3650 不得覆盖新写入")
        assertEquals(4300, AppStore.loadParamsCache(addressA)?.params?.get(0))
        assertEquals(2, transport.writes().count { it.request.func == Proto.FC_PARAM && it.request.reg == 0 && it.request.lengthField == 52 }, "多个活跃 force 合并为后续一轮")
    }

    private fun log(index: Int, code: Int): ByteArray = Frame.build(
        Proto.ADDR_MAIN, Proto.RSP_LOG, index,
        byteArrayOf(0, 0, 1, code.toByte(), 1, 0, 0, 0, 3, 0, 50, 0), 12,
    )

    @Test fun lateWrongIndexCannotAdvancePageAndConcurrentRefreshIsRejected() = withRepo { repo, transport ->
        val second = Gate()
        transport.script = { req, _ ->
            when (req.func) {
                Proto.FC_LOG -> when (req.reg) {
                    0 -> log(0, 1)
                    1 -> {
                        transport.incoming.emit(log(0, 99))
                        second.stop()
                        log(1, 2)
                    }
                    else -> Frame.build(Proto.ADDR_MAIN, Proto.RSP_LOG, req.reg, byteArrayOf(), 0)
                }
                Proto.FC_PROTECT_NUM -> Frame.build(Proto.ADDR_MAIN, Proto.RSP_PROTECT_NUM, 0, byteArrayOf(7, 0), 2)
                else -> transport.engine.handle(req)
            }
        }
        val refresh = async { repo.refreshEventLog() }
        second.awaitEntered()
        assertEquals(-1, repo.refreshEventLog())
        assertEquals(-1, repo.loadMoreEventLog())
        assertTrue(MockBms.eventLogLoading.value)
        second.release.complete(Unit)
        assertEquals(2, refresh.result())
        assertEquals(listOf(0, 1), MockBms.eventLog.value.map { it.index })
        assertEquals(listOf(1, 2), MockBms.eventLog.value.map { it.eventCode })
        assertEquals(listOf(7), MockBms.protectCounters.value)
        assertFalse(MockBms.eventLogLoading.value)
    }

    @Test fun switchingDuringLogReadDoesNotPublishOldPageOrClearNewLoading() = withRepo { repo, transport ->
        val old = Gate()
        val fresh = Gate()
        transport.script = { req, address ->
            when (req.func) {
                Proto.FC_LOG -> {
                    if (address == addressA && req.reg == 1) old.stop()
                    if (address == addressB && req.reg == 0) fresh.stop()
                    if (req.reg == 0) log(0, if (address == addressA) 1 else 2)
                    else Frame.build(Proto.ADDR_MAIN, Proto.RSP_LOG, req.reg, byteArrayOf(), 0)
                }
                Proto.FC_PROTECT_NUM -> Frame.build(Proto.ADDR_MAIN, Proto.RSP_PROTECT_NUM, 0, byteArrayOf(9, 0), 2)
                else -> transport.engine.handle(req)
            }
        }
        val readA = async { repo.refreshEventLog() }
        old.awaitEntered()
        val switch = async(start = CoroutineStart.UNDISPATCHED) { repo.connectTo(addressB) }
        assertTrue(waitUntil { !MockBms.eventLogLoading.value })
        old.release.complete(Unit)
        switch.result()
        assertTrue(waitUntil { repo.linkState.value == LinkState.Connected })
        val readB = async { repo.refreshEventLog() }
        fresh.awaitEntered()
        assertEquals(-1, readA.result())
        // 迟到的 A 序号 1，不得满足 B 正在等待的序号 0。
        transport.incoming.emit(log(1, 99))
        assertTrue(MockBms.eventLogLoading.value)
        assertTrue(MockBms.eventLog.value.isEmpty())
        fresh.release.complete(Unit)
        assertEquals(1, readB.result())
        assertEquals(listOf(2), MockBms.eventLog.value.map { it.eventCode })
        assertEquals(listOf(9), MockBms.protectCounters.value)
    }

    @Test fun switchingWhileCountersPendingCannotFillNewSession() = withRepo { repo, transport ->
        val counters = Gate()
        transport.script = { req, _ ->
            when (req.func) {
                Proto.FC_LOG -> Frame.build(Proto.ADDR_MAIN, Proto.RSP_LOG, req.reg, byteArrayOf(), 0)
                Proto.FC_PROTECT_NUM -> {
                    counters.stop()
                    Frame.build(Proto.ADDR_MAIN, Proto.RSP_PROTECT_NUM, 0, byteArrayOf(42, 0), 2)
                }
                else -> transport.engine.handle(req)
            }
        }
        val read = async { repo.refreshEventLog() }
        counters.awaitEntered()
        val switch = async(start = CoroutineStart.UNDISPATCHED) { repo.connectTo(addressB) }
        counters.release.complete(Unit)
        switch.result()
        assertEquals(-1, read.result())
        assertTrue(MockBms.protectCounters.value.isEmpty())
        assertFalse(MockBms.eventLogLoading.value)
    }

    @Test fun zeroFirstFrameTraversesAllThreeCandidatesOnceAcrossReconnects() = withRepo { repo, transport ->
        transport.script = { _, _ -> null }
        AppForeground.setActive(true)
        assertTrue(waitUntil(20_000) { transport.switches().size == 2 }, "零首帧也必须触发失联轮切")
        assertEquals(listOf(BleChannel.BackupA, BleChannel.BackupB), transport.switches())
        assertTrue(waitUntil(10_000) { repo.connectHint.value?.contains("都试过") == true })
        assertEquals(2, transport.switches().size, "候选耗尽不回到 Default 或重复 A")
        assertTrue(repo.stalled.value, "新计时窗口不等于真实恢复")
        assertFalse(MockBms.status.value.hasData)
        transport.incoming.emit(Frame.build(Proto.ADDR_MAIN, Proto.RSP_REALTIME, 0, byteArrayOf(1), 1))
        delay(100)
        assertTrue(repo.stalled.value, "无效帧不得清空失联进度")
        transport.incoming.emit(Frame.build(Proto.ADDR_MAIN, Proto.RSP_REALTIME, 0, transport.engine.encodeRealtime(), 0))
        assertTrue(waitUntil { !repo.stalled.value && MockBms.status.value.hasData })
    }

    @Test fun backgroundDisconnectRetainsNonNullReconnectingNotification() = withRepo { repo, transport ->
        val notifier = RecordingNotifier()
        Notifications.impl = notifier
        repo.setBackgroundMonitor(true)
        assertTrue(waitUntil { notifier.texts().any { it != null } })
        val before = notifier.texts().size
        transport.linkState.value = LinkState.Disconnected
        assertTrue(waitUntil { notifier.texts().drop(before).any { it?.contains("正在重连") == true } })
        assertTrue(repo.connectionIntentActive.value)
        assertTrue(notifier.texts().drop(before).all { it != null }, "掉线重试不能撤掉 FGS")
        repo.disconnect()
        assertNull(notifier.texts().last())
        assertFalse(repo.connectionIntentActive.value)
    }

    @Test fun queuedOldSaveAndWriteNeverContinueOnNewDevice() = withRepo { repo, transport ->
        val old = Gate()
        val fresh = Gate()
        transport.script = { req, address ->
            val response = transport.engine.handle(req)
            if (req.func == Proto.FC_WRITE_PARAM && address == addressA) old.stop()
            if (req.func == Proto.FC_WRITE_PARAM && address == addressB) fresh.stop()
            response
        }
        val writeA = async { repo.writeParam(0, 4300) }
        old.awaitEntered()
        val queuedSave = async(start = CoroutineStart.UNDISPATCHED) { repo.saveAllParams() }
        val switch = async(start = CoroutineStart.UNDISPATCHED) { repo.connectTo(addressB) }
        assertTrue(waitUntil { !MockBms.modifying.value })
        old.release.complete(Unit)
        switch.result()
        assertTrue(waitUntil { repo.linkState.value == LinkState.Connected })
        val writeB = async { repo.writeParam(0, 4400) }
        fresh.awaitEntered()
        assertEquals(BmsRepository.WRITE_SESSION_ENDED, writeA.result())
        assertEquals(0, queuedSave.result())
        assertTrue(MockBms.modifying.value, "旧事务 finally 不能清掉 B busy")
        assertNull(MockBms.lastControlResult.value)
        assertTrue(transport.writes().none { it.address == addressB && it.request.func == Proto.FC_CONTROL })
        fresh.release.complete(Unit)
        assertEquals(10, writeB.result())
        assertEquals(ControlCmd.SAVE_PARAMS to 1, MockBms.lastControlResult.value)
        assertFalse(MockBms.modifying.value)
    }

    @Test fun idleWithoutConnectionIntentDoesNotStartMonitoring() = withRepo { _, transport ->
        // 新仓库只 start、不 connect：即使 manualDisconnect 默认 false，也不能启动 FGS。
        val idleWorker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = RecordingNotifier()
        try {
            Notifications.impl = notifier
            AppStore.savedAddress = null
            AppStore.autoReconnect = false
            val engine = MockBmsEngine(idleWorker)
            val idle = BmsRepository(idleWorker, engine)
            idle.setRealTransport(ScriptedTransport(engine))
            idle.start()
            idle.setBackgroundMonitor(true)
            delay(100)
            assertFalse(idle.connectionIntentActive.value)
            assertTrue(notifier.texts().isEmpty())
        } finally {
            AppStore.backgroundMonitor = false
            idleWorker.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun clearDeviceResetsSwitchesButRemainsUnknown() = withRepo { _, _ ->
        MockBms.chargeSwitch.value = true
        MockBms.dischargeSwitch.value = true
        MockBms.balanceSwitch.value = true
        MockBms.clearForRealDevice()
        assertFalse(MockBms.chargeSwitch.value)
        assertFalse(MockBms.dischargeSwitch.value)
        assertFalse(MockBms.balanceSwitch.value)
        assertFalse(MockBms.status.value.hasData, "UI 须用 hasData 显示未知，不能把默认 false 当真实状态")
    }
}
