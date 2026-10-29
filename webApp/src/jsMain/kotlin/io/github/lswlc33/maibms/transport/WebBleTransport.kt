package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.LinkStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.await
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import kotlin.js.unsafeCast

/**
 * 网页端 BLE 传输：**Web Bluetooth**（安卓 Chrome 免 flag 可用）。
 *
 * 与 [AndroidBleTransport] 的目标行为一致——扫描/选设备 → 连 FFE0 → 探测通道
 * （FFE1 / FFF3-4 / FFF5-6，电量计走 FFE2-FFE1）→ 先订阅通知 → 发探测帧等 0x11/0x12 应答
 * → 就绪 → 掉线退避重连。差别都在浏览器这一侧的硬约束上，逐条说明：
 *
 * 1. **没有"持续扫描"**。`requestDevice()` 弹的是浏览器自己的设备选择器，返回**一台**设备。
 *    所以 [scan] = 拉起选择器、把选中那台报给上层；且**必须在用户手势里调用**
 *    （Chrome 要求瞬态用户激活，页面加载时自动弹窗会被拒）。
 * 2. **没有 MAC**。`device.id` 是 Chrome 按源分配的稳定标识，不是 MAC——上层只把它当
 *    "设备档案的键"，语义与 Android 的 MAC 相同（[ScanDevice.address] 装的就是它）。
 * 3. **拿不到 RSSI**，一律报 0（`AdvData` 不暴露）。
 * 4. **没有 MTU 协商 API**。写入必须自己分片：这里按 20 字节保守切分。
 *    本项目所有**上行帧都很短**（读实时/读参数/写参数/控制/鉴权，含 CRC 也就 8~16 字节），
 *    实际几乎永远一片写完；20 是为了防个别固件把帧拼长时炸 GATT。
 * 5. **GATT 操作必须串行**。Chrome 对同一特征并发写会抛
 *    "GATT operation already in progress"，所以写全走 [writeMutex]。
 * 6. **CCCD 不用手写**：`startNotifications()` 已内含订阅动作（Android 要自己写 0x2902）。
 */
class WebBleTransport : BmsTransport {

    companion object {
        private const val SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
        private const val UUID_FFF5 = "0000fff5-0000-1000-8000-00805f9b34fb"

        private fun uuidOf(short: String) = "0000${short.lowercase()}-0000-1000-8000-00805f9b34fb"

        /** 探测超时 / 切通道间隔：与 Android 实现保持一致（官方也是 8s/条 + 200ms 间隔） */
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_SETTLE_MS = 200L

        /** 上行分片上限：见类注释第 4 条（本项目的帧几乎都 ≤ 16 字节） */
        const val MAX_CHUNK = 20

        /** 分片间隔：给 GATT 队列一点喘息，避免 Chrome 抛 operation-in-progress */
        const val WRITE_GAP_MS = 20L

        /** 连接超时（gatt.connect() 本身不超时，靠这里兜） */
        const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _linkState = MutableStateFlow(LinkState.Idle)
    override val linkState: StateFlow<LinkState> = _linkState

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: MutableSharedFlow<ByteArray> get() = _incoming

    override val supportsScan: Boolean get() = true

    private val _connectHint = MutableStateFlow<String?>(null)
    override val connectHint: StateFlow<String?> = _connectHint

    private val _activeChannel = MutableStateFlow<BleChannel?>(null)
    override val activeChannel: StateFlow<BleChannel?> = _activeChannel
    private val _availableChannels = MutableStateFlow<List<BleChannel>>(emptyList())
    override val availableChannels: StateFlow<List<BleChannel>> = _availableChannels
    override val supportsChannelSwitch: Boolean get() = true

    private val _currentFamily = MutableStateFlow(DeviceFamily.Unknown)
    override val currentFamily: StateFlow<DeviceFamily> = _currentFamily

    private var targetFamily: DeviceFamily = DeviceFamily.Ant
    override fun setTargetFamily(family: DeviceFamily) {
        targetFamily = family
    }

    /** 浏览器授权过的设备句柄：`connect()` 只能用它（换设备必须重新走用户选择） */
    private var authorized: WebBluetoothDevice? = null

    /** 本次连接目标（device.id）；null = 已主动断开 */
    private var desiredId: String? = null
    private var manualStop = false
    private var loopJob: Job? = null

    /** 通道偏好（手动切换/按设备记忆） */
    private var preferred: BleChannel? = null

    private var server: WebBluetoothGattServer? = null
    private var writeChar: WebBluetoothGattCharacteristic? = null
    private var notifyChar: WebBluetoothGattCharacteristic? = null
    private var notifyListener: ((dynamic) -> Unit)? = null

    /** GATT 掉线回调（与 notifyListener 一样随连接生命周期挂/摘） */
    private var disconnectListener: ((dynamic) -> Unit)? = null

    /**
     * 物理 GATT 连接串行化。
     *
     * Chrome 的 `gatt.connect()` 返回的 Promise 不会因为协程取消而撤销：上一个重连循环
     * 被取消后，它的 connect 仍会稍后 resolve，随后把 server 赋给新会话（或把旧会话的
     * server 覆盖成已断开的对象）。所有 connect/disconnect 都在这把锁里执行，
     * 并且每次 await 之后重新校验"这次操作是否还是当前想要的"。
     */
    private val gattMutex = Mutex()

    /** 连接代次：每次 connect/disconnect +1。await 之后代次变了就说明本次操作已过期。 */
    private var generation = 0L

    /** 正在进行、尚未 resolve 的 gatt.connect() Promise（它不响应协程取消，需单独收尾） */
    private var pendingConnect: Promise<WebBluetoothGattServer>? = null

    /** 就绪后的掉线信号 */
    private var dropSignal: CompletableDeferred<Unit>? = null

    /** 探测期间的应答信号 */
    private var probeSignal: CompletableDeferred<Boolean>? = null

    /** GATT 写必须串行（Chrome 并发写会抛 operation-in-progress） */
    private val writeMutex = Mutex()

    /** 是否已授权过设备（供平台能力/界面判断"能不能自动重连"） */
    val hasAuthorizedDevice: Boolean get() = authorized != null

    private fun logI(msg: String) = BmsLog.i("WEB", msg)
    private fun logW(msg: String) = BmsLog.w("WEB", msg)
    private fun logE(msg: String) = BmsLog.e("WEB", msg)
    private fun logD(msg: String) = BmsLog.d("WEB", msg)

    private fun hex(b: ByteArray) = BmsLog.hex(b)

    // ---------------- 扫描 / 选择设备 ----------------

    /**
     * 拉起浏览器设备选择器。
     *
     * [includeAllDevices] = true 时列出附近所有 BLE 设备（板子的广播名不标准、或名字被截断时用它）；
     * 默认只列已知前缀（ANT / BlueBabe / EM06 / EM2APP…），列表更干净。
     */
    var includeAllDevices: Boolean = false

    override suspend fun scan(onFound: (ScanDevice) -> Unit) {
        val bt = bluetoothAdapter()
            ?: throw IllegalStateException(
                "当前浏览器不支持 Web Bluetooth。请用安卓 Chrome，并确保通过 https 或 localhost 打开。"
            )
        val device = try {
            bt.requestDevice(requestOptions(includeAllDevices)).await<WebBluetoothDevice>()
        } catch (e: Throwable) {
            // 用户在选择器里点了取消：Chrome 抛 NotFoundError。这不是错误，别把它报成"扫描失败"
            if (isCancellation(e)) {
                logI("用户取消了设备选择")
                return
            }
            logE("requestDevice 失败：${e.message}")
            throw IllegalStateException(describeScanError(e))
        }
        authorized = device
        val name = device.name?.trim().orEmpty()
        val family = DeviceFamily.matchName(name)
        logI("已选择设备：${name.ifBlank { "(无名)" }}（${device.id}）→ ${family?.label ?: "未知家族"}")
        // 选择器现在列出全部设备（见 requestOptions 的说明），用户可能选到无关设备：
        // 给一句人话提示，但**不阻断**——协议探测失败会给出"通道无应答"，
        // 而某些板子的广播名确实不标准（官方也允许手选）。
        if (family == null) {
            _connectHint.value = "「${name.ifBlank { "未命名设备" }}」不在已知型号列表里，" +
                "可能是无关设备；仍会尝试连接，失败请换一台。"
        }
        // Web Bluetooth 不给 RSSI；address 用 Chrome 的设备标识（上层只当档案键用）
        onFound(
            ScanDevice(
                name = name.ifBlank { "未命名设备" },
                address = device.id,
                rssi = 0,
                family = family ?: DeviceFamily.Unknown,
            )
        )
        // 选择器已确定唯一目标，扫描到此为止（没有"持续扫描"这回事）
        if (family != null) _connectHint.value = "已选择：${name.ifBlank { "未命名设备" }}"
    }

    private fun requestOptions(all: Boolean): dynamic {
        // 不能用 js() 字面量里写 [SERVICE]：js() 是编译期原样输出，Kotlin 常量不会被
        // 解析进去，SERVICE 会变成裸 JS 标识符 → requestDevice 时抛
        // "SERVICE is not defined"，设备选择器根本弹不出来（实测）。
        // 这里用运行时字符串模板 + JSON.parse，SERVICE 以真字符串插进去。
        //
        // 过滤策略：**默认不按 namePrefix 过滤**，与 Android 侧对齐。
        // Chrome 的 namePrefix 匹配区分大小写，而引擎的 DeviceFamily 是大小写不敏感、
        // 且蓝宝电量计的前缀同时有 "BlueBabe"/"blue"/"lb" 三种写法——用 namePrefix 会
        // 把名字是 "blue-xxx" / "lb123" 的电量计直接挡在选择器之外（用户根本选不到）。
        // Android 是"先扫全部、再按 DeviceFamily.isTarget 判定"，这里照做：
        // 选择器里给出全部设备，家族判定交给 matchName（不区分大小写，规则只有一处）。
        // [all] 参数保留：历史调用点用它表达"更宽的范围"，现在两者等价。
        return JSON.parse("""{"acceptAllDevices":true,"optionalServices":["$SERVICE"]}""")
    }

    override fun stopScan() {
        // 浏览器选择器由用户自己关，没有可取消的扫码会话
    }

    // ---------------- 连接 ----------------

    override suspend fun connect(address: String?) {
        val dev = authorized
        if (dev == null) {
            _connectHint.value = "网页端需要先点「选择设备」授权一台设备"
            _linkState.value = LinkState.Disconnected
            return
        }
        // 指定了别的设备而手上只有这一台的授权：不能"随便换一台连"，那会把会话数据写到错设备档案上
        if (!address.isNullOrBlank() && address != dev.id) {
            _connectHint.value = "要连接的是另一台设备，请重新点「选择设备」"
            _linkState.value = LinkState.Disconnected
            return
        }
        manualStop = false
        desiredId = dev.id
        generation++
        _currentFamily.value = targetFamily
        _linkState.value = LinkState.Connecting
        _connectHint.value = "正在连接 ${dev.name?.trim() ?: "设备"}…"
        logI("连接目标 ${dev.name} ${dev.id}（家族 ${targetFamily.label}）")
        loopJob?.cancel()
        loopJob = scope.launch { connectionLoop(dev) }
    }

    /**
     * 常驻重连循环（与 Android 同一套退避语义）：
     * 连上 → 发现服务 → 逐条探测通道 → 就绪 → 等掉线 → 退避重来。
     */
    private suspend fun connectionLoop(dev: WebBluetoothDevice) {
        var attempt = 0
        while (!manualStop && desiredId == dev.id && currentCoroutineContext().isActive) {
            attempt++
            _linkState.value = LinkState.Connecting
            if (attempt > 1) _connectHint.value = "第 $attempt 次尝试…"
            val connectedAt = io.github.lswlc33.maibms.data.epochMillisNow()
            var livedMs = 0L
            try {
                connectStage = Stage.Connect
                // 代次快照：本轮 await 期间若用户换了设备/主动断开，这里出来的结果必须丢弃
                val myGen = generation
                val srv = gattMutex.withLock {
                    // 锁内再查一次：排队等锁期间可能已经被新的 connect 取代
                    if (myGen != generation || manualStop || desiredId != dev.id) {
                        throw ConnectSuperseded()
                    }
                    // Chrome 的 gatt.connect() Promise **不响应协程取消**：若外层 withTimeoutOrNull
                    // 只是"不再等"，Promise 仍会在稍后 resolve 并留下一条已建立的物理链路。
                    // 所以这里拿到 Promise 后先登记它，再由收尾逻辑（不可取消地）等它结算并断开。
                    val pending = dev.gatt?.connect() ?: throw IllegalStateException("设备没有 GATT 服务")
                    pendingConnect = pending
                    val got = try {
                        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { pending.await<WebBluetoothGattServer>() }
                    } finally {
                        pendingConnect = null
                    }
                    // 已过期（换设备/主动断开）：立刻断开这条迟到的链路
                    if (got != null && (myGen != generation || manualStop || desiredId != dev.id)) {
                        runCatching { got.disconnect() }
                        throw ConnectSuperseded()
                    }
                    got ?: throw IllegalStateException("连接超时")
                }
                server = srv
                _currentFamily.value = targetFamily

                // 掉线事件：Chrome 的 gattserverdisconnected 是唯一可靠的中断来源。
                // 以前没挂它，物理链路断了重连循环还在 dropped.await() 上干等，
                // 表现为"连接中/已连接"但数据永久停流（必须手动断开再连）。
                val onDisconnected: (dynamic) -> Unit = {
                    logW("GATT 掉线事件（gattserverdisconnected）")
                    dropSignal?.complete(Unit)
                }
                disconnectListener = onDisconnected
                dev.addEventListener("gattserverdisconnected", onDisconnected)

                connectStage = Stage.Service
                val service = srv.getPrimaryService(SERVICE).await<WebBluetoothGattService>()

                connectStage = Stage.Characteristics
                val candidates = buildCandidates(service)
                _availableChannels.value = candidates.map { it.channel }.ifEmpty { listOf(BleChannel.Default) }
                if (candidates.isEmpty()) {
                    logE("FFE0 下没有可用的写/通知特征")
                    throw IllegalStateException("设备没有可用的通信通道")
                }
                logI("可用通道：${candidates.joinToString { it.channel.id }}")

                // 偏好优先，其余按默认 → 备用 A → 备用 B
                val ordered = listOfNotNull(candidates.firstOrNull { it.channel == preferred }) +
                    candidates.filter { it.channel != preferred }

                connectStage = Stage.Subscribe
                val picked = probeChannels(ordered)
                    ?: throw IllegalStateException("通道无应答（设备可能被其它主机占用或信号过弱）")

                writeChar = picked.write
                notifyChar = picked.notify
                _activeChannel.value = picked.channel
                logI("通道就绪：${picked.channel.label}")

                // 就绪：等掉线
                val dropped = CompletableDeferred<Unit>()
                dropSignal = dropped
                _connectHint.value = null
                _linkState.value = LinkState.Connected
                logI("链路就绪（通道 ${picked.channel.id}）")
                dropped.await()
                livedMs = io.github.lswlc33.maibms.data.epochMillisNow() - connectedAt
                logW("链路断开（存活 ${livedMs}ms）")
            } catch (e: ConnectSuperseded) {
                logI("本次连接已被新的连接/断开取代，丢弃结果")
                livedMs = -1L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logW("本轮连接失败：${e.message}")
                _connectHint.value = describeConnectError(e)
                livedMs = 0L
            } finally {
                cleanupLink()
            }

            if (livedMs < 0) break   // 已被取代：新循环接手，这里必须退出
            _linkState.value = LinkState.Disconnected
            if (manualStop || !currentCoroutineContext().isActive) break
            // 短命连接按失败计：弱信号下别疯狂重连
            if (livedMs in 1..5_000) {
                _connectHint.value = "连接建立后很快断开，正在重试…"
                attempt = attempt.coerceAtLeast(1)
            } else if (livedMs > 5_000) {
                attempt = 0
                _connectHint.value = "连接已断开，正在重连…"
            }
            delay(backoffMs(attempt))
        }
        if (desiredId == null) {
            cleanupLink()
            _linkState.value = LinkState.Disconnected
        }
    }

    /** 本轮连接已被后来的 connect/disconnect 取代（不是错误，静默退出） */
    private class ConnectSuperseded : Exception("连接已被取代")

    private fun backoffMs(attempt: Int): Long = (1_000L shl minOf(attempt, 3)).coerceAtMost(8_000L)

    /** 一组候选：通道定义 + 实际拿到的写/通知特征 */
    private class Candidate(
        val channel: BleChannel,
        val write: WebBluetoothGattCharacteristic,
        val notify: WebBluetoothGattCharacteristic,
    )

    /** 发现服务后生成候选（规则与 Android 完全一致：只有可写 FFF5 的硬件才有备用通道） */
    private suspend fun buildCandidates(service: WebBluetoothGattService): List<Candidate> {
        val all = service.getCharacteristics().await<Array<WebBluetoothGattCharacteristic>>()
        fun find(short: String): WebBluetoothGattCharacteristic? =
            all.firstOrNull { it.uuid.equals(uuidOf(short), ignoreCase = true) }

        fun writable(c: WebBluetoothGattCharacteristic?) = c != null && propBool(c, "write", "writeWithoutResponse")
        fun notifiable(c: WebBluetoothGattCharacteristic?) = c != null && propBool(c, "notify", "indicate")

        logI("特征列表：" + all.joinToString { "${it.uuid.substring(4, 8).uppercase()}(${propText(it)})" })

        if (targetFamily.isMeter) {
            val w = find(BleChannel.Meter.writeUuid) ?: all.firstOrNull { writable(it) }
            val n = find(BleChannel.Meter.notifyUuid) ?: all.firstOrNull { notifiable(it) }
            return if (w != null && n != null && writable(w) && notifiable(n)) {
                listOf(Candidate(BleChannel.Meter, w, n))
            } else emptyList()
        }

        val hasWritableFff5 = writable(find("fff5"))
        return bleChannelCandidates(hasWritableFff5).mapNotNull { ch ->
            val w = find(ch.writeUuid)
            val n = find(ch.notifyUuid)
            if (w != null && n != null && writable(w) && notifiable(n)) Candidate(ch, w, n) else null
        }
    }

    /** 特征属性 → 人话（写进日志，回答"这块板到底有没有备用通道"） */
    private fun propText(c: WebBluetoothGattCharacteristic): String = buildList {
        if (propBool(c, "read")) add("读")
        if (propBool(c, "write")) add("写")
        if (propBool(c, "writeWithoutResponse")) add("写无响应")
        if (propBool(c, "notify")) add("通知")
        if (propBool(c, "indicate")) add("指示")
    }.joinToString("/").ifEmpty { "无" }

    /**
     * 读 BluetoothCharacteristicProperties 上的布尔位（任一为 true 即真）。
     *
     * **不要改回 `js("c.properties[...]")` 之类的写法**：`js()` 里的标识符是原文输出，
     * Kotlin 的局部变量名在产物里不存在 → 判定恒为 false → 所有设备都被当成
     * "没有可用通道"连不上。用 external 类型属性访问，编译期就绑定到真实成员。
     */
    private fun propBool(c: WebBluetoothGattCharacteristic, vararg names: String): Boolean {
        val p = c.properties
        return names.any { name ->
            when (name) {
                "read" -> p.read == true
                "write" -> p.write == true
                "writeWithoutResponse" -> p.writeWithoutResponse == true
                "notify" -> p.notify == true
                "indicate" -> p.indicate == true
                else -> false
            }
        }
    }

    /**
     * 逐条探测候选通道：订阅通知 → 发实时帧 → 等 0x11/0x12 应答。
     * 第一条有应答的即为当前通道；全部失败返回 null。
     *
     * **电量计不探测**：陆行/蓝宝电量计走 Modbus/私有报文，从不回应保护板的
     * `7E A1 01` 实时帧。以前这里对电量计也发探测帧并等 8s，必然超时，
     * 用户看到的就是"通道无应答（设备可能被其它主机占用或信号过弱）"——
     * 而 App 能连（Android 侧对电量计是"订阅成功即就绪"）。
     * 现在与 Android 一致：电量计只要通知订阅成功就算就绪，
     * 真正的握手交给 Repository 的电量计轮询帧（见 BmsRepository.pollLoop）。
     */
    private suspend fun probeChannels(ordered: List<Candidate>): Candidate? {
        val isMeter = targetFamily.isMeter
        for ((index, c) in ordered.withIndex()) {
            _connectHint.value = "通道探测：${c.channel.label}…"
            logI("通道探测 ${index + 1}/${ordered.size}：${c.channel.id}")
            val signal = CompletableDeferred<Boolean>()
            probeSignal = signal
            val listener: (dynamic) -> Unit = { event ->
                val bytes = notificationBytes(event) ?: ByteArray(0)
                if (bytes.isNotEmpty()) {
                    // 探测应答也照旧投给上层解析（与 Android 一致）
                    if (!_incoming.tryEmit(bytes)) LinkStats.noteDroppedFrame()
                    if (!signal.isCompleted && looksLikeProbeResponse(bytes)) signal.complete(true)
                }
            }
            try {
                c.notify.addEventListener("characteristicvaluechanged", listener)
                // 订阅失败是**阶段性的硬错误**（权限/配对/被其它主机占用），不能吞掉去试下一条：
                // 吞掉后最终只剩"通道无应答"，把"订阅被拒"误报成"设备没应答"，排查方向完全错。
                try {
                    c.notify.startNotifications().await<WebBluetoothGattCharacteristic>()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    connectStage = Stage.Subscribe
                    logE("通知订阅失败（${c.channel.id}）：${e.message}")
                    throw e
                }
                delay(PROBE_SETTLE_MS)
                if (isMeter) {
                    // 电量计：订阅成功即就绪（与 AndroidBleTransport.afterSubscribed 一致）。
                    // 不发 0x01 探测帧——它不会回，白等 8 秒还会被误报成"设备被占用"。
                    logI("电量计通道就绪：${c.channel.id}（家族 ${targetFamily.label}，跳过保护板探测）")
                    notifyListener = listener
                    return c
                }
                connectStage = Stage.Probe
                writeChunks(c.write, io.github.lswlc33.maibms.protocol.Frame.readRealtime())
                val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { signal.await() } ?: false
                if (ok) {
                    notifyListener = listener
                    return c
                }
                logW("通道 ${c.channel.id} 无应答（${PROBE_TIMEOUT_MS}ms 超时），换下一个候选")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (connectStage == Stage.Subscribe) throw e   // 订阅失败：上抛以保留真实阶段
                logW("通道 ${c.channel.id} 探测异常：${e.message}")
            } finally {
                probeSignal = null
                if (notifyListener !== listener) {
                    runCatching { c.notify.removeEventListener("characteristicvaluechanged", listener) }
                    runCatching { c.notify.stopNotifications() }
                }
            }
        }
        return null
    }

    /** 探测应答判定：7E 开头、功能码 0x11/0x12（与 Android 同一判据） */
    private fun looksLikeProbeResponse(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0x7E.toByte() &&
            (b[2] == 0x11.toByte() || b[2] == 0x12.toByte())

    private fun cleanupLink() {
        dropSignal = null
        probeSignal = null
        val n = notifyChar
        val l = notifyListener
        if (n != null && l != null) {
            runCatching { n.removeEventListener("characteristicvaluechanged", l) }
            runCatching { n.stopNotifications() }
        }
        notifyListener = null
        notifyChar = null
        writeChar = null
        val dl = disconnectListener
        val d = authorized
        if (dl != null && d != null) runCatching { d.removeEventListener("gattserverdisconnected", dl) }
        disconnectListener = null
        val srv = server
        server = null
        runCatching { srv?.disconnect() }
        _activeChannel.value = null
    }

    // ---------------- 写入 ----------------

    override suspend fun write(frame: ByteArray) {
        val wc = writeChar ?: run {
            logW("写入被丢弃：无写通道")
            return
        }
        if (_linkState.value != LinkState.Connected) {
            logW("写入被丢弃：链路未就绪")
            return
        }
        // 帧日志走协议层的遮蔽版（0x23 密码帧的数据区不能原样进日志）
        BmsLog.frame("WEB", "→", frame.size) { io.github.lswlc33.maibms.protocol.Frame.hexForLog(frame) }
        writeChunks(wc, frame)
    }

    /** 分片写：串行 + 每片 await（探测帧与业务帧共用） */
    private suspend fun writeChunks(wc: WebBluetoothGattCharacteristic, frame: ByteArray) = writeMutex.withLock {
        val useResponse = propBool(wc, "write") && !propBool(wc, "writeWithoutResponse")
        var offset = 0
        while (offset < frame.size) {
            val len = minOf(MAX_CHUNK, frame.size - offset)
            val piece = frame.copyOfRange(offset, offset + len)
            val payload = toUint8Array(piece)
            try {
                if (useResponse) {
                    wc.writeValueWithResponse(payload).await<Unit>()
                } else {
                    wc.writeValueWithoutResponse(payload).await<Unit>()
                }
            } catch (e: Throwable) {
                // 个别固件只支持带响应写（或反之）：失败一次就换另一种写法再试
                logW("写入失败（${offset}/${frame.size}，${if (useResponse) "带响应" else "无响应"}）：${e.message}")
                try {
                    if (useResponse) wc.writeValueWithoutResponse(payload).await<Unit>()
                    else wc.writeValueWithResponse(payload).await<Unit>()
                } catch (e2: Throwable) {
                    logE("写入彻底失败（${offset}/${frame.size}）：${e2.message}")
                    return@withLock
                }
            }
            offset += len
            if (offset < frame.size) delay(WRITE_GAP_MS)
        }
    }

    // ---------------- 通道切换 / 断开 ----------------

    override fun preferChannel(channel: BleChannel?) {
        preferred = channel
    }

    override suspend fun switchChannel(channel: BleChannel) {
        preferred = channel
        val srv = server
        if (srv == null) {
            logI("已记录通道偏好 ${channel.id}（未连接，下次连接生效）")
            return
        }
        logI("手动切换通道 → ${channel.label}，断开后按新通道重连")
        _connectHint.value = "正在切换到${channel.label}…"
        // 主动断开：掉线信号会唤醒重连循环，按新的 preferred 重新探测
        runCatching { srv.disconnect() }
    }

    override suspend fun disconnect() {
        manualStop = true
        desiredId = null
        // 代次推进：正在 await 的 connect Promise resolve 后会看到代次变了 → 主动断开自己
        generation++
        loopJob?.cancel()
        loopJob = null
        // 不可取消地等那条迟到的 connect Promise 结算并断开：它不认协程取消，
        // 如果这里直接返回，它会在稍后 resolve 时把链路重新挂起来（"断开后又自己连上"）。
        withContext(NonCancellable) {
            val pending = pendingConnect
            if (pending != null) {
                val late = runCatching { pending.await<WebBluetoothGattServer>() }.getOrNull()
                runCatching { late?.disconnect() }
                pendingConnect = null
            }
            gattMutex.withLock { cleanupLink() }
        }
        dropSignal?.complete(Unit)
        _linkState.value = LinkState.Disconnected
        _connectHint.value = null
        logI("主动断开连接")
    }

    /** 页面卸载/退出时释放（浏览器会自己收，这里让日志干净些） */
    fun shutdown() {
        manualStop = true
        desiredId = null
        generation++
        loopJob?.cancel()
        cleanupLink()
        scope.cancel()
    }

    // ---------------- JS 互操作小工具 ----------------

    /** ByteArray → Uint8Array（writeValue* 只接受 BufferSource）。
     *  用 org.khronos.webgl 的 Uint8Array，不写 js() 字面量（见 propBool 的注释）。 */
    private fun toUint8Array(b: ByteArray): Uint8Array {
        // khronos 的 Uint8Array 声明没有 (length) 构造，下标赋值的 set 重载也不收 Byte；
        // 用 Array<Byte> 构造（该声明确实提供的路径）
        val boxed = Array(b.size) { i -> b[i] }
        return Uint8Array(boxed)
    }

    private fun notificationBytes(event: dynamic): ByteArray? {
        val ev = event.unsafeCast<BluetoothCharacteristicValueChangedEvent?>()
        val dv = ev?.target?.value.unsafeCast<WebDataView?>() ?: return null
        val n = dv.byteLength
        if (n <= 0) return null
        return ByteArray(n) { i -> dv.getUint8(i).toByte() }
    }

    private fun isCancellation(e: Throwable): Boolean {
        val name = jsErrorName(e)
        return name == "NotFoundError"
    }

    /** JS 异常的 name（SecurityError/NotFoundError…）。走 asDynamic 属性访问，不用 js() 字面量。 */
    private fun jsErrorName(e: Throwable): String? = runCatching {
        val nm = e.asDynamic().name
        if (nm == null || nm == undefined) null else nm.unsafeCast<String>()
    }.getOrNull()

    private fun describeScanError(e: Throwable): String = when (jsErrorName(e)) {
        "SecurityError" -> "浏览器要求在你的点击后立刻选择设备（或页面不是 https/localhost）"
        "NotSupportedError" -> "这台设备的蓝牙不安全，浏览器拒绝访问"
        else -> e.message ?: "无法打开设备选择器"
    }

    /**
     * 把异常翻译成人话，并且**按失败阶段归因**。
     *
     * 同一个 NotFoundError 在 GATT 连接、服务发现、特征发现、通知订阅四个阶段含义完全不同，
     * 以前一律报"设备上没有 FFE0 服务"——真正的失败点（连不上/没有可写特征/订阅被拒）
     * 全被这句误导性的提示盖住，用户完全没法据此排查。
     */
    private fun describeConnectError(e: Throwable): String {
        val msg = e.message.orEmpty()
        val name = jsErrorName(e)
        val stage = connectStage
        // 协议层自己抛的中文提示直接透出（它们已经是人话）
        if (msg.startsWith("通道无应答") || msg.startsWith("设备没有可用") ||
            msg.startsWith("连接超时") || msg.startsWith("电量计通道")
        ) return msg
        return when {
            name == "SecurityError" ->
                if (stage == Stage.Subscribe) "通知订阅被拒绝（需要重新点「选择设备」授权）"
                else "权限被拒绝，请重新点「选择设备」"
            name == "NetworkError" ->
                if (stage == Stage.Connect) "建立连接失败：设备可能正被其它手机/小程序占用，或距离过远"
                else "通信中断（${stage.label}阶段）：设备可能正被其它主机占用"
            name == "NotSupportedError" ->
                "设备不支持所需的安全配对，请重新在系统蓝牙里配对"
            name == "NotFoundError" -> when (stage) {
                Stage.Connect -> "GATT 连接失败：找不到该设备（请确认设备已开机并在附近）"
                Stage.Service -> "设备上没有 FFE0 服务（不是已知的保护板/电量计？）"
                Stage.Characteristics -> "FFE0 下没有可用的通信特征（固件不兼容？）"
                Stage.Subscribe -> "通知订阅失败（设备拒绝了订阅，可能被其它主机连着）"
                Stage.Probe -> "通道无应答（设备可能被其它主机占用或信号过弱）"
            }
            msg.contains("timeout", ignoreCase = true) || msg.contains("超时") ->
                "连接超时（${stage.label}阶段），正在重试…"
            stage == Stage.Connect && msg.isNotBlank() -> "建立连接失败：$msg"
            msg.isNotBlank() -> "${stage.label}失败：$msg"
            else -> "${stage.label}失败，正在重试…"
        }
    }

    /** 当前连接进行到哪一步——错误提示据此归因 */
    private enum class Stage(val label: String) {
        Connect("建立连接"),
        Service("发现服务"),
        Characteristics("发现特征"),
        Subscribe("订阅通知"),
        Probe("通道探测"),
    }

    private var connectStage = Stage.Connect
}

/** 供界面判断"当前环境能不能用 Web Bluetooth" */
fun webBluetoothAvailable(): Boolean = webBluetoothSupported()