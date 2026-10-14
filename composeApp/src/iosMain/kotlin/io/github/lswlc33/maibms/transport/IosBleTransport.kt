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
    // 下面这些字段被两个线程读写：CoreBluetooth 的 delegate（queue = null → 主队列）与连接循环 /
    // 探测协程（Dispatchers.Default）。Android 那边回调与循环同属一套线程池、JVM 的内存模型也更宽容，
    // 同样的写法没出过事；iOS 上必须显式 @Volatile，否则会出现「探测应答看不到」「掉线回调看不到
    // dropSignal」这类只此一家的错乱。

    @Volatile private var candidates: List<Candidate> = emptyList()
    @Volatile private var candidateIndex = 0
    @Volatile private var probeStarted = false
    @Volatile private var probeSignal: CompletableDeferred<Boolean>? = null

    /**
     * 本次连接的「代次」：每开始一次尝试、每次收尾（[closePeripheral]）都 +1。
     *
     * 为什么不用对象身份判断「这是不是上一轮的迟到者」：CoreBluetooth 在 ObjC 层对同一台设备
     * 只有一个 CBPeripheral 实例，但每次回调跨进 Kotlin 都可能包一层新包装——`p !== peripheral`
     * 这种判断在**同一次连接内都可能为真**，拿它放行/拦人曾把所有通往就绪的路径静默掐断
     * （见 [afterSubscribed] 注释）。代次比较不依赖对象身份，是这里唯一可靠的「认人」方式。
     */
    @Volatile private var attemptGen = 0

    /** 下次连接优先尝试的通道（手动切换或按设备记忆；仅本进程有效） */
    @Volatile private var preferred: BleChannel? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 用户想要连接的目标（iOS 上是外设标识串）；null = 已主动断开 */
    @Volatile private var desiredAddress: String? = null
    @Volatile private var manualStop = false
    private var loopJob: Job? = null

    @Volatile private var peripheral: CBPeripheral? = null
    @Volatile private var writeChar: CBCharacteristic? = null
    @Volatile private var notifyChar: CBCharacteristic? = null

    /** 单次写上限（字节），由 maximumWriteValueLengthForType 更新 */
    @Volatile private var maxWriteLen: Int = 20

    /** 本次尝试的「就绪」信号：true=服务/订阅就绪，false=就绪前断开 */
    @Volatile private var readySignal: CompletableDeferred<Boolean>? = null
    /** 服务发现完成信号：让「建链 + 发现服务」与「通道探测」两段超时分开算 */
    @Volatile private var servicesSignal: CompletableDeferred<Unit>? = null
    /** 已就绪后的断开信号（**必须在可能就绪之前就挂好**，见 connectionLoop） */
    @Volatile private var dropSignal: CompletableDeferred<Unit>? = null

    // ---- 扫描（对外列设备 / 对内找目标）----
    @Volatile private var scanHandler: ((ScanDevice) -> Unit)? = null
    /** 扫描命中的目标：精确匹配优先，兜底取第一个 ANT 设备 */
    @Volatile private var pendingPeripheral: CBPeripheral? = null
    @Volatile private var fallbackPeripheral: CBPeripheral? = null

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
                _connectHint.value = stateHint(central.state)
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
            // 原始 UUIDString 一并打出来：iOS 对 16 位 UUID 有时给短形态（"FFE0"）、有时给完整 128 位，
            // 日志里只留归一化后的短码会把这个问题掩盖掉（对照用的原始值就是这行的用途）
            logD("服务列表: " + services.joinToString { "${shortUuid(it.UUID.UUIDString)}(${it.UUID.UUIDString})" })
            val service = services.firstOrNull { shortUuid(it.UUID.UUIDString) == SERVICE_FFE0 }
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
                val uuid = didUpdateNotificationStateForCharacteristic.UUID.UUIDString
                // 上一轮的迟到回调也会走这里：iOS 复用同一个 CBPeripheral，若它在这里翻页，
                // 本轮的候选指针就被带偏了。认不准期望特征时（expected == null）保持原行为
                val expected = notifyChar?.UUID?.UUIDString
                if (expected != null && !uuid.equals(expected, ignoreCase = true)) {
                    logD("订阅失败回调不属于当前候选（${shortUuid(uuid)}），忽略")
                    return
                }
                // 订阅没落地 = 这条通道收不到任何帧：换下一个候选（没有下一个则本轮连接失败）
                logE("订阅通知失败：${error.localizedDescription}（换下一个候选通道）")
                candidateIndex++
                probeNext(peripheral)
                return
            }
            val uuid = didUpdateNotificationStateForCharacteristic.UUID.UUIDString
            if (uuid.equals(notifyChar?.UUID?.UUIDString, ignoreCase = true)) {
                logD("订阅已落地，开始发探测帧")
                val c = candidates.getOrNull(candidateIndex)
                if (c == null) {
                    // 有订阅确认却没有当前候选：候选表已被收尾清空（本轮已放弃）。
                    // 原先这里静默 return，看起来和「迟到者被正确丢弃」一模一样，
                    // 真正的活链路被吞了就绪信号时也查不到任何痕迹
                    logW("订阅确认到达时候选表为空（本轮已被收尾？），忽略")
                    return
                }
                afterSubscribed(peripheral, c)
            } else {
                logD("订阅确认来自非当前候选（${shortUuid(uuid)} ≠ ${notifyChar?.let { shortUuid(it.UUID.UUIDString) }}），忽略")
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
            val gen = attemptGen
            scope.launch {
                delay(READY_FALLBACK_MS)
                if (gen != attemptGen) return@launch
                // 走到这里说明订阅确认确实没回调（回调了的话探测早就开始了）
                if (!probeStarted && !targetFamily.isMeter) {
                    logD("订阅确认未回调，按 ${READY_FALLBACK_MS}ms 兜底进入探测")
                }
                afterSubscribed(p, c)
            }
        }

        /**
         * 订阅落地后的动作：保护板走 ANT 探测；电量计暂无探测帧，直接按“FFE0 + 写/通知就绪”判就绪。
         *
         * **不要在这里用 `p !== peripheral` 拦人**——CoreBluetooth 每次回调交给 Kotlin 的
         * CBPeripheral 包装对象身份不稳定（与 `peripheral` 字段里持有的经常不是同一个实例），
         * 这个判断恒为真，曾把所有通往就绪的路径全部静默掐断：订阅确认回调来了、
         * afterSubscribed 进来就被 return，既不置就绪也不翻候选，26 秒探测预算耗尽后本端拆链，
         * 日志只剩「订阅已落地」+「连接断开：正常断开」，再无下文。所有设备、每次连接都这样。
         * 身份的甄别一律交给 [attemptGen] 代次（连接循环每轮、closePeripheral 都 +1，
         * 上一轮的迟到者自然过期），与 [startProbe] 的探测协程同一套机制。
         */
        private fun afterSubscribed(p: CBPeripheral, c: Candidate) {
            if (targetFamily.isMeter) {
                // 订阅确认回调与 800ms 兜底都会走到这里，已就绪就不重复置态/刷日志
                if (_linkState.value == LinkState.Connected) return
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
            if (probeStarted) {
                logD("探测已在进行，跳过重复触发（候选 ${c.channel.id}）")
                return
            }
            // 迟到的兜底（候选已经翻页）不能再探：否则会拿旧候选的写特征去发帧
            if (candidates.getOrNull(candidateIndex) !== c) {
                logD("候选已翻页，跳过过期探测（${c.channel.id}）")
                return
            }
            probeStarted = true
            val gen = attemptGen
            val signal = CompletableDeferred<Boolean>()
            probeSignal = signal
            scope.launch {
                delay(PROBE_SETTLE_MS)   // 等订阅生效，与官方 200ms 切通道间隔一致
                if (gen != attemptGen) return@launch   // 本轮已被收尾，别碰新链路
                runCatching {
                    p.writeValue(
                        Frame.readRealtime().toNSData(),
                        forCharacteristic = c.write,
                        type = CBCharacteristicWriteWithoutResponse,
                    )
                }.onFailure { logW("探测帧写入失败：${it.message}") }
                val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { signal.await() } ?: false
                // 这 8 秒里链路可能已经换代（掉线重连/被放弃）：iOS 的 p 与当前 peripheral 是**同一个
                // 对象**，下面的退订和翻页会打在活链路上（退订通知 = 应用从此收不到任何帧），
                // 所以这里必须按代次认人——这是 Android 那句 `g !== gatt` 在 iOS 的等价物
                if (gen != attemptGen) {
                    logD("探测协程已过期（第 $gen 轮），丢弃结果：${c.channel.id}")
                    return@launch
                }
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
        val c = central
        var waited = 0L
        while (c.state == CBManagerStateUnknown && waited < timeoutMs) {
            delay(50)
            waited += 50
        }
        return c.state
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

    private val central: CBCentralManager by lazy {
        CBCentralManager(delegate = delegate, queue = null)
    }

    // ---------------- 扫描（对外） ----------------

    override suspend fun scan(onFound: (ScanDevice) -> Unit) {
        val c = central
        // 先等状态就位（首次创建管理器时 state 还是 unknown，见 awaitState）
        val state = awaitState()
        if (state != CBManagerStatePoweredOn) {
            throw IllegalStateException(stateHint(state))
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
            // 代次 +1：上一轮遗留的探测协程 / 订阅兜底就此作废（它们手里的 CBPeripheral 与本轮
            // 是同一个对象，只比对象身份分不出新旧，见 attemptGen 的注释）
            attemptGen++
            if (attempt > 1) _connectHint.value = "第 $attempt 次尝试…"
            logI("连接尝试 #$attempt（代次 $attemptGen）")
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
            // 掉线信号必须在「可能就绪」之前挂好：delegate 在主队列、这里在 Default，
            // 若等 ready 返回后再赋值，正好落在这个窗口里的掉线会被漏掉（回调落在上一轮的
            // deferred 上），本轮就永远挂在 dropped.await()——界面停在「已连接」，也不再重连
            val dropped = CompletableDeferred<Unit>()
            dropSignal = dropped
            peripheral = target
            logI("连接目标 ${target.name ?: "未命名"} ${target.identifier.UUIDString}")
            central.connectPeripheral(target, null)
            // 两段超时：① 建链 + 发现服务用连接超时；② 通道探测按**最坏候选数**给预算
            //（每条候选最多 PROBE_TIMEOUT_MS，与 Android/官方同量级；不能按此刻的候选数算，见 PROBE_BUDGET_MS）
            val discovered = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { servicesFound.await() } != null
            val outcome: Boolean? = if (!discovered) null else {
                withTimeoutOrNull(PROBE_BUDGET_MS) { ready.await() }
            }
            if (outcome == true) {
                _connectHint.value = null
                _linkState.value = LinkState.Connected
                logI("链路就绪（通道 ${_activeChannel.value?.id ?: "?"}，单次写上限 $maxWriteLen 字节）")
                val connectedAt = epochMillisNow()
                runCatching { dropped.await() }
                val livedMs = epochMillisNow() - connectedAt
                // 存活时长是判断「连上就断」的关键：~7s 说明是本端失联轮切拆的，
                // 秒级以内则更像板子/系统终止的链路（对照上一行的「连接断开：…」看有无 error）
                logW("链路断开：就绪后存活 ${livedMs}ms（通道 ${_activeChannel.value?.id ?: "?"}）")
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
     * 扫描路径：优先标识一致；超时后若只见过别的同族设备就拿它兜底
     * （对应 Android「没有指定地址就用已配对的 ANT 设备」）。找不到返回 null 由调用方退避重试。
     */
    private suspend fun findPeripheral(address: String?): CBPeripheral? {
        val c = central
        val state = awaitState()
        if (state != CBManagerStatePoweredOn) {
            _connectHint.value = stateHint(state)
            return null
        }
        // 1) 回连：按标识从系统缓存直取（免扫描，不受广播抑制影响）
        if (!address.isNullOrBlank()) {
            val uuid = runCatching { NSUUID(UUIDString = address) }.getOrNull()
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
        // 代次先 +1：不管有没有外设可收尾，本轮的所有迟到者（探测协程、订阅兜底）就此作废——
        // 它们手里的 CBPeripheral 与下一轮是同一个对象，退订通知会打在活链路上
        attemptGen++
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
        // 注意：cancelPeripheralConnection 是异步的，返回不等于已断开。这里靠调用方的退避
        // （≥1s）等它落地；若日志里出现「刚就绪就被正常断开」，先怀疑它落在了新链路上
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
