package io.github.lswlc33.maibms.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.data.StartupTrace
import io.github.lswlc33.maibms.data.fmt
import io.github.lswlc33.maibms.data.hasRelayInfo
import kotlinx.coroutines.launch

/**
 * 首屏耗时 Toast 的上限：只有 20s 内出数才弹。
 * 设备长时间不在时，几分钟后突然冒出一个「首屏 300s」会莫名其妙——那种数字留在日志里就够了。
 */
private const val FIRST_SCREEN_TOAST_LIMIT_MS = 20_000L

@Composable
fun DashboardScreen(
    showScanEntry: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp = 78.dp,
    onOpenScan: () -> Unit,
    onOpenProtect: () -> Unit,
    onOpenPerm: () -> Unit = {},
    /** 运行权限是否够执行控制命令（≥3 级且已连接）：不够时只提示、不发帧 */
    canWrite: Boolean = true,
    permissionLevel: Int = 0,
    onControlConfirm: (Int) -> Unit,
    onForceCharge: () -> Unit,
) {
    val liveStatus by MockBms.status.collectAsState()
    val status = liveStatus
    val caps by MockBms.caps.collectAsState()
    // 当前设备家族（电量计品牌名，用于"中继信息卡"标题）
    val family by io.github.lswlc33.maibms.data.Bms.repository.currentFamily.collectAsState()
    // 冷启动首屏耗时：第一次带着实时数据完成组合时上报一次（此刻数值已经画上屏），
    // 20s 内出数再弹个系统 Toast 把秒数直接摆出来。每进程只会上报一次，重连反复不影响。
    LaunchedEffect(status.hasData) {
        if (status.hasData) {
            StartupTrace.elapsedToFirstScreenMs()?.let { ms ->
                BmsLog.i("APP", "启动→上屏 ${ms}ms" + if (StartupTrace.usedProcessStart) "" else "（无进程起点，从发起连接算起）")
                if (ms <= FIRST_SCREEN_TOAST_LIMIT_MS) showSystemToast("首屏 %.1fs".fmt(ms / 1000.0))
            }
        }
    }
    val repo = io.github.lswlc33.maibms.data.Bms.repository
    val link by repo.linkState.collectAsState()
    val connectHint by repo.connectHint.collectAsState()
    val stalled by repo.stalled.collectAsState()
    val manual by repo.manualDisconnect.collectAsState()
    val ctrlResult by MockBms.lastControlResult.collectAsState()
    val chargeOn by MockBms.chargeSwitch.collectAsState()
    val dischargeOn by MockBms.dischargeSwitch.collectAsState()
    val previewing by repo.previewActive.collectAsState()
    val previewLabel by repo.previewLabel.collectAsState()
    val scope = rememberCoroutineScope()
    /** 权限不够时点控制按钮的一次性说明（点掉即清） */
    var permDenied by remember { mutableStateOf<String?>(null) }
    // 中继信息卡（电量计模式）收起/展开：内存态管本次显示，AppStore 落盘管下次启动
    var relayCollapsed by remember { mutableStateOf(AppStore.relayCardCollapsed) }
    fun setRelayCollapsed(v: Boolean) {
        relayCollapsed = v
        AppStore.relayCardCollapsed = v
    }
    val expandRelayCard: () -> Unit = { setRelayCollapsed(false) }

    /** 链路三态：正常 / 失联（GATT 在但数据停流）/ 断开（含未连接、连接中失败） */
    val linkLost = link == io.github.lswlc33.maibms.transport.LinkState.Connected && stalled
    val linkDown = link != io.github.lswlc33.maibms.transport.LinkState.Connected

    // 仪表视图（横屏表盘）：**只按宽高比自动进入**（宽 ≥ 高 × 1.2，见 ClusterMode）——
    // 手机/平板横屏、拉宽的桌面窗口自动进；正方形屏与大竖屏不进（表盘在那种窗口里只能压成
    // 一条 2.4:1 的横带）。三端都可以手动进出：顶栏「仪表视图」进、表盘内「退出仪表」出。
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (useClusterLayout(maxWidth, maxHeight)) {
            ClusterDashboard(status, onExit = { exitCluster() })
        } else {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp))
            // 顶栏：标题 + 横屏入口 + 加号菜单
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("麻衣 BMS", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                // 仪表视图入口：三端都显示（原先只给能锁方向的 Android）——手动进入不依赖屏幕方向，
                // 正方形屏/竖屏平板上也能用；Android 顺带锁横屏，让表盘铺满整屏
                Box(
                    Modifier.clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            enterCluster()
                            if (supportsOrientationLock) showSystemToast("已进入仪表视图 · 转到横屏更完整")
                        }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("仪表视图", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                if (showScanEntry) {
                    // 直接开扫描面板（原先还要过一层「扫码连接/管理设备」菜单，两项指向同一个弹窗）
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable { onOpenScan() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("＋", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            // 中继信息卡（电量计模式）的两态都从这一条判据出：品牌/名称/MAC 任一报到才算有这张卡；
            // 收起时它不在场，改由卡1 右上角的小标代表它
            val hasRelay = status.hasRelayInfo
            val relayVisible = hasRelay && !relayCollapsed
            val relayFolded = hasRelay && relayCollapsed
            // 卡1：电池大卡置顶固定（用户要求：始终显示，不随列表滚动）
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BatteryCard(
                    status = status,
                    connLabel = when {
                        previewing -> "快照预览"
                        manual -> "未连接"
                        status.connected -> "已连接"
                        status.deviceName != "--" -> "重连中"
                        else -> "未连接"
                    },
                    onPermClick = onOpenPerm,
                    // 中继信息卡收起后，卡1 右上角留一枚小标说明"信息在这儿"，点整张卡重新展开。
                    // 非收起态不挂点击：不然随手点一下卡1 就把信息收掉了，反而像点坏了
                    collapsedRelayHint = "中继信息".takeIf { relayFolded },
                    onClick = if (relayFolded) expandRelayCard else null,
                )
                // 链路状态横幅也跟着固定：它是卡1状态的直接延续，不该滚走
                when {
                    previewing -> InfoBanner(
                        "正在预览快照 · ${previewLabel ?: ""} · 自动连接已停用，重启应用恢复",
                        kind = "info",
                        action = "退出快照",
                        onAction = { scope.launch { repo.exitPreview() } },
                    )
                    manual && !status.connected ->
                        InfoBanner("已断开连接 · 点右上角「＋」重新选择设备", kind = "info")
                    linkDown && connectHint != null -> InfoBanner(connectHint!!, kind = "warn")
                    linkDown && link == io.github.lswlc33.maibms.transport.LinkState.Idle ->
                        InfoBanner("未连接 · 点右上角「＋」扫描设备", kind = "warn")
                    linkDown ->
                        InfoBanner("连接已断开，正在自动重连…（以下为断开前最后数据）", kind = "warn")
                    linkLost ->
                        InfoBanner("设备失联：链路仍在但收不到数据，请靠近电池或检查干扰（以下为最后数据）", kind = "err")
                }
            }
            // 电量计模式：这台中继器正在中继哪块保护板（品牌/名称/MAC）。
            // **单独一块、不再塞进上面的固定头部**：收起动画要连它上方的 8dp 间距一起收掉，
            // 否则动画结束时（高度归零那一帧）剩下的那段空隙会突然消失，下面整片内容会跳一下。
            // 展开时它仍在固定头部下方、不随列表滚动——连带间距与原先完全一致。
            AnimatedVisibility(
                visible = relayVisible,
                enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(120)),
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)) {
                    RelayBoardCard(
                        family.label, status.relayBoardBrand, status.relayBoardName, status.relayBoardMac,
                        onCollapse = { setRelayCollapsed(true) },
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 卡2：状态与容量
                StatusCapacityCard(status)
                // 卡3：电流 / 功率（换挡进度条）+ 下方剩余时间小灰字
                // 断开时保留最后已知值（整屏一致），时效性由固定的横幅声明；
                // 从未收到数据时 metrics 自身会返回 "--"
                MetricGridCard(status)
                // 保护/告警双卡
                ProtectAlarmCards(status, onSeeAll = onOpenProtect)
                // 温度
                TempCard(status.temps)
                // 单体电压
                CellGridCard(
                    status.cells, avgCell = status.avgCell, deltaCell = status.deltaCell,
                    maxCell = status.maxCell, minCell = status.minCell,
                )
                // 趋势 + 控制
                SectionCard {
                    SectionHeader("趋势 · 近 1 分钟", tail = "— 电流 ─ 电压")
                    if (status.trendCurrent.size < 2 && status.trendVolt.size < 2) {
                        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (!status.hasData) "未连接 · 无数据"
                                else "采集中…（轮询回填，约 1 分钟成线）",
                                fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        TrendLines(status.trendCurrent, status.trendVolt)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (caps.controlCharge || caps.controlDischarge) ControlButtonsRow(
                        chargeOn = chargeOn,
                        dischargeOn = dischargeOn,
                        onCharge = {
                            if (canWrite) onControlConfirm(
                                if (chargeOn) io.github.lswlc33.maibms.protocol.ControlCmd.CHARGE_OFF
                                else io.github.lswlc33.maibms.protocol.ControlCmd.CHARGE_ON
                            ) else permDenied = "充电开关：需 ${io.github.lswlc33.maibms.protocol.Perm.WRITE_MIN_LEVEL} 级及以上权限（当前 $permissionLevel 级）"
                        },
                        onDischarge = {
                            if (canWrite) onControlConfirm(
                                if (dischargeOn) io.github.lswlc33.maibms.protocol.ControlCmd.DISCHARGE_OFF
                                else io.github.lswlc33.maibms.protocol.ControlCmd.DISCHARGE_ON
                            ) else permDenied = "放电开关：需 ${io.github.lswlc33.maibms.protocol.Perm.WRITE_MIN_LEVEL} 级及以上权限（当前 $permissionLevel 级）"
                        },
                        onForce = {
                            if (canWrite) onForceCharge()
                            else permDenied = "强制充电：需 ${io.github.lswlc33.maibms.protocol.Perm.WRITE_MIN_LEVEL} 级及以上权限（当前 $permissionLevel 级）"
                        },
                        enabled = link == io.github.lswlc33.maibms.transport.LinkState.Connected,
                    )
                    // 权限不够时点了不是没反应，而是当场说明原因（点横幅右侧可直接去校验）
                    permDenied?.let {
                        Spacer(Modifier.height(6.dp))
                        InfoBanner(it, kind = "warn", action = "去校验", onAction = { permDenied = null; onOpenPerm() })
                    }
                    // 命令结果就地回显（0x61 码表），否则按了开关看不出成没成
                    ctrlResult?.let { (cmd, code) ->
                        Spacer(Modifier.height(6.dp))
                        InfoBanner(
                            "${io.github.lswlc33.maibms.protocol.ControlCmd.name(cmd)}：${io.github.lswlc33.maibms.protocol.ResultCodes.controlResult(code)}",
                            kind = if (code == 1) "info" else "err",
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun TrendLines(current: List<Float>, volt: List<Float>) {
    // 照截图：彩线 + 同色渐变面积 + 线尾圆点；电流=主色绿，电压=图表蓝
    val currentColor = MaterialTheme.colorScheme.primary
    val voltColor = BmsColors.ChartBlue
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cutout = MaterialTheme.colorScheme.surface   // draw lambda 不是 @Composable，颜色先取出来
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width; val h = size.height
        val padY = 6.dp.toPx()          // 上下留白：否则电池静置时曲线会贴在边框上，像渲染坏了
        val innerH = (h - 2 * padY).coerceAtLeast(1f)
        fun pts(data: List<Float>): List<androidx.compose.ui.geometry.Offset> =
            data.mapIndexed { i, v ->
                androidx.compose.ui.geometry.Offset(w * i / (data.size - 1), padY + innerH * (1f - v))
            }
        fun area(data: List<Float>, color: Color) {
            if (data.size < 2) return
            val path = androidx.compose.ui.graphics.Path()
            pts(data).forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            path.lineTo(w, h); path.lineTo(0f, h); path.close()
            drawPath(
                path,
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(color.copy(alpha = .20f), color.copy(alpha = 0f)), startY = padY, endY = h,
                )
            )
        }
        fun line(data: List<Float>, color: Color) {
            if (data.size < 2) return
            val path = androidx.compose.ui.graphics.Path()
            val ps = pts(data)
            ps.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(
                path, color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 3.5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                )
            )
            // 线尾圆点（截图里的那个空心圆点）
            drawCircle(color, radius = 4.5f, center = ps.last())
            drawCircle(cutout, radius = 2f, center = ps.last())
        }
        // 淡网格：三条基线，让空白区域看起来是有意留白
        for (k in 0..2) {
            val y = padY + innerH * k / 2f
            drawLine(grid.copy(alpha = .5f), androidx.compose.ui.geometry.Offset(0f, y),
                androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
        }
        area(volt, voltColor)
        area(current, currentColor)
        line(volt, voltColor)
        line(current, currentColor)
    }
}

/* ---------- S01 扫描弹窗（真机 BLE 扫描 / 桌面端降级提示） ---------- */

@Composable
fun ScanDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    val results by io.github.lswlc33.maibms.data.Bms.repository.scanResults.collectAsState()
    val scanning by io.github.lswlc33.maibms.data.Bms.repository.scanning.collectAsState()
    val scanError by io.github.lswlc33.maibms.data.Bms.repository.scanError.collectAsState()
    val canScan = io.github.lswlc33.maibms.data.Bms.repository.canScan
    var selected by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { io.github.lswlc33.maibms.data.Bms.repository.startScan() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text("附近的设备", fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                when {
                    !canScan -> "当前平台没有蓝牙扫描（桌面端）"
                    scanError != null -> scanError!!
                    scanning -> "扫描中… 保护板 / 电量计 · 信号强度排序"
                    results.isEmpty() -> "扫描结束 · 未发现保护板/电量计"
                    else -> "已发现 ${results.size} 台设备"
                },
                fontSize = 11.5.sp,
                color = if (scanError != null) BmsColors.WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            if (canScan && scanError == null && !scanning && results.isEmpty()) {
                Text("未发现保护板/电量计。确认设备已上电、手机蓝牙已打开且在附近。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            // 历史设备快速连接：不用等扫描，点一下直接回连（回连也走扫描不到的等待式建链路径）
            val history = remember { io.github.lswlc33.maibms.data.DeviceProfiles.all()
                .sortedByDescending { it.lastConnectedAt } }
            if (history.isNotEmpty()) {
                Text("历史设备", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
                history.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onConnect(p.address) }
                            .padding(vertical = 6.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(p.address, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("连接 ›", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
            }
            results.forEach { d ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { selected = d.address }
                        .padding(vertical = 7.dp)
                ) {
                    RadioDot(selected == d.address)
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(d.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (d.family == io.github.lswlc33.maibms.transport.DeviceFamily.Unknown) d.address
                            else "${d.address} · ${d.family.label}",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("${d.rssi} dBm", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (scanning) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                StatusDot(BmsColors.OffGray, 6.dp)
                Text("正在搜索…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 9.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 报错时也要能重试：原先带了 scanError == null，一旦扫描失败按钮就消失，用户只剩
                // 「关闭」。iOS 上管理器状态没就绪时首次扫描必然报错，那时没有重试入口等于卡死
                if (canScan && !scanning) {
                    TextButton(onClick = { scope.launch { io.github.lswlc33.maibms.data.Bms.repository.startScan() } }) { Text("重新扫描", color = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭", color = MaterialTheme.colorScheme.primary) }
                Button(onClick = { selected?.let(onConnect) }, enabled = selected != null) { Text("连接") }
            }
        }
    }
}

/* ---------- S03 保护/告警详情弹窗 ---------- */

@Composable
fun ProtectDetailDialog(onDismiss: () -> Unit, onOpenLog: (() -> Unit)? = null) {
    val status by MockBms.status.collectAsState()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Row {
                Text("告警详情", fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text("实时帧偏移 4~11 保护 / 12~19 告警", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            // 加了「运行状态」段后内容会明显变长（状态位常有 3~6 条），这里限高并内部滚动，
            // 否则在小屏 / 大字体档下弹窗底部（含"知道了"按钮）会被推出屏幕
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())
            ) {
            if (!status.hasData) {
                Text("未连接 · 无数据（连接保护板后此处显示位置位详情）",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp))
            }
            if (status.alarmPairs.isNotEmpty() || (status.hasData && status.alarmList.isNotEmpty())) {
                Text("告警", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 与告警卡同源展示：卡片用 displayAlarmList（含合成的「MOS关」提示），
            // 详情只列真实位（带 bit 号），MOS 关提示按同一条规则补在末尾，两边条数才对得上。
            // 没收到过数据时不给「MOS 关」提示：位域全是 0 不等于"MOS 确实是关的"，
            // 合成提示会说出一件没有依据的事（与"缺数据显示 --"同一条原则）
            val mosHints = if (status.hasData) io.github.lswlc33.maibms.protocol.BitDict.mosClosedHints(
                status.chMos == "开启", status.disMos == "开启") else emptyList()
            status.alarmPairs.forEach { (bit, a) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    StatusDot(BmsColors.WarnAmber)
                    Text(a, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 9.dp))
                    Text("bit $bit", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            mosHints.forEach { hint ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    StatusDot(BmsColors.OffGray)
                    Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 9.dp))
                    Text("状态", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (status.protectPairs.isNotEmpty()) {
                Text("保护", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (status.alarmPairs.isNotEmpty()) 6.dp else 0.dp))
            }
            status.protectPairs.forEach { (bit, p) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    StatusDot(BmsColors.BadRed)
                    Text(p, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 9.dp))
                    Text("bit $bit", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (status.hasData && status.alarmPairs.isEmpty() && status.protectPairs.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                StatusDot(BmsColors.OffGray)
                Text("其余位正常", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 9.dp))
                Text("u64", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // ---- 运行状态：以前整段看不到的部分 ----
            // 1) MOS/均衡的状态码：开关读数只说明"开没开"，码值才说明"为什么没开"（MosStateDict）；
            // 2) 告警位域里的状态位（充电器接入 20/21/22、加热 36、防打火 48、调试模式 45…）——
            //    它们既不算告警、又被 stateBits 从告警列表里滤掉，此前界面上没有任何入口。
            if (status.hasData) {
                Text("运行状态", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                // 只列「有话说」的那几项：码值 ≥2 才是断开原因；均衡只在遇到码表外的值时列出
                // （已知模式卡2 已经在显示，这里重复没意义）
                val mosRows = buildList {
                    val mos = io.github.lswlc33.maibms.protocol.MosStateDict
                    if (status.chMosCode >= 2) add("充电MOS状态" to mos.chargeText(status.chMosCode))
                    if (status.disMosCode >= 2) add("放电MOS状态" to mos.dischargeText(status.disMosCode))
                    if (status.balanceCode >= 1 && mos.balanceMode(status.balanceCode) == null)
                        add("均衡状态" to mos.balanceText(status.balanceCode))
                }
                mosRows.forEach { (label, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        StatusDot(BmsColors.IcBlue)
                        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 9.dp))
                        Text(value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (status.statePairs.isEmpty() && mosRows.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        StatusDot(BmsColors.OffGray)
                        Text("无置位的运行状态位", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 9.dp))
                        Text("bit 17~52", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                status.statePairs.forEach { (bit, s) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        StatusDot(BmsColors.OkGreen)
                        Text(s, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 9.dp))
                        Text("bit $bit", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                // 从「这里为什么亮」直接跳到设备里存的历史记录（0x13），不用自己找入口
                if (onOpenLog != null) {
                    TextButton(onClick = onOpenLog) { Text("查看历史事件", color = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(4.dp))
                }
                Button(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}
