package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.FrameParser
import io.github.lswlc33.maibms.protocol.Proto
import io.github.lswlc33.maibms.transport.BmsTransport
import io.github.lswlc33.maibms.transport.DeviceFamily
import io.github.lswlc33.maibms.transport.LinkState
import io.github.lswlc33.maibms.transport.MockBmsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 换设备时的**会话隔离**：读到的东西、回填的状态、落盘的缓存都必须绑在"发起那次读的会话"上。
 *
 * 旧实现的故障形态（真机可复现）：连 A 后参数区正在读（9 个参数块 + 5 个身份块，逐条串行、
 * 每条约 120ms），这时用户切到 B——
 *  1. `readJob` 是独立协程，切设备只取消了连后序列（`postConnectJob`），**没取消它**，
 *     于是它继续用已经指向 B 的链路往下读，把 A 已读到的块和 B 的应答拼成一份混合数据；
 *  2. 收尾时用**当前设备地址**（B）把这份混合数据存成 B 的配置缓存；
 *  3. 它还活着 → `readJob.isActive` 把 B 自己的参数区刷新请求直接挡掉。
 *
 * 本用例跑真实 BmsRepository 路径：一条假链路后面挂两台引擎，参数区首项不同
 * （A=3650 / B=4210）——读数里出现 3650 就说明 A 的数据漏进了 B 的会话。
 */
class SessionSwitchTest {

    private val addrA = "AA:00:00:00:00:0A"
    private val addrB = "BB:00:00:00:00:0B"

    /** 两台"设备"共用一条假链路：`connect(address)` 决定之后的写帧由哪台引擎应答 */
    private class TwoDeviceTransport(private val engines: Map<String, MockBmsEngine>) : BmsTransport {
        private val _linkState = MutableStateFlow(LinkState.Idle)
        override val linkState: StateFlow<LinkState> = _linkState
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming
        private val parser = FrameParser()

        @Volatile var active: MockBmsEngine? = null
            private set

        private val writes = mutableListOf<ByteArray>()

        override suspend fun connect(address: String?) {
            active = engines[address]
            _linkState.value = LinkState.Connected
        }

        override suspend fun disconnect() {
            _linkState.value = LinkState.Disconnected
        }

        override suspend fun write(frame: ByteArray) {
            if (_linkState.value != LinkState.Connected) return
            synchronized(writes) { writes += frame.copyOf() }
            for (req in parser.feed(frame)) {
                val resp = active?.handle(req) ?: continue
                // 与 MockBmsTransport 一致：20 字节分片投递（顺带压到分片重组那条路径）
                var offset = 0
                while (offset < resp.size) {
                    val len = minOf(20, resp.size - offset)
                    _incoming.emit(resp.copyOfRange(offset, offset + len))
                    offset += len
                    delay(3)
                }
            }
        }

        /** 已发出的某功能码帧数（帧格式 7E <地址> <功能码> …，功能码在第 3 字节） */
        fun framesWritten(func: Int): Int = synchronized(writes) {
            writes.count { it.size > 2 && (it[2].toInt() and 0xFF) == func }
        }
    }

    private suspend fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(50)
        }
        return cond()
    }

    @Test fun switchingDeviceAbortsTheOldReadAndKeepsOnlyTheNewDeviceData() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                // 干净起点：KV 是共享的 MemoryStore，档案表必须整表复位
                DeviceProfiles.resetForTest()
                AppStore.clearSnapshots()
                AppStore.snapshotEnabled = false
                AppStore.savedAddress = null
                AppStore.savedDeviceName = null
                AppStore.autoConnectAddress = null
                AppStore.autoReconnect = true
                MockBms.clearForRealDevice()
                MockBms.plainPasswords.value = emptyMap()
                MockBms.liveParams.value = emptyMap()
                MockBms.identity.value = emptyMap()

                val engineA = MockBmsEngine(scope).apply { paramStore[0] = 3650; currentPermission = 5 }
                val engineB = MockBmsEngine(scope).apply { paramStore[0] = 4210; currentPermission = 5 }
                DeviceProfiles.upsert(DeviceProfile(
                    address = addrA, name = "A 板", lastConnectedAt = epochMillisNow(), family = DeviceFamily.Ant,
                ))
                DeviceProfiles.upsert(DeviceProfile(
                    address = addrB, name = "B 板", lastConnectedAt = epochMillisNow(), family = DeviceFamily.Ant,
                ))

                val transport = TwoDeviceTransport(mapOf(addrA to engineA, addrB to engineB))
                val repo = BmsRepository(scope, engineA)
                repo.setRealTransport(transport)
                repo.start()

                repo.connect(addrA)
                // 等 A 的参数区确实读起来了（读到第 2 块就够）再切——这才叫"读到一半换设备"
                assertTrue(
                    awaitTrue(5_000) { transport.framesWritten(Proto.FC_PARAM) >= 2 },
                    "A 的参数区应开始回读（实测只有 ${transport.framesWritten(Proto.FC_PARAM)} 条读帧）",
                )
                repo.connectTo(addrB)

                // B 的参数区必须自己读起来（旧实现里 A 的 readJob 还活着，会把它挡掉），
                // 且最终落地的首项必须是 B 的值：A 已读到的 3650 不得混进来
                val got = awaitTrue(20_000) { MockBms.liveParams.value[0] == 4210 }
                assertTrue(
                    got,
                    "切到 B 后参数区应重读成 B 的值（实测首项 ${MockBms.liveParams.value[0]}，" +
                        "共 ${MockBms.liveParams.value.size} 项；3650 = A 的值混进来了）",
                )
                assertEquals(addrB, MockBms.savedAddress, "会话地址应已切到 B")

                // 配置缓存只能存 B 自己的：旧实现会把 A+B 混合的结果存成 B 的配置
                assertEquals(
                    4210, AppStore.loadParamsCache(addrB)?.params?.get(0),
                    "B 的配置缓存不得混入 A 的参数",
                )
                assertNull(
                    AppStore.loadParamsCache(addrA),
                    "A 那轮没读完就被切走，不该给它落一份配置缓存",
                )
            }
        } finally {
            scope.cancel()
            AppStore.clearSnapshots()
            DeviceProfiles.resetForTest()
            AppStore.savedAddress = null
            AppStore.savedDeviceName = null
            AppStore.autoReconnect = true
            MockBms.clearForRealDevice()
            MockBms.liveParams.value = emptyMap()
        }
    }
}
