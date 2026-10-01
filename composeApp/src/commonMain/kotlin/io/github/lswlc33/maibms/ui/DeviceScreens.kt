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
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.DeviceProfile
import io.github.lswlc33.maibms.data.DeviceProfiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------- S17 历史设备（档案 + 自动重连目标 + 按设备密码） ---------- */

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** 历史连接列表：只能通过连接设备自动入列；这里负责编辑备注/管密码/删设备/选重连目标。 */
@Composable
fun DeviceScreen(onBack: () -> Unit) {
    val repo = Bms.repository
    // profiles 从 KV 读，改动后整表刷新
    var profiles by remember { mutableStateOf(DeviceProfiles.all()) }
    var autoTarget by remember { mutableStateOf(AppStore.autoConnectAddress) }
    var detailFor by remember { mutableStateOf<DeviceProfile?>(null) }
    var pendingDelete by remember { mutableStateOf<DeviceProfile?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val lastAddress = AppStore.savedAddress

    fun refresh() {
        profiles = DeviceProfiles.all()
        autoTarget = AppStore.autoConnectAddress
    }
    LaunchedEffect(note) {
        if (note != null) { kotlinx.coroutines.delay(2000); note = null }
    }

    // 生效中的重连目标：显式指定且还在列表里 → 它；否则上次连接
    val effectiveTarget = autoTarget
        ?.takeIf { addr -> profiles.any { it.address == addr } }
        ?: lastAddress

    ScreenScaffold(
        title = "历史设备",
        subtitle = "连接过的设备自动入列 · 自动重连：${
            profiles.firstOrNull { it.address == effectiveTarget }?.displayName ?: "未指定"
        }",
        onBack = onBack,
    ) {
        SectionCard {
            SectionHeader("自动重连", tail = "启动时回连")
            // 默认 = 上次连接；也可以从历史列表里指定固定目标
            AutoRow(
                title = "上次连接（默认）",
                selected = autoTarget == null,
                onClick = { AppStore.autoConnectAddress = null; refresh() },
            )
            profiles.forEach { p ->
                AutoRow(
                    title = p.displayName,
                    subtitle = p.address,
                    selected = autoTarget == p.address,
                    onClick = { AppStore.autoConnectAddress = p.address; refresh(); note = "自动重连目标：${p.displayName}" },
                )
            }
        }
        SectionCard {
            SectionHeader("历史设备", tail = profiles.size.toString())
            if (profiles.isEmpty()) {
                Text(
                    "暂无历史设备 · 从首页「＋」扫描连接后自动记录",
                    fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                profiles.sortedByDescending { it.lastConnectedAt }.forEach { p ->
                    DeviceRow(
                        profile = p,
                        isLast = p.address == lastAddress,
                        onClick = { detailFor = p },
                    )
                }
            }
        }
        InfoBanner("删除设备会连同其密码与数据快照一并清除；自动重连目标被删后回退为上次连接", kind = "warn", action = "了解")
        note?.let { InfoBanner(it, kind = "info") }
    }

    detailFor?.let { p ->
        DeviceDetailDialog(
            profile = p,
            isAutoTarget = autoTarget == p.address,
            onDismiss = { detailFor = null },
            onChanged = { refresh() },
            onDeleteRequest = { detailFor = null; pendingDelete = p },
        )
    }
    pendingDelete?.let { p ->
        ConfirmDialog(
            title = "删除设备",
            body = "删除「${p.displayName}」？\n该设备保存的密码与全部数据快照将一并删除。",
            confirmText = "删除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                repo.deleteDevice(p.address)
                refresh()
                note = "已删除 ${p.displayName}"
                pendingDelete = null
            },
        )
    }
}

/** 自动重连选择行（RadioDot 单选，不进详情） */
@Composable
private fun AutoRow(title: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
    ) {
        RadioDot(selected = selected)
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 历史设备列表行：显示名 + 摘要，点行进详情 */
@Composable
private fun DeviceRow(profile: DeviceProfile, isLast: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (isLast) {
                    Text("上次连接", fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 5.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Text(
                "${profile.address} · ${timeFmt.format(Date(profile.lastConnectedAt))} · 密码 ${profile.passwords.size} 级",
                fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Chevron()
    }
}

/** 设备详情：改备注名 / 管密码 / 设为重连目标 / 删除 */
@Composable
private fun DeviceDetailDialog(
    profile: DeviceProfile,
    isAutoTarget: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var alias by remember { mutableStateOf(profile.alias ?: "") }
    var editingLevel by remember { mutableStateOf<Int?>(null) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(profile.displayName, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text("${profile.address} · ${timeFmt.format(Date(profile.lastConnectedAt))}",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(20) },
                label = { Text("备注名（留空 = 用蓝牙名）", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            // 按设备密码库：逐级进入编辑（复用权限页的密码弹窗，按地址写入档案）
            Column(Modifier.padding(top = 10.dp)) {
                Text("密码库", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                listOf(1, 2, 3, 4, 5, 9).forEach { lv ->
                    val saved = profile.passwords[lv]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { editingLevel = lv }.padding(vertical = 6.dp),
                    ) {
                        Text(if (lv == 9) "9 级厂家" else "$lv 级", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text(saved?.let { "••••••••" } ?: "未设置", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("删除设备", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BmsColors.BadRed,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDeleteRequest).padding(horizontal = 6.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    DeviceProfiles.rename(profile.address, alias)
                    onChanged()
                    onDismiss()
                }) { Text("保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
    // 密码编辑：按地址写入档案（复用权限页弹窗）
    editingLevel?.let { lv ->
        PasswordEditDialog(profile.address, lv, onDismiss = { editingLevel = null; onChanged() })
    }
}

/** 删除确认（同快照页样式） */
@Composable
private fun ConfirmDialog(title: String, body: String, confirmText: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onConfirm) {
                    Text(confirmText, color = BmsColors.BadRed, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
