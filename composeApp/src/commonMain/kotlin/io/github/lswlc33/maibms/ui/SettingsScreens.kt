package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch

/* ---------- S10 设置主页（外观/连接/关于 的设置项平铺在本页，不再进二级页） ---------- */

@Composable
fun SettingsHomeScreen(
    onOpen: (Route) -> Unit,
    onOpenScan: () -> Unit = {},
    darkOverride: Boolean?,
    onDarkOverrideChange: (Boolean?) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
) {
    // 外观
    val realBle by MockBms.usingRealBle.collectAsState()
    val scope = rememberCoroutineScope()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val hint by io.github.lswlc33.maibms.data.Bms.repository.connectHint.collectAsState()
    val manual by io.github.lswlc33.maibms.data.Bms.repository.manualDisconnect.collectAsState()
    val saved by MockBms.plainPasswords.collectAsState()
    // 关于（身份区）
    val status by MockBms.status.collectAsState()
    val id by MockBms.identity.collectAsState()
    val reading by MockBms.paramsReading.collectAsState()
    val bleName by MockBms.connectedDeviceName.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    /** 身份区/状态里的值，空则显示 --（原设备信息页的逻辑） */
    fun v(real: String?, fallback: String) = real?.ifBlank { null } ?: fallback.ifBlank { "--" }

    ScreenScaffold(title = "设置", bottomPadding = bottomPadding) {
        // 边距与配置页一致：卡片内距 CardPadding、行距默认、卡片间隔 7dp
        SectionCard {
            SectionHeader("外观")
            listOf<Pair<String, Boolean?>>("跟随系统" to null, "浅色" to false, "深色" to true).forEach { (label, themeValue) ->
                SettingRow(
                    title = label,
                    trailing = { RadioDot(selected = darkOverride == themeValue) },
                    onClick = { onDarkOverrideChange(themeValue) },
                )
            }
            // 深色恒为纯黑（原来的 AMOLED 开关已去掉，默认即纯黑）
        }
        SectionCard {
            SectionHeader("连接", tail = "BLE")
            SettingRow(title = "数据源", inlineValue = if (realBle) "真机 BLE" else "未就绪")
            SettingRow(
                title = "链路状态",
                inlineValue = when {
                    manual -> "已手动断开"
                    connected -> "已连接"
                    link == io.github.lswlc33.maibms.transport.LinkState.Connecting -> "连接中"
                    link == io.github.lswlc33.maibms.transport.LinkState.Disconnected -> "已断开"
                    else -> "未连接"
                },
            )
            // 纯信息行：不加 Chevron，免得看着能点却点不动
            SettingRow(
                title = "记忆设备",
                inlineValue = (MockBms.savedAddress?.let { "${MockBms.deviceLabel} · $it" }) ?: "无",
            )
            SettingRow(title = "重新扫描连接", trailing = { Chevron() }, onClick = onOpenScan)
            if (connected) {
                SettingRow(title = "断开连接", danger = true, trailing = { Chevron() }, onClick = {
                    scope.launch { io.github.lswlc33.maibms.data.Bms.repository.disconnect() }
                })
            }
            hint?.let { InfoBanner(it, kind = "warn") }
        }
        SectionCard {
            SettingRow(
                title = "权限与密码",
                inlineValue = if (saved.isEmpty()) "未设置" else "已记住 " + saved.keys.sorted().joinToString("/") + " 级",
                trailing = { Chevron() },
                onClick = { onOpen(Route.Password) },
            )
            SettingRow(title = "开发者", trailing = { Chevron() }, onClick = { onOpen(Route.Developer) })
        }
        SectionCard {
            SectionHeader("关于", tail = "身份区")
            if (reading) InfoBanner("身份区读取中…", kind = "info")
            else if (!connected) InfoBanner("未连接保护板 · 连接后自动读取身份区", kind = "warn")
            else if (id.isEmpty()) InfoBanner("身份区暂时读不到（权限不足或设备未就绪）", kind = "warn")
            SettingRow(title = "软件版本", inlineValue = v(id["swVersion"], status.swVersion))
            SettingRow(title = "硬件版本", inlineValue = v(id["hwVersion"], status.hwVersion))
            SettingRow(title = "Boot 版本", inlineValue = v(id["boot"], "--"))
            SettingRow(title = "设备地址", inlineValue = "A1")
            SettingRow(title = "电池组 ID", inlineValue = v(id["packId"], "--"))
            SettingRow(title = "Code Key", inlineValue = v(id["codeKey"], "--"))
            SettingRow(title = "蓝牙名称", inlineValue = (bleName ?: status.deviceName).trim())
            SettingRow(title = "蓝牙地址", inlineValue = MockBms.savedAddress ?: "--")
        }
    }
}

/* ---------- S13 权限与密码（设备密码库） ---------- */

@Composable
fun PasswordScreen(onBack: () -> Unit) {
    val passwords by MockBms.passwords.collectAsState()
    val autoLevel by MockBms.autoUpgradeLevel.collectAsState()
    val status by MockBms.status.collectAsState()
    val saved by MockBms.plainPasswords.collectAsState()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    var editing by remember { mutableStateOf<Int?>(null) }
    var levelMenu by remember { mutableStateOf(false) }
    // 未连接时实时数据里没有设备名，用记住的那台；否则会显示成「设备：--」
    val label = MockBms.deviceLabel
    val online = status.hasData
    ScreenScaffold(
        title = "权限与密码",
        subtitle = if (online) "设备：$label · 当前权限 ${status.permissionLevel} 级"
                   else "设备：$label · 未连接（权限 ${status.permissionLevel} 级）",
        onBack = onBack,
    ) {
        SectionCard {
            SectionHeader("该设备的密码库", tail = "点行设置/修改")
            passwords.forEach { pw ->
                SettingRow(
                    title = if (pw.level == 9) "9 级厂家" else "${pw.level} 级",
                    inlineValue = pw.masked ?: "未设置",
                    trailing = {
                        if (pw.isCurrent) Text("当前", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        else Chevron()
                    },
                    onClick = { editing = pw.level },
                )
            }
        }
        SectionCard {
            SectionHeader("连接行为", tail = "0x23")
            Box {
                SettingRow(
                    title = "连接后自动升级到",
                    inlineValue = if (autoLevel > 0) "$autoLevel 级" else "自动（最高可用）",
                    trailing = { Text("⌄", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { levelMenu = true },
                )
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    listOf(0, 1, 2, 3, 4, 5, 9).forEach { lv ->
                        val has = saved.containsKey(lv)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (lv == 0) "自动（最高可用）" else if (lv == 9) "9 级厂家" else "$lv 级",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingIcon = {
                                Text(
                                    when {
                                        lv == 0 -> ""
                                        has -> "已记住"
                                        else -> "未设置"
                                    },
                                    fontSize = 10.sp,
                                    color = if (lv != 0 && has) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                io.github.lswlc33.maibms.data.Bms.repository.setAutoUpgradeTarget(lv)
                                levelMenu = false
                            },
                        )
                    }
                }
            }
        }
        if (!connected) {
            InfoBanner("未连接保护板 · 密码可先存本地，连上后自动校验并升权", kind = "warn")
        } else if (saved.isEmpty()) {
            InfoBanner("尚未记住任何密码 · 点上方等级设置，保存时直接向设备校验", kind = "warn")
        } else {
            val savedLabel = saved.keys.sorted().joinToString("/") + " 级"
            InfoBanner("已记住 $savedLabel 密码 · 连接时自动升级，权限回落会自动重升", kind = "info")
        }
        InfoBanner("密码为明文存储，勿共用设备", kind = "err", action = "了解")
    }
    editing?.let { lv -> PasswordEditDialog(lv, onDismiss = { editing = null }) }
}

/** 某一级的密码设置弹窗：保存（连上时顺带校验，成功即升权）/ 清除 */
@Composable
private fun PasswordEditDialog(level: Int, onDismiss: () -> Unit) {
    val saved by MockBms.plainPasswords.collectAsState()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    val existing = saved[level]
    // 已记住的直接带出来，省得重打一遍（本机就是明文存的，页脚也声明了）
    var input by remember { mutableStateOf(existing ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val slot = io.github.lswlc33.maibms.protocol.ParamTable.slotAddr(level)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text(
                if (level == 9) "9 级厂家密码" else "$level 级密码",
                fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "该设备的密码库 · 寄存器 $slot" + if (connected) " · 保存时向设备校验" else " · 未连接，仅存本地",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = input, onValueChange = { input = it; error = null },
                label = { Text("密码") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            error?.let {
                Text(it, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 5.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    TextButton(enabled = !busy, onClick = {
                        io.github.lswlc33.maibms.data.Bms.repository.forgetPassword(level)
                        onDismiss()
                    }) { Text("清除", color = BmsColors.BadRed) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(4.dp))
                Button(
                    enabled = input.isNotBlank() && !busy,
                    onClick = {
                        val pw = input.trim()
                        scope.launch {
                            busy = true
                            val repo = io.github.lswlc33.maibms.data.Bms.repository
                            if (connected) {
                                // 校验失败不存：错密码留在库里只会让每次连接都白试
                                val lv = repo.auth(level, pw)
                                busy = false
                                if (lv > 0) onDismiss() else error = "密码不正确 · 未保存"
                            } else {
                                repo.savePassword(level, pw)
                                busy = false
                                onDismiss()
                            }
                        }
                    },
                ) { Text(if (busy) "校验中…" else if (connected) "保存并校验" else "保存") }
            }
        }
    }
}

/* ---------- S15 开发者 ---------- */

@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    val logOn by io.github.lswlc33.maibms.data.BmsLog.frameLogOn.collectAsState()
    val lines by io.github.lswlc33.maibms.data.BmsLog.lines.collectAsState()
    ScreenScaffold(title = "开发者", onBack = onBack) {
        SectionCard {
            SettingRow(title = "报文调试日志",
                trailing = { AppSwitch(logOn) { io.github.lswlc33.maibms.data.BmsLog.frameLogOn.value = it } },
                onClick = { io.github.lswlc33.maibms.data.BmsLog.frameLogOn.value = !logOn })
        }
        // 操作行放在日志框「上面」：日志满 300 行时有近六屏高，放下面根本够不着
        SectionCard {
            SettingRow(title = "清空日志", trailing = { Chevron() }, onClick = { io.github.lswlc33.maibms.data.BmsLog.clear() })
            // 原来这行只有 Chevron 没有 onClick（点了没反应）。改为真的复制到剪贴板并给回执
            val copy = rememberClipboardWriter()
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) { kotlinx.coroutines.delay(1600); copied = false }
            }
            SettingRow(
                title = "复制全部日志",
                inlineValue = if (lines.isEmpty()) "暂无记录" else "${lines.size} 行",
                trailing = {
                    if (copied) Text("已复制", fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    else Chevron()
                },
                onClick = { if (lines.isNotEmpty()) copied = copy(lines.joinToString("\n")) },
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF0C100F)).padding(11.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    "（暂无记录：连接保护板后此处显示收发帧）",
                    fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                    color = androidx.compose.ui.graphics.Color(0xFF6E8A7C),
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
            lines.forEach { line ->
                Text(
                    line, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                    color = androidx.compose.ui.graphics.Color(0xFF9BE8C4),
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}
