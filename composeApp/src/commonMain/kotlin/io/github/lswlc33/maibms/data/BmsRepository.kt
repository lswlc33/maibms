package io.github.lswlc33.maibms.data

import kotlin.concurrent.Volatile
import io.github.lswlc33.maibms.protocol.*
import io.github.lswlc33.maibms.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

    // ---- 快照预览模式：载入快照后置位，进程存活期间不解除（重启后自动连接按原状态恢复） ----

    /** 预览激活中：连接/轮询/帧回灌全部被挡（见 connect/start 的防护口） */
    val previewActive = MutableStateFlow(false)

    /** 预览中的快照标签（设备名 · 记录时刻），横幅展示用 */
    val previewLabel = MutableStateFlow<String?>(null)

    /** 本会话已记录的快照 id；0 = 本次连接还没记过（同会话重读参数时覆盖同一张） */
    private var sessionSnapshotId = 0L

    /** 载入快照进入预览：停连接、停轮询，之后任何帧都进不来（防护口见各挂点） */
    suspend fun enterPreview(s: BmsSnapshot) {
        BmsLog.i("SNAP", "进入快照预览：${s.deviceName}（${s.timeLabel}），自动连接停用直到重启")
        previewActive.value = true   // 先置位再动手，挡住断开过程中的一切回灌
        previewLabel.value = s.deviceName.ifBlank { null }?.let { "$it · ${s.timeLabel}" } ?: s.timeLabel
        pollJob?.cancel(); pollJob = null
        postConnectJob?.cancel(); postConnectJob = null
        firstFrameSignal = null
        transport.disconnect()
        manualDisconnect.value = true
        // 刻意不写 AppStore.autoReconnect=false：重启后要不要自动连回设备，按用户原来的开关状态来
        MockBms.restoreSnapshot(s)
    }

    /**
     * 退出快照预览：清掉预览态回到未连接空态。自动连接依旧停用（与进入预览时的承诺一致，
     * 重启才恢复）；恢复连接走界面右上角「＋」或历史设备，跟普通未连接一个路径。
     */
    suspend fun exitPreview() {
        if (!previewActive.value) return
        BmsLog.i("SNAP", "退出快照预览，回到未连接空态")
        previewActive.value = false   // 先撤防护再清数据，顺序与 enterPreview 相反
        previewLabel.value = null
        MockBms.clearForRealDevice()
    }

    /** 当前平台能否真机扫描（桌面端 / 演示模式为 false） */
    val canScan: Boolean get() = realTransport?.supportsScan == true

    private val parser = FrameParser()
    private val cmdMutex = Mutex()
    private var awaiting: CompletableDeferred<ParsedFrame>? = null
    private var expectFunc = -1

    /** 期望应答的寄存器值；null=不校验。真机教训：只比功能码会把上一条超时命令的迟到应答收下 */
    private var expectReg: Int? = null

    /**
     * 正在写参数：只有这个窗口内的 0xFF 附加段才算「写参数结果」。
     * 读参数区时每条 0x12 后面也跟一条 0xFF（码 11，权限不足时码 1），
     * 早先靠 `paramsReading` 反着排除，写参数撞上读回就会被吞掉结果（且静默无提示）。
     */
    private var writeInFlight = false

    private var pollJob: Job? = null
    /** 串行化「检查 pollJob 是否活跃 + 启动轮询」，防止并发 connect() 起出双轮询 */
    private val pollGuard = Mutex()
    private var collectorJob: Job? = null
    private var stateJob: Job? = null

    /** start() 已完成装配的标志：幂等守卫的主键（collectorJob 现在真实赋值，作为双保险）。
     *  取锁后「检查+置位」而非先查后置：MaibmsApp.onCreate 与 App() 的 LaunchedEffect
     *  理论上可并发调 start()，两个都必须串行通过守卫 */
    private val startGuard = PlatformLock()
    private var started = false

    /** 链路就绪时的轮询唤醒：首拍立刻发，不等 900ms 相位（CONFLATED：最多积一次，不会空转） */
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    /** 本次连接「首帧已到」信号：连后序列（升权 → 参数区读回）排在它后面 */
    private var firstFrameSignal: CompletableDeferred<Unit>? = null

    /** 连后序列协程：换连接 / 主动断开 / 进快照预览都要取消 */
    private var postConnectJob: Job? = null

    fun start() {
        // 幂等守卫：MaibmsApp.onCreate 与 App() 的 LaunchedEffect 都会调 start()，
        // 只允许第一套收集器/自动重连生效——曾因守卫字段从未赋值而双跑，
        // 两套收集器×两条轮询×两个 postConnectFlow 抢同一个应答槽，连接后直接把会话搅崩
        val alreadyStarted = withLock(startGuard) {
            if (started) true else { started = true; false }
        }
        if (alreadyStarted) return
        BmsLog.i("APP", "应用启动" + (if (realTransport != null) "（Android BLE）" else "（无 BLE 后端）"))
        // 自动重连目标：历史列表里显式指定的设备优先，否则默认上次连接；
        // 被指定的设备若已从历史删除，回退到上次连接
        val target = AppStore.autoConnectAddress
            ?.takeIf { addr -> DeviceProfiles.all().any { it.address == addr } }
            ?: AppStore.savedAddress
        // 冷启动：恢复记忆设备并自动重连（设备档案 + 密码库都落在本地）
        val saved = AppStore.savedAddress
        MockBms.savedAddress = target
        val targetProfile = DeviceProfiles.find(target)
        MockBms.connectedDeviceName.value = targetProfile?.displayName ?: AppStore.savedDeviceName
        restorePasswords(target)
        loadParamsCacheIntoSession(target)   // 未连接/连接中时，配置页先显示该设备的配置缓存
        MockBms.autoUpgradeLevel.value = AppStore.autoUpgradeTarget
        MockBms.usingRealBle.value = realTransport != null
        if (target != null) {
            BmsLog.i("APP", "记忆设备：${targetProfile?.displayName ?: AppStore.savedDeviceName ?: "未命名"} ($target)" +
                if (AppStore.autoConnectAddress != null && AppStore.autoConnectAddress != saved) "（指定的重连目标）" else "")
        }
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
                    // 建链成功即刷新档案的最近连接时间（扫描直连与自动重连都会走到这里）
                    DeviceProfiles.touch(MockBms.savedAddress)
                    // 连后序列：首帧 → 升权 → 参数区读回。必须放独立协程跑——
                    // 以前直接在收集器里 await 升权，0x43 弱信号下 2s 才回，这段
                    // 时间链路状态镜像被卡住，掉线了界面还显示「已连接」
                    firstFrameSignal = CompletableDeferred()
                    postConnectJob?.cancel()
                    postConnectJob = scope.launch { postConnectFlow() }
                    // 唤醒轮询立刻发首拍。放在最后：首帧信号已就位，不会漏接
                    pollWake.trySend(Unit)
                } else {
                    // 掉线后自动重连回来时要重新升权：不清标志的话重连后权限停在 0 级
                    if (st == LinkState.Disconnected || st == LinkState.Idle) autoUpgraded = false
                    // 预览中不抹状态：markDisconnected 会把快照的 battState 改成「等待数据…」
                    if (!previewActive.value) MockBms.markDisconnected()
                }
            }
        }
        collectorJob = scope.launch {
            real.incoming.collect { bytes ->
                // 预览中丢弃一切残余帧：断开瞬间在途的报文不得覆盖快照数据
                if (previewActive.value) return@collect
                for (f in parser.feed(bytes)) handleFrame(f)
            }
        }
        scope.launch {
            real.connectHint.collect { _connectHint.value = it }
        }
        // 用户上次是主动断开的话，就别自作主张再连上（但记忆设备仍然保留）
        val wantAuto = target != null && AppStore.autoReconnect && !previewActive.value
        if (wantAuto && !canAutoConnect()) {
            // 连接到界面前发起，权限对话框可能还没点：这时候碰蓝牙栈会抛 SecurityException。
            // 记一笔就停手，用户授权后从扫描列表/历史设备手动连接
            BmsLog.i("APP", "缺少蓝牙权限，本次不自动重连（授权后手动连接）")
        } else if (wantAuto) {
            StartupTrace.arm()   // 首屏计时只统计"启动即自动重连"这条路径
            scope.launch { connect(target) }
        }
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
     *
     * 调用方是[postConnectFlow]，它已经等到了首个实时帧，链路与通知订阅都确认落地，
     * 所以这里**不再**盲等固定 600ms（那是"首帧前立刻发 0x23 容易无应答"的旧规避手段）。
     * 真机注意：弱信号下 0x43 应答可能 >2s 才回来（实测 2 级那次就超时了），
     * 所以每条给 4s，并让返回值（设备当前权限）而不是循环变量进日志。
     */
    private suspend fun autoUpgradePermission() {
        if (autoUpgraded) return
        autoUpgraded = true
        if (MockBms.plainPasswords.value.isEmpty()) return
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

    /**
     * 连后序列：**首帧 → 升权 → 参数区/身份区读一次**。
     *
     * 顺序是刻意的：首页要看的（电压/电流/单体/温度/保护告警）全在第一条 0x11 实时帧里，
     * 所以首帧优先级最高——升权（0x23，弱信号下独占应答槽可达数秒）与 14 条参数区读回
     * 全部排到它后面。代价是"能改参数"比原先晚约半秒到一秒，这是明确接受的取舍。
     *
     * 参数区只读一遍：不再"等级 0 先读一遍、升权后再读一遍"。升权成功时 RSP_AUTH 分支
     * 已经触发了一轮 force 重读，这里再调一次会被 refreshParams 里的 readJob.isActive
     * 去重挡掉（不是靠运气，是那次调用的显式守卫）。
     */
    /** 连后序列进行中标志：Connected 事件重复触发时不重入（升权/读参数各只跑一套） */
    @Volatile private var postConnectRunning = false

    private suspend fun postConnectFlow() {
        if (postConnectRunning) return   // 上一次连后序列还在跑（弱信号升权可达数秒），别叠第二套
        postConnectRunning = true
        try {
            val firstFrame = firstFrameSignal ?: return
            if (withTimeoutOrNull(FIRST_FRAME_WAIT_MS) { firstFrame.await() } == null) {
                // 连上了却一直不吐数据（走远/被占用）：升权与参数区读回都没有意义，别去刷 14 条注定超时的命令
                BmsLog.w("CONN", "首个实时帧 ${FIRST_FRAME_WAIT_MS}ms 未到，跳过升权与参数区读回")
                return
            }
            if (previewActive.value || manualDisconnect.value || _linkState.value != LinkState.Connected) return
            autoUpgradePermission()
            if (previewActive.value || manualDisconnect.value || _linkState.value != LinkState.Connected) return
            refreshParams(force = true)
        } finally {
            postConnectRunning = false
        }
    }

    /** 幂等：换设备/重连前先清掉上一台的假数据与权限状态 */
    private fun resetSessionState() {
        autoUpgraded = false
        paramsAttempted = false
        sessionSnapshotId = 0L   // 新连接 = 新快照（旧快照保留在库里，不删）
        MockBms.applyPermission(0)
        MockBms.clearForRealDevice()
    }

    /**
     * 冻结当前数据为一张快照并落盘（开关关闭/预览中/无数据时不记）。
     * 同一会话重复记录覆盖同一张：权限升上去后 force 重读的参数区更全，替换掉先前的残缺版。
     */
    private fun recordSnapshot() {
        if (!AppStore.snapshotEnabled || previewActive.value) return
        val status = MockBms.status.value
        if (!status.hasData) return
        val id = if (sessionSnapshotId != 0L) sessionSnapshotId else epochMillisNow()
        sessionSnapshotId = id
        val snap = MockBms.captureSnapshot(id, timeLabel(epochMillisNow()))
        runCatching { AppStore.saveSnapshotJson(id, SnapshotCodec.encode(snap)) }
            .onSuccess { BmsLog.i("SNAP", "已记录快照：${snap.deviceName}（${snap.timeLabel}），参数 ${snap.liveParams.size} 项") }
            .onFailure { BmsLog.e("SNAP", "快照写入失败：$it") }
    }

    /** 记录时刻的可读标签（MM-dd HH:mm，跨平台实现见 DateTime.kt） */
    private fun timeLabel(epochMs: Long): String =
        formatShortDateTime(epochMs)

    // ---- 配置缓存（≠ 快照）：自动重连设备「上次成功连接」的设置项，不含任何实时数据 ----

    /** 未连接时把某设备的配置缓存灌进会话，配置页/关于页才有内容可看；无缓存则保持空态 */
    private fun loadParamsCacheIntoSession(address: String?) {
        val cache = address?.let { AppStore.loadParamsCache(it) } ?: return
        MockBms.loadParamsCache(cache)
        BmsLog.i("CACHE", "已载入配置缓存：${DeviceProfiles.find(address)?.displayName ?: address}（${cache.params.size} 项，记录于 ${timeLabel(cache.savedAt)}）")
    }

    /**
     * 把当前会话的设置项（参数区 + 身份区）落成该设备的配置缓存。
     * 只在「成功读到设置项」后调用（参数区读回完成 / 写参数成功回显）——实时数据不进缓存。
     */
    private fun persistParamsCache() {
        val addr = MockBms.savedAddress ?: return
        val params = MockBms.liveParams.value
        if (params.isEmpty()) return
        runCatching {
            AppStore.saveParamsCache(addr, AppStore.ParamsCache(
                savedAt = epochMillisNow(),
                params = params,
                identity = MockBms.identity.value,
            ))
        }.onSuccess { BmsLog.d("CACHE", "配置缓存已更新：$addr（${params.size} 项）") }
            .onFailure { BmsLog.e("CACHE", "配置缓存写入失败：$it") }
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
            _scanError.value = when {
                // 权限缺失时 Android 抛 SecurityException（common 代码没有这个类，按类名判）
                e != null && e::class.simpleName == "SecurityException" ->
                    "缺少蓝牙权限，请在系统设置中允许本应用使用附近的设备"
                e is IllegalStateException -> e.message
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

    /**
     * 连接指定设备（扫描列表或历史快速连接入口）。
     * 名称解析：先看本轮扫描结果，扫不到（如直接从历史列表回连）用档案里的显示名；
     * 同时把设备写进历史档案（新设备在此自动入列，已有设备刷新最近连接时间）。
     */
    suspend fun connectTo(address: String) {
        // 预览保护口：与 connect() 同一挡位。这里不挡的话，下面会先把地址写进记忆设备
        // 和设备档案，然后连接才被 connect() 拒掉——用户在预览里随手点一下设备，
        // 重启后就会被自动连到它（记忆设备/密码库全被换掉）
        if (previewActive.value) {
            BmsLog.w("CONN", "快照预览中，连接请求被忽略（重启应用后才能重新连接）")
            _connectHint.value = "正在预览快照，自动连接已停用（重启应用恢复）"
            return
        }
        val name = _scanResults.value.firstOrNull { it.address == address }?.name?.trim()
            ?: DeviceProfiles.find(address)?.displayName
        BmsLog.i("CONN", "选择设备 $name ($address)")
        MockBms.savedAddress = address
        MockBms.connectedDeviceName.value = name
        AppStore.savedAddress = address
        if (name != null) AppStore.savedDeviceName = name
        val prev = DeviceProfiles.find(address)
        DeviceProfiles.upsert(DeviceProfile(
            address = address,
            name = name ?: prev?.name.orEmpty(),
            alias = prev?.alias,
            lastConnectedAt = epochMillisNow(),
            passwords = prev?.passwords ?: emptyMap(),
        ))
        restorePasswords(address)
        stopScan()
        connect(address)
    }

    /**
     * 删除历史设备：档案连同其全部密码一并移除，名下的数据快照级联删除；
     * 若它是显式指定的自动重连目标，清除指定并回退「上次连接」；
     * 若它就是上次连接的设备（savedAddress），一并忘记——否则重启还会对它无密码自动重连。
     */
    fun deleteDevice(address: String) {
        val p = DeviceProfiles.find(address)
        DeviceProfiles.remove(address)
        AppStore.deleteSnapshotsForDevice(address)
        AppStore.deleteParamsCache(address)   // 配置缓存也是该设备的数据，一并清除
        if (AppStore.autoConnectAddress == address) AppStore.autoConnectAddress = null
        if (AppStore.savedAddress == address) {
            AppStore.savedAddress = null
            AppStore.savedDeviceName = null
        }
        BmsLog.i("DEV", "已删除历史设备 ${p?.displayName ?: address}（密码 ${p?.passwords?.size ?: 0} 级与快照一并清除）")
    }

    suspend fun connect(address: String? = null) {
        // 预览保护口：快照一旦载入，本进程内不再发起任何连接（含扫描列表手动连）
        if (previewActive.value) {
            BmsLog.w("CONN", "快照预览中，连接请求被忽略（重启应用后才能重新连接）")
            _connectHint.value = "正在预览快照，自动连接已停用（重启应用恢复）"
            return
        }
        BmsLog.i("CONN", "发起连接" + (address?.let { " → $it" } ?: "") + if (AppStore.autoReconnect) "（自动重连）" else "（手动）")
        manualDisconnect.value = false
        AppStore.autoReconnect = true
        resetSessionState()
        // resetSessionState 里的 clearForRealDevice() 会清掉设备名（本意是防快照预览的名字残留），
        // 但这里连的就是同一台设备，必须立刻回填——否则重连成功后大卡的设备名和电量百分比
        // 会一直显示「--」（百分比以设备名判断"是否见过设备"）。
        // 注：connectTo() 虽先设了名字，但它末尾也调本函数，一样会被 reset 清掉，所以修复点在这里
        MockBms.connectedDeviceName.value =
            DeviceProfiles.find(address ?: MockBms.savedAddress)?.displayName ?: AppStore.savedDeviceName
        // 清空会话后立刻回灌该设备的配置缓存：连接期间配置页不至于从有值闪回空值，
        // 连接失败（板子不在）时也还留着上次成功连接的设置项可看
        loadParamsCacheIntoSession(address ?: MockBms.savedAddress)
        lastFrameAt = 0L
        postConnectJob?.cancel()
        postConnectJob = null
        firstFrameSignal = null
        transport.connect(address)
        // check-then-launch 的竞态：两次并发 connect() 都看到 isActive==false 会各起一条轮询，
        // 轮询与命令共用一个应答槽，双管线会把会话搅崩（与 start() 双跑同一类故障）。
        // 用 Mutex 串行化「检查+启动」：赢家启动轮询，输家进来发现已有活跃 Job 就什么都不做
        pollGuard.withLock {
            if (pollJob?.isActive != true) {
                pollJob = scope.launch { pollLoop() }
            }
        }
    }

    /**
     * 轮询节奏：读 AppStore.pollIntervalMs（开发者页三档 900/600/300，默认 600ms），
     * 每拍现读——改完最迟一拍内生效。
     */
    private val pollIntervalMs: Long
        get() = AppStore.pollIntervalMs.toLong()

    /** 轮询循环（connect 启动，disconnect 取消）：无条件常驻，连接态在每拍内部判断 */
    private suspend fun pollLoop() {
        // 无条件常驻：连接态在每拍内部判断（避免链路态镜像延迟导致首拍误判退出）
        // 节奏 = 开发者页三档（900/600/300，默认 600）：更快的轮询在弱信号下会持续占满
        // BLE 连接间隔、反而拖低成功率，遇到丢帧就切回 900 档；链路刚就绪时会被 pollWake
        // 立刻唤醒一次——首帧不用白等这个相位
        while (currentCoroutineContext().isActive) {
            if (_linkState.value == LinkState.Connected) {
                try {
                    requestAndAwait(Frame.readRealtime(), Proto.RSP_REALTIME, expectedFunc = Proto.RSP_REALTIME, expectedReg = 0, timeoutMs = 700, fromPoll = true)
                } catch (e: Exception) {
                    // 每拍都有超时是常态，只有连续异常才有意义，所以只记 DEBUG
                    BmsLog.d("POLL", "实时轮询超时：${e.message ?: e::class.simpleName}")
                }
                // 失联检测：GATT 还在但连续多拍收不到实时帧（设备休眠/走远/干扰）
                val since = epochMillisNow() - lastFrameAt
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
            // 正常按当前档位走（默认 600ms）；链路刚就绪会提前唤醒（见 pollWake）
            withTimeoutOrNull(pollIntervalMs) { pollWake.receive() }
        }
    }

    suspend fun disconnect() {
        // 兜底快照：本会话见过实时数据但参数区一直没读成（如权限卡在 0 级），
        // 趁状态还在先补一张仅实时帧的快照，别让这次连接什么都不留
        if (lastFrameAt > 0 && sessionSnapshotId == 0L) recordSnapshot()
        BmsLog.i("CONN", "主动断开连接")
        manualDisconnect.value = true
        AppStore.autoReconnect = false   // 记住这次主动断开，重启后不要自动连
        pollJob?.cancel(); pollJob = null
        postConnectJob?.cancel(); postConnectJob = null
        firstFrameSignal = null
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
        /** 连后序列等首个实时帧的上限；等不到就跳过升权与参数区读回 */
        const val FIRST_FRAME_WAIT_MS = 10_000L
        /** BLE 扫描窗口：够扫到弱信号设备，又不至于让用户干等 */
        const val SCAN_WINDOW_MS = 20_000L
        /** 两条校验命令的最小间隔，防链路抖动时反复发 0x23 */
        const val RE_AUTH_COOLDOWN_MS = 60_000L
        /** 等 0x42 同帧的 0xFF 结果段落地（真机弱信号下两段可能差数百毫秒，取文档建议值） */
        const val WRITE_STATUS_GRACE_MS = 200L
    }

    private suspend fun handleFrame(f: ParsedFrame) {
        BmsLog.d("RX", "func=%02X reg=%d len=%d %s".fmt(f.func, f.reg, f.data.size, BmsLog.hex(f.data.take(24).toByteArray())))
        when (f.func) {
            Proto.RSP_REALTIME -> {
                runCatching { RealtimeDecoder.decode(f.data) }.onSuccess { r ->
                    lastFrameAt = epochMillisNow()
                    MockBms.updateFromRealtime(r)
                    MockBms.connected.value = true
                    // 首帧到达：唤醒连后序列（升权 → 参数区读回排在它后面）。
                    // 这里以前直接 refreshParams()，导致"等级 0 先读一遍、升权后再读一遍"，
                    // 而且 14 条参数命令会跟升权抢应答槽，把首帧之后的刷新拖成一顿一顿
                    firstFrameSignal?.complete(Unit)
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
                    BmsLog.d("WRITE", "回显 ${patch.size} 项 @0x${"%X".fmt(f.reg)}")
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
                // 只有「正在写参数」时才收（writeInFlight）：码 11 = 读取成功，每条 0x12 读应答后面都跟一条；
                // 读参数区时权限不足也会回码 1 —— 这两种都不该冒充「写参数结果」上屏。
                val code = f.reg and 0xFF
                if (code != 11 && writeInFlight) {
                MockBms.lastWriteResult.value = code
                // 只有「小于最小值 / 大于最大值」才附带限值（docs/附录B B.3）
                MockBms.lastWriteDetail.value = if (f.data.size >= 4 && (code == 2 || code == 3)) {
                    val addr = (f.data[0].toInt() and 0xFF) or ((f.data[1].toInt() and 0xFF) shl 8)
                    val limit = (f.data[2].toInt() and 0xFF) or ((f.data[3].toInt() and 0xFF) shl 8)
                    val def = ParamTable.byAddr(addr)
                    if (def != null) {
                        "%s %s=%.3f %s".fmt(
                            def.name, if (code == 2) "最小" else "最大", limit / def.scale, def.unit
                        )
                    } else "参数 0x%X 限值 %d".fmt(addr, limit)
                } else null
                val ok = ResultCodes.writeOk(code)
                val detail = MockBms.lastWriteDetail.value
                if (ok) BmsLog.i("WRITE", "参数写入成功（0x${"%X".fmt(f.reg)}）")
                else BmsLog.e("WRITE", "参数写入失败：${ResultCodes.writeResult(code)}（0x${"%X".fmt(f.reg)}）" + (detail?.let { " · $it" } ?: ""))
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
            // 密码类帧走遮蔽版：日志会被导出成文件，别把 0x23 的密码明文留在里面
            BmsLog.d("TX", Frame.hexForLog(frame))
            transport.write(frame)
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            awaiting = null; expectFunc = -1; expectReg = null
            if (r == null && !fromPoll) {
                BmsLog.w("TX", "命令无应答 func=%02X reg=%d（${timeoutMs}ms 超时）".fmt(respondFunc, expectedReg ?: -1))
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
        // 槽长按等级取（5 级与管理员槽 12 字节，管理员为点分十进制），见 PasswordCodec
        val payload = PasswordCodec.encode(level, password)
        BmsLog.i("AUTH", "校验 $level 级密码（寄存器 $addr，${payload.size} 字节）")
        lastAuthAt = epochMillisNow()
        // 弱信号下 0x43 可能 2s 后才回（真机实测），给足 4s
        val r = requestAndAwait(Frame.auth(addr, payload), Proto.RSP_AUTH, expectedReg = addr, timeoutMs = 4000)
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
        if (epochMillisNow() - lastAuthAt < RE_AUTH_COOLDOWN_MS) return
        reAuthJob = scope.launch {
            delay(300)   // 让这一拍的轮询应答先落地，别抢应答队列
            val lv = runCatching { auth(target, pw) }.getOrDefault(0)
            if (lv > 0) {
                MockBms.applyPermission(lv)
                BmsLog.i("AUTH", "权限回落，已静默重升到 $lv 级")
            } else {
                BmsLog.w("AUTH", "权限回落重升失败（$target 级密码未通过，已从密码库移除）")
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
                // 读空（全部超时）时不覆盖：配置页宁可显示上次成功的缓存，也别闪回空值
                if (acc.isNotEmpty()) MockBms.liveParams.value = acc
                val id = HashMap<String, String>()
                for ((key, spec) in identityBlocks) {
                    val (addr, len) = spec
                    val r = requestAndAwait(Frame.readParam(addr, len), Proto.RSP_PARAM, expectedReg = addr, timeoutMs = 1500)
                    if (r != null && r.func == Proto.RSP_PARAM && r.data.isNotEmpty()) {
                        id[key] = ascii(r.data)
                    }
                    delay(120)
                }
                if (id.isNotEmpty()) MockBms.identity.value = id
                BmsLog.i("PARAM", "参数区读回 ${acc.size} 项，身份区 ${id.size} 项")
                if (acc.isNotEmpty() || id.isNotEmpty()) {
                    // 全量同步完成：实时帧已在流上、参数区/身份区刚刚落定——此刻冻结快照最全
                    recordSnapshot()
                    // 设置项是最新的了：更新该设备的配置缓存（实时数据不进缓存），并摘掉「缓存」标记
                    MockBms.paramsFromCache.value = false
                    persistParamsCache()
                }
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
        BmsLog.i("CTRL", "发送控制命令：${ControlCmd.name(cmd)}（0x%02X）".fmt(cmd))
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
     *
     * 真机只验证过「被拒」这一侧，成功时是否也追加 0xFF 段没有实证（docs 内部两说），
     * 所以**没收到结果段时回读该参数做兜底判定**——不能把「写没写进去」交给一个假设。
     *
     * @return 0xFF 段结果码；-1 = 未确认（已不再出现，兜底会给出结论）、-2 = 无应答、-3 = 回读不一致
     */
    suspend fun writeParam(addr: Int, rawValue: Int): Int {
        val def = ParamTable.byAddr(addr)
        val label = "0x${"%X".fmt(addr)}" + (def?.let { "（${it.name}）" } ?: "")
        BmsLog.i("WRITE", "写参数 $label = $rawValue")
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

        var answered = true
        var deviceCode = -1
        writeInFlight = true
        try {
            for ((a, fr) in writes) {
                val r = requestAndAwait(fr, Proto.RSP_WRITE, expectedReg = a, timeoutMs = 2500)
                if (r == null) { answered = false; break }   // 无应答：界面能区分「超时」与「设备拒绝」
                delay(WRITE_STATUS_GRACE_MS)   // 结果段与 0x42 同帧、紧随其后，给它一点时间落地
                deviceCode = MockBms.lastWriteResult.value
                if (deviceCode != -1 && !ResultCodes.writeOk(deviceCode)) break   // 设备已明确拒绝，第二帧不必发
            }
        } finally {
            writeInFlight = false
        }

        if (!answered) {
            MockBms.lastWriteResult.value = -2
            MockBms.lastWriteDetail.value = "设备未应答（链路差或参数不可写），本次未保存"
            BmsLog.e("WRITE", "写参数 $label 失败：设备未应答")
            return -2
        }

        if (deviceCode == -1) {
            // 设备没回结果段：回读实锤。一致 → 视同成功（值已进临时区，继续保存）；
            // 不一致/读不到 → 明确报「未确认」，不再静默什么都不显示
            val want = expectedRaw(addr, rawValue)
            val actual = readBack(addr)
            MockBms.lastWriteResult.value = if (actual != null && actual == want) 10 else -3
            MockBms.lastWriteDetail.value = when {
                actual == null -> "设备未回结果码，且回读无应答：写入结果未知，请重读确认"
                actual == want -> "设备未回结果码，已回读确认写入（$label）"
                else -> "设备未回结果码，且回读值不一致（期望 $want / 实际 $actual）：本次写入未生效"
            }
            if (actual == want) {
                BmsLog.w("WRITE", "写参数 $label 未收到 0xFF 结果段，回读一致（$want），按成功处理")
            } else {
                BmsLog.e("WRITE", "写参数 $label 未收到 0xFF 结果段，回读校验不通过（期望 $want / 实际 $actual）")
                return -3
            }
        }

        val code = MockBms.lastWriteResult.value
        if (ResultCodes.writeOk(code)) {
            BmsLog.i("WRITE", "写参数 $label 成功，自动保存（51/07）")
            // 保存的 0x61 应答由 handleFrame 记进 lastControlResult，控制页横幅直接读它
            requestAndAwait(Frame.saveParams(), Proto.RSP_CONTROL, expectedReg = ControlCmd.SAVE_PARAMS)
            // 写入成功 = 设备的设置项变了：0x42 回显已更新会话 liveParams，这里同步刷新配置缓存
            persistParamsCache()
        }
        return code
    }

    /** 期望落库的原始值：u32 取 32 位、u16 取低 16 位（与设备回读口径一致） */
    private fun expectedRaw(addr: Int, rawValue: Int): Long =
        if (addr in ParamTable.u32Addrs) rawValue.toLong() and 0xFFFFFFFFL else (rawValue and 0xFFFF).toLong()

    /** 回读参数做校验：u32 类读 4 字节拼 32 位；读不到返回 null */
    private suspend fun readBack(addr: Int): Long? {
        val bytes = if (addr in ParamTable.u32Addrs) 4 else 2
        val r = requestAndAwait(Frame.readParam(addr, bytes), Proto.RSP_PARAM, expectedReg = addr, timeoutMs = 1500)
        if (r == null || r.func != Proto.RSP_PARAM || r.data.size < bytes) return null
        val lo = (r.data[0].toInt() and 0xFF) or ((r.data[1].toInt() and 0xFF) shl 8)
        if (bytes == 2) return lo.toLong()
        val hi = (r.data[2].toInt() and 0xFF) or ((r.data[3].toInt() and 0xFF) shl 8)
        return ((hi.toLong() shl 16) or lo.toLong()) and 0xFFFFFFFFL
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
        // 预设/恢复出厂会整片改写参数区：不重读的话，配置页还停在旧阈值上
        if (ControlCmd.rewritesParams(cmd)) {
            BmsLog.i("PARAM", "${ControlCmd.name(cmd)} 会改写参数区，强制重读")
            refreshParams(force = true)
        }
    }
}

/** 全局单例：App 启动即接好虚拟 BMS 数据管线 */
object Bms {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = MockBmsEngine(scope)
    val repository = BmsRepository(scope, engine)
}
