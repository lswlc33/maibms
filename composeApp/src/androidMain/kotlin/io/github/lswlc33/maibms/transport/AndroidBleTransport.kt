package io.github.lswlc33.maibms.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Android BLE 传输（docs/02-蓝牙链路.md）：
 * 扫描 ANT 前缀 → 连接 → 发现 FFE0 服务 → 通道探测候选（FFE1 / FFF3-4 / FFF5-6）
 * → 先订阅通知 → 请求 MTU 512（失败按 20）→ 写入按 MTU-10 分片、间隔 12ms。
 *
 * 重连策略（2026-09-29 真机联调后重写）：真机首次联调发现 Android/MIUI 上
 * 「直接 connectGatt 一次」失败率很高（HCI 0x08 超时 / 0x3E 建链失败，多为信号弱或蓝牙栈
 * 未收干净），因此改成常驻重连循环：停扫描后等控制器静默 → 直连 → 失败退避重试，
 * 每第 4 次改用等待式（autoConnect）连接；链路掉线后自动重连。
 * 失败原因经 connectHint 反馈到界面（原先连接失败时界面只显示「重连中」，用户无从判断）。
 */
class AndroidBleTransport(private val context: Context) : BmsTransport {

    companion object {
        private val SERVICE = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        /** 通道候选：写特征 / 通知特征（docs/02 §2.2） */
        private val CHANNELS = listOf(
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB") to UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB") to UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("0000FFF5-0000-1000-8000-00805F9B34FB") to UUID.fromString("0000FFF6-0000-1000-8000-00805F9B34FB"),
        )
        const val MTU_TARGET = 512

        /** 单次 GATT 写分片上限：MTU 协商到 512 时若按 502 写字，部分 ROM/从机会静默丢包 */
        const val MAX_CHUNK = 240
        const val WRITE_GAP_MS = 12L

        /** 停扫描后等控制器静默再建链：扫描未收干净时 connectGatt 极易报 0x3E */
        const val SCAN_SETTLE_MS = 450L
        const val CONNECT_TIMEOUT_MS = 12_000L
        /** 等待式连接（autoConnect）走广播窗口，给更长超时 */
        const val CONNECT_TIMEOUT_AUTO_MS = 25_000L
    }

    private val _linkState = MutableStateFlow(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: MutableSharedFlow<ByteArray> get() = _incoming
    override val supportsScan: Boolean get() = true

    private val _connectHint = MutableStateFlow<String?>(null)
    override val connectHint: StateFlow<String?> = _connectHint

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    @Volatile private var mtu: Int = 20
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 用户想要连接的目标；null = 已主动断开。重连循环靠它判断是否该继续 */
    @Volatile private var desiredAddress: String? = null
    @Volatile private var manualStop = false
    private var loopJob: Job? = null

    /** 本次尝试的「就绪」信号：true=服务/通知就绪，false=就绪前掉线 */
    private var readySignal: CompletableDeferred<Boolean>? = null
    /** 已就绪后的掉线信号 */
    private var dropSignal: CompletableDeferred<Unit>? = null

    private val LOG = io.github.lswlc33.maibms.data.BmsLog
    private fun logI(msg: String) = LOG.i("BLE", msg)
    private fun logW(msg: String) = LOG.w("BLE", msg)
    private fun logE(msg: String) = LOG.e("BLE", msg)
    private fun logD(msg: String) = LOG.d("BLE", msg)

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    /** HCI/GATT 状态码 → 人话（真机联调时的失败原因几乎都出在这里） */
    private fun statusText(s: Int): String = when (s) {
        0 -> "成功"
        8 -> "链路超时（设备未响应，通常距离过远或有遮挡）"
        19 -> "设备侧主动断开"
        22 -> "本机主动断开"
        62 -> "建链失败 0x3E（信号弱或设备正被其它主机占用）"
        133 -> "GATT 133（协议栈异常，重试可恢复）"
        257 -> "建链失败 0x101"
        else -> "错误码 $s"
    }

    // ---------------- 扫描 ----------------

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    override suspend fun scan(onFound: (ScanDevice) -> Unit) {
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) throw IllegalStateException("设备无蓝牙适配器或蓝牙未打开")
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (!name.startsWith("ANT", ignoreCase = true)) return
                logD("scan 发现 $name ${result.device.address} rssi=${result.rssi}")
                onFound(ScanDevice(name, result.device.address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                logE("扫描失败 errorCode=$errorCode")
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // 名称前缀过滤在回调内做（不同 ROM 对硬件过滤支持不一）
        logI("开始扫描 ANT 设备")
        scanner.startScan(emptyList<ScanFilter>(), settings, cb)
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        scanCallback?.let { runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCallback = null
    }

    // ---------------- 连接 ----------------

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String?) {
        val adapter = manager?.adapter
        if (adapter == null) {
            _connectHint.value = "本机没有蓝牙适配器"
            _linkState.value = LinkState.Disconnected
            return
        }
        val device = address?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
            ?: adapter.bondedDevices?.firstOrNull { it.name?.startsWith("ANT", true) == true }
            ?: run {
                _connectHint.value = "没有指定设备，也没有已配对的 ANT 设备"
                _linkState.value = LinkState.Disconnected
                return
            }
        manualStop = false
        desiredAddress = device.address
        _linkState.value = LinkState.Connecting
        _connectHint.value = "正在连接 ${(device.name ?: device.address).trim()}…"
        logI("连接目标 ${device.name} ${device.address}")
        stopScan()
        loopJob?.cancel()
        loopJob = scope.launch {
            delay(SCAN_SETTLE_MS)
            connectionLoop(device)
        }
    }

    /**
     * 常驻重连循环：每次尝试都是「新开 GATT → 等就绪 → 等到掉线」，
     * 任一步失败都退避后再来（用户不主动断开就一直重试）。
     */
    private suspend fun connectionLoop(device: BluetoothDevice) {
        var attempt = 0
        while (!manualStop && desiredAddress == device.address && currentCoroutineContext().isActive) {
            attempt++
            // 每第 4 次改用等待式连接：direct connect 在弱信号下会立刻失败，而等待式能等到设备广播
            val useAuto = attempt % 4 == 0
            if (attempt > 1) _connectHint.value = "第 $attempt 次尝试${if (useAuto) "（等待设备广播）" else ""}…"
            logI("连接尝试 #$attempt${if (useAuto) "（等待广播）" else ""}")
            _linkState.value = LinkState.Connecting

            val ready = CompletableDeferred<Boolean>()
            readySignal = ready
            dropSignal = null
            val started = openGatt(device, useAuto)
            if (!started) {
                _connectHint.value = "蓝牙栈拒绝了连接请求"
            } else {
                val outcome = withTimeoutOrNull(if (useAuto) CONNECT_TIMEOUT_AUTO_MS else CONNECT_TIMEOUT_MS) { ready.await() }
                if (outcome == true) {
                    _connectHint.value = null
                    _linkState.value = LinkState.Connected
                    logI("链路就绪（MTU ${mtu}）")
                    val dropped = CompletableDeferred<Unit>()
                    dropSignal = dropped
                    val connectedAt = System.currentTimeMillis()
                    runCatching { dropped.await() }
                    // 掉线：短命连接（<5s）按失败计入退避，避免弱信号下疯狂重连
                    val livedMs = System.currentTimeMillis() - connectedAt
                    closeGatt()
                    _linkState.value = LinkState.Disconnected
                    if (manualStop || !currentCoroutineContext().isActive) break
                    if (livedMs < 5_000) {
                        attempt = attempt.coerceAtLeast(1)
                        _connectHint.value = "连接建立后很快断开（${statusText(lastDisconnectStatus)}）"
                    } else {
                        attempt = 0
                        _connectHint.value = "连接已断开，正在重连…"
                    }
                } else {
                    closeGatt()
                    _connectHint.value = if (outcome == null) "连接超时，正在重试…"
                                        else "连接失败（${statusText(lastDisconnectStatus)}）"
                }
            }
            readySignal = null
            if (manualStop || !currentCoroutineContext().isActive) break
            val wait = (1_000L shl minOf(attempt, 3)).coerceAtMost(8_000L)
            delay(wait)
        }
        // 正常路径下这里只有手动断开；异常退出时补一次收尾，别让界面卡在「重连中」
        if (desiredAddress == null) {
            closeGatt()
            _linkState.value = LinkState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice, autoConnect: Boolean): Boolean {
        closeGatt()
        mtu = 20
        val g = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, autoConnect, callback)
            }
        }.onFailure { logE("connectGatt 异常：$it") }.getOrNull()
        gatt = g
        return g != null
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        writeChar = null
        notifyChar = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    @Volatile private var lastDisconnectStatus = 0

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // 旧 GATT 的迟到回调（重试时上一次连接的收尾）必须丢弃，否则会把新连接判死
            if (g !== gatt) { runCatching { g.close() }; return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logD("GATT 已连接 status=${statusText(status)}")
                    runCatching { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    lastDisconnectStatus = status
                    logW("GATT 断开：${statusText(status)}")
                    closeGatt()
                    _linkState.value = LinkState.Disconnected
                    readySignal?.complete(false)
                    dropSignal?.complete(Unit)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logE("发现服务失败：${statusText(status)}")
                readySignal?.complete(false)
                runCatching { g.disconnect() }
                return
            }
            logD("服务列表: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
            val service = g.getService(SERVICE) ?: run {
                logE("设备没有 FFE0 服务（不是 ANT 保护板？）")
                readySignal?.complete(false)
                runCatching { g.disconnect() }
                return
            }
            // 通道候选：优先 FFE1
            for ((w, n) in CHANNELS) {
                val wc = service.getCharacteristic(w)
                val nc = service.getCharacteristic(n)
                if (wc != null && nc != null) { writeChar = wc; notifyChar = nc; break }
            }
            val wc = writeChar ?: run {
                logE("FFE0 下没有可用的写/通知通道")
                readySignal?.complete(false)
                runCatching { g.disconnect() }
                return
            }
            logI("通道选中 写=%04X 通知=%04X".format(wc.uuid.toString().substring(4, 8).toInt(16), notifyChar!!.uuid.toString().substring(4, 8).toInt(16)))
            // 先订阅通知（docs 硬性顺序）
            runCatching { g.setCharacteristicNotification(notifyChar, true) }
            val desc = notifyChar?.getDescriptor(CCCD)
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    runCatching { g.writeDescriptor(desc) }
                }
            }
            // MTU 只影响写分片，异步协商，不阻塞「可用」判定
            runCatching { g.requestMtu(MTU_TARGET) }
            _linkState.value = LinkState.Connected
            readySignal?.complete(true)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logD("MTU 协商=$mtu status=$status")
            if (g === gatt) this@AndroidBleTransport.mtu = mtu
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            logD("CCCD 写入 status=$status")
            // 订阅已落地：即使服务发现回调里来不及标记，这里也补一次
            if (g === gatt && writeChar != null) {
                _linkState.value = LinkState.Connected
                readySignal?.complete(true)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = c.value ?: return
            logD("← ${hex(data)}")
            _incoming.tryEmit(data.copyOf())
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            logD("← ${hex(value)}")
            _incoming.tryEmit(value.copyOf())
        }
    }

    // ---------------- 写入 ----------------

    @SuppressLint("MissingPermission")
    override suspend fun write(frame: ByteArray) {
        val g = gatt ?: run { logW("写入被丢弃：未连接"); return }
        val wc = writeChar ?: run { logW("写入被丢弃：无写通道"); return }
        if (_linkState.value != LinkState.Connected) { logW("写入被丢弃：链路未就绪"); return }
        // 帧日志统一走协议层的遮蔽版：0x23 密码帧的数据区不能被原样写进日志
        logD("→ ${io.github.lswlc33.maibms.protocol.Frame.hexForLog(frame)}")
        val chunk = minOf(mtu - 10, MAX_CHUNK).coerceAtLeast(10)
        var offset = 0
        while (offset < frame.size) {
            val len = minOf(chunk, frame.size - offset)
            val piece = frame.copyOfRange(offset, offset + len)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(wc, piece, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                wc.value = piece
                @Suppress("DEPRECATION")
                wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(wc)
            }
            offset += len
            delay(WRITE_GAP_MS)
        }
    }

    /** 主动断开：清掉目标地址，重连循环自然退出 */
    override suspend fun disconnect() {
        manualStop = true
        desiredAddress = null
        loopJob?.cancel()
        loopJob = null
        stopScan()
        closeGatt()
        _linkState.value = LinkState.Disconnected
        _connectHint.value = null
    }
}
