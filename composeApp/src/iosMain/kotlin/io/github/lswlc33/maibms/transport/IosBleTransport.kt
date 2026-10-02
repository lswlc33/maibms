package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.epochMillisNow
import io.github.lswlc33.maibms.data.fmt
import io.github.lswlc33.maibms.protocol.Frame
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.CoreBluetooth.CBCharacteristicWriteType
import platform.CoreBluetooth.CBManagerState
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
        /** 服务与通道候选（与 Android 完全一致，docs/02 §2.2） */
        const val SERVICE_FFE0 = "FFE0"
        val CHANNELS = listOf(
            "FFE1" to "FFE1",
            "FFF3" to "FFF4",
            "FFF5" to "FFF6",
        )

        /** 设备广播名前缀（ANT BMS 的板子都以此开头） */
        const val NAME_PREFIX = "ANT"
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
            if (central.state != CBManagerState.CBManagerStatePoweredOn) {
                _connectHint.value = when (central.state) {
                    CBManagerState.CBManagerStateUnauthorized ->
                        "未获得蓝牙权限，请在系统设置中允许本应用使用蓝牙"
                    CBManagerState.CBManagerStatePoweredOff -> "蓝牙未打开，请在控制中心或设置中打开蓝牙"
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
            if (!name.startsWith(NAME_PREFIX, ignoreCase = true)) return
            val id = didDiscoverPeripheral.identifier.UUIDString
            logD("scan 发现 $name $id rssi=${RSSI.intValue}")
            scanHandler?.invoke(ScanDevice(name, id, RSSI.intValue))

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

        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            logE("连接失败：${error?.localizedDescription ?: "未知原因"}")
            readySignal?.complete(false)
        }

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
            readySignal?.complete(false)
            dropSignal?.complete(Unit)
        }

        // ---------------- 服务 / 特征 ----------------

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                logE("发现服务失败：${didDiscoverServices.localizedDescription}")
                readySignal?.complete(false)
                return
            }
            val services = peripheral.services.orEmpty().mapNotNull { it as? CBService }
            logD("服务列表: " + services.joinToString { shortUuid(it.UUID.UUIDString) })
            val service = services.firstOrNull { it.UUID.UUIDString.equals(SERVICE_FFE0, ignoreCase = true) }
            if (service == null) {
                logE("设备没有 FFE0 服务（不是 ANT 保护板？）")
                readySignal?.complete(false)
                return
            }
            peripheral.discoverCharacteristics(null, forService = service)
        }

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
            logD("特征列表: " + chars.joinToString { shortUuid(it.UUID.UUIDString) })
            for ((w, n) in CHANNELS) {
                val wc = chars.firstOrNull { it.UUID.UUIDString.equals(w, ignoreCase = true) }
                val nc = chars.firstOrNull { it.UUID.UUIDString.equals(n, ignoreCase = true) }
                if (wc != null && nc != null) {
                    writeChar = wc
                    notifyChar = nc
                    break
                }
            }
            val wc = writeChar
            val nc = notifyChar
            if (wc == null || nc == null) {
                logE("FFE0 下没有可用的写/通知通道")
                readySignal?.complete(false)
                return
            }
            logI("通道选中 写=${shortUuid(wc.UUID.UUIDString)} 通知=${shortUuid(nc.UUID.UUIDString)}")
            maxWriteLen = peripheral
                .maximumWriteValueLengthForType(CBCharacteristicWriteType.CBCharacteristicWriteWithoutResponse)
                .toInt()
            logD("单次写上限 $maxWriteLen 字节")
            // 先订阅通知（docs 硬性顺序）；就绪要等订阅落地（见 didUpdateNotificationState）
            peripheral.setNotifyValue(true, forCharacteristic = nc)
            armReadyFallback()
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                // 订阅没落地 = 设备不会主动上报，链路"通着"也收不到任何帧：按本轮失败重连
                logE("订阅通知失败：${error.localizedDescription}（本轮按失败重连）")
                readySignal?.complete(false)
                return
            }
            val uuid = didUpdateNotificationStateForCharacteristic.UUID.UUIDString
            if (uuid.equals(notifyChar?.UUID?.UUIDString, ignoreCase = true)) {
                logD("订阅已落地")
                completeReady()
            }
        }

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
            _incoming.tryEmit(bytes)
        }

        /** 订阅确认兜底：个别固件不回调通知状态，超时放行，绝不把连接卡死 */
        private fun armReadyFallback() {
            scope.launch {
                delay(READY_FALLBACK_MS)
                val s = readySignal
                if (s != null && !s.isCompleted) {
                    logW("订阅确认 ${READY_FALLBACK_MS}ms 未回调，按「订阅已发出」继续（兜底）")
                    completeReady()
                }
            }
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
        CBManagerState.CBManagerStatePoweredOn -> "已开启"
        CBManagerState.CBManagerStatePoweredOff -> "关闭"
        CBManagerState.CBManagerStateUnauthorized -> "未授权"
        CBManagerState.CBManagerStateUnsupported -> "本机不支持"
        CBManagerState.CBManagerStateResetting -> "正在重置"
        CBManagerState.CBManagerStateUnknown -> "未知"
        else -> state.name
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
        if (c.state != CBManagerState.CBManagerStatePoweredOn) {
            throw IllegalStateException("设备蓝牙未打开或未授权")
        }
        runCatching { c.stopScan() }
        scanHandler = onFound
        logI("开始扫描 ANT 设备")
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
                _connectHint.value = if (address.isNullOrBlank()) "没有扫描到 ANT 设备"
                                    else "没有扫描到目标设备（可能不在范围内或未上电）"
                logW("扫描未找到设备（尝试 #$attempt）")
                if (manualStop || !currentCoroutineContext().isActive) break
                delay(backoffMs(attempt))
                continue
            }

            // 2) 建链 + 等订阅就绪
            val ready = CompletableDeferred<Boolean>()
            readySignal = ready
            dropSignal = null
            peripheral = target
            logI("连接目标 ${target.name ?: "未命名"} ${target.identifier.UUIDString}")
            central.connectPeripheral(target, null)
            val outcome = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
            if (outcome == true) {
                _connectHint.value = null
                _linkState.value = LinkState.Connected
                logI("链路就绪（单次写上限 $maxWriteLen 字节）")
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
        if (c.state != CBManagerState.CBManagerStatePoweredOn) {
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
                type = CBCharacteristicWriteType.CBCharacteristicWriteWithoutResponse,
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
