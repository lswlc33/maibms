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
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.DeviceProfile
import io.github.lswlc33.maibms.data.DeviceProfiles
import io.github.lswlc33.maibms.data.formatShortDateTime

/* ---------- S17 历史设备（档案 + 自动重连目标 + 按设备密码） ---------- */

/** 历史连接列表：只能通过连接设备自动入列；这里负责编辑备注/管密码/删设备/选重连目标。 */
@Composable
fun DeviceScreen(onBack: () -> Unit) {
    val repo = Bms.repository
    // profiles 从 KV 读，改动后整表刷新
    var profiles by remember { mutableStateOf(DeviceProfiles.all()) }
    var autoTarget by remember { mutableStateOf(AppStore.autoConnectAddress) }
    var detailFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DeviceProfile?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val lastAddress = AppStore.savedAddress

    fun refresh() {
        profiles = DeviceProfiles.all()
        autoTarget = AppStore.autoConnectAddress
        if (profiles.none { it.address == detailFor }) detailFor = null
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
                key(p.address) {
                    AutoRow(
                        title = p.displayName,
                        subtitle = p.address,
                        selected = autoTarget == p.address,
                        onClick = { AppStore.autoConnectAddress = p.address; refresh(); note = "自动重连目标：${p.displayName}" },
                    )
                }
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
                    key(p.address) {
                        DeviceRow(
                            profile = p,
                            isLast = p.address == lastAddress,
                            onClick = { detailFor = p.address },
                        )
                    }
                }
            }
        }
        InfoBanner(
            "删除设备会连同其密码与数据快照一并清除；自动重连目标被删后回退为上次连接",
            kind = "warn", action = "了解", rememberKey = "device-delete-warning",
        )
        note?.let { InfoBanner(it, kind = "info") }
    }

    profiles.firstOrNull { it.address == detailFor }?.let { p ->
        key(p.address) {
            DeviceDetailDialog(
                profile = p,
                onDismiss = { detailFor = null },
                onChanged = { refresh() },
                onDeleteRequest = { detailFor = null; pendingDelete = p },
            )
        }
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
                // 多级密码是蚂蚁保护板专有；电量计档案没有密码库，摘要里也不再提「密码 N 级」
                "${profile.address} · ${formatShortDateTime(profile.lastConnectedAt)}" +
                    if (profile.family.isMeter) "" else " · 密码 ${profile.passwords.size} 级",
                fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Chevron()
    }
}

/** 设备详情：按地址重取最新档案，改备注/密码后不持有旧快照。 */
@Composable
private fun DeviceDetailDialog(
    profile: DeviceProfile,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var alias by remember(profile.address, profile.alias) { mutableStateOf(profile.alias ?: "") }
    var editingLevel by remember(profile.address) { mutableStateOf<Int?>(null) }
    var busy by remember(profile.address) { mutableStateOf(false) }
    var error by remember(profile.address) { mutableStateOf<String?>(null) }

    // 一次只显示一个 Dialog，键盘与返回键不会落到下面的详情窗口。
    if (editingLevel == null) {
        BoundedSettingsDialog(onDismiss = { if (!busy) onDismiss() }) {
            Text(profile.displayName, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface)
            Text("${profile.address} · ${formatShortDateTime(profile.lastConnectedAt)}",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(20); error = null },
                label = { Text("备注名（留空 = 用蓝牙名）", fontSize = 11.sp) },
                singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            if (!profile.family.isMeter) {
                Column(Modifier.padding(top = 10.dp)) {
                    Text("密码库 · 已记住 ${profile.passwords.size} 级", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    listOf(1, 2, 3, 4, 5, 9).forEach { lv ->
                        key(lv) {
                            val saved = profile.passwords[lv]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(enabled = !busy) { editingLevel = lv }.padding(vertical = 6.dp),
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
                }
            }
            error?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = !busy, onClick = onDeleteRequest) {
                Text("删除设备", color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
                TextButton(enabled = !busy, onClick = {
                    if (busy) return@TextButton
                    busy = true
                    try {
                        DeviceProfiles.rename(profile.address, alias)
                        onChanged()
                        // 保持详情打开，新档案立即更新标题与摘要。
                    } catch (e: Exception) {
                        error = "保存备注失败 · 请重试"
                    } finally {
                        busy = false
                    }
                }) { Text("保存备注", fontWeight = FontWeight.Bold) }
            }
        }
    }
    editingLevel?.let { lv ->
        key(profile.address, lv) {
            PasswordEditDialog(profile.address, lv, onDismiss = { editingLevel = null; onChanged() })
        }
    }
}

/** 删除确认也使用有限、可滚动的视口。 */
@Composable
private fun ConfirmDialog(title: String, body: String, confirmText: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BoundedSettingsDialog(onDismiss = { if (!busy) onDismiss() }) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
        error?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
            TextButton(enabled = !busy, onClick = {
                if (busy) return@TextButton
                busy = true
                try {
                    onConfirm()
                } catch (e: Exception) {
                    error = "删除失败 · 请重试"
                } finally {
                    busy = false
                }
            }) {
                Text(confirmText, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
    }
}
