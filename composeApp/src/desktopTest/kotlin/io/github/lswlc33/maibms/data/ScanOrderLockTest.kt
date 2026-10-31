package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.transport.BmsTransport
import io.github.lswlc33.maibms.transport.DeviceFamily
import io.github.lswlc33.maibms.transport.LinkState
import io.github.lswlc33.maibms.transport.ScanDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 扫描期间**列表顺序必须稳定**。
 *
 * 真机报障（iOS）：「附近的设备」正在扫描出来的新设备点不中、也无法确认连接。
 * 根因是每拍都按信号强度整表重排——用户按在某一行上、松手之前它已被挪走，
 * iOS 的触摸判定就会丢掉这次点击（Android 宽容所以看不出来）。
 *
 * 修法：扫描期间已出现的设备**原位更新**（不重排、不换位置），新设备追加到末尾；
 * 排序只在扫描结束时统一做一次。这条用例钉住这个契约。
 */
class ScanOrderLockTest {

    /** 只用来驱动扫描回调的假传输；connect 等一律不参与本用例 */
    private class FakeScanTransport : BmsTransport {
        override val linkState: Flow<LinkState> = MutableStateFlow(LinkState.Idle)
        override val incoming: Flow<ByteArray> = MutableStateFlow(ByteArray(0))
        override suspend fun connect(address: String?) {}
        override suspend fun disconnect() {}
        override suspend fun write(frame: ByteArray) {}
        override fun stopScan() {}
        override val supportsScan: Boolean get() = true

        var onFound: ((ScanDevice) -> Unit)? = null
        override suspend fun scan(onFound: (ScanDevice) -> Unit) { this.onFound = onFound }
    }

    private fun dev(name: String, rssi: Int) =
        ScanDevice(name, "AA:BB:CC:DD:EE:${name.takeLast(2)}", rssi, DeviceFamily.Ant)

    @Test
    fun existingRowsKeepTheirPositionWhileScanning() = runBlocking {
        val t = FakeScanTransport()
        Bms.repository.setRealTransport(t)
        Bms.repository.startScan()
        val emit = requireNotNull(t.onFound) { "startScan 应注册扫描回调" }

        emit(dev("ANT-01", -60))
        emit(dev("ANT-02", -50))
        emit(dev("ANT-03", -70))
        assertEquals(
            listOf("ANT-01", "ANT-02", "ANT-03"),
            Bms.repository.scanResults.value.map { it.name },
            "首次发现按到达顺序追加",
        )

        // 关键：ANT-03 的信号变强到全场第一，也不能因此跳位——否则用户按着的手指会落在别的设备上
        emit(dev("ANT-03", -30))
        assertEquals(
            listOf("ANT-01", "ANT-02", "ANT-03"),
            Bms.repository.scanResults.value.map { it.name },
            "扫描中已出现的设备必须留在原位（RSSI 更新但不重排）",
        )
        assertEquals(
            -30, Bms.repository.scanResults.value.last().rssi,
            "原位更新仍要把 RSSI 刷成最新值",
        )

        // 新设备追加到末尾，同样不插队
        emit(dev("ANT-04", -20))
        assertEquals(
            listOf("ANT-01", "ANT-02", "ANT-03", "ANT-04"),
            Bms.repository.scanResults.value.map { it.name },
            "扫描中新发现的设备追加到末尾",
        )

        // 同一个地址重复上报不得产生重复行
        emit(dev("ANT-02", -45))
        assertEquals(4, Bms.repository.scanResults.value.size, "同一地址只保留一行")

        Bms.repository.stopScan()
    }

    @Test
    fun sortingHappensOnceWhenScanStops() = runBlocking {
        val t = FakeScanTransport()
        Bms.repository.setRealTransport(t)
        Bms.repository.startScan()
        val emit = requireNotNull(t.onFound)

        emit(dev("ANT-01", -80))
        emit(dev("ANT-02", -40))
        emit(dev("ANT-03", -60))
        assertEquals(
            listOf("ANT-01", "ANT-02", "ANT-03"),
            Bms.repository.scanResults.value.map { it.name },
        )

        // 停下来之后才排序：信号最强的排最前
        Bms.repository.stopScan()
        assertEquals(
            listOf("ANT-02", "ANT-03", "ANT-01"),
            Bms.repository.scanResults.value.map { it.name },
            "扫描结束后按 RSSI 降序统一排序一次",
        )
    }
}
