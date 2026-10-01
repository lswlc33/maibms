package io.github.lswlc33.maibms.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val maxCell: String = "--",
    val minCell: String = "--",
    val avgCell: String = "--",
    val deltaCell: String = "--",
    val totalCycleAh: Int = 0,
    val battState: String = "等待数据…",
    val chMos: String = "--",
    val disMos: String = "--",
    val balance: String = "--",
    val protectList: List<String> = emptyList(),
    val alarmList: List<String> = emptyList(),
    /** 带真实位号的版本（详情弹窗显示 bit N 用） */
    val protectPairs: List<Pair<Int, String>> = emptyList(),
    val alarmPairs: List<Pair<Int, String>> = emptyList(),
    val temps: List<Pair<String, Double>> = emptyList(),
    val cells: List<CellV> = emptyList(),
    val trendCurrent: List<Float> = emptyList(),
    val trendVolt: List<Float> = emptyList(),
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
        deviceName = connectedDeviceName.value ?: "--",
        timeLabel = timeLabel,
        status = _status.value.copy(connected = false),
        liveParams = liveParams.value,
        identity = identity.value,
    )

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
        _status.value = s.status
    }

    /**
     * 配置缓存标志：true = 当前 liveParams/identity 是自动重连设备「上次成功连接」的缓存
     * （未连接时的离线展示），配置页据此显示缓存文案；实时读回/快照载入都会清掉它。
     */
    val paramsFromCache = MutableStateFlow(false)

    /** 载入某设备的配置缓存到会话（未连接时的离线展示）；无缓存返回 false */
    fun loadParamsCache(cache: AppStore.ParamsCache) {
        liveParams.value = cache.params
        identity.value = cache.identity
        paramsFromCache.value = true
    }

    /**
     * 切到真机前清空上一台设备的数据：设备身份/版本来自参数区（0x02 的 528/620/636），
     * 趋势曲线目前只有 Mock 会生成——留着上一条假曲线会让人把假数据当真数据。
     */
    fun clearForRealDevice() {
        liveParams.value = emptyMap()
        identity.value = emptyMap()
        paramsFromCache.value = false   // 会话数据已清空，「缓存」标记自然也不成立
        clearTrend()
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
        val hms = "%02d:%02d:%02d".format(sec % 86400 / 3600, sec % 3600 / 60, sec % 60)
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

    /** 0x11 解码结果 → UI 状态（每 900ms 轮询回填） */
    fun updateFromRealtime(r: io.github.lswlc33.maibms.protocol.RealtimeDecoder.Result) {
        val balBits = r.balanceBits.toLong()
        pushTrend(r.totalVoltage, r.current)
        // MOS/均衡开关的显示状态跟实时帧走（原先停在写死初值，控制页会与实际设备不符）
        chargeSwitch.value = r.chMos == 1
        dischargeSwitch.value = r.disMos == 1
        balanceSwitch.value = r.balanceState != 0
        val newCells = r.cells.mapIndexed { i, v ->
            CellV(
                index = i + 1, volt = v,
                isMax = i + 1 == r.maxCellIdx, isMin = i + 1 == r.minCellIdx,
                balancing = (balBits shr i) and 1L == 1L,
            )
        }
        _status.value = _status.value.copy(
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
            maxCell = "%.3f".format(r.maxCellV),
            minCell = "%.3f".format(r.minCellV),
            avgCell = "%.3f".format(r.avgCellV),
            deltaCell = "%.3f".format(r.deltaCellV),
            totalCycleAh = r.cycleCapAh.toInt(),
            battState = io.github.lswlc33.maibms.protocol.RealtimeDecoder.battStateText(r.battStateCode),
            chMos = if (r.chMos == 1) "开启" else "关闭",
            disMos = if (r.disMos == 1) "开启" else "关闭",
            balance = if (r.balanceState != 0) "均衡中" else "关闭",
            protectList = io.github.lswlc33.maibms.protocol.BitDict.decode(r.protectBits, io.github.lswlc33.maibms.protocol.BitDict.protectNames),
            alarmList = io.github.lswlc33.maibms.protocol.BitDict.displayAlarmList(r.warnBits, r.chMos == 1, r.disMos == 1),
            protectPairs = io.github.lswlc33.maibms.protocol.BitDict.decodePairs(r.protectBits, io.github.lswlc33.maibms.protocol.BitDict.protectNames),
            alarmPairs = io.github.lswlc33.maibms.protocol.BitDict.decodeForDisplayPairs(r.warnBits, io.github.lswlc33.maibms.protocol.BitDict.warnNames),
            temps = listOf("MOS" to r.mosTemp, "均衡" to r.balanceTemp) +
                    r.temps.mapIndexed { i, t -> "T${i + 1}" to t },
            cells = newCells,
            batteryType = io.github.lswlc33.maibms.protocol.RealtimeDecoder.batteryTypeText(r.batteryType),
            connected = true,
            cycles = _status.value.cycles,
            trendCurrent = norm(histCurr.toList(), includeZero = true),
            trendVolt = norm(histVolt.toList(), includeZero = false),
            // 设备名/版本不在实时帧里：设备名用 BLE 名，版本来自身份区（0x02 读回）
            deviceName = connectedDeviceName.value ?: _status.value.deviceName,
            swVersion = identity.value["swVersion"]?.ifBlank { null } ?: _status.value.swVersion,
            hwVersion = identity.value["hwVersion"]?.ifBlank { null } ?: _status.value.hwVersion,
        )
        connected.value = true
    }

    /** 当前设备密码库（按设备存储，连接后由 restorePasswords 填充） */
    val passwords = MutableStateFlow(
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
}
