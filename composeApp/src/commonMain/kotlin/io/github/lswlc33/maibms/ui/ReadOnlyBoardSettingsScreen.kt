package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.transport.DeviceFamily
import io.github.lswlc33.maibms.transport.LinkState

/** 新板只展示协议直接读到的参数，没有编辑、控制或保存到设备的入口。 */
@Composable
fun ReadOnlyBoardSettingsScreen(
    onBack: (() -> Unit)? = null,
    bottomPadding: Dp = 20.dp,
    onConnect: () -> Unit = {},
) {
    val repo = Bms.repository
    val settings by MockBms.boardSettings.collectAsState()
    val error by MockBms.readError.collectAsState()
    val family by repo.currentFamily.collectAsState()
    val link by repo.linkState.collectAsState()
    val preview by repo.previewActive.collectAsState()
    val fromCache by MockBms.paramsFromCache.collectAsState()
    var accessOpen by remember { mutableStateOf(false) }
    val connected = link == LinkState.Connected && !preview
    ScreenScaffold(
        title = "只读参数",
        subtitle = "${family.label} · 已读取 ${settings.size} 项",
        onBack = onBack,
        bottomPadding = bottomPadding,
        trailing = { TextButton(onClick = { repo.refreshBoardSettings() }, enabled = connected) { Text("刷新") } },
    ) {
        if (preview) InfoBanner("快照中的只读参数", kind = "info")
        else if (!connected) InfoBanner(
            if (fromCache) "未连接 · 显示上次成功读取的参数缓存" else "未连接设备",
            kind = "info", action = "连接设备", onAction = onConnect,
        )
        error?.let { InfoBanner(it, kind = "warn") }
        if (family == DeviceFamily.Jbd) {
            SectionCard {
                SettingRow(title = "蓝牙访问", inlineValue = "已有密码登录", trailing = { Chevron() },
                    onClick = if (preview) null else ({ accessOpen = true }))
                Text("普通模块可直接读取；需要蓝牙密码的模块可使用已有密码登录。",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (settings.isEmpty()) InfoBanner(
            if (connected) "等待设备返回可直接读取的参数…" else "连接后自动读取参数", kind = "info",
        )
        settings.groupBy { it.group }.forEach { (group, rows) ->
            SectionCard {
                SectionHeader(group, tail = "只读")
                rows.forEach { item ->
                    SettingRow(title = item.name,
                        inlineValue = listOf(item.value, item.unit).filter { it.isNotBlank() }.joinToString(" "))
                }
            }
        }
        Text(
            if (family == DeviceFamily.Jbd)
                "保护阈值需进入工厂模式，本应用不读取这部分。只读接入不修改参数、MOS 或设备密码。"
            else "仅显示设备实际返回的参数，不发送控制、校准或保存命令。",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (accessOpen) {
        val saved = remember { repo.boardAccessFor() }
        var password by remember { mutableStateOf(saved.first) }
        var mac by remember { mutableStateOf(saved.second) }
        var validation by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { accessOpen = false },
            title = { Text("嘉佰达蓝牙访问") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("仅在本机记住已有凭据，用于连接登录，不改设备密码。若模块要求系统配对，请先在系统蓝牙中完成配对。", fontSize = 12.sp)
                    OutlinedTextField(password, { password = it }, label = { Text("已有蓝牙密码（6 位字符）") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(mac, { mac = it }, label = { Text("模块 MAC（AA:BB:CC:DD:EE:FF）") }, singleLine = true)
                    Text("iOS 和网页的设备标识不是 MAC，请从模块标签或原厂 App 读取真实地址。", fontSize = 10.sp)
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (password.length == 6 && repo.setBoardAccess(password, mac)) accessOpen = false
                    else validation = "请输入 6 位 ASCII 密码和完整的模块 MAC 地址"
                }) { Text(if (connected) "登录并读取" else "保存到本机") }
            },
            dismissButton = {
                TextButton(onClick = { repo.setBoardAccess("", ""); accessOpen = false }) { Text("清除本机凭据") }
            },
        )
    }
}
