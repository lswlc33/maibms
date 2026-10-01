package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.LinkState
import io.github.lswlc33.maibms.transport.MockBmsEngine
import io.github.lswlc33.maibms.transport.MockBmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 冷启动路径的硬不变量：**永远直连真机，快照绝不参与启动**。
 *
 * 首页要看的保护/告警全在实时帧里，而快照可能是几分钟前的状态——启动时铺旧数据
 * 有被误读成当前状态的风险（"快一秒看到数据"不能拿这个换）。
 * 快照只能由用户在设置里手动进入，且只由用户手动退出或重启应用结束。
 *
 * 这条不变量靠三件事保证：previewActive 纯内存、start() 不读快照、enterPreview 只能手动调。
 * 一旦以后有人加了"启动恢复上次快照"，这里必须红。
 */
class ColdStartTest {

    @AfterTest fun cleanup() {
        AppStore.savedAddress = null
        AppStore.savedDeviceName = null
        AppStore.autoConnectAddress = null
        AppStore.clearSnapshots()
        MockBms.plainPasswords.value = emptyMap()
        StartupTrace.resetForTest()
    }

    @Test fun coldStartConnectsRealDeviceAndNeverEntersPreview() {
        AppStore.snapshotEnabled = true
        AppStore.clearSnapshots()
        // 库里预置一张快照（内容不必真实，本用例只验证它不会被自动加载）
        val snap = MockBms.captureSnapshot(777_000L, "10-01 14:30")
        AppStore.saveSnapshotJson(777_000L, SnapshotCodec.encode(snap))
        assertEquals(1, AppStore.snapshotIds().size, "前置条件：库里应有一张快照")

        // 冷启动的条件：有记忆设备 + 自动重连开着
        AppStore.savedAddress = "AA:BB:CC:0D:0E:0F"
        AppStore.savedDeviceName = "ANT@TEST"
        AppStore.autoConnectAddress = null
        AppStore.autoReconnect = true

        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val engine = MockBmsEngine(scope)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(MockBmsTransport(engine, scope))
                repo.start()

                // 等到"链路就绪 + 第一帧实时数据已上屏"（这正是首屏计时要量的那一刻）
                val deadline = System.currentTimeMillis() + 5_000
                while (!(repo.linkState.value == LinkState.Connected && MockBms.status.value.hasData) &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(30)
                }

                assertEquals(false, repo.previewActive.value, "冷启动绝不能进入快照预览")
                assertEquals(LinkState.Connected, repo.linkState.value, "冷启动应直接连真机（这里是虚拟设备）")
                assertTrue(MockBms.status.value.hasData, "首屏数据必须来自实时帧，而不是快照")
                assertTrue(
                    StartupTrace.elapsedToFirstScreenMs() != null,   // 自动重连路径应已 arm
                    "自动重连时应启动首屏计时",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** 主动进入预览后：连接被拒、连后序列不再产生任何写（防护口①②不被本轮改动破坏） */
    @Test fun previewBlocksConnectAndPostConnectTraffic() {
        AppStore.snapshotEnabled = true
        AppStore.clearSnapshots()
        val snap = MockBms.captureSnapshot(888_000L, "10-01 15:00")
        AppStore.savedAddress = null
        AppStore.autoReconnect = true

        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val engine = MockBmsEngine(scope)
                val repo = BmsRepository(scope, engine)
                repo.setRealTransport(MockBmsTransport(engine, scope))
                repo.start()

                kotlinx.coroutines.withTimeout(3_000) { repo.enterPreview(snap) }
                // 预览中发起连接：应被防护口①直接拒绝，且不会留下连后序列
                repo.connect(null)
                delay(400)

                assertEquals(false, repo.linkState.value == LinkState.Connected, "预览中不得建立链路")
                assertTrue(repo.previewActive.value, "应仍在预览态")
                assertEquals(false, MockBms.connected.value, "预览中不得有实时帧回灌（防护口②）")
                assertEquals(snap.status.totalVoltage, MockBms.status.value.totalVoltage, 1e-9, "预览数据应原样保留")
            }
        } finally {
            scope.cancel()
        }
    }
}
