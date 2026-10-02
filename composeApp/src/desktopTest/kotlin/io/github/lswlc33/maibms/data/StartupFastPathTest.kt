package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.FrameParser
import io.github.lswlc33.maibms.protocol.Proto
import io.github.lswlc33.maibms.transport.BmsTransport
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
import kotlin.test.assertTrue

/**
 * 冷启动快路径：链路一就绪就发第一拍，升权与参数区读回都排在首帧之后。
 *
 * 这三件事是"打开就能看到电池状态"的关键时序，之前全靠固定 delay 凑：
 * 首拍要等一个完整轮询相位（当时 900ms）、升权固定 delay(600) 且会抢应答槽、参数区还会读两遍。
 * 改动后必须由测试钉住，否则以后随便加个 delay 又回去了。
 *
 * 用例脚手架沿用 WritePathTest：常驻协程跑在自有用例 scope 上，结束时取消。
 */
class StartupFastPathTest {

    /** 记录型传输：把每条写出的帧连时间戳记下来，收发行为照抄 MockBmsTransport */
    private class RecordingTransport(private val engine: MockBmsEngine) : BmsTransport {
        private val _linkState = MutableStateFlow(LinkState.Idle)
        override val linkState: StateFlow<LinkState> = _linkState
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming
        private val parser = FrameParser()

        private val writes = mutableListOf<Pair<Long, ByteArray>>()

        /** 链路报"已连接"的时刻（毫秒），用来量首拍延迟 */
        @Volatile var connectedAt = 0L
            private set

        override suspend fun connect(address: String?) {
            _linkState.value = LinkState.Connecting
            delay(40)
            connectedAt = System.currentTimeMillis()
            _linkState.value = LinkState.Connected
        }

        override suspend fun disconnect() {
            _linkState.value = LinkState.Disconnected
        }

        override suspend fun write(frame: ByteArray) {
            if (_linkState.value != LinkState.Connected) return
            synchronized(writes) { writes += System.currentTimeMillis() to frame.copyOf() }
            for (req in parser.feed(frame)) {
                val resp = engine.handle(req) ?: continue
                var offset = 0
                while (offset < resp.size) {
                    val len = minOf(20, resp.size - offset)
                    _incoming.emit(resp.copyOfRange(offset, offset + len))
                    offset += len
                    delay(6)
                }
            }
        }

        fun snapshot(): List<Pair<Long, ByteArray>> = synchronized(writes) { writes.toList() }
    }

    /** 帧格式：7E <地址> <功能码> …，第 3 字节是功能码 */
    private fun funcOf(frame: ByteArray): Int? =
        if (frame.size > 2) frame[2].toInt() and 0xFF else null

    private suspend fun awaitWrite(
        t: RecordingTransport,
        func: Int,
        timeoutMs: Long = 3_000,
    ): Pair<Long, ByteArray>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            t.snapshot().firstOrNull { funcOf(it.second) == func }?.let { return it }
            delay(15)
        }
        return null
    }

    /** 复位全局状态：不带记忆设备、不带密码、不记快照（本类只关心时序） */
    private fun prepare() {
        AppStore.savedAddress = null
        AppStore.savedDeviceName = null
        AppStore.autoConnectAddress = null
        AppStore.autoReconnect = true
        AppStore.snapshotEnabled = false
        MockBms.clearForRealDevice()
        MockBms.plainPasswords.value = emptyMap()
        MockBms.autoUpgradeLevel.value = 0
        MockBms.liveParams.value = emptyMap()
        MockBms.identity.value = emptyMap()
        StartupTrace.resetForTest()
    }

    /**
     * 首拍不等整个轮询相位：链路就绪后要立刻读到第一帧。
     * 这是"快一秒看到保护/告警"最直接的一条——首页所有数值都在这条 0x11 帧里。
     */
    @Test fun firstRealtimeReadFiresImmediatelyOnConnected() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                prepare()
                val engine = MockBmsEngine(scope)
                val transport = RecordingTransport(engine)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(transport)
                repo.start()
                repo.connect(null)

                val first = awaitWrite(transport, Proto.FC_REALTIME)
                assertTrue(first != null, "连接后应发出实时读帧（0x01）")
                val lag = first!!.first - transport.connectedAt
                assertTrue(
                    lag in 0..400,
                    "首拍应在链路就绪后立刻发出，实测相差 ${lag}ms（>400ms 说明还在等轮询相位）",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** 升权（0x23）必须排在首帧之后：它要独占应答槽，排前面就是把首帧往后推 */
    @Test fun authWaitsForFirstFrame() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                prepare()
                MockBms.plainPasswords.value = mapOf(5 to "12345678")
                val engine = MockBmsEngine(scope)
                engine.currentPermission = 5
                val transport = RecordingTransport(engine)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(transport)
                repo.start()
                repo.connect(null)

                val read = awaitWrite(transport, Proto.FC_REALTIME)
                val auth = awaitWrite(transport, Proto.FC_AUTH, timeoutMs = 4_000)
                assertTrue(read != null, "应发出实时读帧")
                assertTrue(auth != null, "有本地密码时应发出升权帧")

                val order = transport.snapshot()
                val idxRead = order.indexOfFirst { funcOf(it.second) == Proto.FC_REALTIME }
                val idxAuth = order.indexOfFirst { funcOf(it.second) == Proto.FC_AUTH }
                assertTrue(
                    idxAuth > idxRead,
                    "帧序必须是先读后升权（实测 读#$idxRead / 升权#$idxAuth）",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** 无密码时参数区也要读一轮，不能被"等升权"饿死 */
    @Test fun paramsReadOnceWithoutPasswords() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                prepare()
                val engine = MockBmsEngine(scope)
                val transport = RecordingTransport(engine)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(transport)
                repo.start()
                repo.connect(null)

                assertTrue(awaitWrite(transport, Proto.FC_REALTIME) != null, "应发出实时读帧")
                val count = awaitParamRound(transport)
                // 9 个参数块 + 5 个身份块 = 14
                assertTrue(
                    count in 14..16,
                    "参数区应读一整轮（实测 $count 帧，期望 14：9 参数块 + 5 身份块）",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** 有密码时也只读一轮：升权后的 force 重读与连后序列的那次调用必须被去重，不能读两遍 */
    @Test fun paramsReadOnceWithPasswords() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                prepare()
                MockBms.plainPasswords.value = mapOf(5 to "12345678")
                val engine = MockBmsEngine(scope)
                engine.currentPermission = 5
                val transport = RecordingTransport(engine)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(transport)
                repo.start()
                repo.connect(null)

                assertTrue(awaitWrite(transport, Proto.FC_AUTH, timeoutMs = 4_000) != null, "有密码应升权")
                val count = awaitParamRound(transport)
                assertTrue(
                    count in 14..16,
                    "升权前后各读一遍是旧行为（会到 28 帧）；现在只读一轮（实测 $count 帧）",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** 等参数区 + 身份区读完（identity 在参数块之后才写入），再给一点余量后统计读帧数 */
    private suspend fun awaitParamRound(t: RecordingTransport, timeoutMs: Long = 12_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (MockBms.identity.value.isNotEmpty()) break
            delay(50)
        }
        delay(400)
        return t.snapshot().count { funcOf(it.second) == Proto.FC_PARAM }
    }
}
