package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.epochMillisNow
import io.github.lswlc33.maibms.data.fmt
import io.github.lswlc33.maibms.protocol.Frame
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS BLE 传输（CoreBluetooth），行为对齐 Android 的实现与 docs/02-蓝牙链路.md：
 * 扫描 ANT 前缀 → 连接 → 发现 FFE0 服务 → 通道探测候选（FFE1 / FFF3-4 / FFF5-6）
 * → 先订阅通知（**订阅落地才算就绪**）→ 写入按单次写上限分片、间隔 12ms。
 *
 * 与 Android 的差异（平台能力所限，逐条都有理由）：
 * - **没有 MAC 地址**：iOS 不暴露蓝牙地址，用系统给的外设标识（`CBPeripheral.identifier`）
 *   当"地址"——它按设备稳定唯一，够用；换手机或重装应用后会变，那时重新扫描一次即可。
 * - **没有 MTU 协商 API**：iOS 自己协商，这里用 `maximumWriteValueLengthForType` 取单次写上限。
 * - **没有"等待式连接"**：Android 每第 4 次切 autoConnect 等广播；iOS 只能重试，
 *   靠指数退避达到同样效果（弱信号下多试几次总能连上）。
 * - **连接前必须先"见到"设备**：CoreBluetooth 要先扫描拿到 CBPeripheral 才能连接，
 *   所以 connect() 会先扫描（上限 15 秒）再建链——与 Android 直接按地址连不同。
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleTransport : BmsTransport {

    companion object {
        /** 服务（通道定义统一放在 commonMain 的 BleChannel，见 docs/02 §2.2） */
        const val SERVICE_FFE0 = "FFE0"
        /** 通道探测：超时 8s、切通道间隔 200ms（与 Android / 官方一致，docs/02 §2.2） */
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_SETTLE_MS = 200L

        /** 单次 GATT 写分片上限：与 Android 同值，避免部分从机静默丢包 */
        const val MAX_CHUNK = 240
        const val WRITE_GAP_MS = 12L
        /** 扫描找设备的上限；超时按"没找到"处理，交给重连循环退避重试 */
        const val FIND_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        /** 订阅确认兜底：个别固件不回调通知状态，超时按"订阅已发出"放行（与 Android 同款兜底） */
        const val READY_FALLBACK_MS = 800L
    }

    private val _linkState = MutableStateFlow(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: MutableSharedFlow<ByteArray> get() = _incoming
    override val supportsScan: Boolean get() = true

    private val _connectHint = MutableStateFlow<String?>(null)
    override val connectHint: StateFlow<String?> = _connectHint

    // ---- 通信通道（docs/02 §2.2）：候选 → 逐个探测 → 选中的才置「就绪」----

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
        val write: CBCharacteristic,
        val notify: CBCharacteristic,
    )

    private var candidates: List<Candidate> = emptyList()
    private var candidateIndex = 0
    private var probeStarted = false
    @Volatile private var probeSignal: CompletableDeferred<Boolean>? = null

    /** 下次连接优先尝试的通道（手动切换或按设备记忆；仅本进程有效） */
    @Volatile private var preferred: BleChannel? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 用户想要连接的目标（iOS 上是外设标识串）；null = 已主动断开 */
    @Volatile private var desiredAddress: String? = null
    @Volatile private var manualStop = false
    private var loopJob: Job? = null

    private var peripheral: CBPeripheral? = null
    private var writeChar: CBCharacteristic? = null
    private var notifyChar: CBCharacteristic? = null

    /** 单次写上限（字节），由 maximumWriteValueLengthForType 更新 */
    @Volatile private var maxWriteLen: Int = 20

    /** 本次尝试的「就绪」信号：true=服务/订阅就绪，false=就绪前断开 */
    private var readySignal: CompletableDeferred<Boolean>? = null
    /** 服务发现完成信号：让「建链 + 发现服务」与「通道探测」两段超时分开算 */
    @Volatile private var servicesSignal: CompletableDeferred<Unit>? = null
    /** 已就绪后的断开信号 */
    private var dropSignal: CompletableDeferred<Unit>? = null

    // ---- 扫描（对外列设备 / 对内找目标）----
    private var scanHandler: ((ScanDevice) -> Unit)? = null
    /** 扫描命中的目标：精确匹配优先，兜底取第一个 ANT 设备 */
    private var pendingPeripheral: CBPeripheral? = null
    private var fallbackPeripheral: CBPeripheral? = null

    private fun logI(msg: String) = BmsLog.i("BLE", msg)
    private fun logW(msg: String) = BmsLog.w("BLE", msg)
    private fun logE(msg: String) = BmsLog.e("BLE", msg)
    private fun logD(msg: String) = BmsLog.d("BLE", msg)

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".fmt(it) }

    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

        // ---------------- 中心设备状态 ----------------

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            logI("蓝牙状态：${stateText(central.state)}")
            if (central.state != CBManagerStatePoweredOn) {
                _connectHint.value = when (central.state) {
                    CBManagerStateUnauthorized ->
                        "未获得蓝牙权限，请在系统设置中允许本应用使用蓝牙"
                    CBManagerStatePoweredOff -> "蓝牙未打开，请在控制中心或设置中打开蓝牙"
                    else -> "蓝牙不可用（${stateText(central.state)}）"
                }
            }
        }

        // ---------------- 扫描 ----------------

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val name = didDiscoverPeripheral.name
                ?: (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
                ?: return
            // 按广播名判定家族：保护板（ANT）+ 两家电量计；陆行同厂控制器 CJ01 显式排除
            if (!DeviceFamily.isTarget(name)) return
            val family = DeviceFamily.matchName(name) ?: return
            val id = didDiscoverPeripheral.identifier.UUIDString
            logD("scan 发现 $name $id rssi=${RSSI.intValue} → ${family.label}")
            scanHandler?.invoke(ScanDevice(name, id, RSSI.intValue, family))

            if (pendingPeripheral != null) return
            val want = desiredAddress
            when {
                want.isNullOrBlank() -> pendingPeripheral = didDiscoverPeripheral
                id.equals(want, ignoreCase = true) -> {
                    logD("扫描命中目标设备 $name")
                    pendingPeripheral = didDiscoverPeripheral
                }
                // 有目标但这条不匹配：留作兜底，继续等精确匹配（标识变了的情况在超时后启用）
                else -> if (fallbackPeripheral == null) fallbackPeripheral = didDiscoverPeripheral
            }
        }

        // ---------------- 连接 ----------------

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            logI("已连接 ${didConnectPeripheral.name ?: didConnectPeripheral.identifier.UUIDString}，开始发现服务")
            didConnectPeripheral.delegate = this
            didConnectPeripheral.discoverServices(null)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            logE("连接失败：${error?.localizedDescription ?: "未知原因"}")
            servicesSignal?.complete(Unit)
            readySignal?.complete(false)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            logW("连接断开：${error?.localizedDescription ?: "正常断开"}")
            if (peripheral === didDisconnectPeripheral) {
                peripheral = null
                writeChar = null
                notifyChar = null
            }
            _linkState.value = LinkState.Disconnected
            servicesSignal?.complete(Unit)
            readySignal?.complete(false)
            dropSignal?.complete(Unit)
        }

        // ---------------- 服务 / 特征 ----------------

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            // 阶段一结束（无论成败）
            servicesSignal?.complete(Unit)
            if (didDiscoverServices != null) {
                logE("发现服务失败：${didDiscoverServices.localizedDescription}")
                readySignal?.complete(false)
                return
            }
            val services = peripheral.services.orEmpty().mapNotNull { it as? CBService }
            logD("服务列表: " + services.joinToString { shortUuid(it.UUID.UUIDString) })
            val service = services.firstOrNull { it.UUID.UUIDString.equals(SERVICE_FFE0, ignoreCase = true) }
            if (service == null) {
                logE("设备没有 FFE0 服务（不是已知的保护板/电量计？）")
                readySignal?.complete(false)
                return
            }
            peripheral.discoverCharacteristics(null, forService = service)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                logE("发现特征失败：${error.localizedDescription}")
                readySignal?.complete(false)
                return
            }
            val chars = didDiscoverCharacteristicsForService.characteristics
                .orEmpty().mapNotNull { it as? CBCharacteristic }
            logI("特征列表: " + chars.joinToString { shortUuid(it.UUID.UUIDString) })
            maxWriteLen = peripheral
                .maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
                .toInt()
            logD("单次写上限 $maxWriteLen 字节")
            // 候选通道：先按官方规则判定这块硬件有没有备用通道（存在 FFF5 特征；
            // Android 侧还校验了"可写"属性，iOS 的属性校验待真机验证），再按实际特征过滤
            val found: List<Candidate> = if (targetFamily.isMeter) {
                // 电量计：FFE0 下就一对 写 FFE2 / 通知 FFE1（三家实测都提供这对特征）
                val wc = chars.firstOrNull { it.UUID.UUIDString.equals(BleChannel.Meter.writeUuid, ignoreCase = true) }
                val nc = chars.firstOrNull { it.UUID.UUIDString.equals(BleChannel.Meter.notifyUuid, ignoreCase = true) }
                if (wc != null && nc != null) listOf(Candidate(BleChannel.Meter, wc, nc)) else emptyList()
            } else {
                val hasFff5 = chars.any { it.UUID.UUIDString.equals("FFF5", ignoreCase = true) }
                bleChannelCandidates(hasFff5).mapNotNull { ch ->
                    val wc = chars.firstOrNull { it.UUID.UUIDString.equals(ch.writeUuid, ignoreCase = true) }
                    val nc = chars.firstOrNull { it.UUID.UUIDString.equals(ch.notifyUuid, ignoreCase = true) }
                    if (wc != null && nc != null) Candidate(ch, wc, nc) else null
                }
            }
            _availableChannels.value = found.map { it.channel }.ifEmpty { listOf(BleChannel.Default) }
            if (found.isEmpty()) {
                logE("FFE0 下没有可用的写/通知特征")
                readySignal?.complete(false)
                return
            }
            logI("可用通道：" + found.joinToString { it.channel.id } +
                if (found.size == 1) "（设备只提供默认通道，无备用通道）" else "")
            // 上次用过/手动指定的通道优先，其余按默认 → 备用 A → 备用 B 顺序
            val pref = preferred
            candidates = listOfNotNull(found.firstOrNull { it.channel == pref }) +
                found.filter { it.channel != pref }
            candidateIndex = 0
            probeNext(peripheral)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                // 订阅没落地 = 这条通道收不到任何帧：换下一个候选（没有下一个则本轮连接失败）
                logE("订阅通知失败：${error.localizedDescription}（换下一个候选通道）")
                candidateIndex++
                probeNext(peripheral)
                return
            }
            val uuid = didUpdateNotificationStateForCharacteristic.UUID.UUIDString
            if (uuid.equals(notifyChar?.UUID?.UUIDString, ignoreCase = true)) {
                logD("订阅已落地，开始发探测帧")
                candidates.getOrNull(candidateIndex)?.let { afterSubscribed(peripheral, it) }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                logW("收到数据出错：${error.localizedDescription}")
                return
            }
            val data = didUpdateValueForCharacteristic.value ?: return
            val bytes = data.toByteArray()
            logD("← ${hex(bytes)}")
            noteProbeResponse(bytes)
            _incoming.tryEmit(bytes)
        }

        /**
         * 试下一个候选通道：订阅通知 → 发探测帧等应答。
         * 全部候选都失败就按本轮连接失败处理，交给重连循环退避重试。
         */
        private fun probeNext(p: CBPeripheral) {
            val c = candidates.getOrNull(candidateIndex)
            if (c == null) {
                logE("候选通道全部探测失败（共 ${candidates.size} 条）")
                _activeChannel.value = null
                readySignal?.complete(false)
                return
            }
            writeChar = c.write
            notifyChar = c.notify
            probeStarted = false
            logI("通道探测 ${candidateIndex + 1}/${candidates.size}：${c.channel.id}（写 ${shortUuid(c.write.UUID.UUIDString)} / 通知 ${shortUuid(c.notify.UUID.UUIDString)}）")
            _connectHint.value = "通道探测：${c.channel.label}…"
            // 先订阅通知（docs 硬性顺序）
            p.setNotifyValue(true, forCharacteristic = c.notify)
            // 订阅确认个别固件不回调：超时也按「订阅已发出」进入探测，绝不把连接卡死
            scope.launch {
                delay(READY_FALLBACK_MS)
                afterSubscribed(p, c)
            }
        }

        /**
         * 订阅落地后的动作：保护板走 ANT 探测；电量计暂无探测帧，直接按“FFE0 + 写/通知就绪”判就绪。
         */
        private fun afterSubscribed(p: CBPeripheral, c: Candidate) {
            if (p !== peripheral) return
            if (targetFamily.isMeter) {
                _activeChannel.value = c.channel
                logI("电量计通道就绪：${c.channel.id}（家族 ${targetFamily.label}，跳过保护板探测）")
                _linkState.value = LinkState.Connected
                readySignal?.complete(true)
            } else {
                startProbe(p, c)
            }
        }

        /** 发探测帧并等应答；通过就置「就绪」，否则换下一个候选 */
        private fun startProbe(p: CBPeripheral, c: Candidate) {
            if (probeStarted) return
            // 迟到的兜底（候选已经翻页）不能再探：否则会拿旧候选的写特征去发帧
            if (candidates.getOrNull(candidateIndex) !== c) return
            probeStarted = true
            val signal = CompletableDeferred<Boolean>()
            probeSignal = signal
            scope.launch {
                delay(PROBE_SETTLE_MS)   // 等订阅生效，与官方 200ms 切通道间隔一致
                runCatching {
                    p.writeValue(
                        Frame.readRealtime().toNSData(),
                        forCharacteristic = c.write,
                        type = CBCharacteristicWriteWithoutResponse,
                    )
                }.onFailure { logW("探测帧写入失败：${it.message}") }
                val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { signal.await() } ?: false
                probeSignal = null
                if (ok) {
                    _activeChannel.value = c.channel
                    logI("通道就绪：${c.channel.label}（${c.channel.id}）")
                    completeReady()
                } else {
                    logW("通道 ${c.channel.id} 无应答（${PROBE_TIMEOUT_MS}ms 超时），换下一个候选")
                    runCatching { p.setNotifyValue(false, forCharacteristic = c.notify) }   // 退订这条通道
                    candidateIndex++
                    probeNext(p)
                }
            }
        }

        /** 探测期间：第一条 0x11/0x12 应答即视为该通道可用（应答照旧投给上层解析） */
        private fun noteProbeResponse(b: ByteArray) {
            val s = probeSignal ?: return
            if (!s.isCompleted && b.size >= 3 && b[0] == 0x7E.toByte() &&
                (b[2] == 0x11.toByte() || b[2] == 0x12.toByte())
            ) s.complete(true)
        }

        private fun completeReady() {
            if (_linkState.value != LinkState.Connected) {
                _linkState.value = LinkState.Connected
            }
            readySignal?.complete(true)
        }
    }

    /** 蓝牙状态 → 人话 */
    private fun stateText(state: CBManagerState): String = when (state) {
        CBManagerStatePoweredOn -> "已开启"
        CBManagerStatePoweredOff -> "关闭"
        CBManagerStateUnauthorized -> "未授权"
        CBManagerStateUnsupported -> "本机不支持"
        CBManagerStateResetting -> "正在重置"
        CBManagerStateUnknown -> "未知"
        else -> "未知状态($state)"
    }

    /** "0000FFE0-0000-1000-8000-00805F9B34FB" → "FFE0"；已经是短形态就原样大写 */
    private fun shortUuid(uuid: String): String =
        if (uuid.length >= 8 && uuid.contains('-')) uuid.substring(4, 8).uppercase() else uuid.uppercase()

    private val central: CBCentralManager by lazy {
        CBCentralManager(delegate = delegate, queue = null)
    }

    // ---------------- 扫描（对外） ----------------

    override suspend fun scan(onFound: (ScanDevice) -> Unit) {
        val c = central
        if (c.state != CBManagerStatePoweredOn) {
            throw IllegalStateException("设备蓝牙未打开或未授权")
        }
        runCatching { c.stopScan() }
        scanHandler = onFound
        logI("开始扫描（保护板 ANT + 电量计 蓝宝/陆行）")
        c.scanForPeripheralsWithServices(null, null)
    }

    override fun stopScan() {
        scanHandler = null
        runCatching { central.stopScan() }
    }

    // ---------------- 连接 ----------------

    override suspend fun connect(address: String?) {
        manualStop = false
        desiredAddress = address
        _currentFamily.value = targetFamily
        _linkState.value = LinkState.Connecting
        _connectHint.value = "正在连接…"
        loopJob?.cancel()
        loopJob = scope.launch { connectionLoop(address) }
    }

    private suspend fun connectionLoop(address: String?) {
        var attempt = 0
        while (!manualStop && currentCoroutineContext().isActive) {
            attempt++
            if (attempt > 1) _connectHint.value = "第 $attempt 次尝试…"
            logI("连接尝试 #$attempt")
            _linkState.value = LinkState.Connecting

            // 1) 先扫到设备（CoreBluetooth 必须先持有 CBPeripheral 才能连接）
            val target = runCatching { findPeripheral(address) }.getOrNull()
            if (target == null) {
                _connectHint.value = if (address.isNullOrBlank()) "没有扫描到目标设备"
                                    else "没有扫描到目标设备（可能不在范围内或未上电）"
                logW("扫描未找到设备（尝试 #$attempt）")
                if (manualStop || !currentCoroutineContext().isActive) break
                delay(backoffMs(attempt))
                continue
            }

            // 2) 建链 + 等订阅就绪
            val ready = CompletableDeferred<Boolean>()
            readySignal = ready
            val servicesFound = CompletableDeferred<Unit>()
            servicesSignal = servicesFound
            dropSignal = null
            peripheral = target
            logI("连接目标 ${target.name ?: "未命名"} ${target.identifier.UUIDString}")
            central.connectPeripheral(target, null)
            // 两段超时：① 建链 + 发现服务用连接超时；② 通道探测按候选条数给预算
            //（每条候选最多 PROBE_TIMEOUT_MS，与 Android/官方同量级）
            val discovered = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { servicesFound.await() } != null
            val outcome: Boolean? = if (!discovered) null else {
                val probeBudget = PROBE_TIMEOUT_MS * candidates.size.coerceAtLeast(1) + 2_000L
                withTimeoutOrNull(probeBudget) { ready.await() }
            }
            if (outcome == true) {
                _connectHint.value = null
                _linkState.value = LinkState.Connected
                logI("链路就绪（通道 ${_activeChannel.value?.id ?: "?"}，单次写上限 $maxWriteLen 字节）")
                val dropped = CompletableDeferred<Unit>()
                dropSignal = dropped
                val connectedAt = epochMillisNow()
                runCatching { dropped.await() }
                val livedMs = epochMillisNow() - connectedAt
                closePeripheral()
                _linkState.value = LinkState.Disconnected
                if (manualStop || !currentCoroutineContext().isActive) break
                if (livedMs < 5_000) {
                    _connectHint.value = "连接建立后很快断开，正在重试…"
                } else {
                    attempt = 0
                    _connectHint.value = "连接已断开，正在重连…"
                }
            } else {
                closePeripheral()
                _connectHint.value = if (outcome == null) "连接超时，正在重试…" else "连接失败，正在重试…"
            }
            readySignal = null
            servicesSignal = null
            if (manualStop || !currentCoroutineContext().isActive) break
            delay(backoffMs(attempt))
        }
        if (desiredAddress == null) {
            closePeripheral()
            _linkState.value = LinkState.Disconnected
        }
    }

    private fun backoffMs(attempt: Int): Long = (1_000L shl minOf(attempt, 3)).coerceAtMost(8_000L)

    /**
     * 扫描找目标设备：优先标识一致；超时后若只见过别的 ANT 设备就拿它兜底
     * （对应 Android「没有指定地址就用已配对的 ANT 设备」）。找不到返回 null 由调用方退避重试。
     */
    private suspend fun findPeripheral(address: String?): CBPeripheral? {
        val c = central
        if (c.state != CBManagerStatePoweredOn) {
            _connectHint.value = "蓝牙未打开或未授权"
            return null
        }
        runCatching { c.stopScan() }
        pendingPeripheral = null
        fallbackPeripheral = null
        logD("扫描设备中（上限 ${FIND_TIMEOUT_MS}ms）")
        c.scanForPeripheralsWithServices(null, null)
        // 等"找到目标"：didDiscoverPeripheral 置 pendingPeripheral，这里轮询感知
        var waited = 0L
        while (waited < FIND_TIMEOUT_MS && pendingPeripheral == null && !manualStop) {
            delay(100)
            waited += 100
        }
        runCatching { c.stopScan() }
        val exact = pendingPeripheral
        if (exact != null) return exact
        val fb = fallbackPeripheral
        if (fb != null) {
            logW("未发现标识为 ${address ?: "-"} 的设备，改用扫描到的 ${fb.name ?: ""}（${fb.identifier.UUIDString}）")
        }
        return fb
    }

    private fun closePeripheral() {
        val p = peripheral ?: return
        peripheral = null
        writeChar = null
        notifyChar = null
        probeSignal = null
        servicesSignal = null
        probeStarted = false
        candidates = emptyList()
        candidateIndex = 0
        _activeChannel.value = null
        p.delegate = null
        runCatching { central.cancelPeripheralConnection(p) }
    }

    // ---------------- 写入 ----------------

    override suspend fun write(frame: ByteArray) {
        val p = peripheral ?: run { logW("写入被丢弃：未连接"); return }
        val wc = writeChar ?: run { logW("写入被丢弃：无写通道"); return }
        if (_linkState.value != LinkState.Connected) { logW("写入被丢弃：链路未就绪"); return }
        // 帧日志统一走协议层的遮蔽版：0x23 密码帧的数据区不能被原样写进日志
        logD("→ ${Frame.hexForLog(frame)}")
        val chunk = minOf(maxWriteLen, MAX_CHUNK).coerceAtLeast(20)
        var offset = 0
        while (offset < frame.size) {
            val len = minOf(chunk, frame.size - offset)
            val piece = frame.copyOfRange(offset, offset + len)
            p.writeValue(
                piece.toNSData(),
                forCharacteristic = wc,
                type = CBCharacteristicWriteWithoutResponse,
            )
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
        scanHandler = null
        runCatching { central.stopScan() }
        closePeripheral()
        _linkState.value = LinkState.Disconnected
        _connectHint.value = null
    }

    /** 记住下次连接优先尝试的通道（手动切换或按设备记忆） */
    override fun preferChannel(channel: BleChannel?) {
        preferred = channel
    }

    /** 手动切换通道：断开当前链路，交给重连循环按新通道重连 */
    override suspend fun switchChannel(channel: BleChannel) {
        preferred = channel
        val p = peripheral
        if (p == null) {
            logI("已记录通道偏好 ${channel.id}（未连接，下次连接生效）")
            return
        }
        logI("手动切换通道 → ${channel.label}（${channel.id}），断开后按新通道重连")
        _connectHint.value = "正在切换到${channel.label}…"
        runCatching { central.cancelPeripheralConnection(p) }
    }
}

/** ByteArray → NSData（长度 0 时给空 data，避免取首地址越界） */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

/** NSData → ByteArray（拷出一份，避免之后被 CoreBluetooth 复用） */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
