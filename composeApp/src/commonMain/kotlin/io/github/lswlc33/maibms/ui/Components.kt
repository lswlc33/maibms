package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.BmsStatus
import io.github.lswlc33.maibms.data.CellV
import io.github.lswlc33.maibms.protocol.WriteAccess
import io.github.lswlc33.maibms.ui.BmsColors

/* ---------- 通用小组件 ---------- */

/** 当前配色是不是深色（靠卡面明度判断，AMOLED/强制深色都能覆盖） */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.4f

/** 语义色对（底, 字）：浅色=淡底深字，深色=半透明底亮字 */
@Composable
fun okPair(): Pair<Color, Color> =
    if (isDarkScheme()) BmsColors.OkGreen.copy(alpha = .26f) to Color(0xFF8FE39A)
    else BmsColors.OkBg to BmsColors.OkGreen

@Composable
fun warnPair(): Pair<Color, Color> =
    if (isDarkScheme()) Color(0xFFD08A28).copy(alpha = .26f) to Color(0xFFFFC46B)
    else BmsColors.WarnBg to BmsColors.WarnAmber

@Composable
fun dangerPair(): Pair<Color, Color> =
    if (isDarkScheme()) BmsColors.BadRed.copy(alpha = .26f) to Color(0xFFFF8A85)
    else BmsColors.BadBg to BmsColors.BadRed

@Composable
fun StatusDot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 卡片内边距：左右与上下统一用同一个值（12dp），四边一致 */
val CardPadding: Dp = 12.dp

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    /** 四边统一的内距；条目少的稀疏页面可以整体调大，但不要只调某一个方向 */
    padding: Dp = CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            // 无描边扁平卡：靠底色与页面背景的明度差分块（截图风格）
            .background(MaterialTheme.colorScheme.surface)
            .padding(padding)
    ) { content() }
}

@Composable
fun SectionHeader(title: String, tail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
        Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (tail != null) {
            Spacer(Modifier.weight(1f))
            Text(tail, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 设置/列表单行：图标 + 标题 [+ 行内值] + 控件 */
@Composable
fun SettingRow(
    icon: String? = null,
    title: String,
    inlineValue: String? = null,
    danger: Boolean = false,
    /** 行内上下留白：条目少的页面调大一点把内容摊开 */
    verticalPadding: Dp = 5.dp,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = verticalPadding * 2 + 20.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = verticalPadding)
    ) {
        if (icon != null) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 13.5.sp) }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (inlineValue != null) {
            Spacer(Modifier.width(8.dp))
            Text(inlineValue, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // 行尾控件与文字之间留气口，否则「未设置○」会贴在一起
        if (trailing != null && inlineValue != null) Spacer(Modifier.width(10.dp))
        trailing?.invoke()
    }
}

/**
 * 紧凑开关：M3 `Switch` 控件自身高 32dp，套进列表行后比纯文字行高出一整圈
 * （「开关控制」比「校准与维护」明显高一截就是它撑的）。
 * 自绘 34x20dp 轨道、16dp 滑块；外圈横向留给触摸（44dp 宽），纵向靠调用方给整行挂 onClick。
 */
@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val onColor = MaterialTheme.colorScheme.primary
    val offColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbOn = MaterialTheme.colorScheme.onPrimary
    val thumbOff = MaterialTheme.colorScheme.onSurfaceVariant
    val t by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        label = "appSwitch",
    )
    Box(
        Modifier.width(44.dp).height(20.dp).clip(RoundedCornerShape(99.dp)).clickable { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(34.dp).height(20.dp).clip(RoundedCornerShape(99.dp))
                .background(androidx.compose.ui.graphics.lerp(offColor, onColor, t))
        ) {
            Box(
                Modifier.offset(x = 2.dp + 14.dp * t, y = 2.dp).size(16.dp).clip(CircleShape)
                    .background(androidx.compose.ui.graphics.lerp(thumbOff, thumbOn, t))
            )
        }
    }
}

@Composable
fun Chevron() {
    Text("›", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 顶栏右上角的权限等级数字标识：只显示等级数字，点按打开换级弹窗。
 * 替代原先「当前 N 级只读，改参数需 3 级及以上」那类常驻横幅——同一句话每页都挂着太吵。
 * 颜色自带语义：≥3 可写=主色，1~2 只读=琥珀，0 未校验=灰。
 */
@Composable
fun PermissionBadge(level: Int, onClick: () -> Unit = {}) {
    val (bg, fg) = when {
        level >= 3 -> if (isDarkScheme()) BmsColors.Primary.copy(alpha = .28f) to Color(0xFF8FE39A)
                      else BmsColors.PrimaryContainer to BmsColors.OnPrimaryContainer
        level > 0 -> warnPair()
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(level.toString(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            color = fg, fontFamily = FontFamily.Monospace)
    }
}

/**
 * 顶栏右侧的写权限状态指示（放在权限数字徽标左边）：
 * 可编辑=主色 / 只读=琥珀 / 权限不足=灰。点它等同点权限徽标，直接进换级弹窗。
 */
@Composable
fun WriteAccessChip(access: WriteAccess, onClick: () -> Unit = {}) {
    val (bg, fg) = when (access) {
        WriteAccess.EDIT -> if (isDarkScheme()) BmsColors.Primary.copy(alpha = .22f) to Color(0xFF8FE39A)
                            else BmsColors.PrimaryContainer to BmsColors.OnPrimaryContainer
        WriteAccess.READ_ONLY -> warnPair()
        WriteAccess.DENIED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.height(20.dp).clip(RoundedCornerShape(7.dp)).background(bg)
            .clickable { onClick() }.padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(access.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

/** 顶栏右侧的「写权限状态 + 权限等级徽标」组合（配置类页面统一用这个） */
@Composable
fun WriteAccessTrailing(level: Int, access: WriteAccess, onClick: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WriteAccessChip(access, onClick)
        Spacer(Modifier.width(6.dp))
        PermissionBadge(level, onClick)
    }
}

@Composable
fun RadioDot(selected: Boolean) {
    Box(
        Modifier.size(18.dp).clip(CircleShape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
    ) {
        if (selected) Box(Modifier.padding(3.dp).fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
fun InfoBanner(text: String, kind: String = "info", action: String? = null, onAction: (() -> Unit)? = null) {
    // 行尾动作的兜底：给了回调就执行回调，没给就当作「知道了」把横幅收起。
    // 原来 onAction 默认空实现——调用方写了 action 却没传回调，就成了点不动的死按钮
    //（密码页的「了解」正是踩的这个坑）。按 text 记忆，换一条消息重新有机会显示。
    var dismissed by remember(text) { mutableStateOf(false) }
    if (dismissed) return
    // 深色下用半透明色块 + 亮字，浅色下用淡底 + 深字（照截图的扁平横幅）
    val (bg, fg) = when (kind) {
        "err" -> dangerPair()
        "warn" -> warnPair()
        else -> if (isDarkScheme()) BmsColors.ChartBlue.copy(alpha = .24f) to Color(0xFF8FB8FF)
                else Color(0xFFDCE9FB) to Color(0xFF1B62C4)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = fg, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = fg,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier.padding(start = 8.dp).clickable {
                    if (onAction != null) onAction() else dismissed = true
                })
        }
    }
}

/* ---------- 卡1：电池大卡（背景即电量） ---------- */

@Composable
fun BatteryCard(
    status: BmsStatus,
    modifier: Modifier = Modifier,
    fillColor: Color? = null,
    socText: String? = null,
    showPerm: Boolean = true,
    /** 右下角连接态文案；null=按数据自行推断（兼容旧调用） */
    connLabel: String? = null,
    onPermClick: () -> Unit = {},
) {
    // 填充色 = 电量进度色，未填充部分用同色淡底（截图里的「绿 + 淡绿」进度背景），不再是空卡底
    // 未连接/无数据时用中性灰：真实 0% 才是低电红，别让空态看着像报警
    val fill = fillColor ?: when {
        !status.hasData -> BmsColors.OffGray
        status.soc <= 15 -> BmsColors.BadRed
        else -> BmsColors.GreenFill
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val track = fill.copy(alpha = if (dark) .22f else .18f)
    // 卡内左侧文字的反色策略：填充与轨道是同色不同透明度，填充边界（SOC 55% 前后）会扫过文字，
    // 固定前景色在边界两侧对比度会突变（深字骑深填充 / 白字骑淡轨道都不可读）。
    // 解法：给文字垫一个不透明底衬胶囊（与右上角权限徽章、右下连接态胶囊同一套视觉语言），
    // 文字对比度只取决于底衬色，与底下是填充还是轨道彻底解耦。
    val chipBg = MaterialTheme.colorScheme.surface
    val onChip = MaterialTheme.colorScheme.onSurface
    val onChipLabel = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .fillMaxWidth()
            .clip(CardShape)          // 无描边扁平卡，圆角 20dp
            .background(track)
    ) {
        // 电量填充（左=有电，右=空电）：父级 clip 已把右缘裁直，与截图一致
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(status.soc / 100f).background(fill)
            )
        }
        // 右缘电池极头已去掉：在扁平进度卡上就是一根莫名其妙的竖条
        // 左侧：电压大标题（小 desc 紧跟其后）→ 设备名 → 循环 · 运行时间。
        // 三行都垫 surface 底衬胶囊：填充边界扫过时文字对比度不变（见上方 chipBg 注释）
        Row(
            modifier = Modifier.padding(CardPadding).fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(chipBg).padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (status.hasData) "%.2f".format(status.totalVoltage) else "--",
                        fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                        color = onChip,
                    )
                    Text(
                        " V · 当前电压",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = onChipLabel,
                        modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                    )
                }
                Text(
                    status.deviceName,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = onChip,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(chipBg).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Text(
                    "${status.totalCycleAh}Ah 循环 · ${status.runtime}",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = onChipLabel,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(chipBg).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Column(
                Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                if (showPerm) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onPermClick() }
                        .padding(horizontal = 9.dp, vertical = 2.dp)
                ) {
                    Text("权限 " + status.permissionLevel.toString() + " 级", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("▾", fontSize = 7.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 2.dp))
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    // 右下小块：连接态 + 电量；从未收到数据时显示「未连接 · --」而非误导性的「重连中 · 0%」
                    val everConnected = status.deviceName != "--"
                    val conn = connLabel ?: when {
                        status.connected -> "已连接"
                        everConnected -> "重连中"
                        else -> "未连接"
                    }
                    val pct = socText ?: (if (everConnected && status.hasData) status.soc.toString() + "%" else "--")
                    Text(conn + " · " + pct,
                        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
fun SmallChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(text, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
}

/* ---------- 卡2：状态 + 容量 ---------- */

@Composable
fun StatusCapacityCard(status: BmsStatus, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        // 没收到过数据就一律 "--"：拿 0.0Ah / 0% 当读数是误导
        fun v(text: String) = if (status.hasData) text else "--"
        // 左=MOS/均衡，右=电池状态+容量，左右各三行（SOH 已按需求移除）。
        // 每行必须是同一个 Row：此前左右各一个 Column 自堆自的，行高由各行内容
        // （中文 vs 拉丁数字的字体行高）决定，两列从第二行起逐行错位。
        val rows = listOf(
            listOf(
                Triple(BmsColors.OffGray, "充电 MOS", v(status.chMos)),
                Triple(BmsColors.WarnAmber, "电池状态", v(status.battState)),
            ),
            listOf(
                Triple(BmsColors.OkGreen, "放电 MOS", v(status.disMos)),
                Triple(BmsColors.IcBlue, "剩余容量", if (status.hasData) "%.1f".format(status.remainCapAh) + " Ah" else "--"),
            ),
            listOf(
                Triple(BmsColors.OffGray, "均衡状态", v(status.balance)),
                Triple(BmsColors.IcBlue, "总容量", if (status.hasData) "%.1f".format(status.totalCapAh) + " Ah" else "--"),
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { cells ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cells.forEach { (dot, label, value) ->
                        SRow(dot, label, value, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SRow(dot: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        StatusDot(dot)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

/* ---------- 卡3：电流/功率（换挡进度条）+ 其余指标网格 ---------- */

data class Metric(val label: String, val value: String, val unit: String)

/**
 * 卡3：只显示「电流」与「功率」两个大读数——左右对分、label 在上数值居中在下，
 * 功率下方保留变速箱式换挡进度条（设 N 个阶梯串 N 条轨道，走满一条进下一条）。
 * 点击卡片开/关进度条；长按弹窗设置阶梯（第 1 档必填，2/3 档可空）。
 * 其余指标（总压/循环/平均/最高/最低/压差）已由别的卡片展示，这里不再重复。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MetricGridCard(
    metrics: List<Metric>,
    modifier: Modifier = Modifier,
    powerW: Int? = null,
    hasData: Boolean = false,
) {
    var gaugeOn by remember { mutableStateOf(AppStore.powerGaugeEnabled) }
    var stages by remember { mutableStateOf(AppStore.powerStagesW) }
    var editing by remember { mutableStateOf(false) }
    // 点击/长按挂在整卡读数上：点击开关进度条，长按设阶梯
    val gaugeClickable = Modifier.combinedClickable(
        onClick = { gaugeOn = !gaugeOn; AppStore.powerGaugeEnabled = gaugeOn },
        onLongClick = { editing = true },
    )

    SectionCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = metrics.firstOrNull { it.label == "电流" }
            val power = metrics.firstOrNull { it.label == "功率" }
            Row(
                modifier = Modifier.fillMaxWidth().then(gaugeClickable).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CenterMetric(current, Modifier.weight(1f))
                // 中缝细分隔线：两块读数各占一半
                Box(Modifier.width(1.dp).height(34.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)))
                CenterMetric(power, Modifier.weight(1f))
            }
            if (gaugeOn && powerW != null && stages.isNotEmpty()) {
                PowerGauge(powerW, stages, hasData)
            }
        }
    }
    if (editing) {
        PowerStageDialog(
            initial = stages,
            onDismiss = { editing = false },
            onConfirm = { v -> stages = v; AppStore.powerStagesW = v; editing = false },
        )
    }
}

/** 居中大读数（卡3 用）：label 小字加粗居上，数值大字居中在下 */
@Composable
private fun CenterMetric(m: Metric?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (m == null) {
            Text("--", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(m.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                 color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(m.unit, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
        }
    }
}

/** 变速箱式功率条：每档一条圆角轨道，走满进下一档；未连接时整组淡显。 */
@Composable
private fun PowerGauge(powerW: Int, stages: List<Int>, hasData: Boolean) {
    val total = stages.sum()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        var acc = 0
        stages.forEachIndexed { i, cap ->
            val start = acc; acc += cap
            // 本档进度：未进档=0，已走过=1，正在本档内=比例
            val frac = when {
                !hasData -> 0f
                powerW >= acc -> 1f
                powerW <= start -> 0f
                else -> (powerW - start).toFloat() / cap
            }
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (frac > 0f) Box(
                    Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (i == stages.lastIndex) BmsColors.ChartBlue else BmsColors.Primary)
                )
            }
        }
        Row {
            Text(
                (if (hasData) "${powerW.coerceAtLeast(0)} W" else "-- W") + " / 共 ${total} W",
                fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            // 当前档位提示（负功率=充电不计量，显示待机）
            val gearNo = if (!hasData || powerW < 0) null
                         else stages.indexOfFirst { powerW < it }.let { if (it < 0) stages.size else it + 1 }
            Text(
                if (gearNo == null) "待机" else "挡位 $gearNo/${stages.size}",
                fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 功率阶梯设置弹窗：3 个输入位，第 1 档必填，2/3 档留空即不设；非空必须为正整数 */
@Composable
private fun PowerStageDialog(
    initial: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    var s1 by remember { mutableStateOf(initial.getOrNull(0)?.toString() ?: "1000") }
    var s2 by remember { mutableStateOf(initial.getOrNull(1)?.toString() ?: "") }
    var s3 by remember { mutableStateOf(initial.getOrNull(2)?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    fun parsed(): List<Int>? {
        val out = mutableListOf<Int>()
        listOf(s1, s2, s3).forEachIndexed { i, t ->
            val trimmed = t.trim()
            if (trimmed.isEmpty()) { if (i == 0) return null; return@forEachIndexed }
            val v = trimmed.toIntOrNull() ?: return null
            if (v <= 0) return null
            out.add(v)
        }
        return out
    }
    val fields = listOf("第 1 档（W）" to s1, "第 2 档（W，可选）" to s2, "第 3 档（W，可选）" to s3)
    val setters = listOf<(String) -> Unit>(
        { s1 = it; error = null }, { s2 = it; error = null }, { s3 = it; error = null },
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text("功率阶梯（换挡进度条）", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text("第 1 档必填，后两档可留空；功率走满一档再进下一档", fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            fields.forEachIndexed { i, (label, value) ->
                OutlinedTextField(
                    value = value,
                    onValueChange = { setters[i](it.filter { ch -> ch.isDigit() }.take(6)) },
                    label = { Text(label, fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            error?.let { Text(it, fontSize = 10.sp, color = BmsColors.BadRed, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    val v = parsed()
                    if (v == null) error = "第 1 档必填且所有填写的档位须为正整数（W）"
                    else onConfirm(v)
                }) { Text("确定", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

fun BmsStatus.metrics(off: Boolean = !hasData): List<Metric> {
    // 卡3 只显示电流与功率；总压/循环/平均/最高/最低/压差由单体电压卡等其它卡片展示，不再重复
    if (off) return listOf(
        Metric("电流", "--", "A"),
        Metric("功率", "--", "W"),
    )
    return listOf(
        Metric("电流", "%.1f".format(current), "A"),
        Metric("功率", power.toString(), "W"),
    )
}

/* ---------- 保护/告警双卡 ---------- */

@Composable
fun ProtectAlarmCards(status: BmsStatus, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PwCard("保护信息", if (status.hasData) status.protectList.size else null, BmsColors.BadRed,
               status.protectList, status.hasData, onSeeAll, Modifier.weight(1f))
        PwCard("告警信息", if (status.hasData) status.alarmList.size else null, BmsColors.WarnAmber,
               status.alarmList, status.hasData, onSeeAll, Modifier.weight(1f))
    }
}

@Composable
private fun PwCard(
    title: String,
    count: Int?,
    dot: Color,
    items: List<String>,
    hasData: Boolean,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clip(CardShape).background(MaterialTheme.colorScheme.surface).padding(CardPadding)) {
        Row { Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f)); Text(count?.toString() ?: "--", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!hasData) {
            Text("未连接 · 无数据", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        } else if (items.isEmpty()) {
            Text("无保护动作 ✓", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        } else items.take(3).forEach {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                StatusDot(dot, 5.dp); Spacer(Modifier.width(5.dp))
                Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 无数据时不给点：点开只会看到空详情
        Text(
            "查看全部 ›", fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
            color = if (hasData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp).then(if (hasData) Modifier.clickable { onSeeAll() } else Modifier),
        )
    }
}

/**
 * 温度卡：左列 MOS/均衡 两行，右侧 T1~T4 两行两列。
 * 窄屏（360dp 实测）上 T 块一字排开会被挤成「T122.0」，故右侧取 2×2。
 */
@Composable
fun TempCard(temps: List<Pair<String, Double>>, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        SectionHeader("温度", tail = if (temps.isEmpty()) "--" else temps.size.toString() + " 路")
        if (temps.isEmpty()) {
            Text("未连接 · 无数据", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp))
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(0.9f)) {
                TempBox("MOS", temps.firstOrNull { it.first == "MOS" }?.second)
                TempBox("均衡", temps.firstOrNull { it.first == "均衡" }?.second)
            }
            val sensors = temps.filter { it.first.startsWith("T") }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(2.1f)) {
                sensors.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { (k, v) -> TempBox(k, v, Modifier.weight(1f)) }
                        // 奇数个时补占位，避免最后一个块被拉宽
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TempBox(label: String, value: Double?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (value == null) "--" else "%.1f°".format(value), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/* ---------- 单体电压网格 ---------- */

@Composable
fun CellGridCard(cells: List<CellV>, modifier: Modifier = Modifier,
                 avgCell: String = "--", deltaCell: String = "--") {
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(if (cells.isEmpty()) "单体电压" else "单体电压 × ${cells.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            if (cells.isNotEmpty()) {
                // 正经字体 + 语义标注：平均/压差紧跟标题（图例说明见卡片底部的小字）
                Text(
                    "平均：${avgCell}V  压差：${deltaCell}V",
                    fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (cells.isEmpty()) {
            Text("未连接 · 无数据", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp))
            return@SectionCard
        }
        // 5 列：20 串正好 4 行（4 列要 5 行，仪表盘上白白多一排）。窄屏 360dp 实测每格仍容得下 4.263
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            cells.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { c -> CellBox(c, Modifier.weight(1f)) }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // 颜色图例移到网格下方小字（原先挤在标题行，换成了平均/压差）
        Text(
            "黄=最高 蓝=最低 ●=均衡",
            fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun CellBox(c: CellV, modifier: Modifier = Modifier) {
    // 扁平芯片：普通格=灰底，最高/最低=同色淡底 + 彩色数值（不再用彩色描边）
    val bg = when {
        c.isMax -> BmsColors.CellMax.copy(alpha = if (isDarkScheme()) .26f else .16f)
        c.isMin -> BmsColors.CellMin.copy(alpha = if (isDarkScheme()) .26f else .14f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val valueColor = when {
        c.isMax -> if (isDarkScheme()) Color(0xFFE5A84B) else BmsColors.CellMax
        c.isMin -> if (isDarkScheme()) Color(0xFF7FA9FF) else BmsColors.CellMin
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${c.index}", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text("%.3f".format(c.volt), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = valueColor)
        }
        if (c.balancing) Box(
            Modifier.align(Alignment.TopEnd).padding(2.5.dp).size(4.5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
        )
    }
}

/* ---------- 趋势图（Canvas 简单折线） ---------- */

@Composable
fun TrendCard(current: List<Float>, volt: List<Float>, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        SectionHeader("趋势 · 近 5 分钟", tail = "— 电流 ─ 电压")
        val primaryColor = MaterialTheme.colorScheme.primary
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(52.dp)) {
            val w = size.width; val h = size.height
            fun line(data: List<Float>, color: Color) {
                if (data.size < 2) return
                val path = androidx.compose.ui.graphics.Path()
                data.forEachIndexed { i, v ->
                    val x = w * i / (data.size - 1)
                    val y = h * (1f - v)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
            }
            line(volt, BmsColors.CellMax)
            line(current, primaryColor)
        }
    }
}

/* ---------- 通用大按钮行 ---------- */

@Composable
fun ControlButtonsRow(
    chargeOn: Boolean, dischargeOn: Boolean,
    onCharge: () -> Unit, onDischarge: () -> Unit, onForce: () -> Unit,
    modifier: Modifier = Modifier,
    /** 未连接时置灰：否则点了只会走一遍确认框再被设备拒绝 */
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BigPill("充电开关", chargeOn, Modifier.weight(1f), enabled = enabled, onClick = onCharge)
        BigPill("放电开关", dischargeOn, Modifier.weight(1f), enabled = enabled, onClick = onDischarge)
        val (fbg, ffg) = if (enabled) warnPair()
            else MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(14.dp))
            .background(fbg)
            .then(if (enabled) Modifier.clickable { onForce() } else Modifier),
            contentAlignment = Alignment.Center) {
            Text("强制充电", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ffg)
        }
    }
}

@Composable
private fun BigPill(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (bg, fg) = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        on -> okPair()
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
    }
    Box(modifier.height(32.dp).clip(RoundedCornerShape(14.dp)).background(bg)
        .then(if (enabled) Modifier.clickable { onClick() } else Modifier), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}
