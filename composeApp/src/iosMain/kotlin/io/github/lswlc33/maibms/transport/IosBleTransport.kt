package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.PlatformLock
import io.github.lswlc33.maibms.data.withLock
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
import kotlinx.coroutines.cancelAndJoin
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
import platform.CoreBluetooth.CBCharacteristicProperties
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
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
import platform.Foundation.NSUUID
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
 * - **连接前必须持有 CBPeripheral**：回连记忆设备用 `retrievePeripheralsWithIdentifiers`
 *   从系统缓存按标识直取（免扫描，对齐 Android 的按 MAC 直连）；只有首次连接才需要
 *   先广播扫描拿到外设——所以 connect() 在「没有目标标识」时先扫描（上限 15 秒）再建链。
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleTransport : BmsTransport {

    companion object {
        /** 服务（通道定义统一放在 commonMain 的 BleChannel，见 docs/02 §2.2） */
        const val SERVICE_FFE0 = "FFE0"
        /** 通道探测：超时 8s、切通道间隔 200ms（与 Android / 官方一致，docs/02 §2.2） */
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_SETTLE_MS = 200L

        /**
         * 一轮探测的总预算：**按最坏情况（3 条候选）给**，不能用「此刻的 candidates.size」算——
         * 服务发现完成的信号在特征发现**之前**就发出了，那一刻候选表还是空的，算出来只有 10s，
         * 而默认通道没应答、只有备用通道能通的板子跑完 3 条要约 26s，于是永远连不上。
         */
        const val PROBE_BUDGET_MS = PROBE_TIMEOUT_MS * 3 + 2_000L

        /** 等 CoreBluetooth 状态就位的上限：CBCentralManager 的 state 是异步送的 */
        const val STATE_WAIT_MS = 3_000L

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

    // ---- 连接尝试的共享状态 ----
    // 通信字段由 delegate 与主队列协程使用；公共入口也用 attemptLock 串行核查所有权。

    @Volatile private var candidates: List<Candidate> = emptyList()
    @Volatile private var candidateIndex = 0
    @Volatile private var probeStarted = false
    @Volatile private var probeSignal: CompletableDeferred<Boolean>? = null

    /**
     * 捕获到异步 probe/settle 中的代次；每次建链和收尾递增。
     * 平台 delegate 只携带 identifier，不携带代次：同 identifier 重连必须另等终止屏障。
     * 不使用 CBPeripheral 的 K/N wrapper 身份判定同一台设备。
     */
    @Volatile private var attemptGen = 0

    /** 下次连接优先尝试的通道（手动切换或按设备记忆；仅本进程有效） */
    @Volatile private var preferred: BleChannel? = null

    // 与 CoreBluetooth 的主队列串行：终止回调返回/队列旧事件排空后才启动下一轮。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 用户想要连接的目标（iOS 上是外设标识串）；null = 已主动断开 */
    @Volatile private var desiredAddress: String? = null
    @Volatile private var manualStop = false
    private var loopJob: Job? = null

    @Volatile private var peripheral: CBPeripheral? = null
    @Volatile private var writeChar: CBCharacteristic? = null
    @Volatile private var notifyChar: CBCharacteristic? = null

    /** 单次写上限（字节），由 maximumWriteValueLengthForType 更新 */
    @Volatile private var maxWriteLen: Int = 20

    /** delegate 与公共入口的所有权判断/状态发布都在同锁内。 */
    private val attemptLock = PlatformLock()
    private val attempts = ConnectionAttempts()
    private val terminations = ConnectionTerminationBarrier()
    private val managerEpochs = ConnectionManagerEpochs(terminations)

    private fun accepts(p: CBPeripheral): Boolean =
        managerEpochs.accepts(managerEpochs.current) &&
            attempts.acceptsCallback(p.identifier.UUIDString) &&
            peripheral?.identifier?.UUIDString.equals(p.identifier.UUIDString, ignoreCase = true) &&
            terminations.waiting(p.identifier.UUIDString, managerEpochs.current) == null

    // ---- 扫描（对外列设备 / 对内找目标）----
    @Volatile private var scanHandler: ((ScanDevice) -> Unit)? = null
    /**
     * 扫描命中的目标。
     *
     * **只装"就是我们要连的那台"**：没有指定标识时取第一个目标设备（对齐 Android 的
     * 「没有指定地址就用已配对的 ANT 设备」），指定了标识就必须标识一致——曾经这里还留了
     * 一个"见过别的目标设备就拿它兜底"的候选，结果指定设备找不到时会连上另一台，
     * 而会话仍按原设备的地址/密码/家族管理（见 [acceptsPeripheralId]）。
     */
    @Volatile private var pendingPeripheral: CBPeripheral? = null

    private fun logI(msg: String) = BmsLog.i("BLE", msg)
    private fun logW(msg: String) = BmsLog.w("BLE", msg)
    private fun logE(msg: String) = BmsLog.e("BLE", msg)
    private fun logD(msg: String) = BmsLog.d("BLE", msg)

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".fmt(it) }

    private fun createDelegate(epoch: Int): CBCentralManagerDelegateProtocol = object : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        private fun acceptsEpoch(): Boolean = managerEpochs.accepts(epoch)

        // ---------------- 中心设备状态 ----------------

        override fun centralManagerDidUpdateState(central: CBCentralManager): Unit = withLock(attemptLock) {
            // State is the only entry retained on an invalidated epoch, solely to observe recovery.
            if (!managerEpochs.isCurrent(epoch)) return@withLock
            val state = central.state
            logI("蓝牙状态：${stateText(state)}")
            if (state == CBManagerStatePoweredOn) {
                val nextEpoch = managerEpochs.poweredOn(epoch)
                if (nextEpoch != null) {
                    runCatching { central.stopScan() }
                    central.delegate = null
                    nativeManager = createManager(nextEpoch)
                    logI("蓝牙恢复，切换 manager epoch $epoch → $nextEpoch")
                }
            } else {
                _connectHint.value = stateHint(state)
                if (managerEpochs.unavailable(epoch)) {
                    // Manager loss is not a peripheral cancellation: no terminal callback is promised.
                    closePeripheral(terminal = true)
                    pendingPeripheral = null
                    runCatching { central.stopScan() }
                }
            }
        }

        // ---------------- 扫描 ----------------

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ): Unit = withLock(attemptLock) {
            if (!acceptsEpoch()) return@withLock
            val name = didDiscoverPeripheral.name
                ?: (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
                ?: return@withLock
            // 按广播名判定家族：保护板（ANT）+ 两家电量计；陆行同厂控制器 CJ01 显式排除
            if (!DeviceFamily.isTarget(name)) return@withLock
            val family = DeviceFamily.matchName(name) ?: return@withLock
            val id = didDiscoverPeripheral.identifier.UUIDString
            logD("scan 发现 $name $id rssi=${RSSI.intValue} → ${family.label}")
            scanHandler?.invoke(ScanDevice(name, id, RSSI.intValue, family))

            if (pendingPeripheral != null) return@withLock
            val want = desiredAddress
            if (acceptsPeripheralId(want, id)) {
                logD("扫描命中目标设备 $name")
                pendingPeripheral = didDiscoverPeripheral
            } else {
                // 指定了标识但这条不匹配：它只是**另一台**目标设备，本轮等不到就不连——
                // 拿它顶替会让会话（地址/密码/家族/档案）全部错位到别的设备上
                logD("扫描到其它目标设备 $name $id，不是指定标识 $want")
            }
        }

        // ---------------- 连接 ----------------

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || !accepts(didConnectPeripheral)) return@withLock
            logI("已连接 ${didConnectPeripheral.name ?: didConnectPeripheral.identifier.UUIDString}，开始发现服务")
            didConnectPeripheral.delegate = this
            didConnectPeripheral.discoverServices(null)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ): Unit = withLock(attemptLock) {
            if (!acceptsEpoch()) return@withLock
            // 取消中的旧外设只完成自己的屏障，不允许唤醒/结束另一台的 attempt。
            if (terminations.complete(didFailToConnectPeripheral.identifier.UUIDString, epoch)) return@withLock
            if (!accepts(didFailToConnectPeripheral)) return@withLock
            logE("连接失败：${error?.localizedDescription ?: "未知原因"}")
            closePeripheral(terminal = true)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ): Unit = withLock(attemptLock) {
            if (!acceptsEpoch()) return@withLock
            if (terminations.complete(didDisconnectPeripheral.identifier.UUIDString, epoch)) return@withLock
            if (!accepts(didDisconnectPeripheral)) return@withLock
            logW("连接断开：${error?.localizedDescription ?: "正常断开"}")
            closePeripheral(terminal = true)
        }

        // ---------------- 服务 / 特征 ----------------

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || !accepts(peripheral)) return@withLock
            val owner = attempts.current ?: return@withLock
            // 保留 iOS 的阶段划分与最坏三候选预算。
            attempts.servicesDiscovered(owner, 0)
            if (didDiscoverServices != null) {
                logE("发现服务失败：${didDiscoverServices.localizedDescription}")
                owner.ready.complete(false)
                return@withLock
            }
            val services = peripheral.services.orEmpty().mapNotNull { it as? CBService }
            // 原始 UUIDString 一并打出来：iOS 对 16 位 UUID 有时给短形态（"FFE0"）、有时给完整 128 位，
            // 日志里只留归一化后的短码会把这个问题掩盖掉（对照用的原始值就是这行的用途）
            logD("服务列表: " + services.joinToString { "${shortUuid(it.UUID.UUIDString)}(${it.UUID.UUIDString})" })
            val service = services.firstOrNull { shortUuid(it.UUID.UUIDString) == SERVICE_FFE0 }
            if (service == null) {
                logE("设备没有 FFE0 服务（不是已知的保护板/电量计？）")
                owner.ready.complete(false)
                return@withLock
            }
            peripheral.discoverCharacteristics(null, forService = service)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || !accepts(peripheral) || shortUuid(didDiscoverCharacteristicsForService.UUID.UUIDString) != SERVICE_FFE0) return@withLock
            val owner = attempts.current ?: return@withLock
            if (error != null) {
                logE("发现特征失败：${error.localizedDescription}")
                owner.ready.complete(false)
                return@withLock
            }
            val chars = didDiscoverCharacteristicsForService.characteristics
                .orEmpty().mapNotNull { it as? CBCharacteristic }
            // 特征全集（含属性与原始 UUID）：回答「这块板到底有没有备用通道」的第一手证据
            logI("特征列表: " + chars.joinToString {
                "${shortUuid(it.UUID.UUIDString)}(${it.UUID.UUIDString} ${propText(it.properties)})"
            })
            maxWriteLen = peripheral
                .maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
                .toInt()
            logD("单次写上限 $maxWriteLen 字节")
            // 候选通道：先按官方规则判定这块硬件有没有备用通道（暴露**可写**的 FFF5），
            // 再按实际特征与属性过滤（写特征要可写、通知特征要能 notify/indicate）——与 Android 同款规则
            val found: List<Candidate> = if (targetFamily.isMeter) {
                // 电量计：FFE0 下就一对 写 FFE2 / 通知 FFE1（三家实测都提供这对特征）；
                // 找不到就退到「第一个可写 / 第一个可通知」（与 Android 一致）
                val wc = chars.firstOrNull { shortId(it) == BleChannel.Meter.writeUuid }
                    ?: chars.firstOrNull { isWritable(it) }
                val nc = chars.firstOrNull { shortId(it) == BleChannel.Meter.notifyUuid }
                    ?: chars.firstOrNull { isNotifiable(it) }
                if (wc != null && nc != null && isWritable(wc) && isNotifiable(nc)) {
                    listOf(Candidate(BleChannel.Meter, wc, nc))
                } else emptyList()
            } else {
                val hasWritableFff5 = chars.any { shortId(it) == "FFF5" && isWritable(it) }
                bleChannelCandidates(hasWritableFff5).mapNotNull { ch ->
                    val wc = chars.firstOrNull { shortId(it) == ch.writeUuid }
                    val nc = chars.firstOrNull { shortId(it) == ch.notifyUuid }
                    if (wc != null && nc != null && isWritable(wc) && isNotifiable(nc)) {
                        Candidate(ch, wc, nc)
                    } else null
                }
            }
            _availableChannels.value = found.map { it.channel }.ifEmpty { listOf(BleChannel.Default) }
            if (found.isEmpty()) {
                logE("FFE0 下没有可用的写/通知特征")
                owner.ready.complete(false)
                return@withLock
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
        ): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || !accepts(peripheral) || !isCurrentNotify(didUpdateNotificationStateForCharacteristic)) return@withLock
            val c = candidates.getOrNull(candidateIndex) ?: return@withLock
            if (error != null) {
                logE("订阅通知失败：${error.localizedDescription}（换下一个候选通道）")
                probeSignal?.complete(false)
                probeSignal = null
                candidateIndex++
                probeNext(peripheral)
                return@withLock
            }
            logD("订阅已落地，开始发探测帧")
            afterSubscribed(peripheral, c, attemptGen)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || !accepts(peripheral) || !isCurrentNotify(didUpdateValueForCharacteristic)) return@withLock
            if (error != null) {
                logW("收到数据出错：${error.localizedDescription}")
                return@withLock
            }
            val data = didUpdateValueForCharacteristic.value ?: return@withLock
            val bytes = data.toByteArray()
            logD("← ${hex(bytes)}")
            noteProbeResponse(bytes)
            _incoming.tryEmit(bytes)
        }

        /**
         * 试下一个候选通道：订阅通知 → 发探测帧等应答。
         * 全部候选都失败就按本轮连接失败处理，交给重连循环退避重试。
         */
        private fun probeNext(p: CBPeripheral): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || !accepts(p)) return@withLock
            val owner = attempts.current ?: return@withLock
            val c = candidates.getOrNull(candidateIndex)
            if (c == null) {
                logE("候选通道全部探测失败（共 ${candidates.size} 条）")
                _activeChannel.value = null
                owner.ready.complete(false)
                return@withLock
            }
            attempts.selectCandidate(owner, c)
            writeChar = c.write
            notifyChar = c.notify
            probeStarted = false
            logI("通道探测 ${candidateIndex + 1}/${candidates.size}：${c.channel.id}（写 ${shortUuid(c.write.UUID.UUIDString)} / 通知 ${shortUuid(c.notify.UUID.UUIDString)}）")
            _connectHint.value = "通道探测：${c.channel.label}…"
            // 先订阅通知（docs 硬性顺序）
            p.setNotifyValue(true, forCharacteristic = c.notify)
            // 订阅确认个别固件不回调：超时也按「订阅已发出」进入探测，绝不把连接卡死
            val gen = attemptGen
            scope.launch {
                delay(READY_FALLBACK_MS)
                withLock(attemptLock) {
                    if (!acceptsEpoch() || gen != attemptGen || !accepts(p) || !attempts.acceptsCandidate(owner, c)) return@launch
                    if (!probeStarted && !targetFamily.isMeter) {
                        logD("订阅确认未回调，按 ${READY_FALLBACK_MS}ms 兜底进入探测")
                    }
                    afterSubscribed(p, c, gen)
                }
            }
        }

        /** Wrapper identity is irrelevant; identifier + captured generation/candidate guard async work. */
        private fun afterSubscribed(p: CBPeripheral, c: Candidate, gen: Int): Unit = withLock(attemptLock) {
            val owner = attempts.current ?: return@withLock
            if (!acceptsEpoch() || gen != attemptGen || !accepts(p) || !attempts.acceptsCandidate(owner, c)) return@withLock
            if (targetFamily.isMeter) {
                completeReady(p, owner, c, gen)
            } else {
                startProbe(p, owner, c, gen)
            }
        }

        /** 发探测帧并等应答；settle 与等待返回后都检查旧代次/候选。 */
        private fun startProbe(p: CBPeripheral, owner: ConnectionAttempt, c: Candidate, gen: Int): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || gen != attemptGen || !accepts(p) || !attempts.acceptsCandidate(owner, c) || probeStarted) return@withLock
            probeStarted = true
            val signal = CompletableDeferred<Boolean>()
            probeSignal = signal
            scope.launch {
                delay(PROBE_SETTLE_MS)
                withLock(attemptLock) {
                    if (!acceptsEpoch() || gen != attemptGen || !accepts(p) || !attempts.acceptsCandidate(owner, c) || probeSignal !== signal) return@launch
                    runCatching {
                        p.writeValue(
                            Frame.readRealtime().toNSData(),
                            forCharacteristic = c.write,
                            type = CBCharacteristicWriteWithoutResponse,
                        )
                    }.onFailure { logW("探测帧写入失败：${it.message}") }
                }
                val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { signal.await() } ?: false
                withLock(attemptLock) {
                    if (!acceptsEpoch() || gen != attemptGen || !accepts(p) || !attempts.acceptsCandidate(owner, c) || probeSignal !== signal) return@launch
                    probeSignal = null
                    if (ok) {
                        completeReady(p, owner, c, gen)
                    } else {
                        logW("通道 ${c.channel.id} 无应答（${PROBE_TIMEOUT_MS}ms 超时），换下一个候选")
                        runCatching { p.setNotifyValue(false, forCharacteristic = c.notify) }
                        candidateIndex++
                        probeNext(p)
                    }
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

        private fun completeReady(p: CBPeripheral, owner: ConnectionAttempt, c: Candidate, gen: Int): Unit = withLock(attemptLock) {
            if (!acceptsEpoch() || gen != attemptGen || !accepts(p) || !attempts.acceptsCandidate(owner, c)) return@withLock
            attempts.publishReady(owner) {
                _activeChannel.value = c.channel
                _linkState.value = LinkState.Connected
                _connectHint.value = null
                logI(if (targetFamily.isMeter) "电量计通道就绪：${c.channel.id}（跳过保护板探测）"
                     else "通道就绪：${c.channel.label}（${c.channel.id}）")
            }
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

    /** 蓝牙状态 → 给用户看的失败原因（要能照着做，不能只说「不可用」） */
    private fun stateHint(state: CBManagerState): String = when (state) {
        CBManagerStatePoweredOff -> "蓝牙未打开，请在控制中心或设置中打开蓝牙"
        CBManagerStateUnauthorized -> "未获得蓝牙权限，请在系统设置中允许本应用使用蓝牙"
        CBManagerStateUnsupported -> "本机不支持蓝牙"
        CBManagerStateResetting -> "蓝牙正在重置，请稍后重试"
        else -> "蓝牙尚未就绪，请稍后重试"
    }

    /**
     * 等 CoreBluetooth 状态就位。
     *
     * `CBCentralManager` 刚创建时 `state` 是 `.unknown`，真正的状态由 `centralManagerDidUpdateState`
     * **异步**送来（Apple 要求先等这个回调再用管理器）。不等就会把「还没就绪」误报成「蓝牙未打开」：
     * 全新安装的用户没有历史设备 → 启动时不自动重连 → 管理器直到点开扫描弹窗才被创建，
     * 于是第一次扫描必然报「设备蓝牙未打开或未授权」且扫不到任何设备。
     */
    private suspend fun awaitState(timeoutMs: Long = STATE_WAIT_MS): CBManagerState {
        var waited = 0L
        while (true) {
            // Recovery may replace the manager during delay: never return an old central's state.
            val (state, usable) = withLock(attemptLock) {
                central.state to managerEpochs.accepts(managerEpochs.current)
            }
            if (state != CBManagerStateUnknown && (state != CBManagerStatePoweredOn || usable)) return state
            if (waited >= timeoutMs) return CBManagerStateUnknown
            delay(50)
            waited += 50
        }
    }

    /** "0000FFE0-0000-1000-8000-00805F9B34FB" → "FFE0"；已经是短形态就原样大写 */
    private fun shortUuid(uuid: String): String =
        if (uuid.length >= 8 && uuid.contains('-')) uuid.substring(4, 8).uppercase() else uuid.uppercase()

    /**
     * 特征的短 UUID，用来和 [BleChannel] 里的 "FFE1" 这类常量比。
     * iOS 对 16 位 UUID 可能给短形态、也可能给完整 128 位（同一台设备两种都会出现），
     * 一律归一后再比，别让设备的报法决定我们认不认得它。
     */
    private fun shortId(c: CBCharacteristic): String = shortUuid(c.UUID.UUIDString)

    /** 不比较 K/N wrapper 身份；只接受当前候选的服务 + 通知特征。 */
    private fun isCurrentNotify(c: CBCharacteristic): Boolean {
        val expected = notifyChar ?: return false
        return shortId(c) == shortId(expected) &&
            c.service?.UUID?.UUIDString?.let { shortUuid(it) } == SERVICE_FFE0
    }

    /** 写特征要可写（与 Android 的 isWritable 对齐）；`.toInt()` 是躲开 NS_OPTIONS 映射成 UInt/ULong 的差异 */
    private fun isWritable(c: CBCharacteristic): Boolean {
        val mask = CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse
        return (c.properties and mask).toInt() != 0
    }

    private fun isNotifiable(c: CBCharacteristic): Boolean {
        val mask = CBCharacteristicPropertyNotify or CBCharacteristicPropertyIndicate
        return (c.properties and mask).toInt() != 0
    }

    /** 特征属性 → 人话（写进日志，用来回答「这块板到底有没有备用通道」） */
    private fun propText(p: CBCharacteristicProperties): String = buildList {
        if ((p and CBCharacteristicPropertyRead).toInt() != 0) add("读")
        if ((p and CBCharacteristicPropertyWrite).toInt() != 0) add("写")
        if ((p and CBCharacteristicPropertyWriteWithoutResponse).toInt() != 0) add("写无响应")
        if ((p and CBCharacteristicPropertyNotify).toInt() != 0) add("通知")
        if ((p and CBCharacteristicPropertyIndicate).toInt() != 0) add("指示")
    }.joinToString("/").ifEmpty { "无" }

    /** Strongly retain the delegate with exactly the manager epoch that created it. */
    private inner class NativeManager(val epoch: Int) {
        val delegate = createDelegate(epoch)
        val central = CBCentralManager(delegate = delegate, queue = null)
    }

    private var nativeManager: NativeManager? = null

    private fun createManager(epoch: Int): NativeManager = NativeManager(epoch)

    private val central: CBCentralManager
        get() = withLock(attemptLock) {
            (nativeManager ?: createManager(managerEpochs.current).also { nativeManager = it }).central
        }

    // ---------------- 扫描（对外） ----------------

    override suspend fun scan(onFound: (ScanDevice) -> Unit) {
        // 先等状态就位（首次创建或 epoch 重建时 state 还是 unknown）。
        val state = awaitState()
        if (state != CBManagerStatePoweredOn) {
            throw IllegalStateException(stateHint(state))
        }
        withLock(attemptLock) {
            check(managerEpochs.accepts(managerEpochs.current))
            val c = central
            runCatching { c.stopScan() }
            scanHandler = onFound
            logI("开始扫描（保护板 ANT + 电量计 蓝宝/陆行）")
            c.scanForPeripheralsWithServices(null, null)
        }
    }

    override fun stopScan() {
        scanHandler = null
        runCatching { central.stopScan() }
    }

    // ---------------- 连接 ----------------

    override suspend fun connect(address: String?) {
        loopJob?.cancelAndJoin()
        closePeripheral()
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
            val targetEpoch = withLock(attemptLock) { managerEpochs.current }
            val target = runCatching { findPeripheral(address) }.getOrNull()
            if (target == null) {
                _connectHint.value = if (address.isNullOrBlank()) "没有扫描到目标设备"
                                    else "没有扫描到目标设备（可能不在范围内或未上电）"
                logW("扫描未找到设备（尝试 #$attempt）")
                if (manualStop || !currentCoroutineContext().isActive) break
                delay(backoffMs(attempt))
                continue
            }

            // 同一 identifier 必须先收到旧连接的终止回调；超时只能继续等，不能重开。
            val barrier = withLock(attemptLock) { terminations.waiting(target.identifier.UUIDString, targetEpoch) }
            if (barrier != null && withTimeoutOrNull(CONNECT_TIMEOUT_MS) { barrier.await() } == null) {
                _connectHint.value = "等待上一轮蓝牙连接结束…"
                delay(backoffMs(attempt))
                continue
            }
            val owner = withLock(attemptLock) {
                // A retired epoch's barrier can wake us: re-find the target on the new manager.
                if (!managerEpochs.accepts(targetEpoch)) return@withLock null
                check(terminations.waiting(target.identifier.UUIDString, targetEpoch) == null)
                val owner = attempts.begin(target.identifier.UUIDString)
                attemptGen++
                peripheral = target
                logI("连接目标 ${target.name ?: "未命名"} ${target.identifier.UUIDString}（代次 $attemptGen）")
                central.connectPeripheral(target, null)
                owner
            }
            if (owner == null) {
                delay(backoffMs(attempt))
                continue
            }
            try {
                val discovered = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { owner.services.await() } != null
                val outcome: Boolean? = if (!discovered) null else {
                    withTimeoutOrNull(PROBE_BUDGET_MS) { owner.ready.await() }
                }
                if (outcome == true) {
                    // 等待方不再次发布 Connected，ready 后立即 drop 也不会复活链路。
                    val connectedAt = epochMillisNow()
                    owner.dropped.await()
                    val livedMs = epochMillisNow() - connectedAt
                    logW("链路断开：就绪后存活 ${livedMs}ms")
                    if (manualStop || !currentCoroutineContext().isActive) break
                    if (livedMs < 5_000) {
                        _connectHint.value = "连接建立后很快断开，正在重试…"
                    } else {
                        attempt = 0
                        _connectHint.value = "连接已断开，正在重连…"
                    }
                } else {
                    _connectHint.value = if (outcome == null) "连接超时，正在重试…" else "连接失败，正在重试…"
                }
            } finally {
                closePeripheral(owner)
            }
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
     * 找到要连的 CBPeripheral。
     *
     * **有目标标识（回连记忆设备）时优先直连**：`retrievePeripheralsWithIdentifiers` 从系统
     * 缓存按标识取回外设——这是 CoreBluetooth 官方的重连方式，不需要扫描就能 connect
     * （系统在后台自己找设备），对应 Android 的「按 MAC 直连」。广播扫描只留给
     * 首次连接（没有标识）与直连取回失败（标识失效，比如用户「忽略此设备」）。
     *
     * 为什么不一直扫：连接循环每轮 stop→scan→stop→connect 的反复切换会撞上系统对
     * 已知设备的广播上报抑制（同一设备短时间内的重复广播不再回调 didDiscover），
     * 实测表现为「设备明明在旁边、弹窗也刚扫到过，回连却一轮都发现不了」，
     * 15 秒超时无限退避。直连彻底绕开广播，也就绕开了抑制。
     *
     * 扫描路径：**只认目标标识**（没有标识时才接受任意目标设备）。
     * 标识取回失败（被「忽略此设备」/标识失效）时扫描若也没命中，本轮返回 null 由调用方退避重试——
     * 绝不能连到别的设备上：会话的地址、密码、家族、快照归属全部按目标设备建立，
     * 连错了设备会把这些数据写到另一台设备的档案里（见 [acceptsPeripheralId]）。
     */
    private suspend fun findPeripheral(address: String?): CBPeripheral? {
        val state = awaitState()
        if (state != CBManagerStatePoweredOn) {
            _connectHint.value = stateHint(state)
            return null
        }
        val (c, epoch) = withLock(attemptLock) { central to managerEpochs.current }
        // 1) 回连：按标识从系统缓存直取（免扫描，不受广播抑制影响）
        if (!address.isNullOrBlank()) {
            // 构造失败会抛 NSException 而不是返回 null，Kotlin 侧用 runCatching 兜住。
            // 参数名是 uUIDString（K/N 对 init(UUIDString:) 的压首字母映射），不是 UUIDString
            val uuid: NSUUID? = runCatching { NSUUID(uUIDString = address) }.getOrNull()
            if (uuid != null) {
                val cached = c.retrievePeripheralsWithIdentifiers(listOf(uuid)).firstOrNull()
                    as? CBPeripheral
                if (cached != null) {
                    logD("按标识直连缓存外设 ${cached.name ?: address}（免扫描）")
                    return cached
                }
                logW("系统缓存里没有标识 $address 的外设（可能被「忽略此设备」），退回广播扫描")
            } else {
                logW("目标标识 $address 不是合法 UUID，退回广播扫描")
            }
        }
        // 2) 首次连接 / 缓存取回失败：广播扫描
        runCatching { c.stopScan() }
        pendingPeripheral = null
        logD("扫描设备中（上限 ${FIND_TIMEOUT_MS}ms）")
        c.scanForPeripheralsWithServices(null, null)
        // 等"找到目标"：didDiscoverPeripheral 置 pendingPeripheral，这里轮询感知
        var waited = 0L
        while (waited < FIND_TIMEOUT_MS && pendingPeripheral == null && !manualStop &&
            withLock(attemptLock) { managerEpochs.accepts(epoch) }
        ) {
            delay(100)
            waited += 100
        }
        runCatching { c.stopScan() }
        return withLock(attemptLock) {
            if (!managerEpochs.accepts(epoch)) null else pendingPeripheral.also {
                if (it == null) logW("未发现标识为 ${address ?: "-"} 的目标设备（本轮放弃，稍后重试）")
            }
        }
    }

    private fun closePeripheral(owner: ConnectionAttempt? = null, terminal: Boolean = false): Unit = withLock(attemptLock) {
        if (owner != null && !attempts.owns(owner)) return@withLock
        // null 外设也必须清状态、失效异步工作。旧 attempt 的 finally 不得清新 attempt。
        attemptGen++
        val current = attempts.current
        val p = peripheral
        val probe = probeSignal
        peripheral = null
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
        if (p != null) {
            p.delegate = null
            if (!terminal && managerEpochs.accepts(managerEpochs.current)) {
                // 只给仍有效的 manager 挂取消屏障；manager loss 走 epoch 终止出口。
                terminations.begin(p.identifier.UUIDString, managerEpochs.current)
                runCatching { central.cancelPeripheralConnection(p) }
                    .onFailure { logW("取消连接失败，保留终止屏障：${it.message}") }
            }
        }
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
        loopJob?.cancelAndJoin()
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
