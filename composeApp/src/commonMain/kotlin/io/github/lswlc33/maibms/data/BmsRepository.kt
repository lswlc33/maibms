package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.protocol.*
import io.github.lswlc33.maibms.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BMS 仓库：连接管理 + 900ms 轮询引擎 + 命令队列（发命令前暂停轮询，避免应答串扰）。
 * 数据流：BLE 字节流 → FrameParser → RealtimeDecoder → MockBms.status（UI 更新源）。
 * 桌面端/无 BLE 环境停留在「未连接」态，只有真实传输一条路径（演示内容已按需求移除）。
 */
class BmsRepository(
    private val scope: CoroutineScope,
    @Suppress("unused") private val engine: MockBmsEngine,   // 保留给协议单测/未来波形注入
) {
    /** 当前唯一传输：平台 BLE 实现（Android 注入） */
    var transport: BmsTransport
        private set

    private var realTransport: BmsTransport? = null

    fun setRealTransport(t: BmsTransport) {
        realTransport = t
        transport = t
        MockBms.usingRealBle.value = true
    }

    init {
        transport = realTransport ?: NoopTransport
    }

    val linkState: StateFlow<LinkState> get() = _linkState
    private val _linkState = MutableStateFlow(LinkState.Idle)

    /** 真机扫描结果（按信号强度降序）与扫描中标志 */
    private val _scanResults = MutableStateFlow<List<ScanDevice>>(emptyList())
    val scanResults: StateFlow<List<ScanDevice>> = _scanResults
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** 扫描失败原因（无适配器 / 缺权限等），成功后清空 */
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    /** 真机连接进度/失败原因（重连循环每轮更新），连接成功后清空 */
    private val _connectHint = MutableStateFlow<String?>(null)
    val connectHint: StateFlow<String?> = _connectHint

    /** 用户主动断开（区别于掉线）：置位后不再自动重连，界面也不再显示「正在重连」 */
    val manualDisconnect = MutableStateFlow(false)

    /** 当前平台能否真机扫描（桌面端 / 演示模式为 false） */
    val canScan: Boolean get() = realTransport?.supportsScan == true

    private val parser = FrameParser()
    private val cmdMutex = Mutex()
    private var awaiting: CompletableDeferred<ParsedFrame>? = null
    private var expectFunc = -1

    /** 期望应答的寄存器值；null=不校验。真机教训：只比功能码会把上一条超时命令的迟到应答收下 */
    private var expectReg: Int? = null

    private var pollJob: Job? = null
    private var collectorJob: Job? = null
    private var stateJob: Job? = null

    fun start() {
        if (collectorJob != null) return
        BmsLog.i("APP", "应用启动" + (if (realTransport != null) "（Android BLE）" else "（无 BLE 后端）"))
        // 冷启动：恢复记忆设备并自动重连（设备地址 + 密码库都落在本地）
        val saved = AppStore.savedAddress
        MockBms.savedAddress = saved
        MockBms.connectedDeviceName.value = AppStore.savedDeviceName
        restorePasswords(saved)
        MockBms.autoUpgradeLevel.value = AppStore.autoUpgradeTarget
        MockBms.usingRealBle.value = realTransport != null
        if (saved != null) BmsLog.i("APP", "记忆设备：${AppStore.savedDeviceName ?: "未命名"} ($saved)")
        if (realTransport == null) {
            // 桌面端等没有 BLE 后端的环境：停在这里，界面显示「未连接」
            BmsLog.w("APP", "当前平台无 BLE 后端，等待扫描/连接动作")
            return
        }
        val real = realTransport!!
        stateJob = scope.launch {
            real.linkState.collect { st ->
                _linkState.value = st
                if (st == LinkState.Connected) {
                    autoUpgradePermission()
                } else {
                    // 掉线后自动重连回来时要重新升权：不清标志的话重连后权限停在 0 级
                    if (st == LinkState.Disconnected || st == LinkState.Idle) autoUpgraded = false
                    MockBms.markDisconnected()
                }
            }
        }
        scope.launch {
            real.incoming.collect { bytes ->
                for (f in parser.feed(bytes)) handleFrame(f)
            }
        }
        scope.launch {
            real.connectHint.collect { _connectHint.value = it }
        }
        // 用户上次是主动断开的话，就别自作主张再连上（但记忆设备仍然保留）
        if (saved != null && AppStore.autoReconnect) scope.launch { connect(saved) }
    }

    /** 从本地密码库恢复该设备的已记住密码等级（按设备地址分槽） */
    private fun restorePasswords(address: String?) {
        if (address == null) return
        val pw = mutableMapOf<Int, String>()
        for (lv in listOf(1, 2, 3, 4, 5, 9)) AppStore.loadPassword(address, lv)?.let { pw[lv] = it }
        if (pw.isNotEmpty()) MockBms.plainPasswords.value = pw
        MockBms.passwords.value = MockBms.passwords.value.map {
            it.copy(masked = if (pw.containsKey(it.level)) "••••••••" else null)
        }
    }

    private var autoUpgraded = false

    /**
     * 连接后自动升权：优先目标等级，其次逐级向下，只用本地已记住的密码。
     * 这是「打开就能改参数」的关键——否则每次连接都要手点权限徽章重新输密码。
     * 真机注意：弱信号下 0x43 应答可能 >2s 才回来（实测 2 级那次就超时了），
     * 所以每条给 4s，并让返回值（设备当前权限）而不是循环变量进日志。
     */
    private suspend fun autoUpgradePermission() {
        if (autoUpgraded) return
        autoUpgraded = true
        if (MockBms.plainPasswords.value.isEmpty()) return
        delay(600)   // 等链路/通知订阅稳定，真机实测首帧前立刻发 0x23 容易无应答
        val target = MockBms.autoUpgradeLevel.value
        val order = (listOf(target) + listOf(5, 4, 3, 2, 1)).distinct()
        val best = order.mapNotNull { lv ->
            val pw = MockBms.plainPasswords.value[lv] ?: return@mapNotNull null
            val ok = runCatching { auth(lv, pw) }.getOrDefault(0)
            if (ok > 0) ok else null
        }.maxOrNull()
        if (best != null) {
            MockBms.applyPermission(best)
            // 目标是具体等级但密码不对时，降到实际能达到的最高级，避免每次连接都白试一轮；
            // 目标为 0（自动）则保持不动，它本来就取最高可用
            if (MockBms.autoUpgradeLevel.value > 0 && best < MockBms.autoUpgradeLevel.value) {
                MockBms.autoUpgradeLevel.value = best
                AppStore.autoUpgradeTarget = best
            }
            BmsLog.i("AUTH", "自动升权成功：$best 级")
        } else {
            BmsLog.w("AUTH", "自动升权失败：本地密码均未通过")
        }
    }

    /** 幂等：换设备/重连前先清掉上一台的假数据与权限状态 */
    private fun resetSessionState() {
        autoUpgraded = false
        paramsAttempted = false
        MockBms.applyPermission(0)
        MockBms.clearForRealDevice()
    }

    /** 扫描附近的真实 BMS */
    suspend fun startScan() {
        val real = realTransport
        if (real == null || !real.supportsScan) {
            BmsLog.w("SCAN", "当前平台不支持真实扫描")
            _scanError.value = "当前平台没有蓝牙后端"
            return
        }
        _scanResults.value = emptyList()
        _scanError.value = null
        _scanning.value = true
        // 扫描窗口：到点自动停。否则板子不在时界面会永远停在「正在搜索…」，
        // 那句「未发现 ANT 设备」的提示永远出不来（用户开板前必然遇到）
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_WINDOW_MS)
            if (_scanning.value) {
                stopScan()
                BmsLog.i("SCAN", "扫描窗口到，自动停止，发现 ${_scanResults.value.size} 台")
            }
        }
        val ok = runCatching {
            real.scan { d ->
                _scanResults.value =
                    (_scanResults.value.filterNot { it.address == d.address } + d).sortedByDescending { it.rssi }
            }
        }
        if (ok.isFailure) {
            val e = ok.exceptionOrNull()
            BmsLog.e("SCAN", "扫描失败：$e")
            _scanError.value = when (e) {
                is SecurityException -> "缺少蓝牙权限，请在系统设置中允许本应用使用附近的设备"
                is IllegalStateException -> e.message
                else -> e?.message ?: "扫描失败"
            }
            _scanning.value = false
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel(); scanTimeoutJob = null
        _scanning.value = false
        realTransport?.stopScan()
    }

    /** 连接指定设备（按下即记住地址，供下次自动重连） */
    suspend fun connectTo(address: String) {
        val name = _scanResults.value.firstOrNull { it.address == address }?.name?.trim()
        BmsLog.i("CONN", "选择设备 $name ($address)")
        MockBms.savedAddress = address
        MockBms.connectedDeviceName.value = name
        AppStore.savedAddress = address
        AppStore.savedDeviceName = name
        restorePasswords(address)
        stopScan()
        connect(address)
    }

    suspend fun connect(address: String? = null) {
        BmsLog.i("CONN", "发起连接" + (address?.let { " → $it" } ?: "") + if (AppStore.autoReconnect) "（自动重连）" else "（手动）")
        manualDisconnect.value = false
        AppStore.autoReconnect = true
        resetSessionState()
        lastFrameAt = 0L
        transport.connect(address)
        if (pollJob?.isActive != true) {
        pollJob = scope.launch {
            // 无条件常驻：连接态在每拍内部判断（避免链路态镜像延迟导致首拍误判退出）
            // 节奏 900ms：真机弱信号下 500ms 一轮会持续占满连接间隔，反而拖低成功率
            while (isActive) {
                if (_linkState.value == LinkState.Connected) {
                    try {
                        requestAndAwait(Frame.readRealtime(), Proto.RSP_REALTIME, expectedFunc = Proto.RSP_REALTIME, expectedReg = 0, timeoutMs = 700, fromPoll = true)
                    } catch (e: Exception) {
                        // 每拍都有超时是常态，只有连续异常才有意义，所以只记 DEBUG
                        BmsLog.d("POLL", "实时轮询超时：${e.message ?: e::class.simpleName}")
                    }
                    // 失联检测：GATT 还在但连续多拍收不到实时帧（设备休眠/走远/干扰）
                    val since = System.currentTimeMillis() - lastFrameAt
                    if (MockBms.connected.value && lastFrameAt > 0 && since > STALL_MS) {
                        if (!_stalled.value) {
                            _stalled.value = true
                            BmsLog.w("LINK", "实时帧停流 ${since}ms，判定失联（链路保持，继续轮询）")
                        }
                    } else if (_stalled.value) {
                        _stalled.value = false
                        BmsLog.i("LINK", "实时帧恢复")
                    }
                }
                delay(900)
            }
        }
        }
    }

    suspend fun disconnect() {
        BmsLog.i("CONN", "主动断开连接")
        manualDisconnect.value = true
        AppStore.autoReconnect = false   // 记住这次主动断开，重启后不要自动连
        pollJob?.cancel(); pollJob = null
        transport.disconnect()
        _connectHint.value = null
        _stalled.value = false
        MockBms.connected.value = false
    }

    /** 失联判定阈值：链路在但 X 毫秒没有实时帧（≈连续 7 拍超时） */
    private var lastFrameAt = 0L
    private val _stalled = MutableStateFlow(false)
    val stalled: StateFlow<Boolean> = _stalled
    private var scanTimeoutJob: Job? = null

    /** 静默重升相关的节流与任务（见 ensurePermission） */
    private var reAuthJob: Job? = null
    private var lastAuthAt = 0L

    companion object {
        const val STALL_MS = 7_000L
        /** BLE 扫描窗口：够扫到弱信号设备，又不至于让用户干等 */
        const val SCAN_WINDOW_MS = 20_000L
        /** 两条校验命令的最小间隔，防链路抖动时反复发 0x23 */
        const val RE_AUTH_COOLDOWN_MS = 60_000L
    }

    private suspend fun handleFrame(f: ParsedFrame) {
        BmsLog.d("RX", "func=%02X reg=%d len=%d %s".format(f.func, f.reg, f.data.size, BmsLog.hex(f.data.take(24).toByteArray())))
        when (f.func) {
            Proto.RSP_REALTIME -> {
                runCatching { RealtimeDecoder.decode(f.data) }.onSuccess { r ->
                    lastFrameAt = System.currentTimeMillis()
                    MockBms.updateFromRealtime(r)
                    MockBms.connected.value = true
                    // 首帧到达即拉一次参数区/身份区（等级 0 也可读，失败只记一次）
                    refreshParams()
                    // 设备闲置会把权限退回低等级（实测约 5 分钟）：掉下来就静默重升
                    ensurePermission(r.permission)
                }.onFailure { BmsLog.e("RX", "实时帧解码失败：$it") }
            }
            Proto.RSP_AUTH -> {
                if (f.data.size >= 2) {
                    val level = (f.data[0].toInt() and 0xFF) or ((f.data[1].toInt() and 0xFF) shl 8)
                    BmsLog.i("AUTH", "设备应答当前权限 $level 级")
                    MockBms.applyPermission(level)
                    // 升权后参数区可读范围可能变化，强制重读
                    if (level > 0) refreshParams(force = true)
                }
            }
            Proto.RSP_WRITE -> {
                // 0x42 的数据区是参数块回显（与 0x12 同布局），**不是结果码**：
                // 见 docs/06 §6.4，写进本地缓存即可，结果看同帧追加的 0xFF 段
                var k = 0
                val patch = HashMap<Int, Int>()
                while (k + 1 < f.data.size) {
                    patch[f.reg + k] = (f.data[k].toInt() and 0xFF) or ((f.data[k + 1].toInt() and 0xFF) shl 8)
                    k += 2
                }
                if (patch.isNotEmpty()) {
                    BmsLog.d("WRITE", "回显 ${patch.size} 项 @0x${"%X".format(f.reg)}")
                    MockBms.liveParams.value = MockBms.liveParams.value + patch
                }
            }
            Proto.RSP_CONTROL -> {
                if (f.data.isNotEmpty()) {
                    val code = f.data[0].toInt() and 0xFF
                    MockBms.lastControlResult.value = f.reg to code
                    val cmdName = ControlCmd.name(f.reg)
                    if (code == 1) {
                        BmsLog.i("CTRL", "控制命令成功：$cmdName")
                        applyControlToUi(f.reg)
                    } else {
                        BmsLog.e("CTRL", "控制命令被拒：$cmdName（结果码 $code）")
                    }
                }
            }
            Proto.FC_WRITE_STATUS -> {
                // 0xFF 附加段：结果码在寄存器低字节，失败时带参数地址与限值。
                // 码 11 = 读取成功：每条 0x12 读应答后面都跟一条，不能当作「写参数结果」上屏。
                // 参数读回期间（权限不足时码=1）也一律不入横幅，否则配置页会挂着假的「写参数结果」
                val code = f.reg and 0xFF
                if (code != 11 && !MockBms.paramsReading.value) {
                MockBms.lastWriteResult.value = code
                // 只有「小于最小值 / 大于最大值」才附带限值（docs/附录B B.3）
                MockBms.lastWriteDetail.value = if (f.data.size >= 4 && (code == 2 || code == 3)) {
                    val addr = (f.data[0].toInt() and 0xFF) or ((f.data[1].toInt() and 0xFF) shl 8)
                    val limit = (f.data[2].toInt() and 0xFF) or ((f.data[3].toInt() and 0xFF) shl 8)
                    val def = ParamTable.byAddr(addr)
                    if (def != null) {
                        "%s %s=%.3f %s".format(
                            def.name, if (code == 2) "最小" else "最大", limit / def.scale, def.unit
                        )
                    } else "参数 0x%X 限值 %d".format(addr, limit)
                } else null
                val ok = ResultCodes.writeOk(code)
                val detail = MockBms.lastWriteDetail.value
                if (ok) BmsLog.i("WRITE", "参数写入成功（0x${"%X".format(f.reg)}）")
                else BmsLog.e("WRITE", "参数写入失败：${ResultCodes.writeResult(code)}（0x${"%X".format(f.reg)}）" + (detail?.let { " · $it" } ?: ""))
                }
            }
        }
        // 唤醒等待者（一次性）：功能码与寄存器都要对上
        val a = awaiting
        if (a != null && f.func == expectFunc && (expectReg == null || f.reg == expectReg)) {
            awaiting = null; expectFunc = -1; expectReg = null
            a.complete(f)
        }
    }

    /** 发一条命令并等待指定功能码的应答；期间轮询由 cmdMutex + awaiting 机制天然互斥 */
    suspend fun requestAndAwait(
        frame: ByteArray,
        respondFunc: Int,
        expectedFunc: Int = respondFunc,
        expectedReg: Int? = null,
        timeoutMs: Long = 2000,
        fromPoll: Boolean = false,
    ): ParsedFrame? {
        return cmdMutex.withLock {
            val d = CompletableDeferred<ParsedFrame>()
            awaiting = d
            expectFunc = expectedFunc
            expectReg = expectedReg
            BmsLog.d("TX", BmsLog.hex(frame))
            transport.write(frame)
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            awaiting = null; expectFunc = -1; expectReg = null
            if (r == null && !fromPoll) {
                BmsLog.w("TX", "命令无应答 func=%02X reg=%d（${timeoutMs}ms 超时）".format(respondFunc, expectedReg ?: -1))
            }
            r
        }
    }

    /**
     * 密码校验（成功返回该等级，失败返回 0）。
     *
     * 真机实测的关键点：设备返回的是**校验后的当前权限**，密码不对时不回 0，
     * 而是把「本次连接已获得的权限」原样返回（3 级槽送错密码 → 回 2）。
     * 所以判据必须是 `返回权限 >= 所校验的等级`，只看 `>0` 会把失败当成功。
     * 成功才记住密码；失败则删掉该级已存密码（已知是错的，别每次重试）。
     */
    suspend fun auth(level: Int, password: String): Int {
        val addr = ParamTable.slotAddr(level)
        BmsLog.i("AUTH", "校验 $level 级密码（寄存器 $addr）")
        lastAuthAt = System.currentTimeMillis()
        // 弱信号下 0x43 可能 2s 后才回（真机实测），给足 4s
        val r = requestAndAwait(Frame.auth(addr, password), Proto.RSP_AUTH, expectedReg = addr, timeoutMs = 4000)
        if (r == null) {
            BmsLog.w("AUTH", "$level 级校验无应答")
            return 0
        }
        if (r.data.size < 2) return 0
        val got = (r.data[0].toInt() and 0xFF) or ((r.data[1].toInt() and 0xFF) shl 8)
        val dev = MockBms.savedAddress
        return if (got >= level && got > 0) {
            BmsLog.i("AUTH", "$level 级密码校验通过（设备权限 $got 级），已记住")
            if (dev != null) AppStore.savePassword(dev, level, password)
            MockBms.plainPasswords.value = MockBms.plainPasswords.value + (level to password)
            MockBms.markPasswordSaved(level, true)
            level
        } else {
            BmsLog.w("AUTH", "$level 级密码校验失败（设备权限 $got 级），已从密码库移除")
            if (dev != null) AppStore.removePassword(dev, level)
            MockBms.plainPasswords.value = MockBms.plainPasswords.value - level
            MockBms.markPasswordSaved(level, false)
            0
        }
    }

    /** 只存本地不校验（未连接时也能先把密码记上，连接后自动升权会用到） */
    fun savePassword(level: Int, password: String) {
        BmsLog.i("AUTH", "离线保存 $level 级密码（未向设备校验）")
        MockBms.savedAddress?.let { AppStore.savePassword(it, level, password) }
        MockBms.plainPasswords.value = MockBms.plainPasswords.value + (level to password)
        MockBms.markPasswordSaved(level, true)
    }

    /** 清除某一级已记住的密码 */
    fun forgetPassword(level: Int) {
        BmsLog.i("AUTH", "清除 $level 级已记住的密码")
        MockBms.savedAddress?.let { AppStore.removePassword(it, level) }
        MockBms.plainPasswords.value = MockBms.plainPasswords.value - level
        MockBms.markPasswordSaved(level, false)
    }

    /** 设置连接后的目标等级（0 = 自动取已记住的最高可用级），落盘 */
    fun setAutoUpgradeTarget(level: Int) {
        MockBms.autoUpgradeLevel.value = level
        AppStore.autoUpgradeTarget = level
    }

    /** 目标等级：设为具体等级就用它，设为 0（自动）取已记住的最高级 */
    private fun desiredLevel(): Int =
        MockBms.autoUpgradeLevel.value.takeIf { it > 0 }
            ?: MockBms.plainPasswords.value.keys.maxOrNull() ?: 0

    /**
     * 权限回落自动重升：设备闲置一段时间（实测约 5 分钟）会把权限退回低等级，
     * 实时帧里的当前权限掉到目标以下时，用已记住的密码静默重升，用户无感。
     * 密码错的路径不循环——auth() 失败会把它从密码库删掉，下一拍就不会再试。
     */
    private fun ensurePermission(current: Int) {
        val target = desiredLevel()
        if (target <= 0 || current >= target) return
        val pw = MockBms.plainPasswords.value[target] ?: return
        if (reAuthJob?.isActive == true) return
        if (System.currentTimeMillis() - lastAuthAt < RE_AUTH_COOLDOWN_MS) return
        reAuthJob = scope.launch {
            delay(300)   // 让这一拍的轮询应答先落地，别抢应答队列
            val lv = runCatching { auth(target, pw) }.getOrDefault(0)
            if (lv > 0) {
                MockBms.applyPermission(lv)
                BmsLog.i("AUTH", "权限回落，已静默重升到 $lv 级")
                BmsLog.i("AUTH", "权限回落，已静默重升到 $lv 级")
            } else {
                BmsLog.w("AUTH", "权限回落重升失败（$target 级密码未通过，已从密码库移除）")
                BmsLog.w("AUTH", "权限回落重升失败（$target 级密码未通过）")
            }
        }
    }

    // ---------------- 参数区 / 身份区读取（docs/06 §6.3、附录A A.9） ----------------

    /** 常用读取块（起始地址 to 字节数），与旧版 App 各参数页一致；330~372 是密码区不碰，374 起恢复读 */
    private val paramBlocks = listOf(0 to 52, 56 to 44, 104 to 32, 140 to 12, 152 to 142, 298 to 32, 374 to 66, 592 to 28, 700 to 8)

    /** 身份区字符串块：键 → (地址, 字节数) */
    private val identityBlocks = listOf(
        "boot" to (528 to 16), "codeKey" to (576 to 16),
        "hwVersion" to (620 to 16), "swVersion" to (636 to 16),
        "packId" to (262 to 32),
    )

    private var readJob: Job? = null
    /** 本次连接是否已尝试读参数（失败不重复打，权限升级后再强制重读） */
    private var paramsAttempted = false

    /** 读全部参数块 + 身份区（连接成功/权限升级后调用）。串行发，避免与轮询抢应答 */
    fun refreshParams(force: Boolean = false) {
        if (realTransport == null) return
        if (readJob?.isActive == true) return   // 上一轮还在读：权限升级会 force 重入，但串行读没必要叠
        if (!force && paramsAttempted) return
        paramsAttempted = true
        // 读参数也会追加 0xFF 段（11=读取成功），别让它留成下一条「写参数结果」横幅
        MockBms.lastWriteResult.value = -1
        MockBms.lastWriteDetail.value = null
        readJob = scope.launch {
            MockBms.paramsReading.value = true
            try {
                val acc = HashMap<Int, Int>()
                for ((start, bytes) in paramBlocks) {
                    val r = requestAndAwait(Frame.readParam(start, bytes), Proto.RSP_PARAM, expectedReg = start, timeoutMs = 1500)
                    if (r != null && r.func == Proto.RSP_PARAM) {
                        var k = 0
                        while (k + 1 < r.data.size) {
                            acc[start + k] = (r.data[k].toInt() and 0xFF) or ((r.data[k + 1].toInt() and 0xFF) shl 8)
                            k += 2
                        }
                    }
                    delay(120)
                }
                MockBms.liveParams.value = acc
                val id = HashMap<String, String>()
                for ((key, spec) in identityBlocks) {
                    val (addr, len) = spec
                    val r = requestAndAwait(Frame.readParam(addr, len), Proto.RSP_PARAM, expectedReg = addr, timeoutMs = 1500)
                    if (r != null && r.func == Proto.RSP_PARAM && r.data.isNotEmpty()) {
                        id[key] = ascii(r.data)
                    }
                    delay(120)
                }
                MockBms.identity.value = id
                BmsLog.i("PARAM", "参数区读回 ${acc.size} 项，身份区 ${id.size} 项")
            } finally {
                MockBms.paramsReading.value = false
            }
        }
    }

    /** 身份区字符串：去掉 0 终止符与不可见字符 */
    private fun ascii(data: ByteArray): String {
        val bytes = data.takeWhile { it != 0.toByte() }.toByteArray()
        return buildString {
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) append(c.toChar())
            }
        }
    }

    /** 控制命令（返回结果码，1=成功） */
    suspend fun control(cmd: Int): Int {
        BmsLog.i("CTRL", "发送控制命令：${ControlCmd.name(cmd)}（0x%02X）".format(cmd))
        val r = requestAndAwait(Frame.control(cmd), Proto.RSP_CONTROL, expectedReg = cmd)
        if (r == null) {
            BmsLog.e("CTRL", "控制命令无应答：${ControlCmd.name(cmd)}")
            return 0
        }
        return if (r.data.isNotEmpty()) r.data[0].toInt() and 0xFF else 0
    }

    /** 保存应用参数（0x51/07）：把临时区固化到 flash，结果码进 lastControlResult 供界面回显 */
    suspend fun saveAllParams(): Int {
        BmsLog.i("PARAM", "保存应用参数（0x51/07）")
        val code = control(ControlCmd.SAVE_PARAMS)
        MockBms.lastControlResult.value = ControlCmd.SAVE_PARAMS to code
        return code
    }

    /**
     * 写参数 + 成功后自动保存。
     *
     * 结果来自 0x42 同帧追加的 **0xFF 段**（不是 0x42 数据区）：实测写被拒时
     * 仍会回显参数块，只有 0xFF 段里的码才是真结论（真机 1=权限不够）。
     * @return 0xFF 段结果码；-1 = 无应答
     */
    suspend fun writeParam(addr: Int, rawValue: Int): Int {
        val def = ParamTable.byAddr(addr)
        BmsLog.i("WRITE", "写参数 0x${"%X".format(addr)}" + (def?.let { "（${it.name}）" } ?: "") + " = $rawValue")
        MockBms.lastWriteResult.value = -1
        MockBms.lastWriteDetail.value = null
        // 容量类是 u32（低字 @addr、高字 @addr+2）：0x22 一次只写 2 字节，必须连写两帧，
        // 否则 113.0Ah 这种值会被截成低 16 位，设备拿到一个错得离谱的容量
        val writes: List<Pair<Int, ByteArray>> = if (addr in ParamTable.u32Addrs) {
            BmsLog.d("WRITE", "u32 参数，拆两帧写入")
            listOf(
                (addr to Frame.writeParam(addr, rawValue and 0xFFFF)),
                (addr + 2 to Frame.writeParam(addr + 2, (rawValue ushr 16) and 0xFFFF)),
            )
        } else listOf(addr to Frame.writeParam(addr, rawValue))
        var ok = true
        for ((a, fr) in writes) {
            val r = requestAndAwait(fr, Proto.RSP_WRITE, expectedReg = a, timeoutMs = 2500)
            if (r == null) { ok = false; break }   // 无应答：-2 让界面能区分「超时」与「设备拒绝」
            delay(200)   // 结果段与 0x42 同帧、紧随其后，给它一点时间落到 lastWriteResult
            if (!ResultCodes.writeOk(MockBms.lastWriteResult.value)) break
        }
        if (!ok) {
            MockBms.lastWriteResult.value = -2
            MockBms.lastWriteDetail.value = "设备未应答（链路差或参数不可写），本次未保存"
            BmsLog.e("WRITE", "写参数 0x${"%X".format(addr)} 失败：设备未应答")
            return -2
        }
        val code = MockBms.lastWriteResult.value
        if (ResultCodes.writeOk(code)) requestAndAwait(Frame.saveParams(), Proto.RSP_CONTROL, expectedReg = ControlCmd.SAVE_PARAMS)
        return code
    }

    private fun applyControlToUi(cmd: Int) {
        when (cmd) {
            ControlCmd.CHARGE_ON -> MockBms.chargeSwitch.value = true
            ControlCmd.CHARGE_OFF -> MockBms.chargeSwitch.value = false
            ControlCmd.DISCHARGE_ON -> MockBms.dischargeSwitch.value = true
            ControlCmd.DISCHARGE_OFF -> MockBms.dischargeSwitch.value = false
            ControlCmd.BALANCE_ON -> MockBms.balanceSwitch.value = true
            ControlCmd.BALANCE_OFF -> MockBms.balanceSwitch.value = false
        }
    }
}

/** 全局单例：App 启动即接好虚拟 BMS 数据管线 */
object Bms {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = MockBmsEngine(scope)
    val repository = BmsRepository(scope, engine)
}
