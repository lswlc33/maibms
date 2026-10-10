package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.transport.BleChannel
import io.github.lswlc33.maibms.transport.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通信通道页（对应官方小程序设备详情里的「通信通道」卡片页，docs/02-蓝牙链路.md §2.2）。
 *
 * 三张卡片 = 默认通道 / 备用 A（占蓝牙屏接口）/ 备用 B（占充电器接口），点卡片即切换；
 * 切换会断开当前链路再按目标通道重连，随后自动重新升权、继续轮询。
 */
@Composable
fun ChannelScreen(onBack: () -> Unit) {
    val repo = io.github.lswlc33.maibms.data.Bms.repository
    val active by repo.activeChannel.collectAsState()
    val available by repo.availableChannels.collectAsState()
    val link by repo.linkState.collectAsState()
    val hint by repo.connectHint.collectAsState()
    val scope = rememberCoroutineScope()
    val connected = link == LinkState.Connected
    var pending by remember { mutableStateOf<BleChannel?>(null) }
    var saved by remember { mutableStateOf(repo.savedChannelFor()) }

    // 切换完成（已连上且就是用这条通道）或超时就撤掉「切换中…」
    LaunchedEffect(pending, connected, active) {
        if (pending == null) return@LaunchedEffect
        if (connected && active == pending) { pending = null; return@LaunchedEffect }
        delay(8_000)
        pending = null
    }

    ScreenScaffold(
        title = "通信通道",
        subtitle = "点击通道卡片即可切换，切换会中断当前通信",
        onBack = onBack,
    ) {
        SectionCard {
            SectionHeader("当前", tail = if (connected) "已连接" else "未连接")
            SettingRow(
                title = "使用中",
                inlineValue = active?.let { "${it.label}（${it.id}）" } ?: "--",
            )
            SettingRow(
                title = "设备提供",
                inlineValue = if (available.isEmpty()) "连接后自动检测"
                else available.joinToString(" / ") { it.id },
            )
            saved?.let { SettingRow(title = "记住的选择", inlineValue = "${it.label}（${it.id}）") }
        }
        val h = hint
        if (h != null) {
            InfoBanner(h, kind = if (h.contains("无应答") || h.contains("失败") || h.contains("试过")) "warn" else "info")
        }
        BleChannel.values().forEach { ch ->
            ChannelCard(
                ch = ch,
                active = active,
                available = available,
                connected = connected,
                switching = pending == ch && !(connected && active == ch),
                onSwitch = {
                    pending = ch
                    saved = ch
                    scope.launch { repo.switchChannel(ch) }
                },
            )
        }
        SectionCard {
            SectionHeader("说明")
            Text(
                "备用通道的代价：备用 A 占用蓝牙屏的接口、备用 B 占用充电器的接口，" +
                    "使用期间对应的外设无法与保护板通信；两条备用通道都不支持固件升级。" +
                    "默认通道恢复后建议切回默认通道。",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelCard(
    ch: BleChannel,
    active: BleChannel?,
    available: List<BleChannel>,
    connected: Boolean,
    switching: Boolean,
    onSwitch: () -> Unit,
) {
    val supported = ch in available
    val known = available.isNotEmpty()          // 还没连过 = 通道全集未知（≠ 不支持）
    val inUse = connected && active == ch
    val warn = warnPair().first
    val note = when {
        !known && ch == BleChannel.Default -> "标准数据通信通道"
        !known -> "连接保护板后自动检测（现在还不知道这块板有没有备用通道）"
        !supported -> "当前设备不支持该通道（未发现对应的写/通知特征）"
        ch == BleChannel.Default -> "标准数据通信通道"
        else -> ch.warn
    }
    val noteIsWarn = known && supported && ch != BleChannel.Default
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ch.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(7.dp))
            SmallChip(ch.tag)
            Spacer(Modifier.weight(1f))
            ChannelAction(
                text = when {
                    inUse -> "使用中"
                    switching -> "切换中…"
                    !known -> "未探测"
                    supported -> "点击切换"
                    else -> "不支持"
                },
                primary = inUse || switching,
                enabled = known && supported && !inUse && !switching,
                onClick = onSwitch,
            )
        }
        if (note != null) {
            Text(
                note,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = if (noteIsWarn) warn else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 通道卡右侧的动作药丸（使用中 / 切换中… / 点击切换 / 不支持） */
@Composable
private fun ChannelAction(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(
                if (primary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                primary -> MaterialTheme.colorScheme.onPrimary
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        )
    }
}
