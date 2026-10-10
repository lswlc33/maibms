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
        /** 备用通道的能力判据：设备是否暴露可写 FFF5（docs/02 §2.2） */
        private val UUID_FFF5 = UUID.fromString("0000FFF5-0000-1000-8000-00805F9B34FB")
        /** 通道探测：超时 8s、切通道间隔 200ms（与官方一致，docs/02 §2.2） */
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_SETTLE_MS = 200L

        /** 短 UUID（"FFF3"）→ 完整 128 位 UUID */
        private fun uuidOf(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")
        const val MTU_TARGET = 512

        /** 单次 GATT 写分片上限：MTU 协商到 512 时若按 502 写字，部分 ROM/从机会静默丢包 */
        const val MAX_CHUNK = 240
        const val WRITE_GAP_MS = 12L

        /** 停扫描后等控制器静默再建链：扫描未收干净时 connectGatt 极易报 0x3E */
        const val SCAN_SETTLE_MS = 450L
        const val CONNECT_TIMEOUT_MS = 12_000L
        /** 等待式连接（autoConnect）走广播窗口，给更长超时 */
        const val CONNECT_TIMEOUT_AUTO_MS = 25_000L
        /** 就绪兜底：个别 ROM 不回调 onDescriptorWrite，超过这个时间就按「订阅已发出」继续 */
        const val READY_FALLBACK_MS = 250L
    }

    private val _linkState = MutableStateFlow(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: MutableSharedFlow<ByteArray> get() = _incoming
    override val supportsScan: Boolean get() = true

    private val _connectHint = MutableStateFlow<String?>(null)
    override val connectHint: StateFlow<String?> = _connectHint

    // ---- 通信通道（docs/02 §2.2）：候选 → 逐个探测 → 选中的才置「就绪」 ----

    private val _activeChannel = MutableStateFlow<BleChannel?>(null)
    override val activeChannel: StateFlow<BleChannel?> = _activeChannel
    private val _availableChannels = MutableStateFlow<List<BleChannel>>(emptyList())
    override val availableChannels: StateFlow<List<BleChannel>> = _availableChannels
    override val supportsChannelSwitch: Boolean get() = true

    // ---- 设备家族（按广播名判定；决定服务/通道/握手策略）----

    private val _currentFamily = MutableStateFlow(DeviceFamily.Unknown)
    override val currentFamily: StateFlow<DeviceFamily> = _currentFamily

    /** 本次连接的目标家族；由上层在 connect() 前用 setTargetFamily() 写入 */
    @Volatile private var targetFamily: DeviceFamily = DeviceFamily.Ant

    override fun setTargetFamily(family: DeviceFamily) {
        targetFamily = family
    }

    /** 一组候选：通道定义 + 实际拿到的写/通知特征 */
    private data class Candidate(
        val channel: BleChannel,
        val write: BluetoothGattCharacteristic,
        val notify: BluetoothGattCharacteristic,
    )

    private var candidates: List<Candidate> = emptyList()
    private var candidateIndex = 0
    private var probeStarted = false
    @Volatile private var probeSignal: CompletableDeferred<Boolean>? = null

    /** 下次连接优先尝试的通道（手动切换或按设备记忆；仅本进程有效） */
    @Volatile private var preferred: BleChannel? = null

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
    /** 服务发现完成信号：让「建链 + 发现服务」与「通道探测」两段超时分开算 */
    @Volatile private var servicesSignal: CompletableDeferred<Unit>? = null
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

    // ---------------- 通道（特征）工具 ----------------

    private fun shortUuid(u: UUID): String = u.toString().substring(4, 8).uppercase()

    private fun isWritable(c: BluetoothGattCharacteristic?): Boolean {
        val p = c?.properties ?: return false
        val mask = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
        return (p and mask) != 0
    }

    private fun isNotifiable(c: BluetoothGattCharacteristic?): Boolean {
        val p = c?.properties ?: return false
        val mask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE
        return (p and mask) != 0
    }

    /** 特征属性 → 人话（写进日志，用于回答"这块板到底有没有备用通道"） */
    private fun propText(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("读")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("写")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("写无响应")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("通知")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("指示")
    }.joinToString("/").ifEmpty { "无" }

    /**
     * 生成候选通道：先按官方规则决定"这块硬件有没有备用通道"，
     * 再按实际特征与属性过滤（写特征要可写、通知特征要能 notify/indicate）。
     */
    private fun buildCandidates(service: BluetoothGattService): List<Candidate> {
        // 电量计（中继器）：FFE0 下就一对 写 FFE2 / 通知 FFE1；找不到就回退到“第一个可写 / 第一个可通知”
        if (targetFamily.isMeter) {
            val w = service.getCharacteristic(uuidOf(BleChannel.Meter.writeUuid))
                ?: service.characteristics.firstOrNull { isWritable(it) }
            val n = service.getCharacteristic(uuidOf(BleChannel.Meter.notifyUuid))
                ?: service.characteristics.firstOrNull { isNotifiable(it) }
            return if (w != null && n != null && isWritable(w) && isNotifiable(n)) {
                listOf(Candidate(BleChannel.Meter, w, n))
            } else emptyList()
        }
        val hasWritableFff5 = isWritable(service.getCharacteristic(UUID_FFF5))
        return bleChannelCandidates(hasWritableFff5).mapNotNull { ch ->
            val w = service.getCharacteristic(uuidOf(ch.writeUuid))
            val n = service.getCharacteristic(uuidOf(ch.notifyUuid))
            if (w != null && n != null && isWritable(w) && isNotifiable(n)) Candidate(ch, w, n) else null
        }
    }

    /**
     * 订阅落地后的动作：保护板走 ANT 探测（发实时帧等 0x11/0x12 应答），
     * 电量计暂无探测帧，直接按“FFE0 + 写/通知就绪”判就绪（后续阶段再补各家握手）。
     */
    private fun afterSubscribed(g: BluetoothGatt, c: Candidate) {
        if (g !== gatt) return
        if (targetFamily.isMeter) {
            _activeChannel.value = c.channel
            logI("电量计通道就绪：${c.channel.id}（家族 ${targetFamily.label}，跳过保护板探测）")
            completeReady(g)
        } else {
            startProbe(g, c)
        }
    }

    /** 探测应答判定：以 7E 开头、功能码 0x11/0x12（实时数据/参数应答，docs/02 §2.2） */
    private fun looksLikeProbeResponse(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0x7E.toByte() &&
            (b[2] == 0x11.toByte() || b[2] == 0x12.toByte())

    /**
     * 试下一个候选通道：订阅通知 →（CCCD 落地后）发探测帧等应答。
     * 全部候选都失败就按本轮连接失败处理，交给重连循环退避重试。
     */
    @SuppressLint("MissingPermission")
    private fun probeNext(g: BluetoothGatt) {
        val c = candidates.getOrNull(candidateIndex)
        if (c == null) {
            logE("候选通道全部探测失败（共 ${candidates.size} 条）")
            _activeChannel.value = null
            readySignal?.complete(false)
            runCatching { g.disconnect() }
            return
        }
        writeChar = c.write
        notifyChar = c.notify
        probeStarted = false
        logI("通道探测 ${candidateIndex + 1}/${candidates.size}：${c.channel.id}（写 ${shortUuid(c.write.uuid)} / 通知 ${shortUuid(c.notify.uuid)}）")
        _connectHint.value = "通道探测：${c.channel.label}…"
        runCatching { g.setCharacteristicNotification(c.notify, true) }
        val desc = c.notify.getDescriptor(CCCD)
        if (desc == null) {
            logW("通知特征上没有 CCCD 描述符，直接发探测帧")
            runCatching { g.requestMtu(MTU_TARGET) }
            runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            afterSubscribed(g, c)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
        } else {
            @Suppress("DEPRECATION")
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            runCatching { g.writeDescriptor(desc) }
        }
        // MTU 只影响写分片，异步协商；连接间隔提到最高档，命令往返能少几十毫秒
        runCatching { g.requestMtu(MTU_TARGET) }
        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        // CCCD 写回调个别 ROM 不给：超时也按「订阅已发出」进入探测，别把连接卡死
        scope.launch {
            delay(READY_FALLBACK_MS)
            if (g === gatt) afterSubscribed(g, c)
        }
    }

    /** 发探测帧并等应答；通过就置「就绪」，否则换下一个候选 */
    private fun startProbe(g: BluetoothGatt, c: Candidate) {
        if (probeStarted || g !== gatt) return
        // 迟到的兜底（候选已经翻页）不能再探：否则会拿旧候选的写特征去发帧
        if (candidates.getOrNull(candidateIndex) !== c) return
        probeStarted = true
        val signal = CompletableDeferred<Boolean>()
        probeSignal = signal
        scope.launch {
            delay(PROBE_SETTLE_MS)   // 等订阅生效，与官方 200ms 切通道间隔一致
            if (g !== gatt) return@launch
            val probe = io.github.lswlc33.maibms.protocol.Frame.readRealtime()
            runCatching { writeChunks(g, c.write, probe) }
                .onFailure { logW("探测帧写入失败：${it.message}") }
            val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { signal.await() } ?: false
            if (g !== gatt) return@launch
            probeSignal = null
            if (ok) {
                _activeChannel.value = c.channel
                logI("通道就绪：${c.channel.label}（${c.channel.id}）")
                completeReady(g)
            } else {
                logW("通道 ${c.channel.id} 无应答（${PROBE_TIMEOUT_MS}ms 超时），换下一个候选")
                runCatching { g.setCharacteristicNotification(c.notify, false) }   // 退订这条通道，免得它的帧还往上游送
                candidateIndex++
                probeNext(g)
            }
        }
    }

    /** 标记链路就绪：置连接态并唤醒等待中的重连循环。只在 g 仍是当前 GATT 时生效（丢弃迟到回调） */
    private fun completeReady(g: BluetoothGatt) {
        if (g !== gatt) return
        _linkState.value = LinkState.Connected
        readySignal?.complete(true)
    }

    // ---------------- 扫描 ----------------

    private var scanCallback: ScanCallback? = null

    /** 最近一次**真的停掉扫描**的时刻（uptime）；0 = 本次进程没扫过。见 connect() 里的静默等待 */
    @Volatile private var scanStoppedAt = 0L

    @SuppressLint("MissingPermission")
    override suspend fun scan(onFound: (ScanDevice) -> Unit) {
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) throw IllegalStateException("设备无蓝牙适配器或蓝牙未打开")
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                // 按广播名判定家族：保护板（ANT）+ 两家电量计；陆行同厂控制器 CJ01 显式排除
                if (!DeviceFamily.isTarget(name)) return
                val family = DeviceFamily.matchName(name) ?: return
                logD("scan 发现 $name ${result.device.address} rssi=${result.rssi} → ${family.label}")
                onFound(ScanDevice(name, result.device.address, result.rssi, family))
            }

            override fun onScanFailed(errorCode: Int) {
                logE("扫描失败 errorCode=$errorCode")
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // 名称前缀过滤在回调内做（不同 ROM 对硬件过滤支持不一）
        logI("开始扫描（保护板 ANT + 电量计 蓝宝/陆行）")
        scanner.startScan(emptyList<ScanFilter>(), settings, cb)
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        val cb = scanCallback ?: return
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        scanCallback = null
        scanStoppedAt = android.os.SystemClock.uptimeMillis()
    }

    /** 建链前还要等多久的「扫描静默」；本次进程没扫过就是 0（冷启动直连不必白等 450ms） */
    private fun settleWaitMs(): Long {
        val stopped = scanStoppedAt
        if (stopped == 0L) return 0L
        return (SCAN_SETTLE_MS - (android.os.SystemClock.uptimeMillis() - stopped))
            .coerceIn(0L, SCAN_SETTLE_MS)
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
            // 没指定地址就退回已配对的**目标设备**（保护板或电量计）；读 bondedDevices 要权限，缺权限时不能把协程掀掉
            ?: runCatching { adapter.bondedDevices?.firstOrNull { DeviceFamily.isTarget(it.name) } }.getOrNull()
            ?: run {
                _connectHint.value = "没有指定设备，也没有已配对的目标设备"
                _linkState.value = LinkState.Disconnected
                return
            }
        manualStop = false
        desiredAddress = device.address
        _currentFamily.value = targetFamily
        _linkState.value = LinkState.Connecting
        _connectHint.value = "正在连接 ${(device.name ?: device.address).trim()}…"
        logI("连接目标 ${device.name} ${device.address}（家族 ${targetFamily.label}）")
        stopScan()
        loopJob?.cancel()
        // 扫描刚停就直连会报 0x3E（真机实测），所以要等控制器静默；
        // 但冷启动根本没扫过，等这 450ms 就是纯粹白等——按"距上次真正停扫过了多久"来算。
        val settle = settleWaitMs()
        if (settle > 0) logD("停扫描后等静默 ${settle}ms 再建链")
        loopJob = scope.launch {
            if (settle > 0) delay(settle)
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
            val servicesFound = CompletableDeferred<Unit>()
            servicesSignal = servicesFound
            dropSignal = null
            val started = openGatt(device, useAuto)
            if (!started) {
                _connectHint.value = "蓝牙栈拒绝了连接请求"
            } else {
                // 两段超时：① 建链 + 发现服务用连接超时；② 通道探测按候选条数给预算
                //（每条候选最多 PROBE_TIMEOUT_MS；官方也是 8s/条，不能挤进 12s 里）
                val connectBudget = if (useAuto) CONNECT_TIMEOUT_AUTO_MS else CONNECT_TIMEOUT_MS
                val discovered = withTimeoutOrNull(connectBudget) { servicesFound.await() } != null
                val outcome: Boolean? = if (!discovered) null else {
                    val probeBudget = PROBE_TIMEOUT_MS * candidates.size.coerceAtLeast(1) + 2_000L
                    withTimeoutOrNull(probeBudget) { ready.await() }
                }
                if (outcome == true) {
                    _connectHint.value = null
                    _linkState.value = LinkState.Connected
                    logI("链路就绪（MTU ${mtu}，通道 ${_activeChannel.value?.id ?: "?"}）")
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
            servicesSignal = null
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

    /**
     * 收尾清理：disconnect/close 在 API 31+ 需要 BLUETOOTH_CONNECT，权限被回收时抛
     * SecurityException——这里都是尽力而为的清理调用，runCatching 已兜住，注解仅为标注意图
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        writeChar = null
        notifyChar = null
        probeSignal = null
        servicesSignal = null
        probeStarted = false
        candidates = emptyList()
        candidateIndex = 0
        _activeChannel.value = null
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
                    servicesSignal?.complete(Unit)
                    readySignal?.complete(false)
                    dropSignal?.complete(Unit)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            // 阶段一结束（无论成败）：让建链等待尽快落到"服务发现完毕"
            servicesSignal?.complete(Unit)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logE("发现服务失败：${statusText(status)}")
                readySignal?.complete(false)
                runCatching { g.disconnect() }
                return
            }
            logD("服务列表: " + g.services.joinToString { shortUuid(it.uuid) })
            val service = g.getService(SERVICE) ?: run {
                logE("设备没有 FFE0 服务（不是已知的保护板/电量计？）")
                readySignal?.complete(false)
                runCatching { g.disconnect() }
                return
            }
            // 特征全集（含属性）：回答「这块板到底有没有备用通道」的第一手证据（每次连接一行）
            logI("特征列表: " + service.characteristics.joinToString {
                "${shortUuid(it.uuid)}(${propText(it.properties)})"
            })
            // 候选通道：先按官方规则判定有没有备用通道，再按实际特征属性过滤
            val found = buildCandidates(service)
            _availableChannels.value = found.map { it.channel }.ifEmpty { listOf(BleChannel.Default) }
            if (found.isEmpty()) {
                logE("FFE0 下没有可用的写/通知特征")
                readySignal?.complete(false)
                runCatching { g.disconnect() }
                return
            }
            logI("可用通道：${found.joinToString { it.channel.id }}" +
                if (found.size == 1) "（设备只提供默认通道，无备用通道）" else "")
            // 上次用过/手动指定的通道优先，其余按默认 → 备用 A → 备用 B 顺序
            val pref = preferred
            candidates = listOfNotNull(found.firstOrNull { it.channel == pref }) +
                found.filter { it.channel != pref }
            candidateIndex = 0
            servicesSignal?.complete(Unit)
            probeNext(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logD("MTU 协商=$mtu status=$status")
            if (g === gatt) this@AndroidBleTransport.mtu = mtu
        }

        // 主动断开也属尽力而为的清理：权限被回收时 SecurityException 由 runCatching 兜住
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || d.uuid != CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 订阅没落地 = 这条通道收不到任何帧：换下一个候选（没有下一个则本轮连接失败）
                logE("CCCD 写入失败：${statusText(status)}（订阅未落地，换下一个候选通道）")
                candidateIndex++
                probeNext(g)
                return
            }
            logD("CCCD 写入成功，订阅已落地，开始发探测帧")
            candidates.getOrNull(candidateIndex)?.let { afterSubscribed(g, it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = c.value ?: return
            logD("← ${hex(data)}")
            noteProbeResponse(data)
            _incoming.tryEmit(data.copyOf())
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            logD("← ${hex(value)}")
            noteProbeResponse(value)
            _incoming.tryEmit(value.copyOf())
        }
    }

    /** 探测期间：第一条 0x11/0x12 应答即视为该通道可用（应答照旧投给上层解析） */
    private fun noteProbeResponse(b: ByteArray) {
        val s = probeSignal ?: return
        if (!s.isCompleted && looksLikeProbeResponse(b)) s.complete(true)
    }

    // ---------------- 写入 ----------------

    @SuppressLint("MissingPermission")
    override suspend fun write(frame: ByteArray) {
        val g = gatt ?: run { logW("写入被丢弃：未连接"); return }
        val wc = writeChar ?: run { logW("写入被丢弃：无写通道"); return }
        if (_linkState.value != LinkState.Connected) { logW("写入被丢弃：链路未就绪"); return }
        // 帧日志统一走协议层的遮蔽版：0x23 密码帧的数据区不能被原样写进日志
        logD("→ ${io.github.lswlc33.maibms.protocol.Frame.hexForLog(frame)}")
        writeChunks(g, wc, frame)
    }

    /** 分片写入：业务帧与探测帧共用（探测发生在「就绪」之前，所以这里不检查链路态） */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunks(g: BluetoothGatt, wc: BluetoothGattCharacteristic, frame: ByteArray) {
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

    /** 记住下次连接优先尝试的通道（手动切换或按设备记忆） */
    override fun preferChannel(channel: BleChannel?) {
        preferred = channel
    }

    /** 手动切换通道：断开当前链路，交给常驻重连循环按新通道重连 */
    @SuppressLint("MissingPermission")
    override suspend fun switchChannel(channel: BleChannel) {
        preferred = channel
        val g = gatt
        if (g == null) {
            logI("已记录通道偏好 ${channel.id}（未连接，下次连接生效）")
            return
        }
        logI("手动切换通道 → ${channel.label}（${channel.id}），断开后按新通道重连")
        _connectHint.value = "正在切换到${channel.label}…"
        runCatching { g.disconnect() }
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
