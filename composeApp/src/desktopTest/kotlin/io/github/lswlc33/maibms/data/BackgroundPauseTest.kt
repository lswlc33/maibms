package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.FrameParser
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
import kotlin.test.assertTrue

/**
 * 后台策略：退到后台**默认暂停轮询**，开了「后台监控」才继续；回前台立刻恢复。
 *
 * 改之前的实际行为是"两头不占"：退到后台仍按 600ms 轮询、占着 BLE 连接，
 * 却既没有界面展示也没有通知——电耗换不回任何东西。这里把新约定钉死：
 *  1. 关（默认）：后台不发请求；但**连接保留**，回前台马上继续，不重连；
 *  2. 开：后台照常轮询（否则"后台监控"没有数据可看，也发不出告警通知）。
 *
 * 用例脚手架沿用 StartupFastPathTest：常驻协程跑在自有用例 scope 上，结束时取消。
 * `AppForeground` / `AppStore` 都是全局对象，用例结束必须复原（见 finally）。
 */
class BackgroundPauseTest {

    private val addr = "AA:00:00:00:00:0A"

    /** 计数型传输：把每拍写出的帧记下来，照抄 MockBmsTransport 的收发行为 */
    private class CountingTransport(private val engine: MockBmsEngine) : BmsTransport {
        private val _linkState = MutableStateFlow(LinkState.Idle)
        override val linkState: StateFlow<LinkState> = _linkState
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming
        private val parser = FrameParser()

        private val writes = mutableListOf<ByteArray>()

        override suspend fun connect(address: String?) {
            _linkState.value = LinkState.Connected
        }

        override suspend fun disconnect() {
            _linkState.value = LinkState.Disconnected
        }

        override suspend fun write(frame: ByteArray) {
            if (_linkState.value != LinkState.Connected) return
            synchronized(writes) { writes += frame.copyOf() }
            for (req in parser.feed(frame)) {
                val resp = engine.handle(req) ?: continue
                var offset = 0
                while (offset < resp.size) {
                    val len = minOf(20, resp.size - offset)
                    _incoming.emit(resp.copyOfRange(offset, offset + len))
                    offset += len
                    delay(4)
                }
            }
        }

        fun count(): Int = synchronized(writes) { writes.size }

        /**
         * 只数**实时轮询**帧（功能码 0x01）。
         *
         * 不能数全部写帧：连上之后还有连后序列（升权 0x23 + 参数区 14 个块 0x02）在跑，
         * 它们与"退到后台还轮不轮询"无关，会把计数搅浑（第一版就是这么误判的）。
         */
        fun polls(): Int = synchronized(writes) {
            writes.count { it.size > 2 && (it[2].toInt() and 0xFF) == 0x01 }
        }
    }

    /** 轮询若干次直到条件成立；到点仍未成立就返回 false（不用 withTimeout 断言，避免偶发误判） */
    private suspend fun waitUntil(timeoutMs: Long = 4_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(40)
        }
        return cond()
    }

    @Test
    fun pollPausesInBackgroundByDefaultAndResumesInstantly() = runBlocking {
        val prevBg = AppStore.backgroundMonitor
        val prevInterval = AppStore.pollIntervalMs
        val scope = CoroutineScope(Dispatchers.Default)
        var repo: BmsRepository? = null
        try {
            AppStore.backgroundMonitor = false
            AppStore.pollIntervalMs = 300          // 用例内跑快一点（三档里最快的一档）
            AppForeground.setActive(true)
            val engine = MockBmsEngine(scope)
            val transport = CountingTransport(engine)
            repo = BmsRepository(scope, engine)
            repo.setRealTransport(transport)
            repo.start()
            repo.connect(addr)
            assertTrue(waitUntil { transport.polls() >= 2 }, "前台应当持续轮询")

            // 退到后台（未开后台监控）→ 不再发实时轮询
            AppForeground.setActive(false)
            val atPause = transport.polls()
            delay(1_200)          // 300ms 一档，足够跑好几拍
            assertEquals(atPause, transport.polls(), "退到后台且未开监控时不应再发请求")

            // 回前台 → 立刻恢复（不等下一拍相位）
            AppForeground.setActive(true)
            assertTrue(
                waitUntil { transport.polls() > atPause },
                "回前台应立刻恢复轮询（实际 $atPause → ${transport.polls()}）",
            )
            // 连接是保留的：不算断开重连
            assertEquals(LinkState.Connected, transport.linkState.value)
        } finally {
            AppForeground.setActive(true)
            runCatching { repo?.disconnect() }
            scope.cancel()
            AppStore.backgroundMonitor = prevBg
            AppStore.pollIntervalMs = prevInterval
        }
    }

    @Test
    fun backgroundMonitorKeepsPollingInBackground() = runBlocking {
        val prevBg = AppStore.backgroundMonitor
        val prevInterval = AppStore.pollIntervalMs
        val scope = CoroutineScope(Dispatchers.Default)
        var repo: BmsRepository? = null
        try {
            AppStore.backgroundMonitor = true
            AppStore.pollIntervalMs = 300
            AppForeground.setActive(true)
            val engine = MockBmsEngine(scope)
            val transport = CountingTransport(engine)
            repo = BmsRepository(scope, engine)
            repo.setRealTransport(transport)
            repo.start()
            repo.connect(addr)
            assertTrue(waitUntil { transport.polls() >= 2 }, "前台应当持续轮询")

            AppForeground.setActive(false)
            val atBackground = transport.polls()
            // 开了后台监控：退了也照常轮询（否则没有数据可看，也发不出告警）
            assertTrue(
                waitUntil { transport.polls() >= atBackground + 2 },
                "开启后台监控时退到后台仍应继续轮询（实际 $atBackground → ${transport.polls()}）",
            )
        } finally {
            AppForeground.setActive(true)
            runCatching { repo?.disconnect() }
            scope.cancel()
            AppStore.backgroundMonitor = prevBg
            AppStore.pollIntervalMs = prevInterval
        }
    }
}
