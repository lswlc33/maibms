package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.BmsRepository
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.data.fmt
import io.github.lswlc33.maibms.protocol.ControlCmd
import io.github.lswlc33.maibms.protocol.ParamTable
import io.github.lswlc33.maibms.protocol.Perm
import io.github.lswlc33.maibms.protocol.ResultCodes
import io.github.lswlc33.maibms.protocol.WriteAccess
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/* ---------- S05 配置主页 ---------- */

@Composable
fun ConfigHomeScreen(
    onOpenGroup: (Int) -> Unit,
    onOpenTools: () -> Unit,
    onOpenPerm: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    onConnect: () -> Unit = {},
) {
    // 这三页只用到权限等级：收整份 status 会让上百行参数/控制项每拍重组一次（见 MockBms.permissionLevel）
    val permissionLevel by MockBms.permissionLevel.collectAsState(initial = MockBms.status.value.permissionLevel)
    val usingReal by MockBms.usingRealBle.collectAsState()
    val live by MockBms.liveParams.collectAsState()
    val reading by MockBms.paramsReading.collectAsState()
    val fromCache by MockBms.paramsFromCache.collectAsState()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    ScreenScaffold(
        title = "配置",
        // 读回进度放副标题，不再用常驻横幅占一整行（缓存态注明来源）
        subtitle = when {
            live.isEmpty() -> "保护板参数 · 连接后读取"
            fromCache -> "保护板参数 · 缓存 ${live.size} 项（上次成功连接）"
            else -> "保护板参数 · 已读回 ${live.size} 项"
        },
        bottomPadding = bottomPadding,
        // 写权限状态 + 等级数字：等级不再单独挂横幅，点任一个都能换级
        trailing = { WriteAccessTrailing(permissionLevel, WriteAccess.of(permissionLevel, connected), onOpenPerm) },
    ) {
        UnconfirmedWriteBanner()
        when {
            // 未连接但有缓存：配置项不是实时数据，显示上次成功连接的设置项（只读），横幅说清来源
            !connected && fromCache && live.isNotEmpty() ->
                InfoBanner("未连接保护板 · 以下为「${MockBms.deviceLabel}」上次成功连接的配置缓存（只读）", kind = "info", action = "连接设备", onAction = onConnect)
            !connected -> InfoBanner("未连接保护板 · 连接后可读取和修改参数", kind = "warn", action = "连接设备", onAction = onConnect)
            reading -> InfoBanner("正在读取参数区…", kind = "info")
            live.isEmpty() -> InfoBanner(
                if (permissionLevel < Perm.READ_PARAM_MIN_LEVEL)
                    "当前权限不足：读参数区需 ${Perm.READ_PARAM_MIN_LEVEL} 级及以上"
                else "未读到参数：设备尚未返回数据，请稍后重试或检查连接",
                kind = "warn", action = if (permissionLevel < Perm.READ_PARAM_MIN_LEVEL) "去校验" else null,
                onAction = onOpenPerm,
            )
        }
        // 只读时把「为什么改不了」讲清楚，并给一条去校验的直达路
        if (connected && !Perm.canWrite(permissionLevel)) {
            InfoBanner(
                "当前 ${permissionLevel} 级只读：参数只能查看，写入需 ${Perm.WRITE_MIN_LEVEL} 级及以上",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
        }
        SectionCard {
            SectionHeader("控制与工具", tail = "51/xx")
            SettingRow(title = "MOS 开关 / 强制充电", trailing = { Chevron() }, onClick = onOpenTools)
            SettingRow(title = "化学体系预设", trailing = { Chevron() }, onClick = onOpenTools)
            SettingRow(title = "清零 / 蓝牙 / 恢复出厂", danger = true, trailing = { Chevron() }, onClick = onOpenTools)
        }
        SectionCard {
            SectionHeader("保护参数", tail = "0x02")
            ParamTable.groups.take(4).forEachIndexed { i, g ->
                SettingRow(title = g.first, trailing = { Chevron() }, onClick = { onOpenGroup(i) })
            }
        }
        SectionCard {
            SectionHeader("电池组与系统", tail = "152~374")
            ParamTable.groups.take(6).drop(4).forEachIndexed { i, g ->
                SettingRow(title = g.first, trailing = { Chevron() }, onClick = { onOpenGroup(i + 4) })
            }
        }
        SectionCard {
            SectionHeader("其他与厂家参数", tail = "378~706")
            ParamTable.groups.drop(6).forEachIndexed { i, g ->
                SettingRow(title = g.first, trailing = { Chevron() }, onClick = { onOpenGroup(i + 6) })
            }
        }
    }
}

/* ---------- S06 参数分组页（真机取真值，演示取内置值） ---------- */

@Composable
fun ParamGroupScreen(
    groupIndex: Int,
    onBack: () -> Unit,
    onEdit: (io.github.lswlc33.maibms.data.ParamItem) -> Unit,
    onOpenPerm: () -> Unit = {},
    onSave: () -> Unit = {},
    onConnect: () -> Unit = {},
) {
    val groups = ParamTable.groups
    val (groupName, range) = groups.getOrElse(groupIndex) { groups[0] }
    val defs = ParamTable.defs.filter { it.addr in range }
    val live by MockBms.liveParams.collectAsState()
    val reading by MockBms.paramsReading.collectAsState()
    val usingReal by MockBms.usingRealBle.collectAsState()
    // 这三页只用到权限等级：收整份 status 会让上百行参数/控制项每拍重组一次（见 MockBms.permissionLevel）
    val permissionLevel by MockBms.permissionLevel.collectAsState(initial = MockBms.status.value.permissionLevel)
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    val access = WriteAccess.of(permissionLevel, connected)
    val busy by MockBms.modifying.collectAsState()
    val unsaved by MockBms.unsavedWriteError.collectAsState()

    /**
     * 一律以 ParamTable 的地址为准取值（真机=读回区，演示=虚拟引擎参数区）。
     * 早先演示模式按 `MockBms.paramGroups` 的名字取值，而那张表的地址与 docs 附录A 对不上
     * （均衡组把 140/142 标成了启动/结束压差），会张冠李戴，已废弃这条路径。
     */
    fun itemOf(d: io.github.lswlc33.maibms.protocol.ParamDef): io.github.lswlc33.maibms.data.ParamItem {
        return io.github.lswlc33.maibms.data.ParamItem(
            name = d.name,
            value = ParamTable.format(live, d),
            unit = d.unit,
            addr = "0x%X".fmt(d.addr),
            scale = if (d.scale >= 1000) "1e${d.scale.toLong().toString().length - 1}" else d.scale.toLong().toString(),
            range = ParamTable.rangeText(d),
        )
    }

    ScreenScaffold(
        title = groupName,
        subtitle = "地址 ${range.first}~${range.last} · ${defs.size} 项 · 倍率见条目",
        onBack = onBack,
        trailing = { WriteAccessTrailing(permissionLevel, access, onOpenPerm) },
    ) {
        UnconfirmedWriteBanner()
        if (reading) InfoBanner("读取中…", kind = "info")
        else if (!connected) {
            InfoBanner("未连接保护板，当前显示的是本机缓存（无数据则为 --）", kind = "warn", action = "连接设备", onAction = onConnect)
        }
        SectionCard {
            defs.forEach { d ->
                val it = itemOf(d)
                // 无单位的参数（屏蔽位、编码值）不要留尾随空格
                val shown = if (d.unit.isBlank()) it.value else "${it.value} ${d.unit}"
                // 只读权限下不灰显（值仍要看），点开是预览弹窗；只读参数额外挂个「只读」小标
                SettingRow(
                    title = d.name,
                    inlineValue = shown,
                    trailing = { if (d.readOnly) Text("只读", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onEdit(it) },
                )
            }
            if (defs.isEmpty()) SettingRow(title = "该分组暂未定义条目")
        }
        val writeCode by MockBms.lastWriteResult.collectAsState()
        val writeDetail by MockBms.lastWriteDetail.collectAsState()
        if (writeCode != -1) {
            val ok = ResultCodes.writeOk(writeCode)
            val head = when (writeCode) {
                -2 -> "写参数：未收到应答"
                -3 -> "写参数：结果未确认"
                // -4/-5 是仓库侧的新结论：写进临时区 ≠ 已保存；会话结束则是没写成
                BmsRepository.WRITE_SAVE_FAILED -> "写参数：已写入但未保存"
                BmsRepository.WRITE_SESSION_ENDED -> "写参数：连接已断开"
                else -> "写参数结果：${ResultCodes.writeResult(writeCode)}"
            }
            InfoBanner(head + (writeDetail?.let { "（$it）" } ?: ""), kind = if (ok) "info" else "err")
        }
        if (busy) InfoBanner("正在提交修改，请等待设备确认…", kind = "info")
        // 未连接是链路问题，不应被 DENIED 分支说成权限不足。
        if (!connected) return@ScreenScaffold
        if (usingReal && live.isEmpty() && !reading && connected) {
            InfoBanner(
                if (permissionLevel < Perm.READ_PARAM_MIN_LEVEL)
                    "保护参数区未读到数据：读参数需 ${Perm.READ_PARAM_MIN_LEVEL} 级及以上权限"
                else "保护参数区尚未返回数据，请稍后重试或检查设备连接",
                kind = "warn", action = if (permissionLevel < Perm.READ_PARAM_MIN_LEVEL) "去校验" else null,
                onAction = onOpenPerm,
            )
        } else when (access) {
            WriteAccess.EDIT ->
                InfoBanner("点条目修改，设备确认完整后自动保存", kind = "info", action = if (busy || unsaved != null) null else "保存修改", onAction = { if (!busy && unsaved == null) onSave() })
            WriteAccess.READ_ONLY -> InfoBanner(
                "当前 ${permissionLevel} 级只读 · 写入需 ${Perm.WRITE_MIN_LEVEL} 级及以上权限",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
            WriteAccess.DENIED -> InfoBanner(
                "权限不足（当前 ${permissionLevel} 级）· 读参数区需 ${Perm.READ_PARAM_MIN_LEVEL} 级、写入需 ${Perm.WRITE_MIN_LEVEL} 级",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
        }
    }
}

/* ---------- S08 控制与工具（全部命令号直达，不做名称反查） ---------- */

@Composable
fun ControlToolsScreen(
    onBack: () -> Unit,
    onCommand: (Int) -> Unit,
    onOpenPerm: () -> Unit = {},
    onConnect: () -> Unit = {},
) {
    val chargeOn by MockBms.chargeSwitch.collectAsState()
    val dischargeOn by MockBms.dischargeSwitch.collectAsState()
    val balanceOn by MockBms.balanceSwitch.collectAsState()
    // 这三页只用到权限等级：收整份 status 会让上百行参数/控制项每拍重组一次（见 MockBms.permissionLevel）
    val permissionLevel by MockBms.permissionLevel.collectAsState(initial = MockBms.status.value.permissionLevel)
    val link by remember { io.github.lswlc33.maibms.data.Bms.repository.linkState }.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    val access = WriteAccess.of(permissionLevel, connected)
    val canWrite = access == WriteAccess.EDIT
    val busy by MockBms.modifying.collectAsState()
    val hasData by remember { MockBms.status.map { it.hasData }.distinctUntilChanged() }
        .collectAsState(initial = MockBms.status.value.hasData)
    val toggleEnabled = canWrite && hasData && !busy
    // 只读时点了开关/命令不是静默无反应，而是当场说明原因
    var deniedNote by remember { mutableStateOf<String?>(null) }

    /** 统一入口：权限不够只提示、不发帧；确认弹窗关掉后由 0x61 结果与实时帧驱动开关状态 */
    val fire: (Int) -> Boolean = { cmd ->
        val reason = when {
            !connected -> "未连接保护板，请先连接设备"
            busy -> "正在提交修改，请等待设备确认"
            !canWrite -> "需 ${Perm.WRITE_MIN_LEVEL} 级及以上权限（当前 ${permissionLevel} 级）"
            ControlCmd.isToggle(cmd) && !hasData -> "尚未收到实时数据，开关状态未知"
            else -> null
        }
        if (reason != null) {
            deniedNote = "${ControlCmd.name(cmd)}：$reason"
            false
        } else {
            deniedNote = null
            onCommand(cmd)
            true
        }
    }

    ScreenScaffold(
        title = "控制与工具",
        subtitle = "全部命令执行前需确认 · 高危项需输入确认",
        onBack = onBack,
        trailing = { WriteAccessTrailing(permissionLevel, access, onOpenPerm) },
    ) {
        UnconfirmedWriteBanner()
        // 只说「为什么现在点了会失败」的一次性原因；等级本身右上角数字标识里常显
        if (!connected) {
            InfoBanner("未连接保护板 · 控制命令需要先连上设备", kind = "warn", action = "连接设备", onAction = onConnect)
        } else if (!canWrite) {
            InfoBanner(
                "当前 ${permissionLevel} 级只读 · 控制命令需 ${Perm.WRITE_MIN_LEVEL} 级及以上权限",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
        }
        if (busy) InfoBanner("正在提交修改，请等待设备确认…", kind = "info")
        else if (connected && !hasData) InfoBanner("尚未收到实时数据 · 开关状态未知，暂不可切换", kind = "info")
        deniedNote?.let { InfoBanner(it, kind = "warn") }
        SectionCard {
            SectionHeader("开关控制", tail = "51/1·3·4·6·13·14·52")
            // 开关做小了，整行也做成可点，触摸目标才够（点哪都能切）。
            // 只读时不能只翻本地开关：设备没收到命令，界面却变了（实时帧下一拍会把它拨回来）
            // 本地开关不乐观翻转：fire 只负责弹确认，真正的状态变化由设备 0x61 结果 +
            // 下一拍实时帧驱动（确认弹窗还开着时先翻状态，取消后要等一拍才被拨回）
            val toggleCharge: (Boolean) -> Unit = { on ->
                fire(if (on) ControlCmd.CHARGE_ON else ControlCmd.CHARGE_OFF)
            }
            val toggleDischarge: (Boolean) -> Unit = { on ->
                fire(if (on) ControlCmd.DISCHARGE_ON else ControlCmd.DISCHARGE_OFF)
            }
            val toggleBalance: (Boolean) -> Unit = { on ->
                fire(if (on) ControlCmd.BALANCE_ON else ControlCmd.BALANCE_OFF)
            }
            SwitchSettingRow("充电开关", chargeOn, toggleEnabled, unknown = !hasData, onChange = toggleCharge)
            SwitchSettingRow("放电开关", dischargeOn, toggleEnabled, unknown = !hasData, onChange = toggleDischarge)
            SwitchSettingRow("均衡开关", balanceOn, toggleEnabled, unknown = !hasData, onChange = toggleBalance)
            SettingRow(title = "强制开启充电", trailing = { Chevron() }, onClick = { fire(ControlCmd.FORCE_CHARGE) })
        }
        SectionCard {
            // 预设是整片重写保护阈值，改完必须保存才生效
            SectionHeader("化学体系预设", tail = "51/38·39·40·53 · 覆盖保护阈值")
            SettingRow(title = "钛锂预设", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.PRESET_TITANATE) })
            SettingRow(title = "三元预设", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.PRESET_TERNARY) })
            SettingRow(title = "铁锂预设", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.PRESET_LIFEPO4) })
            SettingRow(title = "钠电预设", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.PRESET_SODIUM) })
        }
        SectionCard {
            SectionHeader("校准与维护", tail = "51/7·8·9·30·31")
            SettingRow(title = "保存应用参数", trailing = { Chevron() }, onClick = { fire(ControlCmd.SAVE_PARAMS) })
            SettingRow(title = "电流归零", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CURRENT_ZERO) })
            SettingRow(title = "重启系统", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.RESTART) })
            SettingRow(title = "蜂鸣器开", trailing = { Chevron() }, onClick = { fire(ControlCmd.BUZZER_ON) })
            SettingRow(title = "蜂鸣器关", trailing = { Chevron() }, onClick = { fire(ControlCmd.BUZZER_OFF) })
        }
        SectionCard {
            SectionHeader("清零类操作", tail = "51/15·32~37 · 数据不可恢复")
            SettingRow(title = "清除系统日志", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CLEAR_LOG) })
            SettingRow(title = "清总放电容量", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CLEAR_DISCHARGE_CAP) })
            SettingRow(title = "清总充电容量", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CLEAR_CHARGE_CAP) })
            SettingRow(title = "清总放电时间", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CLEAR_DISCHARGE_TIME) })
            SettingRow(title = "清总充电时间", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CLEAR_CHARGE_TIME) })
            SettingRow(title = "清零运行时间", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CLEAR_RUNTIME) })
            SettingRow(title = "清零保护次数", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.CLEAR_PROTECT_COUNT) })
        }
        SectionCard {
            SectionHeader("蓝牙", tail = "51/16·28·29")
            SettingRow(title = "蓝牙初始化", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.BT_INIT) })
            SettingRow(title = "蓝牙打开", trailing = { Chevron() }, onClick = { fire(ControlCmd.BT_OPEN) })
            SettingRow(title = "蓝牙关闭", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.BT_CLOSE) })
        }
        SectionCard {
            SectionHeader("高危", tail = "51/11·12·42")
            SettingRow(title = "恢复出厂设置", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.FACTORY_RESET) })
            SettingRow(title = "恢复厂家设置", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.FACTORY_VENDOR_RESET) })
            SettingRow(title = "关闭系统（断电）", danger = true, trailing = { Chevron() }, onClick = { fire(ControlCmd.POWER_OFF) })
        }
        // 命令结果回显：0x61 应答码表与控制开关不同，必须显式告诉用户成没成。
        // 开关类命令由实时帧就地反映，这里不再重复报（否则每次拨开关都弹一条）
        val ctrl by MockBms.lastControlResult.collectAsState()
        val detail by MockBms.lastWriteDetail.collectAsState()
        ctrl?.let { (cmd, code) ->
            if (!ControlCmd.isToggle(cmd) || code != 1) {
                InfoBanner(
                    "${ControlCmd.name(cmd)}：${if (code <= 0) detail ?: ResultCodes.controlResult(code) else ResultCodes.controlResult(code)}",
                    kind = if (code == 1) "info" else "err",
                )
            }
        }
    }
}

/* ---------- S07 参数编辑弹窗（只读时同一弹窗降级为预览） ---------- */

@Composable
private fun UnconfirmedWriteBanner() {
    val error by MockBms.unsavedWriteError.collectAsState()
    error?.let { InfoBanner(it, kind = "err") }
}

@Composable
fun ParamEditDialog(
    item: io.github.lswlc33.maibms.data.ParamItem,
    onDismiss: () -> Unit,
    onWrite: (Int) -> Unit = {},
    /** 只读预览：隐藏输入与写入按钮，只展示当前值、范围与地址 */
    readOnly: Boolean = false,
    /** 只读原因（权限不足 / 参数本身是设备状态量） */
    readOnlyNote: String? = null,
    /** 只读时给的「去校验」直达（参数本身只读则不给） */
    onOpenPerm: (() -> Unit)? = null,
) {
    val addrInt = item.addr.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: -1
    val def = ParamTable.byAddr(addrInt)
    val params by MockBms.liveParams.collectAsState()
    val busy by MockBms.modifying.collectAsState()
    val permissionLevel by MockBms.permissionLevel.collectAsState(initial = MockBms.status.value.permissionLevel)
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    val currentRaw = def?.let { ParamTable.rawFromParams(params, it) }
    val currentValue = if (def != null && currentRaw != null) ParamTable.formatRaw(currentRaw.toInt(), def) else item.value
    var input by remember(item.addr) { mutableStateOf(currentValue.takeIf { it.toDoubleOrNull()?.isFinite() == true } ?: "") }
    var pickedRaw by remember(item.addr) { mutableStateOf(currentRaw) }
    var submitted by remember(item.addr) { mutableStateOf(false) }
    val dict = def?.dict
    val preview = readOnly || def?.readOnly == true || def == null
    val denial = when {
        def == null -> "未知参数地址，不能直接写入"
        def.readOnly -> def.note ?: "该参数由设备维护，只能读取"
        !connected -> "未连接保护板，当前只能查看缓存"
        !Perm.canWrite(permissionLevel) -> "当前 ${permissionLevel} 级只读，写入需 ${Perm.WRITE_MIN_LEVEL} 级及以上权限"
        readOnly -> readOnlyNote ?: "只读参数"
        else -> null
    }
    val parsed = if (dict == null) ParamTable.parseInput(addrInt, input) else {
        val raw = pickedRaw?.toInt()
        if (raw == null) io.github.lswlc33.maibms.protocol.ParamParseResult(error = "请选择参数取值")
        else io.github.lswlc33.maibms.protocol.ParamParseResult(
            rawValue = raw,
            error = ParamTable.validateRaw(addrInt, raw),
            actualValue = def?.let { ParamTable.formatRaw(raw, it) },
        )
    }
    val error = denial ?: parsed.error
    val changed = parsed.rawValue != null && parsed.rawValue != currentRaw?.toInt()
    val canSubmit = !preview && !busy && !submitted && changed && error == null

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(18.dp)
            ) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("当前值 $currentValue ${item.unit}", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    if (!preview && denial == null) {
                        if (dict != null) {
                            Text("选择取值", fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                            dict.forEach { (code, text) ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .clickable(enabled = !busy && !submitted) { pickedRaw = code }) {
                                    RadioDot(pickedRaw == code)
                                    Text(text, modifier = Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp)
                                    Text("0x%X".fmt(code), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (currentRaw != null && currentRaw !in dict) Text("当前设备值不在已知枚举内，请选择已知取值",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        } else {
                            OutlinedTextField(value = input, onValueChange = { input = it },
                                enabled = !busy && !submitted,
                                label = { Text("新值（${item.unit.ifBlank { "原始值" }}）") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isError = parsed.error != null,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                    Text(if (def == null) "地址 ${item.addr}" else "范围 ${ParamTable.rangeText(def)} · 地址 ${item.addr} · 倍率 ${def.scale}",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    if (error != null) Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp))
                    else if (!preview && parsed.actualValue != null) Text("实际写入 ${parsed.actualValue} ${item.unit}",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    if (busy || submitted) Text("正在提交修改，请等待设备确认…", fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    if (preview && onOpenPerm != null && def?.readOnly != true && connected) {
                        TextButton(onClick = { onDismiss(); onOpenPerm() }) { Text("去校验") }
                    }
                    TextButton(onClick = onDismiss) { Text(if (preview || denial != null) "关闭" else "取消") }
                    if (!preview && denial == null) Button(enabled = canSubmit, onClick = {
                        if (canSubmit) parsed.rawValue?.let { raw ->
                            submitted = true
                            onWrite(raw)
                            onDismiss()
                        }
                    }) { Text("保存修改") }
                }
            }
        }
    }
}
