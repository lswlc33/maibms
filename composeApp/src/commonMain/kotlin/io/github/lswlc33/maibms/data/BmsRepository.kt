package io.github.lswlc33.maibms.data

import kotlin.concurrent.Volatile
import io.github.lswlc33.maibms.protocol.*
import io.github.lswlc33.maibms.protocol.meter.MeterProtocol
import io.github.lswlc33.maibms.protocol.meter.modbusReadFrame
import io.github.lswlc33.maibms.protocol.meter.LuXingProtocol
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

    private class Session(val address: String?)
    private val sessionLock = PlatformLock()
    private val connectionGuard = Mutex()
    @Volatile private var session = Session(null)
    private var acceptingFrames = false

    private fun checkSession(expected: Session) {
        if (session !== expected || !acceptingFrames || previewActive.value || manualDisconnect.value) {
            throw CancellationException("设备会话已结束")
        }
    }

    /** 会话失效先于取消任务，已排队的旧命令与迟到结果也必须停用。 */
    private fun invalidateSession(stopPolling: Boolean): List<Job> = withLock(sessionLock) {
        acceptingFrames = false
        session = Session(MockBms.savedAddress)
        awaiting?.cancel(CancellationException("设备会话已结束"))
        awaiting = null
        expectFunc = -1
        expectReg = null
        activeWrite = null
        val jobs = listOfNotNull(postConnectJob, readJob, reAuthJob, if (stopPolling) pollJob else null)
        jobs.forEach { it.cancel() }
        postConnectJob = null
        readJob = null
        reAuthJob = null
        if (stopPolling) pollJob = null
        firstFrameSignal = null
        MockBms.paramsReading.value = false
        autoUpgraded = false
        paramsAttempted = false
        lastAuthAt = 0L
        parser = FrameParser()
        meter?.reset()
        jobs
    }

    private suspend fun stopSessionWork() {
        invalidateSession(stopPolling = true).forEach { it.join() }
        cmdMutex.withLock { }
    }

    /** 电量计协议实现（家族为电量计时非空）；为空走保护板（ANT）协议栈 */
    @Volatile private var meter: MeterProtocol? = null
    @Volatile private var meterInitSent = false
    /** 寄存器探测进行中：暂停常规轮询，避免与探测帧抢应答 */
    @Volatile private var meterProbeActive = false

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

    // ---- 通信通道（docs/02 §2.2）：传输层探测结果镜像 + 手动切换 ----

    /** 当前正在使用的通道（null = 未连接或尚未探明） */
    private val _activeChannel = MutableStateFlow<BleChannel?>(null)
    val activeChannel: StateFlow<BleChannel?> = _activeChannel

    /** 本设备提供且可用的通道（真实 BLE 发现服务后得出） */
    private val _availableChannels = MutableStateFlow<List<BleChannel>>(emptyList())
    val availableChannels: StateFlow<List<BleChannel>> = _availableChannels

    /** 当前连接设备的家族（按广播名判定）：Unknown 视作保护板；电量计 = 中继器 */
    private val _currentFamily = MutableStateFlow(DeviceFamily.Unknown)
    val currentFamily: StateFlow<DeviceFamily> = _currentFamily

    /** 是否支持手动切换通道（桌面/虚拟后端为 false，界面据此隐藏入口） */
    val supportsChannelSwitch: Boolean get() = realTransport?.supportsChannelSwitch == true

    // ---- 快照预览模式：载入快照后置位，进程存活期间不解除（重启后自动连接按原状态恢复） ----

    /** 预览激活中：连接/轮询/帧回灌全部被挡（见 connect/start 的防护口） */
    val previewActive = MutableStateFlow(false)

    /** 预览中的快照标签（设备名 · 记录时刻），横幅展示用 */
    val previewLabel = MutableStateFlow<String?>(null)

    /** 本会话已记录的快照 id；0 = 本次连接还没记过（同会话重读参数时覆盖同一张） */
    private var sessionSnapshotId = 0L
    /** 电量计：已记过一张「完整同步」的快照（各表到齐） */
    private var meterSnapshotComplete = false
    /** 电量计：完整同步迟迟不来时已记过一张兜底快照 */
    private var meterSnapshotFallbackDone = false
    /** 电量计：本会话首个非空读数到账的时刻（兜底窗口从这一刻起算） */
    private var meterFirstReadingAt = 0L

    /** 载入快照进入预览：停连接、停轮询，之后任何帧都进不来（防护口见各挂点） */
    suspend fun enterPreview(s: BmsSnapshot) = connectionGuard.withLock {
        BmsLog.i("SNAP", "进入快照预览：${s.deviceName}（${s.timeLabel}），自动连接停用直到重启")
        previewActive.value = true   // 先置位再动手，挡住断开过程中的一切回灌
        previewLabel.value = s.deviceName.ifBlank { null }?.let { "$it · ${s.timeLabel}" } ?: s.timeLabel
        stopSessionWork()
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

    private var parser = FrameParser()

    /** 实时轮询请求帧内容恒定，建一次复用（原先每拍现建：组帧 + 算一遍 CRC） */
    private val realtimeRequest = Frame.readRealtime()
    private val cmdMutex = Mutex()
    private val writeMutex = Mutex()
    private var awaiting: CompletableDeferred<ParsedFrame>? = null
    private var expectFunc = -1

    /** 期望应答的寄存器值；null=不校验。真机教训：只比功能码会把上一条超时命令的迟到应答收下 */
    private var expectReg: Int? = null

    /**
     * 正在写参数：只有这个窗口内的 0xFF 附加段才算「写参数结果」。
     * 读参数区时每条 0x12 后面也跟一条 0xFF（码 11，权限不足时码 1），
     * 早先靠 `paramsReading` 反着排除，写参数撞上读回就会被吞掉结果（且静默无提示）。
     */
    private class WriteAttempt(val session: Session, val address: Int) {
        var echoReceived = false
        var result = -1
        var detail: String? = null
    }
    private var activeWrite: WriteAttempt? = null

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
                withLock(sessionLock) {
                    _linkState.value = st
                    if (st == LinkState.Connected && acceptingFrames && !previewActive.value && !manualDisconnect.value) {
                        DeviceProfiles.touch(MockBms.savedAddress)
                        if (meter == null) {
                            firstFrameSignal = CompletableDeferred()
                            postConnectJob?.cancel()
                            val expected = session
                            postConnectJob = scope.launch { postConnectFlow(expected) }
                        }
                        pollWake.trySend(Unit)
                    } else if (st != LinkState.Connected) {
                        if (st == LinkState.Disconnected || st == LinkState.Idle) {
                            val resume = acceptingFrames && !previewActive.value && !manualDisconnect.value
                            invalidateSession(stopPolling = false)
                            acceptingFrames = resume
                        }
                        if (!previewActive.value) MockBms.markDisconnected()
                    }
                }
                // 链路态一变就立刻反映到常驻监控通知（连上才显示，断开要马上撤掉）
                refreshMonitorNotification(force = true)
            }
        }
        // 回到前台立刻恢复轮询（否则要等当前这一拍的超时/间隔走完），并刷新监控通知
        scope.launch {
            AppForeground.active.collect { fg ->
                if (fg) pollWake.trySend(Unit)
                refreshMonitorNotification(force = true)
            }
        }
        collectorJob = scope.launch {
            real.incoming.collect { bytes ->
                withLock(sessionLock) {
                    if (previewActive.value || manualDisconnect.value || !acceptingFrames) return@withLock
                    val m = meter
                    if (m != null) {
                        val readings = runCatching { m.onNotification(bytes) }.getOrElse { emptyList() }
                        for (rd in readings) {
                            if (rd.isEmpty) continue
                            lastFrameAt = epochMillisNow()
                            if (meterFirstReadingAt == 0L) meterFirstReadingAt = lastFrameAt
                            MockBms.applyMeterReading(rd)
                            // 设备信息（蓝宝表2 的序列号/版本/电池类型/串数）走身份区，与读数分开合并
                            MockBms.applyMeterDeviceInfo(rd)
                            maybeRecordMeterSnapshot(m)
                        }
                        if (readings.isEmpty()) {
                            BmsLog.d("METER", "未识别或分片(${bytes.size}B) ${BmsLog.hex(bytes.take(24).toByteArray())}")
                        }
                    } else {
                        for (f in parser.feed(bytes)) handleFrame(f)
                    }
                }
            }
        }
        scope.launch {
            real.connectHint.collect { _connectHint.value = it }
        }
        scope.launch { real.activeChannel.collect { _activeChannel.value = it } }
        scope.launch { real.availableChannels.collect { _availableChannels.value = it } }
        scope.launch { real.currentFamily.collect { _currentFamily.value = it } }
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
        val pw = mutableMapOf<Int, String>()
        if (address != null) {
            for (lv in listOf(1, 2, 3, 4, 5, 9)) AppStore.loadPassword(address, lv)?.let { pw[lv] = it }
        }
        MockBms.plainPasswords.value = pw
        MockBms.passwords.value = MockBms.passwords.value.map {
            it.copy(masked = if (pw.containsKey(it.level)) "••••••••" else null, isCurrent = false)
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
    private suspend fun autoUpgradePermission(expected: Session) {
        checkSession(expected)
        if (autoUpgraded) return
        autoUpgraded = true
        if (MockBms.plainPasswords.value.isEmpty()) return
        val target = MockBms.autoUpgradeLevel.value
        val order = (listOf(target) + listOf(5, 4, 3, 2, 1)).distinct()
        val best = order.mapNotNull { lv ->
            checkSession(expected)
            val pw = MockBms.plainPasswords.value[lv] ?: return@mapNotNull null
            val ok = try { authInSession(lv, pw, expected) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { 0 }
            if (ok > 0) ok else null
        }.maxOrNull()
        withLock(sessionLock) {
            checkSession(expected)
            if (best != null) {
                MockBms.applyPermission(best)
                if (MockBms.autoUpgradeLevel.value > 0 && best < MockBms.autoUpgradeLevel.value) {
                    MockBms.autoUpgradeLevel.value = best
                    AppStore.autoUpgradeTarget = best
                }
                BmsLog.i("AUTH", "自动升权成功：$best 级")
            } else {
                BmsLog.w("AUTH", "自动升权失败：本地密码均未通过")
            }
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

    private suspend fun postConnectFlow(expected: Session) {
        if (postConnectRunning) return
        postConnectRunning = true
        try {
            checkSession(expected)
            val firstFrame = firstFrameSignal ?: return
            if (withTimeoutOrNull(FIRST_FRAME_WAIT_MS) { firstFrame.await() } == null) {
                BmsLog.w("CONN", "首个实时帧 ${FIRST_FRAME_WAIT_MS}ms 未到，跳过升权与参数区读回")
                return
            }
            checkSession(expected)
            if (_linkState.value != LinkState.Connected) return
            autoUpgradePermission(expected)
            checkSession(expected)
            if (_linkState.value != LinkState.Connected) return
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
        meterSnapshotComplete = false
        meterSnapshotFallbackDone = false
        meterFirstReadingAt = 0L
        // 换设备/重连：上升沿基线清空——上一台的置位不能当成本台的"已有状态"（否则真正的新保护不报）
        alarmNotifier.reset()
        linkAlerted = false
        // 轮询统计跨通道/跨设备没有可比性，一起归零
        LinkStats.resetPoll()
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

    /**
     * 电量计会话的快照挂点。
     *
     * 「一次完整同步」= 该协议要求的几张表都到齐（[MeterProtocol.snapshotReady]）：蓝宝的容量/循环
     * 在第 5 拍才读的表 2 里、陆行的中继信息与循环各占一帧。正常情况等它到齐再冻结。
     *
     * 但**不能等到底**：某张表始终不来（型号差异、弱信号持续丢帧）时若一直不记，这次连接就什么
     * 都没留下。所以超过 [meterSnapshotFallbackMs] 先用现有数据落一张兜底，之后真同步到位再覆盖
     * 同一张（[sessionSnapshotId] 不变）。这也是旧行为（收到第一帧带电压的读数后固定延迟冻结）
     * 与「等到齐」之间的折中。
     */
    private fun maybeRecordMeterSnapshot(m: MeterProtocol) {
        if (meterSnapshotComplete) return
        if (m.snapshotReady) {
            recordSnapshot()
            meterSnapshotComplete = sessionSnapshotId != 0L
            return
        }
        if (meterSnapshotFallbackDone) return
        val first = meterFirstReadingAt
        if (first == 0L || epochMillisNow() - first < meterSnapshotFallbackMs) return
        BmsLog.w("SNAP", "电量计完整同步未到齐（${epochMillisNow() - first}ms），先用现有数据记一张兜底快照")
        recordSnapshot()
        meterSnapshotFallbackDone = sessionSnapshotId != 0L
    }

    /**
     * 电量计快照兜底窗口：要够跑完一整轮表轮换（蓝宝表 2 每 5 拍读一次）+ 1 拍余量 + 1s 通信余量。
     * 按当前轮询档位算，换档后新连接即生效；正常路径根本用不到它。
     */
    private val meterSnapshotFallbackMs: Long get() = pollIntervalMs * 6 + 1_000L

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
        // 那句「未发现保护板/电量计」的提示永远出不来（用户开设备前必然遇到）
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
    suspend fun connectTo(address: String) = connectionGuard.withLock {
        if (previewActive.value) {
            BmsLog.w("CONN", "快照预览中，连接请求被忽略（重启应用后才能重新连接）")
            _connectHint.value = "正在预览快照，自动连接已停用（重启应用恢复）"
            return@withLock
        }
        val name = _scanResults.value.firstOrNull { it.address == address }?.name?.trim()
            ?: DeviceProfiles.find(address)?.displayName
        BmsLog.i("CONN", "选择设备 $name ($address)")
        stopSessionWork()
        if (_linkState.value != LinkState.Idle && _linkState.value != LinkState.Disconnected) transport.disconnect()
        withLock(sessionLock) {
            MockBms.savedAddress = address
            MockBms.connectedDeviceName.value = name
            AppStore.savedAddress = address
            AppStore.savedDeviceName = name
            val prev = DeviceProfiles.find(address)
            val family = _scanResults.value.firstOrNull { it.address == address }?.family
                ?: prev?.family ?: DeviceFamily.Unknown
            DeviceProfiles.upsert(DeviceProfile(
                address = address,
                name = name ?: prev?.name.orEmpty(),
                alias = prev?.alias,
                lastConnectedAt = epochMillisNow(),
                passwords = prev?.passwords ?: emptyMap(),
                family = family,
            ))
            restorePasswords(address)
        }
        stopScan()
        connectLocked(address)
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
        AppStore.saveChannel(address, null)   // 通道偏好同样跟设备走，删除即清除
        if (AppStore.autoConnectAddress == address) AppStore.autoConnectAddress = null
        if (AppStore.savedAddress == address) {
            AppStore.savedAddress = null
            AppStore.savedDeviceName = null
        }
        BmsLog.i("DEV", "已删除历史设备 ${p?.displayName ?: address}（密码 ${p?.passwords?.size ?: 0} 级与快照一并清除）")
    }

    suspend fun connect(address: String? = null) = connectionGuard.withLock {
        if (previewActive.value) {
            BmsLog.w("CONN", "快照预览中，连接请求被忽略（重启应用后才能重新连接）")
            _connectHint.value = "正在预览快照，自动连接已停用（重启应用恢复）"
            return@withLock
        }
        stopSessionWork()
        if (_linkState.value != LinkState.Idle && _linkState.value != LinkState.Disconnected) transport.disconnect()
        connectLocked(address)
    }

    private suspend fun connectLocked(address: String?) {
        val targetAddr = address ?: MockBms.savedAddress
        BmsLog.i("CONN", "发起连接" + (targetAddr?.let { " → $it" } ?: "") + if (AppStore.autoReconnect) "（自动重连）" else "（手动）")
        withLock(sessionLock) {
            manualDisconnect.value = false
            AppStore.autoReconnect = true
            MockBms.savedAddress = targetAddr
            session = Session(targetAddr)
            resetSessionState()
            MockBms.connectedDeviceName.value = DeviceProfiles.find(targetAddr)?.displayName
                ?: AppStore.savedDeviceName?.takeIf { AppStore.savedAddress == targetAddr }
            restorePasswords(targetAddr)
            loadParamsCacheIntoSession(targetAddr)
            lastFrameAt = 0L
            _stalled.value = false
            stallRotations = 0
            transport.preferChannel(AppStore.channelFor(targetAddr))
            val family = _scanResults.value.firstOrNull { it.address == targetAddr }?.family
                ?: DeviceProfiles.find(targetAddr)?.family ?: DeviceFamily.Ant
            transport.setTargetFamily(family)
            BmsLog.i("CONN", "目标家族：${family.label}（${family.kind}）")
            meter = MeterProtocol.forFamily(family, AppStore.loadPassword(targetAddr ?: "", 0))
            meterInitSent = false
            MockBms.caps.value = meter?.caps ?: DeviceCapabilities.forFamily(family)
            acceptingFrames = true
        }
        transport.connect(targetAddr)
        pollGuard.withLock {
            if (pollJob?.isActive != true) pollJob = scope.launch { pollLoop() }
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
                // 后台策略：没开「后台监控」时，退到后台**整段跳过**（请求不发、失联检测也不做）。
                // 只停请求不停链路：回前台立刻继续，不必重连；失联判定跟着停是因为
                // "没发请求所以没数据"不是失联，继续判会误报并触发无谓的通道轮切
                val paused = !AppForeground.active.value && !AppStore.backgroundMonitor
                if (!paused) {
                val m = meter
                if (m != null) {
                    // 电量计：写轮询帧（不等待应答，靠通知回调解码）；寄存器探测期间暂停
                    if (!meterProbeActive) {
                        try {
                            if (!meterInitSent) {
                                m.initialFrames().forEach { transport.write(it) }
                                meterInitSent = true
                            }
                            m.nextPollFrames().forEach { transport.write(it) }
                        } catch (e: Exception) {
                            BmsLog.d("POLL", "电量计轮询写入失败：${e.message ?: e::class.simpleName}")
                        }
                    }
                } else {
                    try {
                        // 往返耗时埋点：这条统计是"轮询档/通道/信号好坏"的唯一客观依据
                        val t0 = epochMillisNow()
                        val r = requestAndAwait(realtimeRequest, Proto.RSP_REALTIME, expectedFunc = Proto.RSP_REALTIME, expectedReg = 0, timeoutMs = 700, fromPoll = true)
                        val t1 = epochMillisNow()
                        LinkStats.notePoll((t1 - t0).toInt(), r != null, t1)
                    } catch (e: Exception) {
                        // 每拍都有超时是常态，只有连续异常才有意义，所以只记 DEBUG
                        LinkStats.notePoll(0, ok = false, nowMs = epochMillisNow())
                        BmsLog.d("POLL", "实时轮询超时：${e.message ?: e::class.simpleName}")
                    }
                }
                // 失联检测：GATT 还在但连续多拍收不到实时帧（设备休眠/走远/干扰）
                val since = epochMillisNow() - lastFrameAt
                if (MockBms.connected.value && lastFrameAt > 0 && since > STALL_MS) {
                    if (!_stalled.value) {
                        _stalled.value = true
                        BmsLog.w("LINK", "实时帧停流 ${since}ms，判定失联（尝试轮切其它通道）")
                        rotateChannelOnStall()
                        notifyLinkLost(since)
                    }
                } else if (_stalled.value) {
                    _stalled.value = false
                    stallRotations = 0
                    linkAlerted = false
                    BmsLog.i("LINK", "实时帧恢复")
                }
                }
                // 常驻监控通知的摘要（节流在函数内）：暂停态也要走一遍，
                // 否则从后台恢复监控状态时会晚一拍才反映到通知栏
                refreshMonitorNotification()
            }
            // 正常按当前档位走（默认 600ms）；链路刚就绪会提前唤醒（见 pollWake）
            withTimeoutOrNull(pollIntervalMs) { pollWake.receive() }
        }
    }

    suspend fun disconnect() = connectionGuard.withLock {
        withLock(sessionLock) {
            if (lastFrameAt > 0 && sessionSnapshotId == 0L) recordSnapshot()
            manualDisconnect.value = true
            AppStore.autoReconnect = false
        }
        BmsLog.i("CONN", "主动断开连接")
        stopSessionWork()
        transport.disconnect()
        _connectHint.value = null
        _stalled.value = false
        MockBms.markDisconnected()
    }

    /** 失联轮切：本会话已轮切次数（实时帧恢复即清零） */
    private var stallRotations = 0

    /**
     * 失联时按顺序试其它通道（对应官方的「通道轮切」，docs/02 §2.2）：
     * 全部候选都试过仍不通就只提示重启蓝牙，不再无限轮切。
     */
    private fun rotateChannelOnStall() {
        if (!supportsChannelSwitch) return
        val avail = _availableChannels.value
        if (avail.size <= 1) {
            _connectHint.value = "设备无应答，且只提供默认通道：请断电重启蓝牙后重试"
            return
        }
        if (stallRotations >= avail.size) {
            _connectHint.value = "所有通道都试过仍无应答：请断电重启蓝牙后重试"
            return
        }
        val cur = _activeChannel.value
        val next = avail.firstOrNull { it != cur } ?: return
        stallRotations++
        // 给新通道一个完整的失联窗口：切完就重连，不能让旧的时间戳立刻再触发一次轮切
        lastFrameAt = epochMillisNow()
        _stalled.value = false
        BmsLog.w("LINK", "失联 → 轮切到${next.label}（第 $stallRotations 次）")
        scope.launch {
            transport.preferChannel(next)
            transport.switchChannel(next)
        }
    }

    /**
     * 手动切换通道（设置 → 通信通道）：按设备记住选择，再断开重连到目标通道。
     * 未连接时只记偏好，下次连接生效；切换过程会短暂中断通信（随后自动重连 + 重新升权）。
     */
    suspend fun switchChannel(channel: BleChannel) {
        if (previewActive.value) {
            _connectHint.value = "正在预览快照，通道切换已停用（重启应用恢复）"
            return
        }
        val addr = MockBms.savedAddress
        AppStore.setChannelFor(addr, channel)
        BmsLog.i("CONN", "手动切换通道 → ${channel.label}（${channel.id}）")
        _connectHint.value = "正在切换到${channel.label}…"
        transport.preferChannel(channel)
        transport.switchChannel(channel)
    }

    /** 该设备记忆的通道（界面用；null = 未记忆，按默认顺序探测） */
    fun savedChannelFor(address: String? = MockBms.savedAddress): BleChannel? =
        AppStore.channelFor(address)

    /**
     * 记住某电量计的设备密码/匹配码（存 level 0），下次连接自动带上——
     * 蓝宝=6 位匹配码、陆行=无。保护板不使用这一槽。
     */
    fun setMeterSecret(address: String, secret: String) {
        AppStore.savePassword(address, 0, secret)
        if (MockBms.savedAddress == address) meter?.setSecret(secret)
        BmsLog.i("SEC", "已记住电量计密码（${address.takeLast(5)}），长度 ${secret.length}")
    }

    /** 当前设备已记住的电量计密码/匹配码（蓝宝 6 位数字或 12 位十六进制；无则空串） */
    fun meterSecretFor(address: String? = MockBms.savedAddress): String =
        address?.let { AppStore.loadPassword(it, 0) } ?: ""

    /**
     * 陆行寄存器探测（**只读 FC03**）：摸清设备的真实从机号与寄存器表，结果全部打到日志（tag=PROBE）。
     *
     * 顺序：① 官方 5 块原样复测 → ② 从机号 1..8 → ③ 地址 0..47 单寄存器 → ④ 从首个可用地址起长度递增。
     * 探测期间暂停常规轮询（避免抢应答）。约 22 秒；完成后在「设置 → 开发者」导出日志即可分析。
     */
    suspend fun probeMeterRegisters() {
        val real = realTransport
        val m = meter
        if (m !is LuXingProtocol) {
            BmsLog.w("PROBE", "当前不是陆行电量计模式，跳过探测")
            return
        }
        if (real == null || _linkState.value != LinkState.Connected) {
            BmsLog.w("PROBE", "链路未就绪，跳过探测")
            return
        }
        if (meterProbeActive) {
            BmsLog.w("PROBE", "探测进行中，忽略重复触发")
            return
        }
        meterProbeActive = true
        BmsLog.i("PROBE", "=== 陆行寄存器探测开始（只读 FC03）===")
        val mu = Mutex()
        val buf = mutableListOf<ByteArray>()
        val collectJob = scope.launch { real.incoming.collect { b -> mu.withLock { buf.add(b) } } }
        suspend fun ask(label: String, frame: ByteArray): ByteArray? {
            mu.withLock { buf.clear() }
            BmsLog.i("PROBE", "$label → ${BmsLog.hex(frame)}")
            runCatching { transport.write(frame) }
            delay(PROBE_STEP_MS)
            val replies = mu.withLock { val r = buf.toList(); buf.clear(); r }
            if (replies.isEmpty()) {
                BmsLog.i("PROBE", "$label ← (无应答)")
            } else {
                replies.forEach { BmsLog.i("PROBE", "$label ← ${BmsLog.hex(it)}") }
            }
            return replies.firstOrNull()
        }
        fun isOk(r: ByteArray?): Boolean = r != null && r.size >= 5 && ((r[1].toInt() and 0x80) == 0)
        try {
            // ① 协议自身的轮询帧（EM2APP = 4000/4092/4200/4152）
            m.nextPollFrames().forEachIndexed { i, fr -> ask("A命令${i + 1}", fr) }
            // ② 从机号 1..8
            val unitsOk = mutableListOf<Int>()
            for (u in 1..8) if (isOk(ask("B从机$u", modbusReadFrame(u, 0, 1)))) unitsOk.add(u)
            BmsLog.i("PROBE", "从机号扫描结果：$unitsOk")
            val unit = unitsOk.firstOrNull() ?: 1
            // ③ 地址扫描：EM2APP 的真实寄存器区在 4000 附近，扫 3995..4012
            val addrOk = mutableListOf<Int>()
            for (a in 3995..4012) if (isOk(ask("C地址$a", modbusReadFrame(unit, a, 1)))) addrOk.add(a)
            BmsLog.i("PROBE", "地址扫描结果（从机 $unit）：$addrOk")
            // ④ 长度试读
            val base = addrOk.firstOrNull() ?: 0
            listOf(1, 2, 4, 8, 16, 30, 50).forEach { c -> ask("D长度$c", modbusReadFrame(unit, base, c)) }
            BmsLog.i("PROBE", "=== 探测结束：可用从机=$unitsOk 可用地址=$addrOk ===")
        } finally {
            collectJob.cancel()
            meterProbeActive = false
        }
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
        /** 寄存器探测每步等待应答的时间（ms） */
        const val PROBE_STEP_MS = 320L
        /** 连后序列等首个实时帧的上限；等不到就跳过升权与参数区读回 */
        const val FIRST_FRAME_WAIT_MS = 10_000L
        /** BLE 扫描窗口：够扫到弱信号设备，又不至于让用户干等 */
        const val SCAN_WINDOW_MS = 20_000L
        /** 两条校验命令的最小间隔，防链路抖动时反复发 0x23 */
        const val RE_AUTH_COOLDOWN_MS = 60_000L
        /** 等 0x42 同帧的 0xFF 结果段落地（真机弱信号下两段可能差数百毫秒，取文档建议值） */
        const val WRITE_STATUS_GRACE_MS = 200L
        /** 写入已确认，但保存到设备存储失败或未确认。 */
        const val WRITE_SAVE_FAILED = -4
        /** 操作期间设备会话结束，后续帧不得发送到新设备。 */
        const val WRITE_SESSION_ENDED = -5
        /** 日志翻页大小：官方也是每批 10 条（`bmsLog.js` 的 `10 * logIndex`） */
        const val EVENT_LOG_PAGE = 10
        /** 读一条日志的等待上限：比轮询宽松（要读设备存储），但别让界面干等 */
        const val EVENT_LOG_TIMEOUT_MS = 1_500L
        /** 常驻监控通知的内容刷新间隔：实时数据 600ms 一拍，逐拍推给系统会把通知栏刷爆 */
        const val MONITOR_REFRESH_MS = 5_000L
    }

    private fun handleFrame(f: ParsedFrame) {
        // 只拼前缀；十六进制由 BmsLog.frame 在开关打开时才构造（原先无条件拼，每帧白付一次）
        BmsLog.frame("RX", "func=%02X reg=%d len=%d".fmt(f.func, f.reg, f.data.size), f.data.size) {
            BmsLog.hex(f.data.copyOfRange(0, minOf(24, f.data.size)))
        }
        when (f.func) {
            Proto.RSP_REALTIME -> {
                runCatching { RealtimeDecoder.decode(f.data) }.onSuccess { r ->
                    lastFrameAt = epochMillisNow()
                    MockBms.updateFromRealtime(r)
                    MockBms.connected.value = true
                    // 保护/告警的上升沿 → 后台时发通知（前台不发，界面本身有横幅）
                    onAlerts(r.protectBits, r.warnBits)
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
                activeWrite?.takeIf { it.session === session && it.address == f.reg }?.echoReceived = true
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
                val code = f.reg and 0xFF
                val write = activeWrite
                val reportedAddr = if (f.data.size >= 2)
                    (f.data[0].toInt() and 0xFF) or ((f.data[1].toInt() and 0xFF) shl 8)
                else null
                if (code != 11 && write != null && write.session === session &&
                    write.echoReceived && (reportedAddr == null || reportedAddr == write.address)) {
                    write.result = code
                    write.detail = if (f.data.size >= 4 && (code == 2 || code == 3)) {
                        val addr = reportedAddr!!
                        val limit = (f.data[2].toInt() and 0xFF) or ((f.data[3].toInt() and 0xFF) shl 8)
                        val def = ParamTable.byAddr(addr)
                        if (def != null) {
                            "%s %s=%.3f %s".fmt(
                                def.name, if (code == 2) "最小" else "最大", limit / def.scale, def.unit
                            )
                        } else "参数 0x%X 限值 %d".fmt(addr, limit)
                    } else null
                    MockBms.lastWriteResult.value = code
                    MockBms.lastWriteDetail.value = write.detail
                    if (ResultCodes.writeOk(code)) BmsLog.i("WRITE", "参数写入成功（0x${"%X".fmt(f.reg)}）")
                    else BmsLog.e("WRITE", "参数写入失败：${ResultCodes.writeResult(code)}（0x${"%X".fmt(f.reg)}）" +
                        (write.detail?.let { " · $it" } ?: ""))
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
    ): ParsedFrame? = requestInSession(frame, respondFunc, expectedFunc, expectedReg, timeoutMs, fromPoll, session)

    private suspend fun requestInSession(
        frame: ByteArray,
        respondFunc: Int,
        expectedFunc: Int = respondFunc,
        expectedReg: Int? = null,
        timeoutMs: Long = 2000,
        fromPoll: Boolean = false,
        expectedSession: Session,
    ): ParsedFrame? = cmdMutex.withLock {
        requestInSessionLocked(frame, respondFunc, expectedFunc, expectedReg, timeoutMs, fromPoll, expectedSession)
    }

    private suspend fun requestInSessionLocked(
        frame: ByteArray,
        respondFunc: Int,
        expectedFunc: Int = respondFunc,
        expectedReg: Int? = null,
        timeoutMs: Long = 2000,
        fromPoll: Boolean = false,
        expectedSession: Session,
    ): ParsedFrame? {
        currentCoroutineContext().ensureActive()
        val d = CompletableDeferred<ParsedFrame>()
        withLock(sessionLock) {
            checkSession(expectedSession)
            awaiting = d
            expectFunc = expectedFunc
            expectReg = expectedReg
        }
        try {
            BmsLog.frame("TX", "→", frame.size) { Frame.hexForLog(frame) }
            checkSession(expectedSession)
            transport.write(frame)
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            checkSession(expectedSession)
            if (r == null && !fromPoll) {
                BmsLog.w("TX", "命令无应答 func=%02X reg=%d（${timeoutMs}ms 超时）".fmt(respondFunc, expectedReg ?: -1))
            }
            return r
        } finally {
            withLock(sessionLock) {
                if (awaiting === d) {
                    awaiting = null
                    expectFunc = -1
                    expectReg = null
                }
            }
            d.cancel()
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
    suspend fun auth(level: Int, password: String): Int = try {
        authInSession(level, password, session)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        0
    }

    private suspend fun authInSession(level: Int, password: String, expected: Session): Int {
        val addr = ParamTable.slotAddr(level)
        val payload = PasswordCodec.encode(level, password)
        withLock(sessionLock) {
            checkSession(expected)
            lastAuthAt = epochMillisNow()
        }
        BmsLog.i("AUTH", "校验 $level 级密码（寄存器 $addr，${payload.size} 字节）")
        val r = requestInSession(Frame.auth(addr, payload), Proto.RSP_AUTH, expectedReg = addr,
            timeoutMs = 4000, expectedSession = expected)
        if (r == null) {
            BmsLog.w("AUTH", "$level 级校验无应答")
            return 0
        }
        if (r.data.size < 2) return 0
        val got = (r.data[0].toInt() and 0xFF) or ((r.data[1].toInt() and 0xFF) shl 8)
        return withLock(sessionLock) {
            checkSession(expected)
            if (got >= level && got > 0) {
                BmsLog.i("AUTH", "$level 级密码校验通过（设备权限 $got 级），已记住")
                expected.address?.let { AppStore.savePassword(it, level, password) }
                MockBms.plainPasswords.value = MockBms.plainPasswords.value + (level to password)
                MockBms.markPasswordSaved(level, true)
                level
            } else {
                BmsLog.w("AUTH", "$level 级密码校验失败（设备权限 $got 级），已从密码库移除")
                expected.address?.let { AppStore.removePassword(it, level) }
                MockBms.plainPasswords.value = MockBms.plainPasswords.value - level
                MockBms.markPasswordSaved(level, false)
                0
            }
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
        val expected = session
        reAuthJob = scope.launch {
            delay(300)
            checkSession(expected)
            val lv = try { authInSession(target, pw, expected) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { 0 }
            withLock(sessionLock) {
                checkSession(expected)
                if (lv > 0) {
                    MockBms.applyPermission(lv)
                    BmsLog.i("AUTH", "权限回落，已静默重升到 $lv 级")
                } else {
                    BmsLog.w("AUTH", "权限回落重升失败（$target 级密码未通过，已从密码库移除）")
                }
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
    fun refreshParams(force: Boolean = false) = withLock(sessionLock) {
        if (realTransport == null || meter != null || previewActive.value || manualDisconnect.value || !acceptingFrames) return@withLock
        if (readJob?.isActive == true) return@withLock
        if (!force && paramsAttempted) return@withLock
        val expected = session
        paramsAttempted = true
        readJob = scope.launch {
            withLock(sessionLock) {
                checkSession(expected)
                MockBms.paramsReading.value = true
            }
            try {
                val acc = HashMap<Int, Int>()
                for ((start, bytes) in paramBlocks) {
                    val r = requestInSession(Frame.readParam(start, bytes), Proto.RSP_PARAM, expectedReg = start,
                        timeoutMs = 1500, expectedSession = expected)
                    if (r != null && r.func == Proto.RSP_PARAM) {
                        var k = 0
                        while (k + 1 < r.data.size) {
                            acc[start + k] = (r.data[k].toInt() and 0xFF) or ((r.data[k + 1].toInt() and 0xFF) shl 8)
                            k += 2
                        }
                    }
                    delay(120)
                }
                val id = HashMap<String, String>()
                for ((key, spec) in identityBlocks) {
                    val (addr, len) = spec
                    val r = requestInSession(Frame.readParam(addr, len), Proto.RSP_PARAM, expectedReg = addr,
                        timeoutMs = 1500, expectedSession = expected)
                    if (r != null && r.func == Proto.RSP_PARAM && r.data.isNotEmpty()) id[key] = ascii(r.data)
                    delay(120)
                }
                withLock(sessionLock) {
                    checkSession(expected)
                    if (acc.isNotEmpty()) MockBms.liveParams.value = acc
                    if (id.isNotEmpty()) MockBms.identity.value = id
                    BmsLog.i("PARAM", "参数区读回 ${acc.size} 项，身份区 ${id.size} 项")
                    if (acc.isNotEmpty() || id.isNotEmpty()) {
                        recordSnapshot()
                        MockBms.paramsFromCache.value = false
                        persistParamsCache()
                    }
                }
            } finally {
                withLock(sessionLock) {
                    if (session === expected) MockBms.paramsReading.value = false
                }
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

    /* ---------------- 后台策略：暂停轮询 / 常驻监控 / 告警通知 ---------------- */

    private val alarmNotifier = AlarmNotifier()

    /**
     * 监控通知的三个变量由三条协程共写（轮询循环、链路态收集、前后台收集），
     * 用同一把锁串行化——不然"检查要不要更新 + 更新"之间会被插进来，可能重复推同一条通知
     */
    private val monitorLock = PlatformLock()

    /** 常驻监控通知当前文案（null = 未在监控）；用于去重，避免每拍都推给系统 */
    private var monitorText: String? = null
    private var monitorUpdatedAt = 0L

    /** 本次会话是否已就失联发过通知（恢复后重置，避免反复提醒） */
    private var linkAlerted = false

    /**
     * 设置页拨「后台监控」开关：写偏好 + 立刻反映（开的时候顺手要一次通知权限）。
     *
     * 关掉时若之前正在监控，这里会立刻撤掉常驻通知——不能让用户以为还在看着。
     */
    fun setBackgroundMonitor(on: Boolean) {
        AppStore.backgroundMonitor = on
        if (on) Notifications.ensurePermission()
        refreshMonitorNotification(force = true)
        BmsLog.i("APP", if (on) "后台监控已开启（退到后台继续轮询，保护触发会通知）" else "后台监控已关闭（退到后台暂停轮询）")
    }

    /**
     * 常驻监控通知：**开关 + 平台支持 + 链路就绪**三者都满足才显示。
     *
     * 内容每 [MONITOR_REFRESH_MS] 最多刷一次：实时数据 600ms 一拍，逐拍推给系统会把通知栏刷爆。
     */
    private fun refreshMonitorNotification(force: Boolean = false) {
        withLock(monitorLock) {
            val on = AppStore.backgroundMonitor && Notifications.supported &&
                    _linkState.value == LinkState.Connected
            if (!on) {
                if (monitorText != null) {
                    monitorText = null
                    Notifications.updateMonitoring(null)
                }
                return
            }
            val now = epochMillisNow()
            if (!force && now - monitorUpdatedAt < MONITOR_REFRESH_MS) return
            monitorUpdatedAt = now
            val st = MockBms.status.value
            val text = buildString {
                append(MockBms.deviceLabel)
                if (st.hasData) {
                    append(" · ")
                    append(st.soc)
                    append("% · %.1fV".fmt(st.totalVoltage))
                } else {
                    append(" · 等待数据")
                }
            }
            if (text != monitorText) {
                monitorText = text
                Notifications.updateMonitoring(text)
            }
        }
    }

    /**
     * 保护/告警上升沿 → 通知。
     *
     * **只在人看不到界面时发**：前台时仪表盘本身就有告警卡与横幅，再推一条系统通知
     * 只会让"哪边才是最新的"变成新问题。
     */
    private fun onAlerts(protect: ULong, warn: ULong) {
        val fired = alarmNotifier.onFrame(protect, warn, epochMillisNow())
        if (fired.isEmpty()) return
        if (AppForeground.active.value) return
        if (!AppStore.backgroundMonitor || !AppStore.alarmNotify || !Notifications.supported) return
        val title = if (fired.any { it.isProtect }) "电池保护触发" else "电池告警"
        val names = fired.joinToString(" / ") { it.name }
        val shown = if (fired.size > 3) fired.take(3).joinToString(" / ") { it.name } + " 等 ${fired.size} 项" else names
        BmsLog.w("ALERT", "$title：$shown")
        Notifications.alert(Notifications.ID_ALERT, title, "${MockBms.deviceLabel} · $shown")
    }

    /** 失联通知（同样只在前台不可见时发）；到底是不是"人在看"由调用方判定 */
    private fun notifyLinkLost(sinceMs: Long) {
        if (linkAlerted) return
        if (AppForeground.active.value) return
        if (!AppStore.backgroundMonitor || !AppStore.alarmNotify || !Notifications.supported) return
        linkAlerted = true
        Notifications.alert(
            Notifications.ID_ALERT_LINK, "设备失联",
            "${MockBms.deviceLabel} · 实时数据已中断 ${sinceMs / 1000}s（信号问题或设备休眠）",
        )
    }

    /* ---------------- 设备历史事件日志（0x03→0x13）与保护计数（0x05→0x15） ---------------- */

    /**
     * 一次翻页读取的结果。
     *
     * @param records 本次拿到的记录（按序号升序）
     * @param nextIndex 下一页应从哪个序号开始（失败时**不推进**，重试不会跳记录）
     * @param atEnd 设备已无更早的记录（读到空记录）
     * @param failed 中途超时/会话结束：records 是已拿到的部分
     */
    data class EventLogPage(
        val records: List<io.github.lswlc33.maibms.protocol.EventLogRecord>,
        val nextIndex: Int,
        val atEnd: Boolean,
        val failed: Boolean,
    )

    /**
     * 读一批历史日志。
     *
     * 请求序号 = 已取到的条数（官方做法：`logList.length`），一条一条回；空记录即到底。
     * 走 [requestInSession]，与实时轮询共用 `cmdMutex` 天然互斥——**不暂停轮询**（官方文档
     * §8.7 也确认了 `0x13` 与轮询可共存），所以不会影响失联判定。
     *
     * `expectedReg = null`：序号取帧头回显，但「设备是否回显」文档与源码两说，
     * 只按功能码匹配更稳（同序号迟到的应答也不会被误杀）。
     */
    suspend fun readEventLog(fromIndex: Int, batch: Int = EVENT_LOG_PAGE): EventLogPage {
        val expected = session
        val out = ArrayList<io.github.lswlc33.maibms.protocol.EventLogRecord>(batch)
        var idx = fromIndex
        var atEnd = false
        var failed = false
        try {
            while (out.size < batch) {
                val r = requestInSession(
                    io.github.lswlc33.maibms.protocol.EventLogCodec.request(idx), Proto.RSP_LOG,
                    expectedReg = null, timeoutMs = EVENT_LOG_TIMEOUT_MS, expectedSession = expected,
                )
                if (r == null) {
                    // 无应答：可能是设备不支持/到边界后不回空记录。不推进序号，下次可原样重试
                    BmsLog.w("LOG", "日志第 $idx 条无应答（${EVENT_LOG_TIMEOUT_MS}ms 超时）")
                    failed = true
                    break
                }
                val rec = io.github.lswlc33.maibms.protocol.EventLogCodec.parse(r)
                if (rec == null) {
                    BmsLog.i("LOG", "日志读取到底：已取 ${fromIndex + out.size} 条（第 $idx 条为空记录）")
                    atEnd = true
                    break
                }
                out += rec
                idx++
            }
        } catch (e: CancellationException) {
            // 会话结束（换设备/断开/进入预览）走这里：把已拿到的吐出去，界面不至于一片空白
            currentCoroutineContext().ensureActive()
            failed = true
        }
        return EventLogPage(out, idx, atEnd, failed)
    }

    /** 读 20 个保护动作计数；设备无应答返回 null */
    suspend fun readProtectCounters(): List<Int>? = try {
        val r = requestInSession(
            io.github.lswlc33.maibms.protocol.EventLogCodec.countersRequest(), Proto.RSP_PROTECT_NUM,
            expectedReg = null, timeoutMs = EVENT_LOG_TIMEOUT_MS, expectedSession = session,
        )
        r?.let { io.github.lswlc33.maibms.protocol.EventLogCodec.parseCounters(it.data) }?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }

    /**
     * 重读日志：清空后从头取第一批（外加计数器）。界面每次进页/点刷新走它。
     *
     * @return 成功条数；-1 = 失败（原因写在 [MockBms.eventLogError]）
     */
    suspend fun refreshEventLog(): Int {
        if (MockBms.eventLogLoading.value) return -1
        MockBms.eventLogLoading.value = true
        MockBms.eventLogError.value = null
        return try {
            val page = readEventLog(0)
            // 读取失败时**保留已显示的那一份**（只挂横幅说明），不要把用户正在看的历史清空——
            // 已读到的记录仍然有效，清空只会让人觉得"设备日志没了"
            if (page.records.isNotEmpty() || !page.failed) {
                MockBms.eventLog.value = page.records
                MockBms.eventLogAtEnd.value = page.atEnd
            }
            if (page.failed) MockBms.eventLogError.value = "读取中断：设备未应答（已取到 ${page.records.size} 条）"
            readProtectCounters()?.let { MockBms.protectCounters.value = it }
            page.records.size
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            -1
        } finally {
            MockBms.eventLogLoading.value = false
        }
    }

    /** 继续往后取一批（按序号追加，不去重——序号本身唯一） */
    suspend fun loadMoreEventLog(): Int {
        if (MockBms.eventLogLoading.value || MockBms.eventLogAtEnd.value) return -1
        MockBms.eventLogLoading.value = true
        MockBms.eventLogError.value = null
        return try {
            val have = MockBms.eventLog.value
            val page = readEventLog(have.size)
            MockBms.eventLog.value = have + page.records
            MockBms.eventLogAtEnd.value = page.atEnd
            if (page.failed) MockBms.eventLogError.value = "读取中断：设备未应答"
            page.records.size
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            -1
        } finally {
            MockBms.eventLogLoading.value = false
        }
    }

    /** 控制命令（返回结果码，1=成功） */
    suspend fun control(cmd: Int): Int = try {
        controlInSession(cmd, session)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        0
    }

    private suspend fun controlInSession(cmd: Int, expected: Session): Int {
        BmsLog.i("CTRL", "发送控制命令：${ControlCmd.name(cmd)}（0x%02X）".fmt(cmd))
        val r = requestInSession(Frame.control(cmd), Proto.RSP_CONTROL, expectedReg = cmd, expectedSession = expected)
        if (r == null) {
            BmsLog.e("CTRL", "控制命令无应答：${ControlCmd.name(cmd)}")
            return 0
        }
        return r.data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
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
        val expected = session
        return try {
            writeMutex.withLock { writeParamInSession(addr, rawValue, expected) }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            BmsLog.w("WRITE", "写参数中止：设备会话已结束，未向新设备继续写入")
            WRITE_SESSION_ENDED
        }
    }

    private suspend fun writeParamInSession(addr: Int, rawValue: Int, expected: Session): Int {
        val def = ParamTable.byAddr(addr)
        val label = "0x${"%X".fmt(addr)}" + (def?.let { "（${it.name}）" } ?: "")
        withLock(sessionLock) {
            checkSession(expected)
            MockBms.lastWriteResult.value = -1
            MockBms.lastWriteDetail.value = null
        }
        BmsLog.i("WRITE", "写参数 $label = $rawValue")
        val writes = if (addr in ParamTable.u32Addrs) {
            listOf(
                addr to Frame.writeParam(addr, rawValue and 0xFFFF),
                (addr + 2) to Frame.writeParam(addr + 2, (rawValue ushr 16) and 0xFFFF),
            )
        } else listOf(addr to Frame.writeParam(addr, rawValue))

        var code = -1
        var needsReadback = addr in ParamTable.u32Addrs
        for ((a, frame) in writes) {
            val result = cmdMutex.withLock {
                val attempt = WriteAttempt(expected, a)
                withLock(sessionLock) {
                    checkSession(expected)
                    activeWrite = attempt
                }
                try {
                    val r = requestInSessionLocked(frame, Proto.RSP_WRITE, expectedReg = a,
                        timeoutMs = 2500, expectedSession = expected)
                    if (r == null) null else {
                        delay(WRITE_STATUS_GRACE_MS)
                        withLock(sessionLock) {
                            checkSession(expected)
                            attempt.result to attempt.detail
                        }
                    }
                } finally {
                    withLock(sessionLock) { if (activeWrite === attempt) activeWrite = null }
                }
            }
            if (result == null) {
                return publishWriteResult(expected, -2, "设备未应答（链路差或参数不可写），本次未保存")
            }
            code = result.first
            if (code == -1) needsReadback = true
            else if (!ResultCodes.writeOk(code)) return publishWriteResult(expected, code, result.second)
        }

        if (needsReadback) {
            val want = expectedRaw(addr, rawValue)
            val actual = readBack(addr, expected)
            if (actual != want) {
                val detail = if (actual == null) "回读无应答：写入结果未知，本次未保存，请重读确认"
                    else "回读值不一致（期望 $want / 实际 $actual）：写入未完整生效，本次未保存"
                return publishWriteResult(expected, -3, detail)
            }
            code = 10
            publishWriteResult(expected, code, "已回读确认写入（$label）")
        } else publishWriteResult(expected, code, null)

        BmsLog.i("WRITE", "写参数 $label 已确认，自动保存（51/07）")
        val saved = requestInSession(Frame.saveParams(), Proto.RSP_CONTROL, expectedReg = ControlCmd.SAVE_PARAMS,
            expectedSession = expected)
        val saveCode = saved?.data?.firstOrNull()?.toInt()?.and(0xFF)
        if (saveCode != 1) {
            val reason = if (saveCode == null) "设备未确认保存" else ResultCodes.controlResult(saveCode)
            return publishWriteResult(expected, WRITE_SAVE_FAILED,
                "参数已写入临时区，但保存失败或未确认（$reason）；掉电可能丢失，请重读并重新保存")
        }
        withLock(sessionLock) {
            checkSession(expected)
            persistParamsCache()
        }
        return code
    }

    private fun publishWriteResult(expected: Session, code: Int, detail: String?): Int = withLock(sessionLock) {
        checkSession(expected)
        MockBms.lastWriteResult.value = code
        MockBms.lastWriteDetail.value = detail
        if (!ResultCodes.writeOk(code)) BmsLog.e("WRITE", "写参数失败或未确认：${detail ?: ResultCodes.writeResult(code)}")
        code
    }

    /** 期望落库的原始值：u32 取 32 位、u16 取低 16 位（与设备回读口径一致） */
    private fun expectedRaw(addr: Int, rawValue: Int): Long =
        if (addr in ParamTable.u32Addrs) rawValue.toLong() and 0xFFFFFFFFL else (rawValue and 0xFFFF).toLong()

    /** 回读参数做校验：u32 类读 4 字节拼 32 位；读不到返回 null */
    private suspend fun readBack(addr: Int, expected: Session): Long? {
        val bytes = if (addr in ParamTable.u32Addrs) 4 else 2
        val r = requestInSession(Frame.readParam(addr, bytes), Proto.RSP_PARAM, expectedReg = addr,
            timeoutMs = 1500, expectedSession = expected)
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
