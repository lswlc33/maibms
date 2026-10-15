package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.meter.modbusReadFrame
import io.github.lswlc33.maibms.transport.BleChannel
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
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 电量计（陆行）会话的快照：**必须记一张，且名字取"连接的那台电量计"**。
 *
 * 真机报障（2026-10-15）：连着陆行电量计，快照记录里最新一条却是保护板的名字
 * （`ANT@BLE24CBUB-3547`）——那是上一场直连保护板的会话留下的。两个原因：
 *  1. 快照挂点是保护板的「参数区读回完成」，而电量计没有参数区 → 电量计会话从来不记快照；
 *  2. 名字一旦取错源，就会显示成电量计**中继的那块保护板**（90 帧里报的另一台设备）。
 *
 * 这条用例跑在**真实 BmsRepository 路径**上（假传输只负责按轮询回真机实录帧），把
 * 「连电量计 → 快照里出现一条以电量计名字命名的记录」钉死。
 */
class MeterSnapshotTest {

    private val meterAddr = "C4:24:8A:78:A5:81"
    private val meterName = "EM2APP-A581"

    /** 真机实录 108 实时帧（EM2APP-A581）：79.67V / 0.10A / SOC 83% / 额定 113.0Ah */
    private val realtime108 = hexStr(
        "01 03 6C 14 00 00 00 00 00 00 04 40 40 40 40 00 00 00 00 00 00 40 40 07 00 36 37 01 00 0A 00 00 00 8D 0F 93 0F " +
        "8E 0F 8E 0F 8E 0F 8F 0F 93 0F 94 0F 8F 0F 8E 0F 8E 0F 90 0F 92 0F 8F 0F 90 0F 8E 0F 8F 0F 8E 0F 8E 0F 93 0F 00 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 07 94 0F 8D 0F 00 00 00 00 00 00 6A 04 6A 04 53 64 " +
        "96 0F"
    )

    /** 真机实录 90 BMS 帧：中继的那块保护板叫 ANT@BLE24CBUB-3547（MAC F9:99:1B:2B:1B:70） */
    private val bms90 = hexStr(
        "01 03 5A 01 01 0B 00 0B 00 00 00 00 00 00 00 40 00 62 00 00 00 49 35 01 00 54 02 6A 04 14 00 14 0C 18 0D D1 0D " +
        "19 0E 5F 0E B9 0E 18 0F 71 0F D7 0F F5 0F 4C 10 02 00 41 4E 54 40 42 4C 45 32 34 43 42 55 42 2D 33 35 34 37 20 " +
        "00 00 00 00 00 00 00 00 00 00 00 00 00 F9 99 1B 2B 1B 70 9D ED"
    )

    private fun hexStr(s: String) =
        s.trim().split(' ', '\n').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun meterSessionRecordsOneSnapshotNamedAfterTheMeter() {
        // 干净起点：各用例间 KV 是共享的 MemoryStore，档案表必须整表复位——
        // 表满（MAX=20）时会按 lastConnectedAt 淘汰最旧，留下别人 20 台设备就会把本用例刚写进去的档案挤掉
        AppStore.clearSnapshots()
        DeviceProfiles.resetForTest()
        AppStore.snapshotEnabled = true
        AppStore.savedAddress = null
        AppStore.savedDeviceName = null
        // 档案决定家族：扫描结果里没有它时，connect() 靠档案判出"陆行电量计"
        DeviceProfiles.upsert(DeviceProfile(
            address = meterAddr, name = meterName, lastConnectedAt = epochMillisNow(), family = DeviceFamily.LuXing,
        ))
        MockBms.clearForRealDevice()

        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val transport = FakeMeterTransport(realtime108, bms90)
            val repo = BmsRepository(scope, MockBmsEngine(scope))
            repo.setRealTransport(transport)
            repo.start()
            runBlocking {
                repo.connectTo(meterAddr)
                // 轮询一拍内就会收到 108 帧，落快照再等一拍内剩余应答（见 METER_SNAPSHOT_SETTLE_MS）
                var waited = 0
                while (AppStore.snapshotIds().isEmpty() && waited < 10_000) {
                    delay(50); waited += 50
                }
                assertTrue(
                    AppStore.snapshotIds().isNotEmpty(),
                    "电量计会话应落一张快照；等待 ${waited}ms 后仍为空" +
                        "（链路 ${repo.linkState.value}，家族 ${transport.currentFamily.value}，" +
                        "hasData=${MockBms.status.value.hasData}，电压=${MockBms.status.value.totalVoltage}，" +
                        "开关=${AppStore.snapshotEnabled}）",
                )
            }
            val ids = AppStore.snapshotIds()
            assertEquals(1, ids.size, "一次连接只记一张（同会话覆盖同一张）")

            val snap = SnapshotCodec.decode(AppStore.loadSnapshotJson(ids[0])!!)!!
            assertEquals(meterName, snap.deviceName, "快照名必须是连接的那台电量计")
            assertEquals(meterAddr, snap.deviceAddress)
            assertEquals(93.79, snap.status.remainCapAh, 1e-9, "剩余容量 = SOC×额定容量（官方口径）")
            assertEquals(113.0, snap.status.totalCapAh, 1e-9)
            // 中继的那块保护板只进「中继信息」字段，绝不能顶替设备名/设备地址
            assertEquals("ANT@BLE24CBUB-3547", snap.status.relayBoardName)
            assertEquals("蚂蚁保护板", snap.status.relayBoardBrand)
            assertNotEquals(snap.status.relayBoardName, snap.deviceName, "中继板的名字是另一台设备")
            assertNotEquals("F9:99:1B:2B:1B:70", snap.deviceAddress, "设备地址不得被中继板的 MAC 顶替")
            assertTrue(!snap.status.connected, "快照恒为离线数据")
        } finally {
            scope.cancel()
            AppStore.clearSnapshots()
            DeviceProfiles.resetForTest()
            AppStore.savedAddress = null
            AppStore.savedDeviceName = null
            AppStore.autoReconnect = true   // 复位成默认：别的用例靠它自动连（ParamsCacheTest）
            MockBms.clearForRealDevice()
        }
    }

    /**
     * 只认陆行的假传输：链路立刻就绪，收到哪条轮询就回对应的真机实录帧（不校验 CRC，
     * 与 LuXingProtocol.onData 的实际判据一致）。其余扫描/通道能力一律用接口默认值。
     */
    private class FakeMeterTransport(
        private val f108: ByteArray,
        private val f90: ByteArray,
    ) : BmsTransport {
        private val _linkState = MutableStateFlow(LinkState.Idle)
        override val linkState: StateFlow<LinkState> = _linkState
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        override val incoming: SharedFlow<ByteArray> = _incoming
        private val _family = MutableStateFlow(DeviceFamily.Unknown)
        override val currentFamily: StateFlow<DeviceFamily> = _family

        override fun setTargetFamily(family: DeviceFamily) { _family.value = family }
        override suspend fun connect(address: String?) { _linkState.value = LinkState.Connected }
        override suspend fun disconnect() { _linkState.value = LinkState.Disconnected }

        override suspend fun write(frame: ByteArray) {
            if (_linkState.value != LinkState.Connected) return
            when {
                frame.contentEquals(modbusReadFrame(1, 4092, 54)) -> _incoming.emit(f108)
                frame.contentEquals(modbusReadFrame(1, 4200, 45)) -> _incoming.emit(f90)
            }
        }

        /** 通道协商在生产代码里由真实传输完成；这里直接给出电量计通道，避免 repo 走探测分支 */
        private val _channel = MutableStateFlow<BleChannel?>(BleChannel.Meter)
        override val activeChannel: StateFlow<BleChannel?> = _channel
    }
}
