package io.github.lswlc33.maibms.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** 单体电压格 */
@Serializable
data class CellV(val index: Int, val volt: Double, val isMax: Boolean = false, val isMin: Boolean = false, val balancing: Boolean = false)

/** 实时数据（全部来自 Mock，模拟 0x11 解码结果） */
/** 实时数据（全部来自 0x11 解码回填；默认即「未连接」空态，不含任何演示数据）。可整体冻结进快照 */
@Serializable
data class BmsStatus(
    val deviceName: String = "--",
    val runtime: String = "--",
    val swVersion: String = "--",
    val hwVersion: String = "--",
    val batteryType: String = "--",
    val connected: Boolean = false,
    /** 本次会话是否至少收到过一帧实时数据：未收到时所有数值一律显示 "--"，不拿 0 冒充读数 */
    val hasData: Boolean = false,
    val permissionLevel: Int = 0,
    val soc: Int = 0,
    val totalCapAh: Double = 0.0,
    val remainCapAh: Double = 0.0,
    val soh: Int = 0,
    val cycles: Int = 0,
    val totalVoltage: Double = 0.0,
    val current: Double = 0.0,
    val power: Int = 0,
    /** 本次充电时长（实时帧扩展段 78，秒；老固件无扩展段恒 0） */
    val thisChargeSec: Long = 0,
    /** 上次充电间隔（扩展段 82，秒） */
    val lastChargeGapSec: Long = 0,
    /** 充电剩余时间（扩展段 86，分钟；0=设备未报，界面显示 --） */
    val remainChargeMin: Int = 0,
    /** 放电剩余时间（扩展段 88，分钟；0=设备未报） */
    val remainDischargeMin: Int = 0,
    val maxCell: String = "--",
    val minCell: String = "--",
    val avgCell: String = "--",
    val deltaCell: String = "--",
    val totalCycleAh: Int = 0,
    val battState: String = "等待数据…",
    val chMos: String = "--",
    val disMos: String = "--",
    val balance: String = "--",
    /**
     * MOS/均衡的**原始状态码**（实时帧尾部偏移 12/13/14）。开关读数只看 `== 1`，
     * 但码值本身带原因（`MosStateDict`：2=单体过压、12=开启失败…）——只存“开/关”会把
     * 断开原因整块丢掉。这里存原始码，界面按需查表。
     */
    val chMosCode: Int = 0,
    val disMosCode: Int = 0,
    val balanceCode: Int = 0,
    val protectList: List<String> = emptyList(),
    val alarmList: List<String> = emptyList(),
    /** 带真实位号的版本（详情弹窗显示 bit N 用） */
    val protectPairs: List<Pair<Int, String>> = emptyList(),
    val alarmPairs: List<Pair<Int, String>> = emptyList(),
    /** 告警位域里的**状态类**位（充电器接入/MOS 开/待机/加热…）：不进告警列表，详情弹窗单列一段 */
    val statePairs: List<Pair<Int, String>> = emptyList(),
    val temps: List<Pair<String, Double>> = emptyList(),
    val cells: List<CellV> = emptyList(),
    val trendCurrent: List<Float> = emptyList(),
    val trendVolt: List<Float> = emptyList(),
    /** 电量计中继的保护板品牌（如"蚂蚁保护板"）；保护板直连/未知 = "--" */
    val relayBoardBrand: String = "--",
    /** 电量计中继的保护板的蓝牙名（不是本机的设备名，见 MeterReading.relayBoardName）；未知 = "--" */
    val relayBoardName: String = "--",
    /** 电量计中继的保护板 MAC；未知 = "--" */
    val relayBoardMac: String = "--",
)

/** 参数条目（配置页） */
data class ParamItem(
    val name: String,
    val value: String,
    val unit: String,
    val addr: String,
    val scale: String,
    val range: String,
)

/** 设备密码条目 */
data class DevicePassword(val level: Int, val masked: String?, val isCurrent: Boolean = false)

/** 全局 Mock 仓库：UI 试验全部数据来源，无后端 */
object MockBms {
    private val _status = MutableStateFlow(BmsStatus())
    val status: StateFlow<BmsStatus> = _status

    /**
     * 运行权限等级（从 [status] 派生，只在真的变了才发射）。
     *
     * 存在的理由：应用根、配置页、控制页**只需要这一个标量**（判断可写/只读、显示徽章），
     * 此前它们收的是整份 `status`——而 status 每拍都换新对象，于是每 600ms 就把
     * 「根组合 + 当前屏 + 上百行参数」整片重组一次，哪怕权限一直是 3 级。
     * 派生流让这些地方只在权限**真的变化**时重组（升权、权限回落、换设备）。
     */
    val permissionLevel: Flow<Int> = _status.map { it.permissionLevel }.distinctUntilChanged()

    // ---- 数据回填（BmsRepository 解码后写入；UI 只读这两个流） ----
    val connected = MutableStateFlow(false)
    val usingRealBle = MutableStateFlow(false)
    var savedAddress: String? = null
    val lastWriteResult = MutableStateFlow(-1)      // 写参数结果码（0xFF 附加段，-1=尚无）
    /** 写参数失败时设备附带的信息（参数地址 + 限值） */
    val lastWriteDetail = MutableStateFlow<String?>(null)
    val lastControlResult = MutableStateFlow<Pair<Int, Int>?>(null)  // 命令号 to 结果码，1=成功
    /** 各等级已记住的明文密码（按设备从本地恢复，出厂为空） */
    val plainPasswords = MutableStateFlow<Map<Int, String>>(emptyMap())

    // ---- 真机参数区（0x02 读回）与身份区（528/576/620/636/262） ----
    /** 参数原始值：字节地址 → u16（读回后按 ParamTable 的倍率换算显示） */
    val liveParams = MutableStateFlow<Map<Int, Int>>(emptyMap())
    /** 身份区字符串：boot / codeKey / hwVersion / swVersion / packId */
    val identity = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 参数区读取中（界面显示进度） */
    val paramsReading = MutableStateFlow(false)
    /** 当前连接的 BLE 设备名（真机模式用于顶栏「设备名称」） */
    val connectedDeviceName = MutableStateFlow<String?>(null)

    /** 界面显示用的设备名：真机优先真实 BLE 名；设备广播名常带尾部空格，统一 trim */
    val deviceLabel: String get() = connectedDeviceName.value?.trim() ?: "未命名设备"

    fun applyPermission(level: Int) {
        _status.value = _status.value.copy(permissionLevel = level)
        if (level > 0) passwords.value = passwords.value.map { it.copy(isCurrent = it.level == level) }
    }

    /** 保存/清除密码后同步密码库条目的掩码显示（PasswordScreen 的「已记住/未设置」） */
    fun markPasswordSaved(level: Int, saved: Boolean) {
        passwords.value = passwords.value.map {
            if (it.level == level) it.copy(masked = if (saved) "••••••••" else null) else it
        }
    }

    /** 链路断开：状态流里也要落 connected=false，否则界面仍按「已连接」渲染（横幅/开关会自相矛盾） */
    fun markDisconnected() {
        connected.value = false
        if (_status.value.connected) {
            _status.value = _status.value.copy(connected = false, battState = "等待数据…")
        }
    }

    /** 冻结当前全部数据为一张快照（connected 强制为 false：快照天然离线）。 */
    fun captureSnapshot(id: Long, timeLabel: String): BmsSnapshot = BmsSnapshot(
        id = id,
        deviceAddress = savedAddress,
        deviceName = sessionDeviceName(),
        timeLabel = timeLabel,
        status = _status.value.copy(connected = false),
        liveParams = liveParams.value,
        identity = identity.value,
    )

    /**
     * 快照上的设备名 = **本机连的那台设备**（电量计就是电量计自己的名字，保护板就是板名）：
     * 连接时写入的广播名 → 设备档案显示名 → 上次记忆名 → 地址兜底。
     *
     * 绝不能取电量计协议里的那些名字：90 帧的 `BoundBmsBtName` 是**被中继的那块保护板**的
     * 蓝牙名（真机上就是另一台设备的广播名），拿来当快照名会让人以为这记录是那块板建的。
     */
    private fun sessionDeviceName(): String {
        connectedDeviceName.value?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        DeviceProfiles.find(savedAddress)?.displayName?.let { return it }
        AppStore.savedDeviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return savedAddress ?: "--"
    }

    /**
     * 载入快照供预览：回填 status/liveParams/identity/设备名。
     * 刻意不碰 connected 流与三个控制开关——预览是纯展示态，
     * 连接标志仍为 false，配置页/控制按钮走既有的「未连接只读」路径。
     */
    fun restoreSnapshot(s: BmsSnapshot) {
        liveParams.value = s.liveParams
        identity.value = s.identity
        connectedDeviceName.value = s.deviceName.ifBlank { null }
        paramsFromCache.value = false   // 快照是快照：横幅走预览文案，不标「缓存」
        // 历史日志是「设备此刻读出来的」，不属于快照内容：预览时清掉，
        // 免得在预览态下把真机会话读到的记录当成这张快照的历史（退出预览再清一次是同一口径）
        clearEventLog()
        _status.value = s.status
    }

    /**
     * 配置缓存标志：true = 当前 liveParams/identity 是自动重连设备「上次成功连接」的缓存
     * （未连接时的离线展示），配置页据此显示缓存文案；实时读回/快照载入都会清掉它。
     */
    val paramsFromCache = MutableStateFlow(false)

    // ---- 设备历史事件日志（0x03→0x13）与保护动作计数（0x05→0x15） ----
    // 换设备/退出预览都会清空（见 clearForRealDevice / restoreSnapshot）：日志是**设备的**，
    // 留着上一台的记录会被当成当前这台的历史——与快照、参数区同一条隔离原则。
    val eventLog = MutableStateFlow<List<io.github.lswlc33.maibms.protocol.EventLogRecord>>(emptyList())
    val eventLogLoading = MutableStateFlow(false)
    /** 设备侧已无更早的记录（读到底） */
    val eventLogAtEnd = MutableStateFlow(false)
    val protectCounters = MutableStateFlow<List<Int>>(emptyList())
    /** 上一次读取失败的原因（界面提示用）；null = 没问题 */
    val eventLogError = MutableStateFlow<String?>(null)

    private fun clearEventLog() {
        eventLog.value = emptyList()
        eventLogAtEnd.value = false
        eventLogLoading.value = false
        protectCounters.value = emptyList()
        eventLogError.value = null
    }

    /** 载入某设备的配置缓存到会话（未连接时的离线展示）；无缓存返回 false */
    fun loadParamsCache(cache: AppStore.ParamsCache) {
        liveParams.value = cache.params
        identity.value = cache.identity
        paramsFromCache.value = true
    }

    /**
     * 切到真机前清空上一台设备的数据：设备身份/版本来自参数区（0x02 的 528/620/636），
     * 趋势曲线目前只有 Mock 会生成——留着上一条假曲线会让人把假数据当真数据。
     * 退出快照预览也走这里：连预览里回填的设备名一起清，否则大卡仍显示快照设备。
     */
    fun clearForRealDevice() {
        liveParams.value = emptyMap()
        identity.value = emptyMap()
        paramsFromCache.value = false   // 会话数据已清空，「缓存」标记自然也不成立
        connectedDeviceName.value = null
        clearTrend()
        clearEventLog()
        _status.value = BmsStatus(
            deviceName = "--", runtime = "--", swVersion = "--", hwVersion = "--",
            batteryType = "--", connected = false, permissionLevel = 0, soc = 0,
            totalCapAh = 0.0, remainCapAh = 0.0, soh = 0, cycles = 0,
            totalVoltage = 0.0, current = 0.0, power = 0,
            maxCell = "--", minCell = "--", avgCell = "--", deltaCell = "--",
            totalCycleAh = 0, battState = "等待数据…", chMos = "--", disMos = "--", balance = "--",
            protectList = emptyList(), alarmList = emptyList(),
            temps = emptyList(), cells = emptyList(),
            trendCurrent = emptyList(), trendVolt = emptyList(),
        )
        connected.value = false
    }

    private fun fmtRuntime(sec: Long): String {
        val d = sec / 86400
        val hms = "%02d:%02d:%02d".fmt(sec % 86400 / 3600, sec % 3600 / 60, sec % 60)
        return if (d > 0) "${d}天 $hms" else hms
    }

    // ---- 趋势曲线历史（真机/演示共用：都由 updateFromRealtime 喂） ----
    private val histVolt = ArrayDeque<Double>()
    private val histCurr = ArrayDeque<Double>()
    private const val TREND_POINTS = 60   // 900ms 一拍 ≈ 近 1 分钟

    private fun pushTrend(v: Double, c: Double) {
        histVolt.addLast(v); if (histVolt.size > TREND_POINTS) histVolt.removeFirst()
        histCurr.addLast(c); if (histCurr.size > TREND_POINTS) histCurr.removeFirst()
    }

    /** 归一化到 0..1 供折线绘制；不足 2 点返回空（界面不画假线） */
    private fun norm(data: List<Double>, includeZero: Boolean): List<Float> {
        if (data.size < 2) return emptyList()
        var lo = data.min(); var hi = data.max()
        if (includeZero) { lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0) }
        val span = (hi - lo).coerceAtLeast(1e-6)
        return data.map { ((it - lo) / span).toFloat() }
    }

    fun clearTrend() { histVolt.clear(); histCurr.clear() }

    /**
     * 内容相同就复用旧实例。
     *
     * Compose 对 `List` 参数用的是**引用相等**（列表被视作不稳定类型），每拍新建一份等值列表
     * 会让下游卡片全部重组——20~32 格的单体网格最明显。复用实例后，"这一拍值没变"的那些卡片
     * 可以直接跳过重组（温度、保护/告警列表多数拍都不变）。
     */
    private fun <T> reuse(prev: List<T>, next: List<T>): List<T> = if (prev == next) prev else next

    /** 0x11 解码结果 → UI 状态（每 600ms 轮询回填） */
    fun updateFromRealtime(r: io.github.lswlc33.maibms.protocol.RealtimeDecoder.Result) {
        val balBits = r.balanceBits.toLong()
        pushTrend(r.totalVoltage, r.current)
        // MOS/均衡开关的显示状态跟实时帧走（原先停在写死初值，控制页会与实际设备不符）
        chargeSwitch.value = r.chMos == 1
        dischargeSwitch.value = r.disMos == 1
        balanceSwitch.value = r.balanceState != 0
        val cur = _status.value
        val newCells = r.cells.mapIndexed { i, v ->
            CellV(
                index = i + 1, volt = v,
                isMax = i + 1 == r.maxCellIdx, isMin = i + 1 == r.minCellIdx,
                balancing = (balBits shr i) and 1L == 1L,
            )
        }
        _status.value = cur.copy(
            hasData = true,
            runtime = fmtRuntime(r.runtimeSec),
            permissionLevel = r.permission,
            soc = r.soc,
            totalCapAh = r.physicalCapAh,
            remainCapAh = r.remainCapAh,
            soh = r.soh,
            totalVoltage = r.totalVoltage,
            current = r.current,
            power = r.powerW,
            thisChargeSec = r.thisChargeSec,
            lastChargeGapSec = r.lastChargeGapSec,
            remainChargeMin = r.remainChargeMin,
            remainDischargeMin = r.remainDischargeMin,
            maxCell = "%.3f".fmt(r.maxCellV),
            minCell = "%.3f".fmt(r.minCellV),
            avgCell = "%.3f".fmt(r.avgCellV),
            deltaCell = "%.3f".fmt(r.deltaCellV),
            totalCycleAh = r.cycleCapAh.toInt(),
            battState = io.github.lswlc33.maibms.protocol.RealtimeDecoder.battStateText(r.battStateCode),
            chMos = if (r.chMos == 1) "开启" else "关闭",
            disMos = if (r.disMos == 1) "开启" else "关闭",
            balance = if (r.balanceState != 0) "均衡中" else "关闭",
            // 原始状态码一并存下：开关读数只说明“开没开”，码值才说明“为什么没开”（见 MosStateDict）
            chMosCode = r.chMos,
            disMosCode = r.disMos,
            balanceCode = r.balanceState,
            protectList = reuse(cur.protectList, io.github.lswlc33.maibms.protocol.BitDict.decode(r.protectBits, io.github.lswlc33.maibms.protocol.BitDict.protectNames)),
            alarmList = reuse(cur.alarmList, io.github.lswlc33.maibms.protocol.BitDict.displayAlarmList(r.warnBits, r.chMos == 1, r.disMos == 1)),
            protectPairs = reuse(cur.protectPairs, io.github.lswlc33.maibms.protocol.BitDict.decodePairs(r.protectBits, io.github.lswlc33.maibms.protocol.BitDict.protectNames)),
            alarmPairs = reuse(cur.alarmPairs, io.github.lswlc33.maibms.protocol.BitDict.decodeForDisplayPairs(r.warnBits, io.github.lswlc33.maibms.protocol.BitDict.warnNames)),
            statePairs = reuse(cur.statePairs, io.github.lswlc33.maibms.protocol.BitDict.statePairs(r.warnBits, io.github.lswlc33.maibms.protocol.BitDict.warnNames)),
            temps = reuse(cur.temps, listOf("MOS" to r.mosTemp, "均衡" to r.balanceTemp) +
                    r.temps.mapIndexed { i, t -> "T${i + 1}" to t }),
            cells = reuse(cur.cells, newCells),
            batteryType = io.github.lswlc33.maibms.protocol.RealtimeDecoder.batteryTypeText(r.batteryType),
            connected = true,
            cycles = cur.cycles,
            trendCurrent = norm(histCurr.toList(), includeZero = true),
            trendVolt = norm(histVolt.toList(), includeZero = false),
            // 设备名/版本不在实时帧里：设备名用 BLE 名，版本来自身份区（0x02 读回）
            deviceName = connectedDeviceName.value ?: cur.deviceName,
            swVersion = identity.value["swVersion"]?.ifBlank { null } ?: cur.swVersion,
            hwVersion = identity.value["hwVersion"]?.ifBlank { null } ?: cur.hwVersion,
        )
        connected.value = true
    }

    /** 当前设备密码库（按设备存储，连接后由 restorePasswords 填充） */    val passwords = MutableStateFlow(
        listOf(
            DevicePassword(1, null),
            DevicePassword(2, null),
            DevicePassword(3, null),
            DevicePassword(4, null),
            DevicePassword(5, null),
            DevicePassword(9, null),
        )
    )
    val autoUpgradeLevel = MutableStateFlow(3)

    /** 开关状态（跟从 0x61 控制命令与实时帧的 MOS 位） */
    val chargeSwitch = MutableStateFlow(false)
    val dischargeSwitch = MutableStateFlow(true)
    val balanceSwitch = MutableStateFlow(false)

    /** 当前设备能力位（电量计/保护板不同）；首页按它显隐卡片 */
    val caps = MutableStateFlow(DeviceCapabilities.Board)

    /**
     * 电量计读数 → UI 状态（增量合并：只覆盖本次提供的字段，其余保留）。
     * 与 [updateFromRealtime] 并列，由 [BmsRepository] 在电量计模式下调用。
     */
    fun applyMeterReading(r: io.github.lswlc33.maibms.protocol.meter.MeterReading) {
        if (r.isEmpty) return
        r.totalVoltage?.let { v -> r.current?.let { c -> pushTrend(v, c) } }
        r.chMos?.let { chargeSwitch.value = it }
        r.disMos?.let { dischargeSwitch.value = it }
        r.balancing?.let { balanceSwitch.value = it }
        val cur = _status.value
        val newCells = r.cells?.mapIndexed { i, v -> CellV(index = i + 1, volt = v, isMax = false, isMin = false, balancing = false) }
        val maxV = r.maxCell ?: r.cells?.maxOrNull()
        val minV = r.minCell ?: r.cells?.minOrNull()
        val avgV = r.avgCell ?: r.cells?.takeIf { it.isNotEmpty() }?.average()
        val deltaV = r.deltaCell ?: if (maxV != null && minV != null) maxV - minV else null
        _status.value = cur.copy(
            hasData = true,
            connected = true,
            // 本机设备名走 BLE 名/设备档案（与保护板路径 updateFromRealtime 同一口径）：
            // 电量计协议里能读到的名字是"被中继那块板"的，不能当本机设备名（见 MeterReading）
            deviceName = connectedDeviceName.value ?: cur.deviceName,
            totalVoltage = r.totalVoltage ?: cur.totalVoltage,
            current = r.current ?: cur.current,
            power = r.power ?: cur.power,
            soc = r.soc ?: cur.soc,
            totalCapAh = r.totalCapAh ?: cur.totalCapAh,
            remainCapAh = r.remainCapAh ?: cur.remainCapAh,
            cycles = r.cycles ?: cur.cycles,
            battState = r.battState ?: cur.battState,
            chMos = r.chMos?.let { if (it) "开启" else "关闭" } ?: cur.chMos,
            disMos = r.disMos?.let { if (it) "开启" else "关闭" } ?: cur.disMos,
            balance = r.balancing?.let { if (it) "均衡中" else "关闭" } ?: cur.balance,
            // 电量计只有开/关两态，没有原因码（码表是保护板的状态字节）
            chMosCode = r.chMos?.let { if (it) 1 else 0 } ?: cur.chMosCode,
            disMosCode = r.disMos?.let { if (it) 1 else 0 } ?: cur.disMosCode,
            balanceCode = r.balancing?.let { if (it) 4 else 0 } ?: cur.balanceCode,
            temps = r.temps ?: cur.temps,
            cells = newCells?.let { reuse(cur.cells, it) } ?: cur.cells,
            maxCell = maxV?.let { "%.3f".fmt(it) } ?: cur.maxCell,
            minCell = minV?.let { "%.3f".fmt(it) } ?: cur.minCell,
            avgCell = avgV?.let { "%.3f".fmt(it) } ?: cur.avgCell,
            deltaCell = deltaV?.let { "%.3f".fmt(it) } ?: cur.deltaCell,
            remainChargeMin = r.remainChargeMin ?: cur.remainChargeMin,
            remainDischargeMin = r.remainDischargeMin ?: cur.remainDischargeMin,
            relayBoardBrand = r.relayBoardBrand ?: cur.relayBoardBrand,
            relayBoardName = r.relayBoardName ?: cur.relayBoardName,
            relayBoardMac = r.relayBoardMac ?: cur.relayBoardMac,
            trendCurrent = norm(histCurr.toList(), includeZero = true),
            trendVolt = norm(histVolt.toList(), includeZero = false),
        )
        connected.value = true
    }

    /**
     * 电量计**自身**的设备信息 → 身份区（设置 → 关于）。
     *
     * 键名与保护板身份区一致（`serialNo`/`hwVersion`/`swVersion`/`batteryType`/`cellCount`），
     * 设置页复用同一批行即可，不必为电量计再写一套展示。只做增量合并：
     * 协议里报什么写什么，没报的键不动（陆行目前只报读数，不报这些，身份区就保持空）。
     */
    fun applyMeterDeviceInfo(r: io.github.lswlc33.maibms.protocol.meter.MeterReading) {
        val patch = buildMap {
            r.serialNo?.let { put("serialNo", it) }
            r.hwVersion?.let { put("hwVersion", it) }
            r.swVersion?.let { put("swVersion", it) }
            r.batteryType?.let { put("batteryType", it) }
            r.cellCount?.let { put("cellCount", it.toString()) }
        }
        if (patch.isEmpty()) return
        val merged = identity.value + patch
        // 表2 每 5 拍读一次，内容不变时别触发身份区重组（Map 结构相等即视为没变）
        if (merged != identity.value) identity.value = merged
    }
}
