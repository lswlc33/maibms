package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsRepository
import io.github.lswlc33.maibms.data.BmsSnapshot
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.data.SnapshotCodec
import io.github.lswlc33.maibms.data.fmt
import kotlinx.coroutines.launch

/* ---------- S16 快照管理与预览 ---------- */

/**
 * 快照列表页：自动记录开关 + 已保存快照（点行进入预览）。
 * 预览激活期间自动连接停用直到重启（见 BmsRepository.enterPreview 的防护口）。
 */
@Composable
fun SnapshotScreen(onBack: () -> Unit, onPreviewed: () -> Unit) {
    val repo = Bms.repository
    val previewing by repo.previewActive.collectAsState()
    val previewLabel by repo.previewLabel.collectAsState()
    var snapEnabled by remember { mutableStateOf(AppStore.snapshotEnabled) }
    // 快照列表存在 KV 里（JSON 文本），读一次进内存状态；删除/清空后刷新
    var snapshots by remember { mutableStateOf(AppStore.loadSnapshots()) }
    var pendingDelete by remember { mutableStateOf<BmsSnapshot?>(null) }
    var pendingClear by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() { snapshots = AppStore.loadSnapshots() }
    // 操作反馈 2 秒自动清除（同 Developer 页 actionNote 的节奏）
    LaunchedEffect(note) {
        if (note != null) { kotlinx.coroutines.delay(2000); note = null }
    }

    ScreenScaffold(title = "快照", subtitle = "连接完成全量同步后自动记录 · 未连接时可回看", onBack = onBack) {
        SectionCard {
            SectionHeader("自动记录", tail = if (snapEnabled) "开" else "关")
            SettingRow(
                title = "连接后自动记录快照",
                inlineValue = if (snapEnabled) "开启" else "关闭",
                trailing = { AppSwitch(snapEnabled) { snapEnabled = it; AppStore.snapshotEnabled = it } },
                onClick = { snapEnabled = !snapEnabled; AppStore.snapshotEnabled = snapEnabled },
            )
            InfoBanner(
                if (snapEnabled) "每次连接完成参数区同步后记录一张（同一连接只保留最新），最多 ${20} 张"
                else "已关闭：不再自动记录，已保存的快照仍可预览",
                kind = if (snapEnabled) "info" else "warn",
            )
        }
        if (previewing) {
            InfoBanner("正在预览快照 · ${previewLabel ?: ""}（自动连接已停用，重启应用恢复）", kind = "info")
        }
        SectionCard {
            SectionHeader("已保存快照", tail = snapshots.size.toString())
            if (snapshots.isEmpty()) {
                Text(
                    "暂无快照 · 连接设备并完成同步后自动记录",
                    fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                snapshots.forEach { s ->
                    SnapshotRow(
                        snapshot = s,
                        onOpen = {
                            scope.launch {
                                repo.enterPreview(s)
                                onPreviewed()   // 跳到仪表盘看快照数据
                            }
                        },
                        onDelete = { pendingDelete = s },
                    )
                }
            }
        }
        if (snapshots.isNotEmpty()) {
            SectionCard {
                // 与单条删除一致走确认弹窗：一键抹掉全部快照不可恢复，不该点一下就执行
                SettingRow(title = "清空全部快照", danger = true, trailing = { Chevron() }, onClick = {
                    pendingClear = true
                })
            }
        }
        note?.let { InfoBanner(it, kind = "info") }
    }
    pendingDelete?.let { s ->
        ConfirmDialog(
            title = "删除快照",
            body = "删除「${s.deviceName} · ${s.timeLabel}」？删除后不可恢复。",
            confirmText = "删除",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                AppStore.deleteSnapshot(s.id); refresh()
                note = "已删除快照（${s.timeLabel}）"
                pendingDelete = null
            },
        )
    }
    if (pendingClear) {
        ConfirmDialog(
            title = "清空全部快照",
            body = "将删除全部 ${snapshots.size} 张快照（含各设备的），删除后不可恢复。",
            confirmText = "全部删除",
            onDismiss = { pendingClear = false },
            onConfirm = {
                AppStore.clearSnapshots(); refresh()
                note = "已清空全部快照"
                pendingClear = false
            },
        )
    }
}

/** 快照列表行：标题 = 设备名，副行 = 关键读数摘要，点行进预览；行尾删除。 */
@Composable
private fun SnapshotRow(snapshot: BmsSnapshot, onOpen: () -> Unit, onDelete: () -> Unit) {
    val st = snapshot.status
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 5.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                snapshot.deviceName.ifBlank { "未命名设备" },
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${snapshot.timeLabel} · SOC ${st.soc}% · ${"%.1f".fmt(st.totalVoltage)}V",
                fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Text(
            "删除",
            fontSize = 10.sp, fontWeight = FontWeight.Bold,
            // 用主题 error 而非 BadRed：暗色下 BadRed(#D23B36) 压深灰卡面对比度不足
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(horizontal = 8.dp, vertical = 5.dp),
        )
        Chevron()
    }
}

/** 通用确认弹窗：删除快照/清空共用（危险操作红字确认键） */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
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
                    Text(confirmText, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
