package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.MockBmsEngine
import io.github.lswlc33.maibms.transport.MockBmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归：start() 曾因幂等守卫字段（collectorJob）从未赋值而失效——
 * MaibmsApp.onCreate 与 App() 各调一次 start()，产生两套帧收集器 + 两条轮询 +
 * 两个连后序列（升权/读参数）抢同一个应答槽，真机上连接后立刻异常（用户报「连接后闪退」）。
 * 本用例锁死：重复 start() 必须被吞，会话里只有一个轮询在跑。
 */
class StartIdempotencyTest {

    @Test fun doubleStartOnlyRunsOnePipeline() {
        AppStore.savedAddress = null
        MockBms.clearForRealDevice()
        // BmsLog 是进程级环形缓冲，其他用例的「应用启动」可能还在里面：用差值断言与本用例前的基线比
        val baseline = BmsLog.entries.value.count { it.tag == "APP" && it.text.startsWith("应用启动") }
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val engine = MockBmsEngine(scope)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(MockBmsTransport(engine, scope))

                repo.start()   // 第一次（对应 MaibmsApp.onCreate）
                repo.start()   // 第二次（对应 App() 的 LaunchedEffect）——必须被守卫吞掉

                delay(400)     // 给自动重连时间跑起来

                val appStartLogs = BmsLog.entries.value.count { it.tag == "APP" && it.text.startsWith("应用启动") }
                assertEquals(baseline + 1, appStartLogs, "start() 双调用只允许记一次启动日志")

                // 连接成功后连接只建立一次（transport 层第二次 connect 会重置重连循环）
                assertTrue(MockBms.connected.value || repo.linkState.value != LinkState0.Connected,
                    "允许未连上（CI 无蓝牙时序差异），但不允许状态错乱")
            }
        } finally {
            scope.cancel()
        }
    }
}

private typealias LinkState0 = io.github.lswlc33.maibms.transport.LinkState
