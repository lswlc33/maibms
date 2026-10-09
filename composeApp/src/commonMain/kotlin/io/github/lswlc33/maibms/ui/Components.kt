package io.github.lswlc33.maibms.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ant_bms_open.composeapp.generated.resources.Res
import ant_bms_open.composeapp.generated.resources.power_charging
import ant_bms_open.composeapp.generated.resources.power_gear1
import ant_bms_open.composeapp.generated.resources.power_gear2
import ant_bms_open.composeapp.generated.resources.power_gear3
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.BmsStatus
import io.github.lswlc33.maibms.data.CellV
import io.github.lswlc33.maibms.data.DEFAULT_POWER_STAGES
import io.github.lswlc33.maibms.data.fmt
import io.github.lswlc33.maibms.data.fmtDurationSec
import io.github.lswlc33.maibms.data.fmtRemainingMin
import io.github.lswlc33.maibms.protocol.WriteAccess
import io.github.lswlc33.maibms.ui.BmsColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

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
    // 卡内左侧文字的可读性（用户要求：不要底衬，用透明/滤镜方式）：
    // 填充层用**均匀半透明**色画到 soc% 为止——进度条必须表达电量进度：
    // 曾用「左实右透」渐变滤镜（饱和色留最左 12% 作锚点、25% 后全透明），但渐变色标
    // 是相对填充层自身宽度分布的，实际画出来的是一条与 soc 无关的左侧渐变色块
    //（快照 76% 电量下肉眼可见的绿只到 ~19%），既不是电量进度、也谈不上锚点。
    // 均匀填充叠在轨道之上仍是均匀色，填充右缘就是电量边界本身，任何电量下都是一条
    // 干净的进度边界，不会再出现渐变色块或竖向色带。
    // 填充层透明度取中档：远深于轨道（进度可辨），又浅到文字直接坐在上面仍可读。
    // 文字前景用 onSurface（不是 onSurfaceVariant）：轨道/填充都是同色系淡底，
    // 灰字对比度不足，只有主前景色才够
    val fillAlpha = if (dark) 0.50f else 0.40f
    // 设备名/见过设备：广播名可能是空串（部分设备常见），空白行看着像"名字丢了"——
    // 有连接/有数据时兜底「未命名设备」；「见过设备」也不再看名字这一项，
    // 已连接或已有数据都算，避免名字缺失把电量百分比一起带成 "--"
    val devName = when {
        status.deviceName.isNotBlank() && status.deviceName != "--" -> status.deviceName
        status.connected || status.hasData -> "未命名设备"
        else -> "--"
    }
    val everConnected = status.deviceName != "--" || status.connected || status.hasData
    Box(
        modifier
            .fillMaxWidth()
            .clip(CardShape)          // 无描边扁平卡，圆角 20dp
            .background(track)
    ) {
        // 电量填充（左=有电，右=空电）：父级 clip 已把右缘裁直，与截图一致
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(status.soc / 100f)
                    .background(fill.copy(alpha = fillAlpha))
            )
        }
        // 右缘电池极头已去掉：在扁平进度卡上就是一根莫名其妙的竖条
        // 左侧：电压大标题（小 desc 紧跟其后）→ 设备名 → 循环 · 运行时间（无底衬，常规前景）
        Row(
            modifier = Modifier.padding(CardPadding).fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (status.hasData) "%.2f".fmt(status.totalVoltage) else "--",
                        fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        " V · 当前电压",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
                        modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                    )
                }
                Text(
                    devName,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    "${status.totalCycleAh}Ah 循环 · ${status.runtime}",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
                    maxLines = 1,
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
                Triple(BmsColors.IcBlue, "剩余容量", if (status.hasData) "%.1f".fmt(status.remainCapAh) + " Ah" else "--"),
            ),
            listOf(
                Triple(BmsColors.OffGray, "均衡状态", v(status.balance)),
                Triple(BmsColors.IcBlue, "总容量", if (status.hasData) "%.1f".fmt(status.totalCapAh) + " Ah" else "--"),
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

/* ---------- 卡3：电流/功率（背景换挡进度条） ---------- */

data class Metric(val label: String, val value: String, val unit: String)

/**
 * 卡3：只显示「电流」与「功率」两个大读数——左右对分、label 在上数值居中在下。
 * 大读数下方是小灰字：**按状态只显示一项**剩余时间——充电中给「充电剩余」、放电/静置给
 * 「放电剩余」（扩展段 86/88，0=设备未报 → "--"），有值时再补一行已充/距上次充电
 * （扩展段 78/82）；两行都收在本列内，不往右挤立牌。
 * 进度条即**卡片背景本身**：放电时每个档位一条**完整**进度条（刻度 0~本档上限），功率到档
 * 整条切换（500W 用 0~1000 的条、1500W 切 0~2000 的条），配色=节能绿/均衡蓝/运动红；
 * **副档位**（充电与动能回收共用，默认 -1500W）画成节能绿的反向条（右→左），
 * 静置（|功率|<20W）当 0 显示第 1 档空条。
 * 右下角状态立牌随状态切图（充电/一档/二档/三档，composeResources 贴纸），读数左移；
 * 功率顶破末档上限时立牌左右轻摇表示到顶。
 * **双击卡片**开/关（关闭后右上角留一个小圆点提示，Toast 提示开/关成功）；
 * 长按弹窗设置阶梯（副档位 + 三档必填，默认 -1500/1000/3000/5000）。
 * 其余指标（总压/循环/平均/最高/最低/压差）已由别的卡片展示，这里不再重复。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MetricGridCard(
    status: BmsStatus,
    modifier: Modifier = Modifier,
) {
    val metrics = status.metrics()
    val powerW = status.power
    val hasData = status.hasData
    var gaugeOn by remember { mutableStateOf(AppStore.powerGaugeEnabled) }
    var stages by remember { mutableStateOf(AppStore.powerStagesW) }
    var editing by remember { mutableStateOf(false) }
    // 双击开关进度条（用户要求双击 + 开关提示）；长按设阶梯。
    // combinedClickable 的双击重载要求同时给 onClick（给空实现，单击不占任何行为）
    val cardClickable = Modifier.combinedClickable(
        onClick = {},
        onDoubleClick = {
            gaugeOn = !gaugeOn
            AppStore.powerGaugeEnabled = gaugeOn
            showSystemToast(if (gaugeOn) "功率进度条已开启" else "功率进度条已关闭")
        },
        onLongClick = { editing = true },
    )
    /** 表盘特性是否启用（双击开关）：背景条、状态立牌、关闭圆点都以它为前提 */
    val gaugeActive = gaugeOn && stages.isNotEmpty()
    val bgOn = gaugeActive
    /** 当前条号：0 = 副档位（充电/动能回收，反向绿条）；1/2/3 = 放电档 */
    val barIdx = if (hasData) powerGaugeBarIndex(powerW, stages) else 1
    /** 顶破末档上限：立牌左右轻摇表示到顶 */
    val overTop = hasData && powerGaugeOverTop(powerW, stages)
    // 进度条作底时文字换用 onSurface 系：onSurfaceVariant 的灰在色块上对比度不足
    val labelColor = if (bgOn) MaterialTheme.colorScheme.onSurface.copy(alpha = .72f)
                     else MaterialTheme.colorScheme.onSurfaceVariant
    // 立牌「到顶」左右轻摇：只在 overTop 时才挂载无限动画——常驻的无限动画会让
    // 仪表盘永不空闲、一直满帧刷新（纯耗电）；未到顶时角度恒 0，视觉完全一致
    val shakeDeg = rememberTopShakeDeg(overTop)
    /** 状态立牌（充电/一档/二档/三档各一张）：表关了或未连接时不占位 */
    val sticker: DrawableResource? = when {
        !gaugeActive || !hasData -> null
        barIdx == 0 -> Res.drawable.power_charging
        barIdx == 1 -> Res.drawable.power_gear1
        barIdx == 2 -> Res.drawable.power_gear2
        else -> Res.drawable.power_gear3
    }

    Column(
        modifier.fillMaxWidth().clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)   // 底色：进度条未覆盖时的轨道区
            .then(cardClickable)
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (bgOn) PowerGaugeBackground(powerW, stages, hasData, Modifier.matchParentSize())
            // 关闭态提示点：卡片被双击关掉后右上角留一个小圆点（再次双击即恢复）
            if (!gaugeOn) Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 9.dp)
                    .size(6.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .45f))
            )
            // 上下内边距压到 2dp：卡片高度尽量由读数决定，立牌靠右下角
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CardPadding, vertical = 2.dp)
            ) {
                Column(
                    Modifier.weight(1f).align(Alignment.CenterVertically),
                ) {
                    val current = metrics.firstOrNull { it.label == "电流" }
                    val power = metrics.firstOrNull { it.label == "功率" }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CenterMetric(current, labelColor, Modifier.weight(1f))
                        CenterMetric(power, labelColor, Modifier.weight(1f))
                    }
                    // 大读数下方的小灰字：充电/放电剩余（扩展段 86/88，0=设备未报 → "--"）。
                    // 只显示与当前状态相关的一项：充电中（P < -20W，与横屏表盘同一死区）给
                    // 「充电剩余」，放电/静置给「放电剩余」——不再两项并列，免掉恒有一项 "--" 的噪音。
                    // 文字整行居中收在本列内，不往右挤立牌和卡1 的电量角标
                    val chargingNow = hasData && powerW < -20
                    Text(
                        (if (chargingNow) "充电剩余 " else "放电剩余 ") +
                            fmtRemainingMin(if (chargingNow) status.remainChargeMin else status.remainDischargeMin),
                        fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = labelColor,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                    )
                    // 本次充电时长/上次充电间隔（扩展段 78/82）：有值才出现的一行，整行居中
                    val statLine = buildList {
                        if (status.thisChargeSec > 0) add("已充 " + fmtDurationSec(status.thisChargeSec))
                        if (status.lastChargeGapSec > 0) add("距上次充电 " + fmtDurationSec(status.lastChargeGapSec))
                    }
                    if (statLine.isNotEmpty()) Text(
                        statLine.joinToString(" · "),
                        fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = labelColor,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                    )
                }
                // 右下角状态立牌：裁边贴纸显示 80dp，充电/一档/二档/三档随状态切图（不放任何提示文字）；
                // 顶破末档时左右轻摇表示到顶
                sticker?.let { res ->
                    Image(
                        painterResource(res),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Bottom)
                            .size(80.dp)
                            .graphicsLayer { rotationZ = shakeDeg },
                    )
                }
            }
        }
    }
    if (editing) {
        PowerStageDialog(
            initial = stages,
            onDismiss = { editing = false },
            onConfirm = { v ->
                stages = v; AppStore.powerStagesW = v; editing = false
                showSystemToast("功率阶梯已设置：${v.joinToString("/")}W")
            },
        )
    }
}

/** 居中大读数（卡3 用）：label 小字加粗居上，数值大字居中在下；大字固定 onSurface（色块上仍够对比） */
@Composable
private fun CenterMetric(m: Metric?, labelColor: Color, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (m == null) {
            Text("--", fontSize = 10.sp, color = labelColor)
            return@Column
        }
        Text(m.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = labelColor)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                 color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(m.unit, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = labelColor,
                 modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
        }
    }
}

/**
 * 「到顶摇晃」角度：仅在 [active] 期间挂载无限动画（-5°↔+5° 往复），否则返回 0。
 * 关键在**条件挂载**——`rememberInfiniteTransition` 一旦组合就永远驱动帧循环，
 * 会让仪表盘常年满帧刷新（纯耗电）；把动画关进 `if (active)` 后，未到顶时
 * 这块组合没有任何动画在跑，到顶才起振、离顶即停。
 */
@Composable
private fun rememberTopShakeDeg(active: Boolean): Float {
    if (!active) return 0f
    val shake = rememberInfiniteTransition(label = "topShake")
    val deg by shake.animateFloat(
        initialValue = -5f, targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 130, easing = LinearEasing), RepeatMode.Reverse),
        label = "shakeDeg",
    )
    return deg
}

/**
 * 全卡背景进度条：放电档 = 每个档位一条**完整**进度条（刻度 0~本档上限），功率到档整条
 * 切换，填充 = (功率/本档上限)²（前段压缩后段冲刺），跨档整条换色：节能绿 → 均衡蓝 → 运动红。
 * **副档位**（充电/动能回收共用）= 节能绿同款配色、固定量程（默认 1500W）、**反向填充**
 * （右→左），填充 = (|功率|/副档位量程)²；静置死区内为第 1 档空条。
 * 未连接/无数据整条中性灰——空态别看着像低电/告警。
 */
@Composable
private fun PowerGaugeBackground(powerW: Int?, stages: List<Int>, hasData: Boolean, modifier: Modifier = Modifier) {
    val dark = isDarkScheme()
    val caps = powerGaugeCaps(stages)
    val barIdx = if (hasData && powerW != null) powerGaugeBarIndex(powerW, stages) else 1
    val fill = if (hasData && powerW != null) powerGaugeBarFill(powerW, stages) else 0f
    val reversed = barIdx == 0   // 副档位：充电/动能回收，反向（右→左）
    val base = when {
        !hasData -> BmsColors.OffGray
        reversed -> BmsColors.GreenFill                                  // 副档位=节能绿同款
        caps.size <= 1 || barIdx == 1 -> BmsColors.GreenFill             // 节能档
        barIdx >= caps.size -> BmsColors.BadRed                          // 运动档（末档=红区）
        else -> BmsColors.ChartBlue                                      // 均衡档
    }
    // 填充与换色都走短动画：进度跟手，跨档瞬间是整条渐变而非硬切
    val animFill by animateFloatAsState(fill, tween(180))
    val animBase by animateColorAsState(base, tween(250))
    // 填充明度与卡1 电池大卡完全同参：.40（深色 .50）叠在同色 .18/.22 轨道上，
    // 两张卡的色块亮度一致——不过纯也不过淡
    val fillAlpha = if (dark) 0.50f else 0.40f
    Box(
        modifier.background(animBase.copy(alpha = if (dark) .22f else .18f)),   // 轨道=同色淡底
        contentAlignment = if (reversed) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        if (animFill > 0f) Box(
            Modifier.fillMaxHeight().fillMaxWidth(animFill.coerceIn(0f, 1f))
                .background(animBase.copy(alpha = fillAlpha))
        )
    }
}

/**
 * 功率阶梯设置弹窗：**四个档位全部必填**——副档位（充电/动能回收共用，负数或填正数自动取负）
 * ＋ 三个放电档上限（正整数、互不相同）。不需要的档位可以把上限设得很高。
 */
@Composable
private fun PowerStageDialog(
    initial: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    val sub0 = initial.firstOrNull { it < 0 } ?: DEFAULT_POWER_STAGES.first()
    val caps0 = powerGaugeCaps(initial).ifEmpty { DEFAULT_POWER_STAGES.drop(1) }
    var sub by remember { mutableStateOf(sub0.toString()) }
    var s1 by remember { mutableStateOf(caps0.getOrNull(0)?.toString() ?: "1000") }
    var s2 by remember { mutableStateOf(caps0.getOrNull(1)?.toString() ?: "3000") }
    var s3 by remember { mutableStateOf(caps0.getOrNull(2)?.toString() ?: "5000") }
    var error by remember { mutableStateOf<String?>(null) }
    fun parsed(): List<Int>? {
        val subV = sub.trim().toIntOrNull() ?: return null
        if (subV == 0) return null
        val caps = listOf(s1, s2, s3).map { it.trim().toIntOrNull() ?: return null }
        if (caps.any { it <= 0 }) return null
        if (caps.distinct().size < 3) return null
        // 副档位统一存负数；正档排序在这里做（onConfirm 把返回值原样赋给卡面状态）
        return listOf(-kotlin.math.abs(subV)) + caps.sorted()
    }
    val fields = listOf(
        "副档位上限（W，充电/动能回收）" to sub,
        "一档上限（W）" to s1,
        "二档上限（W）" to s2,
        "三档上限（W）" to s3,
    )
    val setters = listOf<(String) -> Unit>(
        { sub = it; error = null }, { s1 = it; error = null },
        { s2 = it; error = null }, { s3 = it; error = null },
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text("功率阶梯（换挡进度条）", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text("副档位=充电/动能回收共用的量程（如 -1500，填正数也按负值存）；" +
                    "三个放电档填上限，功率升到上限就换下一档，不需要的档位可以把上限设得很高",
                fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            fields.forEachIndexed { i, (label, value) ->
                OutlinedTextField(
                    value = value,
                    // 副档位框允许前导负号；其余只收数字。6 位上限（999999W）足够覆盖实际量程
                    onValueChange = { setters[i](it.filter { ch -> ch.isDigit() || (i == 0 && ch == '-') }.take(7)) },
                    label = { Text(label, fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            error?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    val v = parsed()
                    if (v == null) error = "四个档位都要填：副档位为非零整数，三个放电档为正整数且互不相同"
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
        Metric("电流", "%.1f".fmt(current), "A"),
        Metric("功率", power.toString(), "W"),
    )
}

/* ---------- 保护/告警双卡 ---------- */

@Composable
fun ProtectAlarmCards(status: BmsStatus, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    // 连接后两卡都空 → 整块隐藏（父列 spacedBy(8dp) 自动吸收间距）；未连接保留占位
    if (status.hasData && status.protectList.isEmpty() && status.alarmList.isEmpty()) return
    // IntrinsicSize.Min：两卡按内容较多的一侧撑齐高度（一侧空一侧有条目时不再一高一低）
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

/** 协议约定 -40℃ = 该路未接传感器（RealtimeDecoder 同注），界面直接隐藏 */
private const val TEMP_NOT_CONNECTED = -40.0

/**
 * 温度卡。未接的 -40 路不显示（通常是 T1~T4 没装探头），按 T 剩余路数收排：
 * 缺 0/1 个：左列 MOS/均衡 两行 + 右侧 T 两行两列（窄屏 360dp 实测一字排开会挤成「T122.0」）；
 * 缺 2 个：四块收成 2×2，MOS/均衡 上排、两路 T 下排；缺 3 个：一行 3 个；缺 4 个：一行 2 个。
 */
@Composable
fun TempCard(temps: List<Pair<String, Double>>, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        val present = temps.filter { it.second != TEMP_NOT_CONNECTED }
        SectionHeader("温度", tail = if (temps.isEmpty()) "--" else present.size.toString() + " 路")
        if (temps.isEmpty()) {
            Text("未连接 · 无数据", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp))
            return@SectionCard
        }
        val mosEq = present.filter { it.first == "MOS" || it.first == "均衡" }
        val sensors = present.filter { it.first.startsWith("T") }
        when (sensors.size) {
            // 缺 3/4 个：一行 3 个 / 一行 2 个
            0, 1 -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                (mosEq + sensors).forEach { (k, v) -> TempBox(k, v, Modifier.weight(1f)) }
            }
            // 缺 2 个：MOS/均衡 + 两路 T 正好收成 2×2
            2 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                (mosEq + sensors).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (k, v) -> TempBox(k, v, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            // 缺 0/1 个：常规布局——左列 MOS/均衡，右侧 T 两行两列
            else -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                if (mosEq.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(0.9f)) {
                        mosEq.forEach { (k, v) -> TempBox(k, v) }
                    }
                }
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
        Text(if (value == null) "--" else "%.1f°".fmt(value), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
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
            Text("%.3f".fmt(c.volt), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = valueColor)
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
