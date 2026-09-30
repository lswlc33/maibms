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
    ScreenScaffold(
        title = "配置",
        // 读回进度放副标题，不再用常驻横幅占一整行
        subtitle = if (live.isNotEmpty()) "保护板参数 · 已读回 ${live.size} 项" else "保护板参数 · 0x02 分块读回",
        bottomPadding = bottomPadding,
        // 只读不再弹横幅：等级在右上角数字标识里，点它就能换级
        trailing = { PermissionBadge(status.permissionLevel, onOpenPerm) },
    ) {
        val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
        val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
        when {
            !connected -> InfoBanner("未连接保护板 · 点右上角「＋」扫描设备", kind = "warn")
            reading -> InfoBanner("正在读取参数区…", kind = "info")
            live.isEmpty() -> InfoBanner("未读到参数：权限不足或设备未就绪（真机实测读参数区需 2 级及以上）", kind = "warn", action = "去校验", onAction = onOpenPerm)
        }
        SectionCard {
            SectionHeader("控制与工具", tail = "51/xx")
            SettingRow(title = "MOS 开关 / 强制充电", trailing = { Chevron() }, onClick = onOpenTools)
            SettingRow(title = "化学体系预设", trailing = { Chevron() }, onClick = onOpenTools)
            SettingRow(title = "恢复出厂 / 清零类", danger = true, trailing = { Chevron() }, onClick = onOpenTools)
        }
        SectionCard {
            SectionHeader("保护参数", tail = "0x02")
            io.github.lswlc33.maibms.protocol.ParamTable.groups.take(4).forEachIndexed { i, g ->
                SettingRow(title = g.first, trailing = { Chevron() }, onClick = { onOpenGroup(i) })
            }
        }
        SectionCard {
            SectionHeader("电池组与系统", tail = "152~374")
            io.github.lswlc33.maibms.protocol.ParamTable.groups.take(6).drop(4).forEachIndexed { i, g ->
                SettingRow(title = g.first, trailing = { Chevron() }, onClick = { onOpenGroup(i + 4) })
            }
        }
        SectionCard {
            SectionHeader("其他与厂家参数", tail = "378~706")
            io.github.lswlc33.maibms.protocol.ParamTable.groups.drop(6).forEachIndexed { i, g ->
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
    val groups = io.github.lswlc33.maibms.protocol.ParamTable.groups
    val (groupName, range) = groups.getOrElse(groupIndex) { groups[0] }
    val defs = io.github.lswlc33.maibms.protocol.ParamTable.defs.filter { it.addr in range }
    val live by MockBms.liveParams.collectAsState()
    val reading by MockBms.paramsReading.collectAsState()
    val usingReal by MockBms.usingRealBle.collectAsState()
    val status by MockBms.status.collectAsState()

    /**
     * 一律以 ParamTable 的地址为准取值（真机=读回区，演示=虚拟引擎参数区）。
     * 早先演示模式按 `MockBms.paramGroups` 的名字取值，而那张表的地址与 docs 附录A 对不上
     * （均衡组把 140/142 标成了启动/结束压差），会张冠李戴，已废弃这条路径。
     */
    fun itemOf(d: io.github.lswlc33.maibms.protocol.ParamDef): io.github.lswlc33.maibms.data.ParamItem {
        return io.github.lswlc33.maibms.data.ParamItem(
            name = d.name,
            value = io.github.lswlc33.maibms.protocol.ParamTable.format(live, d),
            unit = d.unit,
            addr = "0x%X".format(d.addr),
            scale = if (d.scale >= 1000) "1e${d.scale.toLong().toString().length - 1}" else d.scale.toLong().toString(),
            range = io.github.lswlc33.maibms.protocol.ParamTable.rangeText(d),
        )
    }

    ScreenScaffold(
        title = groupName,
        subtitle = "地址 ${range.first}~${range.last} · ${defs.size} 项 · 倍率见条目",
        onBack = onBack,
        trailing = { PermissionBadge(status.permissionLevel, onOpenPerm) },
    ) {
        val connected by remember { io.github.lswlc33.maibms.data.Bms.repository.linkState }
            .collectAsState()
        if (reading) InfoBanner("读取中…", kind = "info")
        else if (connected != io.github.lswlc33.maibms.transport.LinkState.Connected) {
            InfoBanner("未连接保护板，当前显示的是本机缓存（无数据则为 --）", kind = "warn")
        }
        SectionCard {
            defs.forEach { d ->
                val it = itemOf(d)
                // 无单位的参数（屏蔽位、编码值）不要留尾随空格
                val shown = if (d.unit.isBlank()) it.value else "${it.value} ${d.unit}"
                SettingRow(title = d.name, inlineValue = shown, onClick = { onEdit(it) })
            }
            if (defs.isEmpty()) SettingRow(title = "该分组暂未定义条目")
        }
        val writeCode by io.github.lswlc33.maibms.data.MockBms.lastWriteResult.collectAsState()
        val writeDetail by io.github.lswlc33.maibms.data.MockBms.lastWriteDetail.collectAsState()
        if (writeCode != -1) {
            val ok = io.github.lswlc33.maibms.protocol.ResultCodes.writeOk(writeCode)
            val head = if (writeCode == -2) "写参数：未收到应答" else "写参数结果：${io.github.lswlc33.maibms.protocol.ResultCodes.writeResult(writeCode)}"
            InfoBanner(head + (writeDetail?.let { "（$it）" } ?: ""), kind = if (ok) "info" else "err")
        }
        if (usingReal && live.isEmpty() && !reading) {
            InfoBanner("保护参数区未读到数据：真机实测需 2 级及以上权限", kind = "warn", action = "去校验", onAction = onOpenPerm)
        } else {
            InfoBanner("修改写入临时区，需「保存应用参数」才持久化", kind = "info", action = "立即保存", onAction = onSave)
        }
    }
}

/* ---------- S08 控制与工具 ---------- */

@Composable
fun ControlToolsScreen(
    onBack: () -> Unit,
    onCommand: (String) -> Unit,
    onOpenPerm: () -> Unit = {},
) {
    val chargeOn by MockBms.chargeSwitch.collectAsState()
    val dischargeOn by MockBms.dischargeSwitch.collectAsState()
    val balanceOn by MockBms.balanceSwitch.collectAsState()
    val status by MockBms.status.collectAsState()
    ScreenScaffold(
        title = "控制与工具",
        subtitle = "全部命令执行前需确认 · 高危项需输入确认",
        onBack = onBack,
        trailing = { PermissionBadge(status.permissionLevel, onOpenPerm) },
    ) {
        // 只说「为什么现在点了会失败」的一次性原因；等级本身右上角数字标识里常显
        val link by remember { io.github.lswlc33.maibms.data.Bms.repository.linkState }.collectAsState()
        val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
        if (!connected) {
            InfoBanner("未连接保护板 · 控制命令需要先连上设备", kind = "warn")
        }
        SectionCard {
            SectionHeader("开关控制", tail = "51/1·3·4·6·52")
            // 开关做小了，整行也做成可点，触摸目标才够（点哪都能切）
            val toggleCharge: (Boolean) -> Unit = { MockBms.chargeSwitch.value = it; onCommand("充电开关") }
            val toggleDischarge: (Boolean) -> Unit = { MockBms.dischargeSwitch.value = it; onCommand("放电开关") }
            val toggleBalance: (Boolean) -> Unit = { MockBms.balanceSwitch.value = it; onCommand("均衡开关") }
            SettingRow(title = "充电开关", trailing = { AppSwitch(chargeOn, toggleCharge) }, onClick = { toggleCharge(!chargeOn) })
            SettingRow(title = "放电开关", trailing = { AppSwitch(dischargeOn, toggleDischarge) }, onClick = { toggleDischarge(!dischargeOn) })
            SettingRow(title = "强制开启充电", trailing = { Chevron() }, onClick = { onCommand("强制开启充电") })
            SettingRow(title = "均衡开关", trailing = { AppSwitch(balanceOn, toggleBalance) }, onClick = { toggleBalance(!balanceOn) })
        }
        SectionCard {
            SectionHeader("校准与维护", tail = "51/7·8·36·44")
            SettingRow(title = "保存应用参数", trailing = { Chevron() }, onClick = { onCommand("保存应用参数") })
            SettingRow(title = "电流归零", trailing = { Chevron() }, onClick = { onCommand("电流归零") })
            SettingRow(title = "重启系统", trailing = { Chevron() }, onClick = { onCommand("重启系统") })
            SettingRow(title = "蜂鸣器", trailing = { Chevron() }, onClick = { onCommand("蜂鸣器") })
        }
        SectionCard {
            SettingRow(title = "清零类操作", danger = true, trailing = { Chevron() }, onClick = { onCommand("清零类操作") })
            SettingRow(title = "恢复出厂 / 恢复厂家设置", danger = true, trailing = { Chevron() }, onClick = { onCommand("恢复出厂设置") })
            SettingRow(title = "关闭系统 / 蓝牙关闭", danger = true, trailing = { Chevron() }, onClick = { onCommand("关闭系统") })
        }
        // 命令结果回显：0x61 应答码表与控制开关不同，必须显式告诉用户成没成
        // （「保存应用参数」也是 0x51 命令，结果同样走这条；开关命令不回显，仪表盘已就地显示）
        val ctrl by MockBms.lastControlResult.collectAsState()
        ctrl?.let { (cmd, code) ->
            if (io.github.lswlc33.maibms.protocol.ControlCmd.name(cmd).let { n ->
                    n == "保存应用参数" || n.startsWith("恢复") || n.contains("清") || n.contains("重置") ||
                    n == "重启系统" || n == "关闭系统" || n == "电流归零" || n.contains("预设") || n.contains("蜂鸣")
                }
            ) {
                InfoBanner(
                    "${io.github.lswlc33.maibms.protocol.ControlCmd.name(cmd)}：${io.github.lswlc33.maibms.protocol.ResultCodes.controlResult(code)}",
                    kind = if (code == 1) "info" else "err",
                )
            }
        }
    }
}

/* ---------- S07 参数编辑弹窗 ---------- */

@Composable
fun ParamEditDialog(item: io.github.lswlc33.maibms.data.ParamItem, onDismiss: () -> Unit, onWrite: (Int) -> Unit = {}) {
    // 读不到值时别把 "--" 填进输入框，留空让用户直接输；枚举类条目显示的是文字，同样留空
    var input by remember { mutableStateOf(if (item.value.toDoubleOrNull() == null) "" else item.value) }
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
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("新值（${item.unit}）") },
                singleLine = true,
                isError = input.toDoubleOrNull() == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            // 校验：非数字/超范围都不允许提交（旧实现在这两种情况下会静默写 0 或写超限值）
            val parsed = input.replace(',', '.').toDoubleOrNull()
            val bounds = item.range.substringBefore(' ').split('~').mapNotNull { it.toDoubleOrNull() }
            val outOfRange = parsed != null && bounds.size == 2 && (parsed < bounds[0] || parsed > bounds[1])
            Text(
                when {
                    parsed == null -> "请输入数字"
                    outOfRange -> "超出允许范围（${item.range}）"
                    else -> "范围 ${item.range} · 地址 ${item.addr} · 倍率 ${item.scale}"
                },
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = if (parsed == null || outOfRange) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(4.dp))
                Button(
                    enabled = parsed != null && !outOfRange,
                    onClick = {
                        val addrInt = item.addr.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                        val scale = io.github.lswlc33.maibms.protocol.ParamTable.byAddr(addrInt)?.scale ?: 1.0
                        val raw = ((parsed ?: 0.0) * scale).toLong()
                        onWrite(raw.toInt())
                        onDismiss()
                    },
                ) { Text("写入 0x22") }
            }
        }
    }
}
