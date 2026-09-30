package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.protocol.ControlCmd
import io.github.lswlc33.maibms.protocol.ParamTable
import io.github.lswlc33.maibms.protocol.Perm
import io.github.lswlc33.maibms.protocol.ResultCodes
import io.github.lswlc33.maibms.protocol.WriteAccess

/* ---------- S05 配置主页 ---------- */

@Composable
fun ConfigHomeScreen(
    onOpenGroup: (Int) -> Unit,
    onOpenTools: () -> Unit,
    onOpenPerm: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val status by MockBms.status.collectAsState()
    val usingReal by MockBms.usingRealBle.collectAsState()
    val live by MockBms.liveParams.collectAsState()
    val reading by MockBms.paramsReading.collectAsState()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    ScreenScaffold(
        title = "配置",
        // 读回进度放副标题，不再用常驻横幅占一整行
        subtitle = if (live.isNotEmpty()) "保护板参数 · 已读回 ${live.size} 项" else "保护板参数 · 0x02 分块读回",
        bottomPadding = bottomPadding,
        // 写权限状态 + 等级数字：等级不再单独挂横幅，点任一个都能换级
        trailing = { WriteAccessTrailing(status.permissionLevel, WriteAccess.of(status.permissionLevel, connected), onOpenPerm) },
    ) {
        when {
            !connected -> InfoBanner("未连接保护板 · 点右上角「＋」扫描设备", kind = "warn")
            reading -> InfoBanner("正在读取参数区…", kind = "info")
            live.isEmpty() -> InfoBanner(
                "未读到参数：权限不足或设备未就绪（真机实测读参数区需 ${Perm.READ_PARAM_MIN_LEVEL} 级及以上）",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
        }
        // 只读时把「为什么改不了」讲清楚，并给一条去校验的直达路
        if (connected && !Perm.canWrite(status.permissionLevel)) {
            InfoBanner(
                "当前 ${status.permissionLevel} 级只读：参数只能查看，写入需 ${Perm.WRITE_MIN_LEVEL} 级及以上",
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
) {
    val groups = ParamTable.groups
    val (groupName, range) = groups.getOrElse(groupIndex) { groups[0] }
    val defs = ParamTable.defs.filter { it.addr in range }
    val live by MockBms.liveParams.collectAsState()
    val reading by MockBms.paramsReading.collectAsState()
    val usingReal by MockBms.usingRealBle.collectAsState()
    val status by MockBms.status.collectAsState()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    val access = WriteAccess.of(status.permissionLevel, connected)

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
            addr = "0x%X".format(d.addr),
            scale = if (d.scale >= 1000) "1e${d.scale.toLong().toString().length - 1}" else d.scale.toLong().toString(),
            range = ParamTable.rangeText(d),
        )
    }

    ScreenScaffold(
        title = groupName,
        subtitle = "地址 ${range.first}~${range.last} · ${defs.size} 项 · 倍率见条目",
        onBack = onBack,
        trailing = { WriteAccessTrailing(status.permissionLevel, access, onOpenPerm) },
    ) {
        if (reading) InfoBanner("读取中…", kind = "info")
        else if (!connected) {
            InfoBanner("未连接保护板，当前显示的是本机缓存（无数据则为 --）", kind = "warn")
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
                else -> "写参数结果：${ResultCodes.writeResult(writeCode)}"
            }
            InfoBanner(head + (writeDetail?.let { "（$it）" } ?: ""), kind = if (ok) "info" else "err")
        }
        // 读到空 + 已连接才算「权限不够」；未连接时上游横幅已经说明是链路问题
        if (usingReal && live.isEmpty() && !reading && connected) {
            InfoBanner(
                "保护参数区未读到数据：真机实测需 ${Perm.READ_PARAM_MIN_LEVEL} 级及以上权限",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
        } else when (access) {
            WriteAccess.EDIT ->
                InfoBanner("点条目修改，写入成功后自动保存（51/07）", kind = "info", action = "立即保存", onAction = onSave)
            WriteAccess.READ_ONLY -> InfoBanner(
                "当前 ${status.permissionLevel} 级只读 · 写入需 ${Perm.WRITE_MIN_LEVEL} 级及以上权限",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
            WriteAccess.DENIED -> InfoBanner(
                "权限不足（当前 ${status.permissionLevel} 级）· 读参数区需 ${Perm.READ_PARAM_MIN_LEVEL} 级、写入需 ${Perm.WRITE_MIN_LEVEL} 级",
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
) {
    val chargeOn by MockBms.chargeSwitch.collectAsState()
    val dischargeOn by MockBms.dischargeSwitch.collectAsState()
    val balanceOn by MockBms.balanceSwitch.collectAsState()
    val status by MockBms.status.collectAsState()
    val link by remember { io.github.lswlc33.maibms.data.Bms.repository.linkState }.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    val access = WriteAccess.of(status.permissionLevel, connected)
    val canWrite = access == WriteAccess.EDIT
    // 只读时点了开关/命令不是静默无反应，而是当场说明原因
    var deniedNote by remember { mutableStateOf<String?>(null) }

    /** 统一入口：权限不够只提示、不发帧；返回 true 才继续（调用方据此决定要不要改本地开关状态） */
    val fire: (Int) -> Boolean = { cmd ->
        if (!canWrite) {
            deniedNote = "${ControlCmd.name(cmd)} 需 ${Perm.WRITE_MIN_LEVEL} 级及以上权限（当前 ${status.permissionLevel} 级）"
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
        trailing = { WriteAccessTrailing(status.permissionLevel, access, onOpenPerm) },
    ) {
        // 只说「为什么现在点了会失败」的一次性原因；等级本身右上角数字标识里常显
        if (!connected) {
            InfoBanner("未连接保护板 · 控制命令需要先连上设备", kind = "warn")
        } else if (!canWrite) {
            InfoBanner(
                "当前 ${status.permissionLevel} 级只读 · 控制命令需 ${Perm.WRITE_MIN_LEVEL} 级及以上权限",
                kind = "warn", action = "去校验", onAction = onOpenPerm,
            )
        }
        deniedNote?.let { InfoBanner(it, kind = "warn") }
        SectionCard {
            SectionHeader("开关控制", tail = "51/1·3·4·6·13·14·52")
            // 开关做小了，整行也做成可点，触摸目标才够（点哪都能切）。
            // 只读时不能只翻本地开关：设备没收到命令，界面却变了（实时帧下一拍会把它拨回来）
            val toggleCharge: (Boolean) -> Unit = { on ->
                if (fire(if (on) ControlCmd.CHARGE_ON else ControlCmd.CHARGE_OFF)) MockBms.chargeSwitch.value = on
            }
            val toggleDischarge: (Boolean) -> Unit = { on ->
                if (fire(if (on) ControlCmd.DISCHARGE_ON else ControlCmd.DISCHARGE_OFF)) MockBms.dischargeSwitch.value = on
            }
            val toggleBalance: (Boolean) -> Unit = { on ->
                if (fire(if (on) ControlCmd.BALANCE_ON else ControlCmd.BALANCE_OFF)) MockBms.balanceSwitch.value = on
            }
            SettingRow(title = "充电开关", trailing = { AppSwitch(chargeOn, toggleCharge) }, onClick = { toggleCharge(!chargeOn) })
            SettingRow(title = "放电开关", trailing = { AppSwitch(dischargeOn, toggleDischarge) }, onClick = { toggleDischarge(!dischargeOn) })
            SettingRow(title = "均衡开关", trailing = { AppSwitch(balanceOn, toggleBalance) }, onClick = { toggleBalance(!balanceOn) })
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
        ctrl?.let { (cmd, code) ->
            if (!ControlCmd.isToggle(cmd)) {
                InfoBanner(
                    "${ControlCmd.name(cmd)}：${ResultCodes.controlResult(code)}",
                    kind = if (code == 1) "info" else "err",
                )
            }
        }
    }
}

/* ---------- S07 参数编辑弹窗（只读时同一弹窗降级为预览） ---------- */

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
    val addrInt = item.addr.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
    val def = ParamTable.byAddr(addrInt)
    val scale = def?.scale ?: 1.0
    val params by MockBms.liveParams.collectAsState()
    val currentRaw: Long = if (addrInt in ParamTable.u32Addrs) {
        val lo = params[addrInt]?.toLong() ?: 0L
        val hi = params[addrInt + 2]?.toLong() ?: 0L
        ((hi shl 16) or (lo and 0xFFFF)) and 0xFFFFFFFFL
    } else (params[addrInt]?.toLong() ?: 0L) and 0xFFFF

    // 读不到值时别把 "--" 填进输入框，留空让用户直接输；枚举类条目由下面的选择列表负责
    var input by remember { mutableStateOf(if (item.value.toDoubleOrNull() == null) "" else item.value) }
    var pickedRaw by remember { mutableStateOf(currentRaw) }
    val dict = def?.dict

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                Text("当前值 ${item.value} ${item.unit}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (readOnly) {
                // 只读：不灰显当前值，但不给输入框，明确说明为什么改不了
                Text(
                    readOnlyNote ?: "只读参数",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "范围 ${item.range} · 地址 ${item.addr} · 倍率 ${item.scale}",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    if (onOpenPerm != null) {
                        TextButton(onClick = { onDismiss(); onOpenPerm() }) { Text("去校验", color = MaterialTheme.colorScheme.primary) }
                        Spacer(Modifier.width(4.dp))
                    }
                    Button(onClick = onDismiss) { Text("关闭") }
                }
                return@Column
            }

            if (dict != null) {
                // 枚举参数：直接给可选项，不再要求用户知道 0xFAF1=64241 这种码值
                Text("选择取值", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp))
                dict.forEach { (code, text) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { pickedRaw = code }
                            .padding(vertical = 7.dp),
                    ) {
                        RadioDot(selected = pickedRaw == code)
                        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 10.dp))
                        Text("0x%X".format(code), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (dict.keys.none { it == currentRaw }) {
                    Text("当前设备值 0x%X 不在已知枚举内".format(currentRaw), fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    "范围 ${item.range} · 地址 ${item.addr} · 倍率 ${item.scale}",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        enabled = pickedRaw != currentRaw,
                        onClick = { onWrite((pickedRaw * scale).toLong().toInt()); onDismiss() },
                    ) { Text("写入 0x22") }
                }
                return@Column
            }

            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("新值（${item.unit}）") },
                singleLine = true,
                isError = input.replace(',', '.').toDoubleOrNull() == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            // 校验：非数字/超范围都不允许提交（旧实现在这两种情况下会静默写 0 或写超限值）
            val parsed = input.replace(',', '.').toDoubleOrNull()
            val bounds = item.range.substringBefore(' ').split('~').mapNotNull { it.toDoubleOrNull() }
            val outOfRange = parsed != null && bounds.size == 2 && (parsed < bounds[0] || parsed > bounds[1])
            // u32 类参数（容量）单寄存器放不下：倍率 1e6 时上限约 4294.9，超了会截断成错值
            val overU32 = parsed != null && addrInt in ParamTable.u32Addrs && parsed * scale > 4294967295.0
            Text(
                when {
                    parsed == null -> "请输入数字"
                    outOfRange -> "超出允许范围（${item.range}）"
                    overU32 -> "超出该参数可表示范围（u32 上限 4294.967295）"
                    else -> "范围 ${item.range} · 地址 ${item.addr} · 倍率 ${item.scale}"
                },
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = if (parsed == null || outOfRange || overU32) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(4.dp))
                Button(
                    enabled = parsed != null && !outOfRange && !overU32,
                    onClick = {
                        val raw = ((parsed ?: 0.0) * scale).toLong()
                        onWrite(raw.toInt())
                        onDismiss()
                    },
                ) { Text("写入 0x22") }
            }
        }
    }
}
