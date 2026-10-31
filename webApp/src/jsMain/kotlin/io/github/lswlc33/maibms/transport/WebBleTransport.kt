package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.LinkStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** 每轮连接持有自己的设备、信号、监听器；旧 finally 不能清理新会话。 */
    private class LinkAttempt(
        val generation: Long,
        val device: WebBluetoothDevice,
        val family: DeviceFamily,
    ) {
        val dropped = CompletableDeferred<Unit>()
        var server: WebBluetoothGattServer? = null
        var write: WebBluetoothGattCharacteristic? = null
        var notify: WebBluetoothGattCharacteristic? = null
        var notifyListener: ((dynamic) -> Unit)? = null
        var disconnectListener: ((dynamic) -> Unit)? = null
        var probe: CompletableDeferred<Boolean>? = null
        var stage = Stage.Connect
        var cleaned = false
    }

    private var activeAttempt: LinkAttempt? = null
    private val server: WebBluetoothGattServer? get() = activeAttempt?.server
    private val writeChar: WebBluetoothGattCharacteristic? get() = activeAttempt?.write
    private var suspended = false

    /** 同 device.id 或同实际 server 共用队列；不同设备不被悬空 Promise 阻塞。 */
    private class GattQueue(gatt: WebBluetoothGattServer, deviceId: String) {
        val servers = mutableListOf(gatt)
        val deviceIds = mutableSetOf(deviceId)
        val mutex = Mutex()
        var pending: PendingConnection? = null
    }

    private val gattQueues = mutableListOf<GattQueue>()
    private var generation = 0L

    /** 超时/取消不等于结算。原生 Promise continuation 独立于业务协程与页面作用域。 */
    private class PendingConnection(
        val generation: Long,
        val device: WebBluetoothDevice,
        val gatt: WebBluetoothGattServer,
        val queue: GattQueue,
        val promise: Promise<WebBluetoothGattServer>,
    ) {
        val result = CompletableDeferred<WebBluetoothGattServer>()
        val drained = CompletableDeferred<Unit>()
        var abandoned = false
        var claimed = false
        var server: WebBluetoothGattServer? = null
    }

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
        val myGen = generation
        val family = targetFamily
        loopJob?.cancel()
        activeAttempt?.let { cleanupLink(it) }
        abandonPending()
        _currentFamily.value = family
        _linkState.value = if (suspended) LinkState.Disconnected else LinkState.Connecting
        _connectHint.value = "正在连接 ${dev.name?.trim() ?: "设备"}…"
        logI("连接目标 ${dev.name} ${dev.id}（家族 ${family.label}）")
        if (!suspended) loopJob = scope.launch { connectionLoop(dev, myGen, family) }
    }

    /**
     * 常驻重连循环（与 Android 同一套退避语义）：
     * 连上 → 发现服务 → 逐条探测通道 → 就绪 → 等掉线 → 退避重来。
     */
    private fun wantsConnection(myGen: Long, dev: WebBluetoothDevice): Boolean =
        myGen == generation && !manualStop && !suspended && desiredId == dev.id

    private fun checkAttempt(a: LinkAttempt) {
        if (!wantsConnection(a.generation, a.device) || activeAttempt !== a || a.cleaned) {
            throw ConnectSuperseded()
        }
        if (a.dropped.isCompleted || a.server?.connected != true) {
            throw IllegalStateException("通信中断（${a.stage.label}阶段）")
        }
    }

    /** 结算后先收掉旧 result，才开放下一次 connect；绝不给 then 留下拆新链路的机会。 */
    private fun drainPending(p: PendingConnection) {
        if (!p.result.isCompleted || p.drained.isCompleted) return
        if (!p.claimed) runCatching { p.server?.disconnect() }
        if (p.queue.pending === p) p.queue.pending = null
        p.drained.complete(Unit)
    }

    private fun abandonPending(p: PendingConnection) {
        if (!p.abandoned) {
            p.abandoned = true
            // 尝试平台 abort，但 disconnect 不保证 Promise 结算，绝不据此开放同一队列。
            if (!p.result.isCompleted) runCatching { p.gatt.disconnect() }
        }
        drainPending(p)
    }

    private fun abandonPending() {
        gattQueues.mapNotNull { it.pending }.forEach { abandonPending(it) }
    }

    private suspend fun connectGatt(a: LinkAttempt): WebBluetoothGattServer {
        val gatt = a.device.gatt ?: throw IllegalStateException("设备没有 GATT 服务")
        val queue = gattQueues.firstOrNull {
            a.device.id in it.deviceIds || it.servers.any { known -> known === gatt }
        } ?: GattQueue(gatt, a.device.id).also { gattQueues.add(it) }
        queue.deviceIds.add(a.device.id)
        if (queue.servers.none { it === gatt }) queue.servers.add(gatt)
        return queue.mutex.withLock {
            // 只等本设备/实际 server 的操作，A 永不结算不能挡住独立设备 B。
            queue.pending?.drained?.await()
            if (!wantsConnection(a.generation, a.device)) throw ConnectSuperseded()
            val p = PendingConnection(a.generation, a.device, gatt, queue, gatt.connect())
            queue.pending = p
            p.promise.then<Unit>(onFulfilled = { got ->
                p.server = got
                p.result.complete(got)
                if (p.abandoned || !wantsConnection(p.generation, p.device)) abandonPending(p)
                Unit
            }, onRejected = { error ->
                p.result.completeExceptionally(error)
                drainPending(p)
                Unit
            })
            try {
                val got = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { p.result.await() }
                    ?: throw IllegalStateException("连接超时")
                if (!wantsConnection(a.generation, a.device)) throw ConnectSuperseded()
                a.server = got
                p.claimed = true
                drainPending(p)
                got
            } finally {
                if (!p.claimed) abandonPending(p)
            }
        }
    }

    private suspend fun connectionLoop(dev: WebBluetoothDevice, myGen: Long, family: DeviceFamily) {
        var attempt = 0
        while (wantsConnection(myGen, dev) && currentCoroutineContext().isActive) {
            attempt++
            _linkState.value = LinkState.Connecting
            if (attempt > 1) _connectHint.value = "第 $attempt 次尝试…"
            val connectedAt = io.github.lswlc33.maibms.data.epochMillisNow()
            var livedMs = 0L
            // 掉线信号在建链前创建，回调只捕获这轮，不读取全局可变信号。
            val a = LinkAttempt(myGen, dev, family)
            activeAttempt = a
            val onDisconnected: (dynamic) -> Unit = {
                // 同一个 GATT server 会被下一轮复用；旧 disconnect 事件迟到时若新链路
                // 已物理连接，不能把它当成新会话掉线。建链前事件由接管后的 checkAttempt 兜底。
                if (activeAttempt === a && a.server?.connected == false &&
                    wantsConnection(myGen, dev) && !a.cleaned
                ) {
                    logW("GATT 掉线事件（gattserverdisconnected）")
                    a.dropped.complete(Unit)
                    a.probe?.complete(false)
                    _linkState.value = LinkState.Disconnected
                }
            }
            a.disconnectListener = onDisconnected
            dev.addEventListener("gattserverdisconnected", onDisconnected)
            try {
                val srv = connectGatt(a)
                checkAttempt(a)
                _currentFamily.value = family

                a.stage = Stage.Service
                val service = srv.getPrimaryService(SERVICE).await<WebBluetoothGattService>()
                checkAttempt(a)

                a.stage = Stage.Characteristics
                val candidates = buildCandidates(service, a)
                checkAttempt(a)
                _availableChannels.value = candidates.map { it.channel }.ifEmpty { listOf(BleChannel.Default) }
                if (candidates.isEmpty()) {
                    logE("FFE0 下没有可用的写/通知特征")
                    throw IllegalStateException("设备没有可用的通信通道")
                }
                logI("可用通道：${candidates.joinToString { it.channel.id }}")
                val ordered = listOfNotNull(candidates.firstOrNull { it.channel == preferred }) +
                    candidates.filter { it.channel != preferred }

                a.stage = Stage.Subscribe
                val picked = probeChannels(ordered, a)
                    ?: throw IllegalStateException("通道无应答（设备可能被其它主机占用或信号过弱）")
                checkAttempt(a)
                a.write = picked.write
                a.notify = picked.notify
                _activeChannel.value = picked.channel
                _connectHint.value = null
                // 最后的提交也校验物理状态；电量计跳过 ANT probe，但不跳过这些校验。
                checkAttempt(a)
                _linkState.value = LinkState.Connected
                logI("链路就绪（通道 ${picked.channel.id}）")
                a.dropped.await()
                livedMs = io.github.lswlc33.maibms.data.epochMillisNow() - connectedAt
                logW("链路断开（存活 ${livedMs}ms）")
            } catch (e: ConnectSuperseded) {
                logI("本次连接已被新的连接/断开取代，丢弃结果")
                livedMs = -1L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!wantsConnection(myGen, dev)) break
                logW("本轮连接失败：${e.message}")
                _connectHint.value = describeConnectError(e, a.stage)
                livedMs = 0L
            } finally {
                cleanupLink(a)
            }

            if (livedMs < 0 || !wantsConnection(myGen, dev) || !currentCoroutineContext().isActive) break
            _linkState.value = LinkState.Disconnected
            if (livedMs in 1..5_000) {
                _connectHint.value = "连接建立后很快断开，正在重试…"
                attempt = attempt.coerceAtLeast(1)
            } else if (livedMs > 5_000) {
                attempt = 0
                _connectHint.value = "连接已断开，正在重连…"
            }
            delay(backoffMs(attempt))
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
    private suspend fun buildCandidates(service: WebBluetoothGattService, a: LinkAttempt): List<Candidate> {
        val all = service.getCharacteristics().await<Array<WebBluetoothGattCharacteristic>>()
        checkAttempt(a)
        fun find(short: String): WebBluetoothGattCharacteristic? =
            all.firstOrNull { it.uuid.equals(uuidOf(short), ignoreCase = true) }

        fun writable(c: WebBluetoothGattCharacteristic?) = c != null && propBool(c, "write", "writeWithoutResponse")
        fun notifiable(c: WebBluetoothGattCharacteristic?) = c != null && propBool(c, "notify", "indicate")

        logI("特征列表：" + all.joinToString { "${it.uuid.substring(4, 8).uppercase()}(${propText(it)})" })

        if (a.family.isMeter) {
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
    private suspend fun probeChannels(ordered: List<Candidate>, a: LinkAttempt): Candidate? {
        val isMeter = a.family.isMeter
        for ((index, c) in ordered.withIndex()) {
            checkAttempt(a)
            _connectHint.value = "通道探测：${c.channel.label}…"
            logI("通道探测 ${index + 1}/${ordered.size}：${c.channel.id}")
            val signal = CompletableDeferred<Boolean>()
            a.probe = signal
            val listener: (dynamic) -> Unit = { event ->
                if (activeAttempt === a && wantsConnection(a.generation, a.device) &&
                    !a.cleaned && !a.dropped.isCompleted && a.server?.connected == true
                ) {
                    val bytes = notificationBytes(event) ?: ByteArray(0)
                    if (bytes.isNotEmpty()) {
                        if (!_incoming.tryEmit(bytes)) LinkStats.noteDroppedFrame()
                        if (!signal.isCompleted && looksLikeProbeResponse(bytes)) signal.complete(true)
                    }
                }
            }
            a.notify = c.notify
            a.notifyListener = listener
            var picked = false
            try {
                c.notify.addEventListener("characteristicvaluechanged", listener)
                try {
                    c.notify.startNotifications().await<WebBluetoothGattCharacteristic>()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    a.stage = Stage.Subscribe
                    logE("通知订阅失败（${c.channel.id}）：${e.message}")
                    throw e
                }
                checkAttempt(a)
                delay(PROBE_SETTLE_MS)
                checkAttempt(a)
                if (isMeter) {
                    logI("电量计通道就绪：${c.channel.id}（家族 ${a.family.label}，跳过保护板探测）")
                    picked = true
                    return c
                }
                a.stage = Stage.Probe
                writeChunks(c.write, io.github.lswlc33.maibms.protocol.Frame.readRealtime(), a)
                checkAttempt(a)
                val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) { signal.await() } ?: false
                checkAttempt(a)
                if (ok) {
                    picked = true
                    return c
                }
                logW("通道 ${c.channel.id} 无应答（${PROBE_TIMEOUT_MS}ms 超时），换下一个候选")
            } catch (e: ConnectSuperseded) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                checkAttempt(a)
                if (a.stage == Stage.Subscribe) throw e
                logW("通道 ${c.channel.id} 探测异常：${e.message}")
            } finally {
                if (a.probe === signal) a.probe = null
                if (!picked && !a.cleaned) {
                    runCatching { c.notify.removeEventListener("characteristicvaluechanged", listener) }
                    runCatching { c.notify.stopNotifications() }
                    if (a.notifyListener === listener) {
                        a.notifyListener = null
                        a.notify = null
                    }
                }
            }
        }
        return null
    }

    /** 探测应答判定：7E 开头、功能码 0x11/0x12（与 Android 同一判据） */
    private fun looksLikeProbeResponse(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0x7E.toByte() &&
            (b[2] == 0x11.toByte() || b[2] == 0x12.toByte())

    private fun cleanupLink(a: LinkAttempt) {
        if (a.cleaned) return
        a.cleaned = true
        a.dropped.complete(Unit)
        a.probe?.complete(false)
        a.probe = null
        val n = a.notify
        val l = a.notifyListener
        if (n != null && l != null) {
            runCatching { n.removeEventListener("characteristicvaluechanged", l) }
            runCatching { n.stopNotifications() }
        }
        a.notifyListener = null
        a.notify = null
        a.write = null
        a.disconnectListener?.let { dl ->
            runCatching { a.device.removeEventListener("gattserverdisconnected", dl) }
        }
        a.disconnectListener = null
        val srv = a.server
        a.server = null
        if (activeAttempt === a) {
            activeAttempt = null
            _activeChannel.value = null
        }
        runCatching { srv?.disconnect() }
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
        val a = activeAttempt ?: return
        checkAttempt(a)
        writeChunks(wc, frame, a)
    }

    /** 分片写：串行 + 每片 await（探测帧与业务帧共用），过期会话不再发送。 */
    private suspend fun writeChunks(wc: WebBluetoothGattCharacteristic, frame: ByteArray, a: LinkAttempt) = writeMutex.withLock {
        checkAttempt(a)
        val useResponse = propBool(wc, "write") && !propBool(wc, "writeWithoutResponse")
        var offset = 0
        while (offset < frame.size) {
            checkAttempt(a)
            val len = minOf(MAX_CHUNK, frame.size - offset)
            val piece = frame.copyOfRange(offset, offset + len)
            val payload = toUint8Array(piece)
            try {
                if (useResponse) {
                    wc.writeValueWithResponse(payload).await<Unit>()
                } else {
                    wc.writeValueWithoutResponse(payload).await<Unit>()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                checkAttempt(a)
                // 个别固件只支持带响应写（或反之）：失败一次就换另一种写法再试
                logW("写入失败（${offset}/${frame.size}，${if (useResponse) "带响应" else "无响应"}）：${e.message}")
                try {
                    if (useResponse) wc.writeValueWithoutResponse(payload).await<Unit>()
                    else wc.writeValueWithResponse(payload).await<Unit>()
                } catch (e2: kotlinx.coroutines.CancellationException) {
                    throw e2
                } catch (e2: Throwable) {
                    checkAttempt(a)
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

    /** 立即失效逻辑会话；未结算 connect 的收尾由原生 continuation 负责。 */
    private fun invalidateLink() {
        generation++
        loopJob?.cancel()
        loopJob = null
        activeAttempt?.let { cleanupLink(it) }
        abandonPending()
        _linkState.value = LinkState.Disconnected
        _connectHint.value = null
    }

    override suspend fun disconnect() {
        manualStop = true
        desiredId = null
        invalidateLink()
        logI("主动断开连接")
    }

    /** BFCache 只暂停链路，保留授权和连接意图，不取消可恢复的 scope。 */
    fun suspendForPageCache() {
        if (suspended) return
        suspended = true
        invalidateLink()
    }

    fun resumeFromPageCache() {
        if (!suspended) return
        suspended = false
        val dev = authorized ?: return
        if (manualStop || desiredId != dev.id) return
        val myGen = generation
        val family = targetFamily
        _linkState.value = LinkState.Connecting
        _connectHint.value = "页面已恢复，正在重新连接…"
        loopJob = scope.launch { connectionLoop(dev, myGen, family) }
    }

    /** 仅真正卸载页面才永久释放；迟到 connect 仍由独立 continuation 收尾。 */
    fun shutdown() {
        manualStop = true
        desiredId = null
        invalidateLink()
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
    private fun describeConnectError(e: Throwable, stage: Stage): String {
        val msg = e.message.orEmpty()
        val name = jsErrorName(e)
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
}

/** 供界面判断"当前环境能不能用 Web Bluetooth" */
fun webBluetoothAvailable(): Boolean = webBluetoothSupported()