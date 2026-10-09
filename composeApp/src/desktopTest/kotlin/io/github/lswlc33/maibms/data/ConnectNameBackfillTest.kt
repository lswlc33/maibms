package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.MockBmsEngine
import io.github.lswlc33.maibms.transport.MockBmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 回归：真机连接后卡1 的设备名与电量百分比一起显示「--」。
 *
 * 根因：`connect()` 里的 `resetSessionState()` → `clearForRealDevice()` 会清掉设备名
 * （本意是防快照预览的名字残留），但它从不回填；而电量百分比的显示门控恰好以
 * 「设备名 != --」表示「见过设备」，于是被一并带空——两处同时消失的共因。
 *
 * 锁死：连接同一台设备时，会话重置后必须立刻从设备档案回填名字，首个实时帧
 * 到达后状态里的设备名也不能是 "--"（卡1 的设备名与百分比都挂在它上面）。
 */
class ConnectNameBackfillTest {

    @Test fun connectBackfillsDeviceNameAfterSessionReset() = runBlocking {
        val addr = "AA:BB:CC:00:11:22"
        val name = "ANT@BACKFILL-TEST"
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            DeviceProfiles.resetForTest()
            DeviceProfiles.upsert(DeviceProfile(address = addr, name = name))
            AppStore.savedAddress = addr
            AppStore.savedDeviceName = name
            AppStore.autoReconnect = false   // 只走下面显式的 connect()，不让 start() 自己发起自动重连
            MockBms.clearForRealDevice()

            val engine = MockBmsEngine(scope)
            val repo = BmsRepository(scope, engine)
            repo.setRealTransport(MockBmsTransport(engine, scope))
            repo.start()                     // 装配链路状态镜像与轮询（真机同款管线）
            MockBms.connectedDeviceName.value = null   // 模拟空会话/换设备前的状态

            // 会话重置之后立刻回填：不等首个实时帧，此刻名字就必须在
            repo.connect(addr)
            assertEquals(
                name, MockBms.connectedDeviceName.value,
                "connect() 必须从设备档案回填名字——卡1 的设备名与电量百分比都挂在它上面",
            )

            // 首个实时帧到达后：状态里的设备名不能是 "--"，卡1 右侧角标这时才会显示百分比
            val got = withTimeoutOrNull(5_000) {
                while (!MockBms.status.value.hasData) delay(50)
                true
            }
            if (got == null) fail("5s 内没有收到实时帧，无法验证设备名回填")
            val s = MockBms.status.value
            assertEquals(name, s.deviceName, "实时帧回填后设备名不能是 --")
            assertTrue(s.connected, "实时帧到达后 connected 应为 true")
        } finally {
            scope.cancel()
            MockBms.clearForRealDevice()
            AppStore.savedAddress = null
            AppStore.savedDeviceName = null
            AppStore.autoReconnect = true
            DeviceProfiles.resetForTest()
        }
    }
}
