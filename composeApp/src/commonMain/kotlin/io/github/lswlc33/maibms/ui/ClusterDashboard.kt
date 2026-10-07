package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsStatus
import io.github.lswlc33.maibms.data.fmt
import io.github.lswlc33.maibms.data.fmtRemainingMin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 横屏表盘（仪表总成）：左盘总电压 + 中央 SOC 芯区 + 右盘功率，顶/底横条包夹。
 * 由 `Plan/横屏仪表原型.html` 定稿（A 案「新能源简洁」：弧量填充、无指针、大数字居盘心），
 * 数学全部来自 [ClusterMath]（原型「静态函数表」的 Kotlin 直迁），绘制口径与其 JS 逐项对应。
 *
 * 恒深色面（汽车仪表惯例，不随主题）；竖屏布局由 DashboardScreen 的宽度断点（≥600dp）
 * 自动切换进来——设备旋转到横屏、桌面拉宽窗口、顶栏入口锁向都会走到这里。
 */
private val ClusterFace0 = Color(0xFF0C1110)
private val ClusterFace1 = Color(0xFF141B1A)
private val ClusterLine = Color(0xFF243030)
private val ClusterFg = Color(0xFFECF2F0)
private val ClusterFg2 = Color(0xFF93A09D)
private val ClusterFg3 = Color(0xFF5E6B68)
private val ClusterTrack = Color(0xFF1D2827)
private val ClusterGreen = Color(0xFF34D399)
private val ClusterTeal = Color(0xFF2DD4BF)
private val ClusterBlue = Color(0xFF60A5FA)
private val ClusterAmber = Color(0xFFFBBF24)
private val ClusterRed = Color(0xFFF87171)

@Composable
fun ClusterDashboard(
    status: BmsStatus,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {},
) {
    var useKw by remember { mutableStateOf(AppStore.clusterPowerKw) }
    val link by Bms.repository.linkState.collectAsState()
    val stalled by Bms.repository.stalled.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    val linkLost = connected && stalled
    // 沉浸：表盘在组合期间隐藏状态栏+手势条（边缘滑动临时呼出），离开表盘自动恢复
    androidx.compose.runtime.DisposableEffect(Unit) {
        systemBarsImmersive(true)
        onDispose { systemBarsImmersive(false) }
    }

    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(ClusterFace0, ClusterFace1)))
    ) {
        val u = maxWidth / 112f   // 表盘单位的基准：全屏宽的 ~0.9%——16:9 主盒比全屏窄，
        // 字号/间距按它派生才不会溢出（之前按全屏宽派生，六灯排在盒内被裁）
        // 厂家系统（MIUI/HyperOS 实测）在旋转配置变化后会重新显示系统栏——
        // 宽度落定（竖→横）后再挂一次隐藏
        LaunchedEffect(maxWidth) { systemBarsImmersive(true) }
        // 左右对称安全区：两侧都可能有摄像头挖孔与屏幕圆角，内容只在两安全区之间显示
        // （背景铺满全屏）。宽度取 max(左/右 safeDrawing, 屏幕圆角, 50dp)——用户基准约一个
        // 状态栏高度、不小于 50dp。K90 实测：左=挖孔列，右=对称留白，表盘视觉居中。
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val sideSafe = maxOf(
            insets.calculateStartPadding(LayoutDirection.Ltr),
            insets.calculateEndPadding(LayoutDirection.Ltr),
            screenCornerRadius(),
            50.dp,
        )
        Column(Modifier.fillMaxSize()) {
            // ---- 顶条：背景延伸出安全区铺满全宽，内容收在安全区内 ----
            Row(
                Modifier.fillMaxWidth()
                    .background(ClusterLine.copy(alpha = 0.6f))
                    .padding(horizontal = sideSafe + u * 1.9f, vertical = u * 0.9f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u * 2.2f),
            ) {
                Box(
                    Modifier.size(u * 0.95f).clip(CircleShape)
                        .background(when {
                            !connected -> ClusterFg3
                            linkLost -> ClusterAmber
                            else -> ClusterGreen
                        })
                )
                Text(
                    status.deviceName, fontSize = (u.value * 1.35).sp, fontWeight = FontWeight.SemiBold,
                    color = ClusterFg, maxLines = 1,
                )
                ClusterChip("权限 ${status.permissionLevel} 级", u)
                ClusterChip("运行 ${status.runtime}", u)
                Spacer(Modifier.weight(1f))
                // 单位切换（W 默认 / kW），点按即切并落盘
                ClusterChip(if (useKw) "单位 kW" else "单位 W", u, emphasize = true) {
                    useKw = !useKw
                    AppStore.clusterPowerKw = useKw
                }
                // 退出回竖屏：只在能锁方向的平台上出现（桌面宽窗口无意义）
                if (supportsOrientationLock) {
                    ClusterChip("退出仪表 ✕", u, emphasize = true, onClick = onExit)
                }
            }
            // ---- 三区主视图：16:9 约束（用户定稿）——宽富余按高定宽居中，高富余按宽定高居中；
            //      富余部分只显背景。竖分隔线画满 16:9 盒高（不再通到顶/底条）。
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val availW = maxWidth
                val availH = maxHeight
                // 首选 16:9；高度太扁（<2.4:1 都保不住）时放宽到 2.4:1 保底，避免表盘被压得过小
                val ratio = availW / availH
                val targetRatio = if (ratio > 16f / 9f) 16f / 9f else maxOf(ratio, 2.4f)
                val innerW = if (availW < availH * targetRatio) availW else availH * targetRatio
                val innerH = innerW / targetRatio
                Box(
                    Modifier.width(innerW).height(innerH).align(Alignment.Center)
                ) {
                    Row(Modifier.fillMaxSize()) {
                        ClusterGaugeV(status, Modifier.weight(1.08f).fillMaxHeight(), u)
                        ClusterMidColumn(
                            status, connected, linkLost,
                            Modifier.weight(1.18f).fillMaxHeight()
                                .padding(horizontal = u * 1.6f, vertical = u * 0.8f),
                            u,
                        )
                        ClusterGaugeP(status, useKw, Modifier.weight(1.08f).fillMaxHeight(), u)
                    }
                    Box(Modifier.align(Alignment.CenterStart).width(u * 0.09f).fillMaxHeight().background(ClusterLine))
                    Box(Modifier.align(Alignment.CenterEnd).width(u * 0.09f).fillMaxHeight().background(ClusterLine))
                }
            }
            // ---- 底条：背景延伸出安全区铺满全宽，内容收在安全区内 ----
            ClusterBottomBar(status, u, sideSafe)
        }
    }
}

/** 顶/底条与顶条按钮通用的胶囊 chip */
@Composable
private fun ClusterChip(text: String, u: Dp, emphasize: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(99.dp))
            .border(0.8.dp, if (emphasize) ClusterGreen.copy(alpha = 0.55f) else ClusterLine, RoundedCornerShape(99.dp))
            .background(if (emphasize) ClusterGreen.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = u * 0.95f, vertical = u * 0.22f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = (u.value * 1.25).sp, color = if (emphasize) ClusterGreen else ClusterFg2,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/* ================= 中央芯区 ================= */

@Composable
private fun ClusterMidColumn(
    status: BmsStatus, connected: Boolean, linkLost: Boolean,
    modifier: Modifier = Modifier, u: Dp,
) {
    val charging = status.hasData && status.power < -20
    val remainAh = status.remainCapAh
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(u * 1.15f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // SOC 大字 + 状态章
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 1.4f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (status.hasData) status.soc.toString() else "--",
                    fontSize = (u.value * 5.6).sp, fontWeight = FontWeight.Light,
                    color = ClusterFg, maxLines = 1,
                )
                Text("%", fontSize = (u.value * 2.3).sp, color = ClusterFg2, modifier = Modifier.padding(bottom = u * 0.7f))
            }
            val state = when {
                !status.hasData -> "未连接" to ClusterFg2
                charging -> "充电" to ClusterTeal
                status.power > 20 -> "放电" to ClusterGreen
                else -> "待机" to ClusterFg2
            }
            Box(
                Modifier.clip(RoundedCornerShape(99.dp))
                    .border(0.8.dp, state.second, RoundedCornerShape(99.dp))
                    .padding(horizontal = u * 1.5f, vertical = u * 0.28f)
            ) {
                Text(state.first, fontSize = (u.value * 1.5).sp, fontWeight = FontWeight.SemiBold, color = state.second)
            }
        }
        // 10 段电量条（<15% 整条转红）
        val lit = if (status.hasData) ClusterMath.socSegments(status.soc) else 0
        val segColor = if (status.hasData && ClusterMath.socLow(status.soc)) ClusterRed else ClusterGreen
        Row(
            Modifier.fillMaxWidth().padding(horizontal = u * 0.8f).height(u * 1.85f),
            horizontalArrangement = Arrangement.spacedBy(u * 0.34f),
        ) {
            repeat(10) { i ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(u * 0.3f))
                        .background(if (i < lit) segColor else ClusterTrack)
                )
            }
        }
        // 充电闪电占位（恒高，避免切换时跳动）
        Box(Modifier.height(u * 1.8f)) {
            if (charging) Text("⚡ ⚡", fontSize = (u.value * 1.5).sp, color = ClusterAmber)
        }
        // 剩余时间（扩展段 86/88；0=设备未报 → "--"）
        Row(horizontalArrangement = Arrangement.spacedBy(u * 2.4f)) {
            Text("充电剩余 ", fontSize = (u.value * 1.45).sp, color = ClusterFg2)
            Text(
                if (connected && charging) fmtRemainingMin(status.remainChargeMin) else "--",
                fontSize = (u.value * 1.45).sp, fontWeight = FontWeight.SemiBold, color = ClusterFg,
            )
            Text("放电剩余 ", fontSize = (u.value * 1.45).sp, color = ClusterFg2)
            Text(
                if (connected && status.power > 20) fmtRemainingMin(status.remainDischargeMin) else "--",
                fontSize = (u.value * 1.45).sp, fontWeight = FontWeight.SemiBold, color = ClusterFg,
            )
        }
        // 六灯排
        // 六灯排：SpaceEvenly 随列宽自适应分布（fixed 间距在 16:9 盒的中央列里会溢出被裁）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ClusterLed("连接", u, ClusterGreen, connected)
            ClusterLed("充电MOS", u, ClusterTeal, status.chMos == "开启")
            ClusterLed("放电MOS", u, ClusterGreen, status.disMos == "开启")
            ClusterLed("均衡", u, ClusterBlue, status.balance == "均衡中")
            ClusterLed("告警", u, ClusterAmber, status.alarmList.isNotEmpty())
            ClusterLed("保护", u, ClusterRed, status.protectList.isNotEmpty())
        }
        // 剩余 Ah 供剩余时间旁证（占用空间小，跟随底条数据）
        if (linkLost) Text("设备失联 · 数据停流", fontSize = (u.value * 1.2).sp, color = ClusterAmber)
        else if (remainAh > 0) Text("剩余 ${fmt1(remainAh)}Ah", fontSize = (u.value * 1.2).sp, color = ClusterFg3)
    }
}

/** 指示灯：圆点 + 9.5sp 字，灭灯一律暗灰 */
@Composable
private fun ClusterLed(label: String, u: Dp, color: Color, on: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u * 0.35f)) {
        Box(
            Modifier.size(u * 1.45f).clip(CircleShape)
                .background(if (on) color else ClusterTrack)
                .border(0.6.dp, if (on) color else ClusterLine, CircleShape)
        )
        Text(label, fontSize = (u.value * 1.05).sp, color = if (on) ClusterFg2 else ClusterFg3, maxLines = 1)
    }
}

/* ================= 双盘 ================= */

/** 左盘 · 总电压：量程 串数×2.5~4.5V，红区>过充、琥珀区<过放压在填充之上 */
@Composable
private fun ClusterGaugeV(status: BmsStatus, modifier: Modifier = Modifier, u: Dp) {
    val textMeasurer = rememberTextMeasurer()
    val hasData = status.hasData
    val cellCount = status.cells.size.coerceAtLeast(1)
    val range = ClusterMath.voltageRange(cellCount)
    val fillV = status.totalVoltage.toFloat()
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // 半径贴满 Canvas 的 270° 弧足迹（宽 2r × 高 1.707r），双向取 min——瘦长条里由宽度
        // 决定，比按短边算大 ~36%；外发光再留 6% 余量。弧足迹垂直居中（cy=0.5h）。
        val r = min(size.width / 2f, size.height / 1.707f) * 0.97f
        val stroke = r * 0.133f
        dialTrack(cx, cy, r, stroke)
        // 琥珀（过放侧）/ 红（过充侧）警示带
        val amberSweep = ClusterMath.sweepAngle(range.start, range.endInclusive, ClusterMath.vAmberTo(cellCount)) - ClusterMath.START_ANGLE
        dialArc(cx, cy, r, stroke, ClusterAmber.copy(alpha = 0.5f), ClusterMath.START_ANGLE, amberSweep, glow = false, cap = StrokeCap.Butt)
        val redStart = ClusterMath.sweepAngle(range.start, range.endInclusive, ClusterMath.vRedFrom(cellCount))
        dialArc(cx, cy, r, stroke, ClusterRed.copy(alpha = 0.5f), redStart, ClusterMath.END_ANGLE - redStart, glow = false, cap = StrokeCap.Butt)
        // 值弧（teal 发光 + 绿填充）
        if (hasData) {
            val sweep = ClusterMath.sweepAngle(range.start, range.endInclusive, fillV) - ClusterMath.START_ANGLE
            if (sweep > 0.5f) dialArc(cx, cy, r, stroke, ClusterTeal.copy(alpha = 0.28f), ClusterMath.START_ANGLE, sweep, glowWidth = stroke * 1.9f)
            if (sweep > 0.5f) dialArc(cx, cy, r, stroke, ClusterGreen, ClusterMath.START_ANGLE, sweep)
        }
        dialTicks(cx, cy, ClusterMath.ticksV(range), range, r, zeroBold = false)
        // 盘心数字
        drawCenterText(
            textMeasurer, if (hasData) "%.2f".fmt(fillV) else "--", "总电压 V",
            cx, cy, r, u,
        )
    }
}

/** 右盘 · 功率：中零双向（W 域），放电侧按阶梯三色带、充电侧绿带，单位 W/kW 切换 */
@Composable
private fun ClusterGaugeP(status: BmsStatus, useKw: Boolean, modifier: Modifier = Modifier, u: Dp) {
    val textMeasurer = rememberTextMeasurer()
    val hasData = status.hasData
    val stages = AppStore.powerStagesW
    val range = ClusterMath.powerRangeW(stages)
    val chargeFrom = ClusterMath.chargeBandFromW(stages)
    val zones = ClusterMath.dischargeZonesW(stages)
    val fillW = if (hasData) status.power.toFloat() else 0f
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // 同左盘：半径贴满 270° 弧足迹（宽 2r × 高 1.707r）双向取 min，外发光留 6%
        val r = min(size.width / 2f, size.height / 1.707f) * 0.97f
        val stroke = r * 0.133f
        dialTrack(cx, cy, r, stroke)
        // 充电绿带（0 → -副档位）与放电三色带，弱化铺底
        val zeroA = ClusterMath.sweepAngle(range.start, range.endInclusive, 0f)
        val chgA = ClusterMath.sweepAngle(range.start, range.endInclusive, chargeFrom)
        dialArc(cx, cy, r, stroke, ClusterTeal.copy(alpha = 0.3f), chgA, zeroA - chgA, glow = false, cap = StrokeCap.Butt)
        val bandColors = listOf(ClusterGreen, ClusterBlue, ClusterRed)
        zones.forEachIndexed { i, z ->
            val a0 = ClusterMath.sweepAngle(range.start, range.endInclusive, z.fromW)
            val a1 = ClusterMath.sweepAngle(range.start, range.endInclusive, z.toW)
            dialArc(cx, cy, r, stroke, bandColors[i.coerceAtMost(2)].copy(alpha = 0.24f), a0, a1 - a0, glow = false, cap = StrokeCap.Butt)
        }
        // 值弧：0 位起向左（充电 teal）或向右（按所在档位色）
        if (hasData) {
            val a = ClusterMath.sweepAngle(range.start, range.endInclusive, fillW.coerceIn(range.start, range.endInclusive))
            val start = min(zeroA, a)
            val sweep = kotlin.math.abs(a - zeroA)
            if (sweep > 0.5f) {
                val color = when {
                    fillW < -2f -> ClusterTeal
                    fillW > 2f -> bandColors[ClusterMath.zoneIndexW(stages, fillW).coerceAtMost(2)]
                    else -> ClusterFg2
                }
                dialArc(cx, cy, r, stroke, color.copy(alpha = 0.28f), start, sweep, glowWidth = stroke * 1.9f)
                dialArc(cx, cy, r, stroke, color, start, sweep)
            }
        }
        dialTicks(cx, cy, ClusterMath.ticksP(range, useKw), range, r, zeroBold = true)
        drawCenterText(
            textMeasurer,
            if (hasData) ClusterMath.fmtPower(fillW, useKw) else "--",
            if (!hasData) "" else (if (fillW < -2f) "充电 " else "") +
                (if (useKw) "%.2fkW".fmt(kotlin.math.abs(fillW) / 1000) else "${kotlin.math.abs(fillW).toInt()}W") +
                " · " + (if (status.current > 0.05f) "+" else "") + "%.1fA".fmt(status.current),
            cx, cy, r, u,
        )
    }
}

/* ---- 绘制基元（角度口径 = ClusterMath/原型：0°=3点钟、顺时针） ---- */

private fun DrawScope.dialTrack(cx: Float, cy: Float, r: Float, stroke: Float) {
    drawArc(
        color = ClusterTrack, startAngle = ClusterMath.START_ANGLE, sweepAngle = ClusterMath.SWEEP,
        useCenter = false, topLeft = Offset(cx - r, cy - r), size = Size(r * 2f, r * 2f),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
}

private fun DrawScope.dialArc(
    cx: Float, cy: Float, r: Float, stroke: Float, color: Color, startDeg: Float, sweepDeg: Float,
    glow: Boolean = true, glowWidth: Float = stroke * 1.9f, cap: StrokeCap = StrokeCap.Round,
) {
    if (sweepDeg <= 0f) return
    val box = Offset(cx - r, cy - r)
    val boxSize = Size(r * 2f, r * 2f)
    if (glow) drawArc(
        color = color.copy(alpha = color.alpha * 0.35f), startAngle = startDeg, sweepAngle = sweepDeg,
        useCenter = false, topLeft = box, size = boxSize, style = Stroke(glowWidth, cap = cap),
    )
    drawArc(
        color = color, startAngle = startDeg, sweepAngle = sweepDeg,
        useCenter = false, topLeft = box, size = boxSize, style = Stroke(stroke, cap = cap),
    )
}

private fun DrawScope.dialTicks(
    cx: Float, cy: Float, ticks: List<ClusterMath.Tick>,
    range: ClosedFloatingPointRange<Float>, r: Float, zeroBold: Boolean,
) {
    val majorLen = r * 0.124f
    val minorLen = r * 0.057f
    ticks.forEach { tick ->
        val a = ClusterMath.sweepAngle(range.start, range.endInclusive, tick.value)
        val len = if (tick.major) majorLen else minorLen
        val zero = zeroBold && kotlin.math.abs(tick.value) < 1e-3f
        drawLine(
            color = when {
                zero -> ClusterFg
                tick.major -> Color(0xFF8A9693)
                else -> Color(0xFF414D4A)
            },
            start = pointOn(cx, cy, r, a), end = pointOn(cx, cy, r - len, a),
            strokeWidth = when {
                zero -> r * 0.033f
                tick.major -> r * 0.019f
                else -> r * 0.011f
            },
        )
    }
}

/** 盘心大数字 + 小字：画在指针根部下方（cy + r*0.30 起），与原型文字位一致 */
private fun DrawScope.drawCenterText(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    value: String, sub: String, cx: Float, cy: Float, r: Float, u: Dp,
) {
    val valStyle = TextStyle(
        fontSize = (u.value * 3.2f).sp, fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace, color = ClusterFg,
    )
    val subStyle = TextStyle(fontSize = (u.value * 1.15f).sp, color = ClusterFg2)
    val vm = textMeasurer.measure(value, valStyle)
    drawText(vm, topLeft = Offset(cx - vm.size.width / 2f, cy + r * 0.30f))
    if (sub.isNotEmpty()) {
        val sm = textMeasurer.measure(sub, subStyle)
        drawText(sm, topLeft = Offset(cx - sm.size.width / 2f, cy + r * 0.30f + vm.size.height + u.toPx() * 0.15f))
    }
}

private fun pointOn(cx: Float, cy: Float, r: Float, angleDeg: Float): Offset {
    val rad = angleDeg * Math.PI / 180.0
    return Offset(cx + (r * cos(rad)).toFloat(), cy + (r * sin(rad)).toFloat())
}

private fun fmt1(v: Double): String = "%.1f".fmt(v)

/* ================= 底条 ================= */

@Composable
private fun ClusterBottomBar(status: BmsStatus, u: Dp, sideSafe: Dp) {
    val on = status.hasData
    val maxCell = status.cells.firstOrNull { it.isMax }
    val minCell = status.cells.firstOrNull { it.isMin }
    // 背景延伸出安全区铺满全宽；文字与主区同宽（对称安全区内不显示内容）
    Row(
        Modifier.fillMaxWidth()
            .background(ClusterLine.copy(alpha = 0.6f))
            .padding(horizontal = sideSafe + u * 0.5f, vertical = u * 0.9f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 2.0f),
    ) {
        ClusterBot("单体 ${if (on) status.maxCell else "--"}@${if (on) maxCell?.index ?: 0 else "-"}" +
            " / ${if (on) status.minCell else "--"}@${if (on) minCell?.index ?: 0 else "-"}", u)
        ClusterBot("压差 ${if (on) status.deltaCell else "--"}V", u)
        ClusterBot("平均 ${if (on) status.avgCell else "--"}V", u)
        ClusterBot("MOS ${if (on) "%.1f°".fmt(status.temps.firstOrNull { it.first == "MOS" }?.second ?: 0.0) else "--"}", u)
        ClusterBot("剩余 ${if (on) "%.1f".fmt(status.remainCapAh) else "--"}/${"%.0f".fmt(status.totalCapAh)}Ah", u)
        ClusterBot("电流 ${if (on) (if (status.current > 0.05f) "+" else "") + "%.1fA".fmt(status.current) else "--"}", u)
        if (on && status.alarmList.isNotEmpty()) {
            Text("⚠ ${status.alarmList.first()}", fontSize = (u.value * 1.35).sp, color = ClusterAmber, maxLines = 1)
        }
    }
}

@Composable
private fun ClusterBot(text: String, u: Dp) {
    Text(text, fontSize = (u.value * 1.35).sp, color = ClusterFg2, maxLines = 1)
}
