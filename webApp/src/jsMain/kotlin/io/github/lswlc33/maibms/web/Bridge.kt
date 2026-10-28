package io.github.lswlc33.maibms.web

import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.AppVersion
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.BmsStatus
import io.github.lswlc33.maibms.data.hasRelayInfo
import io.github.lswlc33.maibms.data.DeviceCapabilities
import io.github.lswlc33.maibms.data.DeviceProfiles
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.data.fmtDurationSec
import io.github.lswlc33.maibms.data.fmtRemainingMin
import io.github.lswlc33.maibms.protocol.ControlCmd
import io.github.lswlc33.maibms.protocol.MosStateDict
import io.github.lswlc33.maibms.protocol.ParamTable
import io.github.lswlc33.maibms.transport.DeviceFamily
import io.github.lswlc33.maibms.transport.LinkState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/* ============================ 传给主题页的 DTO ============================ */

/**
 * 这些 DTO 就是**主题页面的数据契约**。
 *
 * 为什么不直接序列化引擎里的 `BmsStatus`：
 *  - 引擎的 `BmsStatus` 随协议演进（加字段、改语义），直接暴露会把主题页钉死在内部结构上；
 *  - 主题真正要的是"能直接显示的字符串 + 能自己判断的布尔/数值"，所以把时长格式化、
 *    MOS 断开原因查表这类共用逻辑在这里算好——否则每个主题各抄一遍，必然漂移。
 *  - 字段只增不改名，老主题页多忽略几个字段照样能跑。
 */
@Serializable
internal data class ThemeState(
    val link: String,
    val linkText: String,
    val connected: Boolean,
    val hasData: Boolean,
    val stalled: Boolean,
    val hint: String? = null,
    val scanning: Boolean,
    val scanError: String? = null,
    val previewActive: Boolean,
    val previewLabel: String? = null,
    val deviceName: String,
    val deviceAddress: String? = null,
    val family: String,
    val familyLabel: String,
    val channel: String? = null,
    val channelLabel: String? = null,
    val channels: List<ThemeChannel>,
    val caps: ThemeCaps,
    val permission: Int,
    val passwords: List<Int>,
    val device: ThemeDevice,
    val cells: List<ThemeCell>,
    val temps: List<ThemeTemp>,
    val trend: ThemeTrend,
    val flags: ThemeFlags,
    val relay: ThemeRelay,
    val params: ThemeParams,
    val devices: List<ThemeMetaDevice>,
    val settings: ThemeSettings,
    val version: ThemeVersion,
)

@Serializable
internal data class ThemeChannel(val id: String, val label: String, val tag: String, val warn: String? = null)

@Serializable
internal data class ThemeCaps(
    val protectAlarm: Boolean,
    val mos: Boolean,
    val balance: Boolean,
    val perCellVoltage: Boolean,
    val multiTemp: Boolean,
    val extTimes: Boolean,
    val permission: Boolean,
    val controlCharge: Boolean,
    val controlDischarge: Boolean,
    val controlBalance: Boolean,
    val maxCells: Int,
    val voltageDecimals: Int,
)

@Serializable
internal data class ThemeDevice(
    val hasData: Boolean,
    val battState: String,
    val soc: Int,
    val totalCapAh: Double,
    val remainCapAh: Double,
    val soh: Int,
    val cycles: Int,
    val totalVoltage: Double,
    val current: Double,
    val power: Int,
    val voltageDecimals: Int,
    val maxCell: String,
    val minCell: String,
    val avgCell: String,
    val deltaCell: String,
    val totalCycleAh: Int,
    val chMos: String,
    val disMos: String,
    val balance: String,
    val chMosCode: Int,
    val disMosCode: Int,
    val balanceCode: Int,
    val chMosReason: String? = null,
    val disMosReason: String? = null,
    val balanceReason: String? = null,
    val thisChargeText: String,
    val lastChargeGapText: String,
    val remainChargeText: String,
    val remainDischargeText: String,
)

@Serializable
internal data class ThemeCell(
    val index: Int,
    val volt: Double,
    val isMax: Boolean,
    val isMin: Boolean,
    val balancing: Boolean,
    val missing: Boolean,
)

@Serializable
internal data class ThemeTemp(val name: String, val value: Double)

@Serializable
internal data class ThemeTrend(val current: List<Float>, val volt: List<Float>)

@Serializable
internal data class ThemeFlag(val bit: Int, val text: String)

@Serializable
internal data class ThemeFlags(
    val protect: List<String>,
    val alarm: List<String>,
    val states: List<String>,
    val protectPairs: List<ThemeFlag>,
    val alarmPairs: List<ThemeFlag>,
)

@Serializable
internal data class ThemeRelay(val active: Boolean, val brand: String, val name: String, val mac: String)

@Serializable
internal data class ThemeParams(
    val reading: Boolean,
    val fromCache: Boolean,
    val identity: Map<String, String>,
    /** 已读到的原始值：字节地址（字符串）→ u16。主题按 paramGroups 里的 def 自行格式化 */
    val values: Map<String, Int>,
)

@Serializable
internal data class ThemeVersion(val name: String, val code: Int)

@Serializable
internal data class ThemeMetaParam(
    val name: String,
    val addr: Int,
    val addrHex: String,
    val unit: String,
    val scale: Double,
    val min: Double,
    val max: Double,
    val readOnly: Boolean,
    val note: String? = null,
    val rangeText: String,
    val u32: Boolean,
    /** 已格式化好的显示值（未读到为 "--"）：主题直接贴，不用自己算倍率 */
    val display: String,
    /** 原始值（未读到为 null）：编辑弹窗回填用 */
    val raw: Int? = null,
)

@Serializable
internal data class ThemeMetaGroup(val index: Int, val name: String, val defs: List<ThemeMetaParam>)

@Serializable
internal data class ThemeMetaCommand(
    val cmd: Int,
    val name: String,
    val dangerous: Boolean,
    val typedConfirm: Boolean,
    val toggle: Boolean,
    val warning: String? = null,
)

@Serializable
internal data class ThemeMetaDevice(
    val address: String,
    val name: String,
    val family: String,
    val familyLabel: String,
    val passwords: Int,
    val lastConnectedAt: Long,
    val saved: Boolean,
)

@Serializable
internal data class ThemeMeta(
    val version: ThemeVersion,
    val builtAt: Long,
    val paramGroups: List<ThemeMetaGroup>,
    val commands: List<ThemeMetaCommand>,
    val families: Map<String, String>,
)

@Serializable
internal data class ThemeSettings(
    val themeMode: String,
    val pollInterval: Int,
    val autoUpgradeTarget: Int,
    val autoReconnect: Boolean,
    val logFrameOn: Boolean,
    val logMinLevel: String,
)

@Serializable
internal data class ThemeResult(val ok: Boolean, val code: Int, val text: String)

@Serializable
internal data class ThemeAuthResult(val ok: Boolean, val level: Int)

/** 主题清单（静态 HTML 页面，所以清单也在这里统一给，避免 switch 页与各页各写一份） */
@Serializable
internal data class ThemeInfo(val id: String, val name: String, val desc: String, val href: String, val accent: String)

/* ============================ 桥本体 ============================ */

internal val BridgeJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/** 序列化助手：Kotlin DTO → 普通 JS 对象（主题页拿到的是 `{...}`，不是 Kotlin 对象） */
internal inline fun <reified T> T.toJs(serializer: kotlinx.serialization.SerializationStrategy<T>): dynamic =
    parseJson(BridgeJson.encodeToString(serializer, this))

/**
 * 数据桥：**唯一**对主题页面暴露的接口（`window.Maibms`）。
 *
 * 只给三样东西：
 * - `Maibms.state` / `Maibms.onState(fn)`：JSON 快照 + 订阅（每次数据变化推整份）；
 * - `Maibms.meta()`：不常变但渲染必需的表（参数分组、命令表、设备、设置、主题清单）；
 * - `Maibms.xxx()` 动作：连接、鉴权、写参数、控制……
 *
 * 刻意**不做细粒度 diff**：轮询 600~900ms 一次，整份重画对浏览器毫无压力；
 * 而细粒度更新会在每个主题里引入"某处忘了刷新"的 bug——这是主题系统最容易翻车的地方。
 */
object Bridge {

    private val stateListeners = ArrayList<(dynamic) -> Unit>()
    private val logListeners = ArrayList<(dynamic) -> Unit>()
    private var lastState: dynamic = null

    /** 装配 `window.Maibms`（Main 里调用一次） */
    fun install(): Boolean {
        val ok = installBridgeApi()
        if (ok) pushState()
        return ok
    }

    /* ---------------- 订阅 ---------------- */

    fun subscribeState(fn: (dynamic) -> Unit) {
        stateListeners += fn
        // 首次订阅立刻补一份当前状态：主题页不必等下一个轮询拍才画出界面
        lastState?.let { s -> runCatching { fn(s) } }
    }

    fun subscribeLog(fn: (dynamic) -> Unit) {
        logListeners += fn
    }

    fun clearListeners() {
        stateListeners.clear()
        logListeners.clear()
    }

    internal fun currentState(): dynamic = lastState ?: snapshot()

    /* ---------------- 推送 ---------------- */

    /** 数据层每次变化都调它：把整份快照推给所有订阅者 */
    fun pushState() {
        val s = snapshot()
        lastState = s
        // window.Maibms.state 也始终最新：有的主题只读它、不订阅
        // 同步 window.Maibms.state（有的主题只读它、不订阅）
        runCatching {
            val api = jsGet(jsWindow(), "Maibms")
            if (api != null && api != undefined) jsPut(api, "state", s)
        }
        stateListeners.toList().forEach { fn -> runCatching { fn(s) } }
    }

    fun pushLog(lines: List<String>) {
        if (lines.isEmpty()) return
        val arr = parseJson(BridgeJson.encodeToString(ListSerializer(String.serializer()), lines))
        logListeners.toList().forEach { fn -> runCatching { fn(arr) } }
    }

    /* ---------------- 快照 ---------------- */

    private fun snapshot(): dynamic {
        val st = MockBms.status.value
        val caps = MockBms.caps.value
        val link = Bms.repository.linkState.value
        val state = ThemeState(
            link = link.name,
            linkText = linkText(link),
            connected = link == LinkState.Connected,
            hasData = st.hasData,
            stalled = Bms.repository.stalled.value,
            hint = Bms.repository.connectHint.value,
            scanning = Bms.repository.scanning.value,
            scanError = Bms.repository.scanError.value,
            previewActive = Bms.repository.previewActive.value,
            previewLabel = Bms.repository.previewLabel.value,
            deviceName = MockBms.deviceLabel,
            deviceAddress = MockBms.savedAddress,
            family = Bms.repository.currentFamily.value.name,
            familyLabel = Bms.repository.currentFamily.value.label,
            channel = Bms.repository.activeChannel.value?.id,
            channelLabel = Bms.repository.activeChannel.value?.label,
            channels = Bms.repository.availableChannels.value.map {
                ThemeChannel(it.id, it.label, it.tag, it.warn)
            },
            caps = caps.toTheme(),
            permission = st.permissionLevel,
            passwords = MockBms.plainPasswords.value.keys.sorted(),
            device = deviceDto(st, caps),
            cells = st.cells.map { ThemeCell(it.index, it.volt, it.isMax, it.isMin, it.balancing, it.missing) },
            temps = st.temps.map { (n, v) -> ThemeTemp(n, v) },
            trend = ThemeTrend(st.trendCurrent, st.trendVolt),
            flags = ThemeFlags(
                protect = st.protectList,
                alarm = st.alarmList,
                states = st.statePairs.map { it.second },
                protectPairs = st.protectPairs.map { ThemeFlag(it.first, it.second) },
                alarmPairs = st.alarmPairs.map { ThemeFlag(it.first, it.second) },
            ),
            relay = ThemeRelay(st.hasRelayInfo, st.relayBoardBrand, st.relayBoardName, st.relayBoardMac),
            params = ThemeParams(
                reading = MockBms.paramsReading.value,
                fromCache = MockBms.paramsFromCache.value,
                identity = MockBms.identity.value,
                values = MockBms.liveParams.value.mapKeys { it.key.toString() },
            ),
            devices = devices(),
            settings = settings(),
            version = ThemeVersion(AppVersion.name, AppVersion.code),
        )
        return state.toJs(ThemeState.serializer())
    }

    internal fun metaPublic(): ThemeMeta = meta()

    /**
     * meta 只放**不随轮询变的表**（参数分组、命令表）。
     * 设备列表与设置曾经也在这里，但那是个坑：主题页在加载时抓一份 ui.meta，
     * 之后删除设备/改设置都只触发 pushState（推的是 state），ui.meta 里的
     * devices 不会更新——界面上已删的行还在，直到刷新页面。
     * 现在 devices/settings 随 state 每拍推（见 [ThemeState]），主题页只读 state 即可。
     */
    private fun meta(): ThemeMeta = ThemeMeta(
        version = ThemeVersion(AppVersion.name, AppVersion.code),
        builtAt = AppVersion.builtAtEpochSec,
        paramGroups = paramGroups(),
        commands = commands(),
        families = DeviceFamily.entries.associate { it.name to it.label },
    )

    /**
     * 参数分组：**带已格式化的显示值**。
     *
     * 主题侧不重算倍率/范围——`ParamTable.format` 处理了 u32 双字、字典、未读回（0xFFFF）
     * 等一排细节；在 JS 里重写一遍等于把 docs/附录A 的口径复制一份，必然漂移。
     */
    private fun paramGroups(): List<ThemeMetaGroup> {
        val live = MockBms.liveParams.value
        return ParamTable.groups.mapIndexedNotNull { gi, (name, range) ->
            val defs = ParamTable.defs.filter { it.addr in range }
            if (defs.isEmpty()) return@mapIndexedNotNull null
            ThemeMetaGroup(
                index = gi,
                name = name,
                defs = defs.map { def ->
                    ThemeMetaParam(
                        name = def.name,
                        addr = def.addr,
                        addrHex = "0x" + def.addr.toString(16).uppercase().padStart(4, '0'),
                        unit = def.unit,
                        scale = def.scale,
                        min = def.min,
                        max = def.max,
                        readOnly = def.readOnly,
                        note = def.note,
                        rangeText = ParamTable.rangeText(def),
                        u32 = def.addr in ParamTable.u32Addrs,
                        display = ParamTable.format(live, def),
                        raw = live[def.addr],
                    )
                },
            )
        }
    }

    private fun commands(): List<ThemeMetaCommand> = listOf(
        ControlCmd.DISCHARGE_OFF, ControlCmd.DISCHARGE_ON,
        ControlCmd.CHARGE_OFF, ControlCmd.CHARGE_ON,
        ControlCmd.BALANCE_ON, ControlCmd.BALANCE_OFF,
        ControlCmd.SAVE_PARAMS, ControlCmd.BUZZER_ON, ControlCmd.BUZZER_OFF,
        ControlCmd.CURRENT_ZERO, ControlCmd.RESTART, ControlCmd.POWER_OFF,
        ControlCmd.FACTORY_RESET, ControlCmd.FACTORY_VENDOR_RESET,
        ControlCmd.CLEAR_LOG, ControlCmd.CLEAR_RUNTIME, ControlCmd.CLEAR_PROTECT_COUNT,
        ControlCmd.PRESET_TERNARY, ControlCmd.PRESET_LIFEPO4, ControlCmd.BT_INIT,
    ).map { cmd ->
        ThemeMetaCommand(
            cmd = cmd,
            name = ControlCmd.name(cmd),
            dangerous = ControlCmd.isDangerous(cmd),
            typedConfirm = ControlCmd.requiresTypedConfirm(cmd),
            toggle = ControlCmd.isToggle(cmd),
            warning = ControlCmd.warning(cmd),
        )
    }

    private fun settings(): ThemeSettings = ThemeSettings(
        themeMode = AppStore.themeMode,
        pollInterval = AppStore.pollIntervalMs,
        autoUpgradeTarget = AppStore.autoUpgradeTarget,
        autoReconnect = AppStore.autoReconnect,
        logFrameOn = BmsLog.frameLogOn.value,
        logMinLevel = BmsLog.minLevel.value.tag,
    )

    private fun devices(): List<ThemeMetaDevice> = DeviceProfiles.all().map { p ->
        ThemeMetaDevice(
            address = p.address,
            name = p.displayName,
            family = p.family.name,
            familyLabel = p.family.label,
            passwords = p.passwords.size,
            lastConnectedAt = p.lastConnectedAt,
            saved = p.address == MockBms.savedAddress,
        )
    }

    /** 主题清单：switch.html 与各页主题按钮共用一个来源 */
    internal fun themes(): List<ThemeInfo> = listOf(
        ThemeInfo("default", "默认", "无边框扁平卡片 · 深色/浅色跟随系统", "index.html", "#4caf50"),
        ThemeInfo("mdui", "Material", "mdui 2 组件库 · Material You 动态取色", "mdui.html", "#6750a4"),
        ThemeInfo("daisy", "Daisy", "Tailwind + daisyUI · 现代卡片与徽标", "daisy.html", "#1dcdbc"),
        ThemeInfo("admin", "控制台", "Bootstrap 侧栏仪表盘 · 宽屏并排布局", "admin.html", "#0d6efd"),
        ThemeInfo("console", "终端", "等宽字体 HUD · 键盘快捷键", "console.html", "#7ee787"),
        ThemeInfo("paper", "杂志", "排版优先 · 特大数字与细分隔线", "paper.html", "#c9a227"),
    )

    private fun deviceDto(st: BmsStatus, caps: DeviceCapabilities) = ThemeDevice(
        hasData = st.hasData,
        battState = st.battState,
        soc = st.soc,
        totalCapAh = st.totalCapAh,
        remainCapAh = st.remainCapAh,
        soh = st.soh,
        cycles = st.cycles,
        totalVoltage = st.totalVoltage,
        current = st.current,
        power = st.power,
        voltageDecimals = caps.voltageDecimals,
        maxCell = st.maxCell,
        minCell = st.minCell,
        avgCell = st.avgCell,
        deltaCell = st.deltaCell,
        totalCycleAh = st.totalCycleAh,
        chMos = st.chMos,
        disMos = st.disMos,
        balance = st.balance,
        chMosCode = st.chMosCode,
        disMosCode = st.disMosCode,
        balanceCode = st.balanceCode,
        chMosReason = MosStateDict.chargeReason(st.chMosCode),
        disMosReason = MosStateDict.dischargeReason(st.disMosCode),
        balanceReason = MosStateDict.balanceMode(st.balanceCode),
        thisChargeText = fmtDurationSec(st.thisChargeSec),
        lastChargeGapText = fmtDurationSec(st.lastChargeGapSec),
        remainChargeText = fmtRemainingMin(st.remainChargeMin),
        remainDischargeText = fmtRemainingMin(st.remainDischargeMin),
    )

    private fun DeviceCapabilities.toTheme() = ThemeCaps(
        protectAlarm, mos, balance, perCellVoltage, multiTemp, extTimes, permission,
        controlCharge, controlDischarge, controlBalance, maxCells, voltageDecimals,
    )

    private fun linkText(link: LinkState): String = when (link) {
        LinkState.Idle -> "未连接"
        LinkState.Connecting -> "连接中…"
        LinkState.Connected -> "已连接"
        LinkState.Disconnected -> "已断开"
    }
}