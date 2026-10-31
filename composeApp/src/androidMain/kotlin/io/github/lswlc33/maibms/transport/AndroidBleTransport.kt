package io.github.lswlc33.maibms.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.github.lswlc33.maibms.data.PlatformLock
import io.github.lswlc33.maibms.data.withLock
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

    /** 回调、候选切换、收尾与 Connected 发布必须在同一把可重入锁内。 */
    private val attemptLock = PlatformLock()
    private val attempts = ConnectionAttempts()

    private val LOG = io.github.lswlc33.maibms.data.BmsLog

    /** 通知缓冲写满、帧被丢掉：记一笔给开发者页看（此前 tryEmit 的返回值被直接扔掉，丢了帧没人知道） */
    private fun noteDropped() = io.github.lswlc33.maibms.data.LinkStats.noteDroppedFrame()
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

    /** 退订一条候选通道。权限被回收时抛 SecurityException，这里按尽力而为处理。 */
    @SuppressLint("MissingPermission")
    private fun unsubscribe(g: BluetoothGatt, c: Candidate) {
        runCatching { g.setCharacteristicNotification(c.notify, false) }
    }

    /**
     * 订阅落地后的动作：保护板走 ANT 探测（发实时帧等 0x11/0x12 应答），
     * 电量计暂无探测帧，直接按“FFE0 + 写/通知就绪”判就绪（后续阶段再补各家握手）。
     */
    private fun afterSubscribed(g: BluetoothGatt, c: Candidate): Unit = withLock(attemptLock) {
        val owner = attempts.current ?: return@withLock
        if (g !== gatt || !attempts.acceptsCandidate(owner, c)) return@withLock
        if (targetFamily.isMeter) {
            completeReady(g, owner, c)
        } else {
            startProbe(g, owner, c)
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
    private fun probeNext(g: BluetoothGatt): Unit = withLock(attemptLock) {
        val owner = attempts.current ?: return@withLock
        if (g !== gatt || !attempts.owns(owner)) return@withLock
        val c = candidates.getOrNull(candidateIndex)
        if (c == null) {
            logE("候选通道全部探测失败（共 ${candidates.size} 条）")
            _activeChannel.value = null
            owner.ready.complete(false)
            runCatching { g.disconnect() }
            return@withLock
        }
        attempts.selectCandidate(owner, c)
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
            return@withLock
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
            afterSubscribed(g, c)
        }
    }

    /** 发探测帧并等应答；每次 suspend 后重新核查 attempt / GATT / 候选所有权。 */
    private fun startProbe(g: BluetoothGatt, owner: ConnectionAttempt, c: Candidate): Unit = withLock(attemptLock) {
        if (probeStarted || g !== gatt || !attempts.acceptsCandidate(owner, c)) return@withLock
        probeStarted = true
        val signal = CompletableDeferred<Boolean>()
        probeSignal = signal
        scope.launch {
            delay(PROBE_SETTLE_MS)
            val current = withLock(attemptLock) {
                g === gatt && attempts.acceptsCandidate(owner, c) && probeSignal === signal
            }
            if (!current) return@launch
            val probe = io.github.lswlc33.maibms.protocol.Frame.readRealtime()
            runCatching { writeChunks(g, c.write, probe) }
                .onFailure { logW("探测帧写入失败：${it.message}") }
            val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { signal.await() } ?: false
            withLock(attemptLock) {
                if (g !== gatt || !attempts.acceptsCandidate(owner, c) || probeSignal !== signal) return@launch
                probeSignal = null
                if (ok) {
                    completeReady(g, owner, c)
                } else {
                    logW("通道 ${c.channel.id} 无应答（${PROBE_TIMEOUT_MS}ms 超时），换下一个候选")
                    unsubscribe(g, c)
                    candidateIndex++
                    probeNext(g)
                }
            }
        }
    }

    /** valid/current GATT/drop 检查与 Connected 发布同锁，不允许掉线后被等待方重新置态。 */
    private fun completeReady(g: BluetoothGatt, owner: ConnectionAttempt, c: Candidate): Unit = withLock(attemptLock) {
        if (g !== gatt || !attempts.acceptsCandidate(owner, c)) return@withLock
        attempts.publishReady(owner) {
            _activeChannel.value = c.channel
            _linkState.value = LinkState.Connected
            _connectHint.value = null
            logI(if (targetFamily.isMeter) "电量计通道就绪：${c.channel.id}（跳过保护板探测）"
                 else "通道就绪：${c.channel.label}（${c.channel.id}）")
        }
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
                // 权限在扫描过程中被回收时，device.name/address 会抛 SecurityException；
                // 这里是 Binder 回调线程，未捕获会直接崩进程
                runCatching {
                    val name = result.device.name ?: return@runCatching
                    // 按广播名判定家族：保护板（ANT）+ 两家电量计；陆行同厂控制器 CJ01 显式排除
                    if (!DeviceFamily.isTarget(name)) return@runCatching
                    val family = DeviceFamily.matchName(name) ?: return@runCatching
                    logD("scan 发现 $name ${result.device.address} rssi=${result.rssi} → ${family.label}")
                    onFound(ScanDevice(name, result.device.address, result.rssi, family))
                }.onFailure { logW("扫描回调异常（权限被回收？）：${it.message}") }
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
        // 指定了地址就只连它：解析失败（地址不是合法 MAC）宁可报错重试，也不能退回已配对的
        // 另一台设备——那样会话会按**目标设备**的地址/密码/家族去管一台别的设备。
        // 只有「没指定地址」才退回已配对的**目标设备**（保护板或电量计）；
        // 读 bondedDevices 要权限，缺权限时不能把协程掀掉
        val device = if (address.isNullOrBlank()) {
            runCatching { adapter.bondedDevices?.firstOrNull { DeviceFamily.isTarget(it.name) } }.getOrNull()
        } else {
            runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        }
            ?: run {
                _connectHint.value = if (address.isNullOrBlank()) "没有指定设备，也没有已配对的目标设备"
                                     else "设备地址无效：$address"
                _linkState.value = LinkState.Disconnected
                return
            }
        loopJob?.cancelAndJoin()
        closeGatt()
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

            // 本轮 services / ready / drop 在 connectGatt 前一起创建，不能漏掉 ready→wait 窗口的掉线。
            val (owner, started) = withLock(attemptLock) {
                closeGatt()
                _linkState.value = LinkState.Connecting
                val owner = attempts.begin(device.address)
                owner to openGatt(device, useAuto)
            }
            try {
                if (!started) {
                    _connectHint.value = "蓝牙栈拒绝了连接请求"
                } else {
                    val connectBudget = if (useAuto) CONNECT_TIMEOUT_AUTO_MS else CONNECT_TIMEOUT_MS
                    val discovered = withTimeoutOrNull(connectBudget) { owner.services.await() } != null
                    val outcome: Boolean? = if (!discovered) null else {
                        val probeBudget = withLock(attemptLock) {
                            PROBE_TIMEOUT_MS * owner.candidateCount.coerceAtLeast(1) + 2_000L
                        }
                        withTimeoutOrNull(probeBudget) { owner.ready.await() }
                    }
                    if (outcome == true) {
                        // Connected 只由 completeReady 同锁发布，等待方绝不复活已经断开的链路。
                        val connectedAt = System.currentTimeMillis()
                        owner.dropped.await()
                        val livedMs = System.currentTimeMillis() - connectedAt
                        if (manualStop || !currentCoroutineContext().isActive) break
                        if (livedMs < 5_000) {
                            attempt = attempt.coerceAtLeast(1)
                            _connectHint.value = "连接建立后很快断开（${statusText(lastDisconnectStatus)}）"
                        } else {
                            attempt = 0
                            _connectHint.value = "连接已断开，正在重连…"
                        }
                    } else {
                        _connectHint.value = if (outcome == null) "连接超时，正在重试…"
                                            else "连接失败（${statusText(lastDisconnectStatus)}）"
                    }
                }
            } finally {
                closeGatt(owner)
            }
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
        mtu = 20
        // minSdk 26 ≥ M，直接走 LE 传输；API 23 以下的分支不可能到达
        val g = runCatching {
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        }.onFailure { logE("connectGatt 异常：$it") }.getOrNull()
        gatt = g
        return g != null
    }

    /**
     * 收尾清理：disconnect/close 在 API 31+ 需要 BLUETOOTH_CONNECT，权限被回收时抛
     * SecurityException——这里都是尽力而为的清理调用，runCatching 已兜住，注解仅为标注意图
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt(owner: ConnectionAttempt? = null): Unit = withLock(attemptLock) {
        if (owner != null && !attempts.owns(owner)) return@withLock
        val current = attempts.current
        val g = gatt
        val probe = probeSignal
        gatt = null
        writeChar = null
        notifyChar = null
        probeSignal = null
        probeStarted = false
        candidates = emptyList()
        candidateIndex = 0
        _activeChannel.value = null
        _linkState.value = LinkState.Disconnected
        if (current != null) attempts.finish(current)
        probe?.complete(false)
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    @Volatile private var lastDisconnectStatus = 0

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int): Unit = withLock(attemptLock) {
            // 旧 GATT 的迟到回调（重试时上一次连接的收尾）必须丢弃，否则会把新连接判死
            if (g !== gatt) { runCatching { g.close() }; return@withLock }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logD("GATT 已连接 status=${statusText(status)}")
                    runCatching { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    lastDisconnectStatus = status
                    logW("GATT 断开：${statusText(status)}")
                    closeGatt()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int): Unit = withLock(attemptLock) {
            val owner = attempts.current ?: return@withLock
            if (g !== gatt || !attempts.owns(owner)) return@withLock
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logE("发现服务失败：${statusText(status)}")
                owner.services.complete(Unit)
                owner.ready.complete(false)
                runCatching { g.disconnect() }
                return@withLock
            }
            logD("服务列表: " + g.services.joinToString { shortUuid(it.uuid) })
            val service = g.getService(SERVICE) ?: run {
                logE("设备没有 FFE0 服务（不是已知的保护板/电量计？）")
                owner.services.complete(Unit)
                owner.ready.complete(false)
                runCatching { g.disconnect() }
                return@withLock
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
                owner.services.complete(Unit)
                owner.ready.complete(false)
                runCatching { g.disconnect() }
                return@withLock
            }
            logI("可用通道：${found.joinToString { it.channel.id }}" +
                if (found.size == 1) "（设备只提供默认通道，无备用通道）" else "")
            // 上次用过/手动指定的通道优先，其余按默认 → 备用 A → 备用 B 顺序
            val pref = preferred
            candidates = listOfNotNull(found.firstOrNull { it.channel == pref }) +
                found.filter { it.channel != pref }
            candidateIndex = 0
            // 候选表发布后再唤醒等待方，备用三通道获得完整探测预算。
            attempts.servicesDiscovered(owner, candidates.size)
            probeNext(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int): Unit = withLock(attemptLock) {
            if (g !== gatt) return@withLock
            logD("MTU 协商=$mtu status=$status")
            this@AndroidBleTransport.mtu = mtu
        }

        // 主动断开也属尽力而为的清理：权限被回收时 SecurityException 由 runCatching 兜住
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int): Unit = withLock(attemptLock) {
            if (g !== gatt || d.uuid != CCCD || d.characteristic !== notifyChar) return@withLock
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 订阅没落地 = 这条通道收不到任何帧：换下一个候选（没有下一个则本轮连接失败）
                logE("CCCD 写入失败：${statusText(status)}（订阅未落地，换下一个候选通道）")
                probeSignal?.complete(false)
                probeSignal = null
                candidateIndex++
                probeNext(g)
                return@withLock
            }
            logD("CCCD 写入成功，订阅已落地，开始发探测帧")
            candidates.getOrNull(candidateIndex)?.let { afterSubscribed(g, it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic): Unit = withLock(attemptLock) {
            if (g !== gatt || c !== notifyChar || attempts.current == null) return@withLock
            @Suppress("DEPRECATION")
            val data = c.value ?: return@withLock
            // 帧级转储：开关关着时只拼「← 245B」——这里在 BLE 回调线程上，
            // 一帧 245 字节的十六进制是 245 次字符串分配，会推迟后面的 tryEmit
            LOG.frame("BLE", "←", data.size) { hex(data) }
            noteProbeResponse(data)
            if (!_incoming.tryEmit(data.copyOf())) noteDropped()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ): Unit = withLock(attemptLock) {
            if (g !== gatt || c !== notifyChar || attempts.current == null) return@withLock
            LOG.frame("BLE", "←", value.size) { hex(value) }
            noteProbeResponse(value)
            if (!_incoming.tryEmit(value.copyOf())) noteDropped()
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
        // 帧日志统一走协议层的遮蔽版：0x23 密码帧的数据区不能被原样写进日志。
        // 同样只在开关打开时拼（仓库层也会记一条 TX，两边都是"要看细节"时才付出代价）
        LOG.frame("BLE", "→", frame.size) { io.github.lswlc33.maibms.protocol.Frame.hexForLog(frame) }
        writeChunks(g, wc, frame)
    }

    /** 分片写入：业务帧与探测帧共用（探测发生在「就绪」之前，所以这里不检查链路态） */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunks(g: BluetoothGatt, wc: BluetoothGattCharacteristic, frame: ByteArray) {
        val chunk = minOf(mtu - 10, MAX_CHUNK).coerceAtLeast(10)
        var offset = 0
        var retried = false
        while (offset < frame.size) {
            val len = minOf(chunk, frame.size - offset)
            val piece = frame.copyOfRange(offset, offset + len)
            // 写入可能被栈拒绝（返回 false）或在权限被回收时抛 SecurityException：
            // 前者重试一次，后者按失败处理，不能让一帧丢掉后没人知道。
            val queued = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+ 的新重载返回 BluetoothStatusCodes，不是 boolean
                    g.writeCharacteristic(wc, piece, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    wc.value = piece
                    @Suppress("DEPRECATION")
                    wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(wc)
                }
            }.getOrElse {
                logW("写入分片异常（${offset}/${frame.size}）：${it.message}")
                false
            }
            if (!queued) {
                if (!retried) {
                    retried = true
                    logW("写入分片被蓝牙栈拒绝（${offset}/${frame.size}），重试一次")
                    delay(WRITE_GAP_MS)
                    continue
                }
                logW("写入分片连续被拒（${offset}/${frame.size}），丢弃本帧剩余部分")
                return
            }
            retried = false
            offset += len
            // 只在**还有下一片**时等：最后一片写完后没有后续写入，那 12ms 是白等的。
            // 电量计每拍要连发 4 条短帧（每条正好 1 片），省下的就是每拍 4×12ms
            if (offset < frame.size) delay(WRITE_GAP_MS)
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
        loopJob?.cancelAndJoin()
        loopJob = null
        stopScan()
        closeGatt()
        _linkState.value = LinkState.Disconnected
        _connectHint.value = null
    }
}
