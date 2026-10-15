package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.protocol.EventLogCodec
import io.github.lswlc33.maibms.protocol.EventLogRecord
import io.github.lswlc33.maibms.transport.LinkState
import kotlinx.coroutines.launch

/**
 * 设备历史事件日志页（`0x03`→`0x13`）＋保护动作计数（`0x05`→`0x15`）。
 *
 * 回答的是「为什么跳保护、什么时候跳的、当时电压电流多少」——这些记录存在设备里，
 * 以前这个 App 一条都读不到。协议与文案依据见 [EventLogCodec]。
 *
 * 用 LazyColumn 而不是 ScreenScaffold 的滚动 Column：设备日志可以翻到上百条，
 * 一次性布局几百行会明显卡（与开发者页日志列表同类的坑）。
 */
@Composable
fun EventLogScreen(
    onBack: () -> Unit,
    onOpenPerm: () -> Unit = {},
    bottomPadding: Dp = 20.dp,
) {
    val repo = io.github.lswlc33.maibms.data.Bms.repository
    val records by MockBms.eventLog.collectAsState()
    val loading by MockBms.eventLogLoading.collectAsState()
    val atEnd by MockBms.eventLogAtEnd.collectAsState()
    val counters by MockBms.protectCounters.collectAsState()
    val error by MockBms.eventLogError.collectAsState()
    val status by MockBms.status.collectAsState()
    val link by repo.linkState.collectAsState()
    val connected = link == LinkState.Connected
    val canRead = io.github.lswlc33.maibms.protocol.Perm.canReadLogs(status.permissionLevel)
    val scope = rememberCoroutineScope()
    var loadedOnce by remember { mutableStateOf(false) }

    // 进页自动读一次；权限不够/未连接时不去打扰设备，由横幅说明原因。
    // 已经有数据就不重读（避免每次切回这一页都去读一遍设备），要最新的点右上「刷新」
    LaunchedEffect(connected, canRead) {
        if (connected && canRead && !loadedOnce && records.isEmpty()) {
            loadedOnce = true
            repo.refreshEventLog()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                "‹", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { onBack() }.padding(end = 10.dp),
            )
            Text("设备日志", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            // 刷新：重读计数 + 第一批（不会丢已翻的页，只是回到第一批）
            Text(
                if (loading) "读取中…" else "刷新",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (loading || !connected) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = connected && !loading) {
                        loadedOnce = true
                        scope.launch { repo.refreshEventLog() }
                    }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
        Text(
            "设备内保存的历史事件与保护动作次数 · 读到一条就长一条，序号即设备里的顺序",
            fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(top = 2.dp, bottom = bottomPadding),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    when {
                        !connected -> InfoBanner(
                            if (status.hasData) "已断开连接 · 日志需要连着设备读" else "未连接设备 · 连接后可读设备内的历史事件",
                            kind = "warn",
                        )
                        !canRead -> InfoBanner(
                            "读日志需要 ${io.github.lswlc33.maibms.protocol.Perm.READ_LOG_MIN_LEVEL} 级及以上权限（当前 ${status.permissionLevel} 级）",
                            kind = "warn", action = "去校验", onAction = onOpenPerm,
                        )
                        loading && records.isEmpty() -> InfoBanner("读取中…（一条一条读，首批 10 条）", kind = "info")
                    }
                    error?.let { InfoBanner(it, kind = "err") }
                    if (counters.isNotEmpty()) ProtectCounterCard(counters)
                }
            }
            item {
                SectionCard {
                    SectionHeader("历史事件", tail = if (records.isEmpty()) "0 条" else "${records.size} 条")
                    if (records.isEmpty()) {
                        Text(
                            when {
                                !connected || !canRead -> "连上设备后在这里显示历史事件"
                                loading -> "读取中…"
                                else -> "设备里没有历史记录（部分固件不保存日志，或已用「清除系统日志」清过）"
                            },
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        Text(
                            "时间：≥2020 年按日期显示；否则按设备运行时长显示（固件两种都有，见 docs/08）",
                            fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
            items(records, key = { it.index }) { rec -> EventLogRow(rec) }
            if (records.isNotEmpty()) {
                item {
                    SectionCard {
                        if (atEnd) {
                            Text(
                                "已到最早一条 · 共 ${records.size} 条",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(enabled = !loading && connected && canRead) {
                                        scope.launch { repo.loadMoreEventLog() }
                                    }
                                    .padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (loading) "读取中…" else "加载更早的 ${io.github.lswlc33.maibms.data.BmsRepository.EVENT_LOG_PAGE} 条",
                                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                                    color = if (!loading && connected && canRead) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "清除设备日志 / 清零保护次数：配置 → 控制与工具（都是设备侧操作，不可恢复）",
                            fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 一条历史事件：序号 + 事件名 + 时间，下面一行带该事件记录的数据项 */
@Composable
private fun EventLogRow(rec: EventLogRecord) {
    val accent = when {
        SEVERE_KEYS.any { rec.eventName.contains(it) } -> BmsColors.BadRed
        rec.eventName.contains("告警") -> BmsColors.WarnAmber
        else -> BmsColors.OffGray
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(7.dp))
            Text(
                "#${rec.index}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                rec.eventName, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
            )
            Text(
                rec.timeText, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rec.items.isNotEmpty()) {
            Text(
                rec.items.joinToString(" · ") { "${it.name} ${it.value}" },
                fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 14.dp),
            )
        }
    }
}

/** 保护动作计数（20 项，顺序见 EventLogCodec.counterNames）；循环次数单独高亮 */
@Composable
private fun ProtectCounterCard(counters: List<Int>) {
    SectionCard {
        SectionHeader("保护动作次数", tail = "设备累计")
        val names = EventLogCodec.counterNames
        val rows = counters.indices.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { i ->
                        val cycle = i == EventLogCodec.CYCLE_COUNTER_INDEX
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(
                                names.getOrNull(i) ?: "计数 $i",
                                fontSize = 10.sp,
                                fontWeight = if (cycle) FontWeight.Bold else FontWeight.Normal,
                                color = if (cycle) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                counters[i].toString(),
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    // 奇数项时右侧留空，保持两列对齐
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private val SEVERE_KEYS = listOf("保护", "异常", "失联", "故障", "失败", "超限", "短路")
