package io.github.lswlc33.maibms.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.lswlc33.maibms.data.MockBms

/* ---------- 简易导航：三底栏 + push 栈 ---------- */

sealed class Route(val key: String) {
    data object Dashboard : Route("dashboard")
    data object Config : Route("config")
    data object Settings : Route("settings")
    data class ParamGroup(val index: Int) : Route("param")   // S06（下标 → ParamTable.groups）
    data object ControlTools : Route("control")      // S08
    data object Password : Route("password")         // S13
    data object Developer : Route("developer")       // S15
}

/** 弹窗种类 */
sealed class DialogKind {
    data object Scan : DialogKind()          // S01
    data object ProtectDetail : DialogKind() // S03
    data class ControlConfirmNamed(val command: String) : DialogKind()
    data class ParamEdit(val item: io.github.lswlc33.maibms.data.ParamItem) : DialogKind() // S07
    data object PermLevels : DialogKind()          // 权限换级
}

/** 命令名 → 命令号（开关类按当前状态取反） */
private fun cmdFor(name: String): Int? = when (name) {
    "充电开关" -> if (MockBms.chargeSwitch.value) io.github.lswlc33.maibms.protocol.ControlCmd.CHARGE_OFF else io.github.lswlc33.maibms.protocol.ControlCmd.CHARGE_ON
    "放电开关" -> if (MockBms.dischargeSwitch.value) io.github.lswlc33.maibms.protocol.ControlCmd.DISCHARGE_OFF else io.github.lswlc33.maibms.protocol.ControlCmd.DISCHARGE_ON
    "均衡开关" -> if (MockBms.balanceSwitch.value) io.github.lswlc33.maibms.protocol.ControlCmd.BALANCE_OFF else io.github.lswlc33.maibms.protocol.ControlCmd.BALANCE_ON
    "强制开启充电" -> io.github.lswlc33.maibms.protocol.ControlCmd.FORCE_CHARGE
    "保存应用参数" -> io.github.lswlc33.maibms.protocol.ControlCmd.SAVE_PARAMS
    "电流归零" -> io.github.lswlc33.maibms.protocol.ControlCmd.CURRENT_ZERO
    "重启系统" -> io.github.lswlc33.maibms.protocol.ControlCmd.RESTART
    "蜂鸣器" -> io.github.lswlc33.maibms.protocol.ControlCmd.BUZZER_ON
    "恢复出厂设置" -> io.github.lswlc33.maibms.protocol.ControlCmd.FACTORY_RESET
    "关闭系统" -> io.github.lswlc33.maibms.protocol.ControlCmd.POWER_OFF
    else -> null
}

/** 深链直达：底栏三根页占 tab，其余页压栈 */
private fun Route.isTabRoot(): Boolean =
    this is Route.Dashboard || this == Route.Config || this == Route.Settings

/** 底栏标签的左右顺序（只有三个根页有值），标签切换方向按它算 */
private fun Route.tabIndex(): Int? = when (this) {
    Route.Dashboard -> 0
    Route.Config -> 1
    Route.Settings -> 2
    else -> null
}

/**
 * @param deepLink 直达指定页面（截图工具用）
 * @param deepDialog 直达指定弹窗
 * @param forceDark 强制主题，null = 跟随系统/用户设置
 */
@Composable
fun App(
    deepLink: Route? = null,
    deepDialog: DialogKind? = null,
    forceDark: Boolean? = null,
) {
    // 启动数据管线：真机 BLE（无记忆设备时停在未连接态等待扫描）
    LaunchedEffect(Unit) { io.github.lswlc33.maibms.data.Bms.repository.start() }
    BmsTheme {
        val initial = deepLink ?: Route.Dashboard
        val currentTab = remember { mutableStateOf<Route>(if (initial.isTabRoot()) initial else Route.Dashboard) }
        val backStack = remember {
            mutableStateListOf<Route>().apply { if (!initial.isTabRoot()) add(initial) }
        }   // push 页
        val dialog = remember { mutableStateOf<DialogKind?>(deepDialog) }
        // ---- 过渡动画方向：压栈=新页从右进，出栈=原路退回（标签切换按底栏顺序另算） ----
        var navForward by remember { mutableStateOf(true) }
        val push: (Route) -> Unit = { r -> navForward = true; backStack.add(r) }
        val popBack: () -> Unit = { navForward = false; backStack.pop() }
        // 外观设置落盘（原先只存在内存里，重启就回到跟随系统）
        var themeMode by remember { mutableStateOf(io.github.lswlc33.maibms.data.AppStore.themeMode) }
        val darkOverride: Boolean? = when (themeMode) {
            "light" -> false; "dark" -> true; else -> null
        }
        // 外观设置与设置页共用同一个写入口，改动即落盘
        val setDarkOverride: (Boolean?) -> Unit = { v ->
            themeMode = when (v) { null -> "system"; false -> "light"; true -> "dark" }
            io.github.lswlc33.maibms.data.AppStore.themeMode = themeMode
        }

        val dark = forceDark ?: darkOverride ?: androidx.compose.foundation.isSystemInDarkTheme()
        BmsTheme(darkTheme = dark) {
            // 系统返回键：先关弹窗，再退栈，最后回仪表盘标签
            PlatformBackHandler(
                enabled = dialog.value != null || backStack.isNotEmpty() || currentTab.value != Route.Dashboard
            ) {
                when {
                    dialog.value != null -> dialog.value = null
                    backStack.isNotEmpty() -> { navForward = false; backStack.pop() }
                    else -> currentTab.value = Route.Dashboard
                }
            }
            val route = backStack.lastOrNull() ?: currentTab.value
            val isRoot = backStack.isEmpty()
            // 页面内的异步动作（写参数/控制命令）与弹窗共用一个 scope
            val scope = rememberCoroutineScope()
            // 底栏在三个主标签（含仪表盘变体状态）显示
            val isTabRoot = backStack.isEmpty() && route.isTabRoot()
            // 有底栏时，滚动内容末尾要留出底栏 + 系统导航栏的高度，否则最后一行被压在底栏下
            val navBarPad = if (isTabRoot) BottomNavHeight else 20.dp

            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    // 窗口过渡：压栈/出栈横向滑动，底栏标签之间按左右顺序横移 + 淡入淡出
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            val fromTab = initialState.tabIndex()
                            val toTab = targetState.tabIndex()
                            if (fromTab != null && toTab != null) {
                                val rightward = toTab > fromTab
                                (slideInHorizontally(tween(240)) { if (rightward) it / 2 else -it / 2 } + fadeIn(tween(200)))
                                    .togetherWith(
                                        slideOutHorizontally(tween(240)) { if (rightward) -it / 3 else it / 3 } + fadeOut(tween(160))
                                    )
                            } else if (navForward) {
                                // 压栈：新页从右滑入，旧页小幅左移淡出（避免整屏横飞）
                                (slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)))
                                    .togetherWith(
                                        slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(160))
                                    )
                            } else {
                                // 出栈：反向原路退回
                                (slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(200)))
                                    .togetherWith(
                                        slideOutHorizontally(tween(280)) { it } + fadeOut(tween(160))
                                    )
                            }
                        },
                        label = "screen",
                    ) { r ->
                        Column(Modifier.fillMaxSize()) {
                            when (r) {
                                Route.Dashboard -> DashboardScreen(
                                    showScanEntry = isRoot,
                                    bottomPadding = navBarPad,
                                    onOpenScan = { dialog.value = DialogKind.Scan },
                                    onOpenProtect = { dialog.value = DialogKind.ProtectDetail },
                                    onOpenPerm = { dialog.value = DialogKind.PermLevels },
                                    onControlConfirm = { cmd -> dialog.value = DialogKind.ControlConfirmNamed(cmd) },
                                    onForceCharge = { dialog.value = DialogKind.ControlConfirmNamed("强制开启充电") },
                                )
                                Route.Config -> ConfigHomeScreen(
                                    bottomPadding = navBarPad,
                                    onOpenGroup = { i -> push(Route.ParamGroup(i)) },
                                    onOpenTools = { push(Route.ControlTools) },
                                    onOpenPerm = { dialog.value = DialogKind.PermLevels },
                                )
                                Route.Settings -> SettingsHomeScreen(
                                    bottomPadding = navBarPad,
                                    onOpen = { r2 -> push(r2) },
                                    onOpenScan = { dialog.value = DialogKind.Scan },
                                    darkOverride = darkOverride,
                                    onDarkOverrideChange = setDarkOverride,
                                )
                                is Route.ParamGroup -> ParamGroupScreen(
                                    groupIndex = r.index,
                                    onBack = popBack,
                                    onEdit = { item -> dialog.value = DialogKind.ParamEdit(item) },
                                    onOpenPerm = { dialog.value = DialogKind.PermLevels },
                                    onSave = { scope.launch { io.github.lswlc33.maibms.data.Bms.repository.saveAllParams() } },
                                )
                                Route.ControlTools -> ControlToolsScreen(
                                    onBack = popBack,
                                    onCommand = { cmd -> dialog.value = DialogKind.ControlConfirmNamed(cmd) },
                                    onOpenPerm = { dialog.value = DialogKind.PermLevels },
                                )
                                Route.Password -> PasswordScreen(onBack = popBack)
                                Route.Developer -> DeveloperScreen(onBack = popBack)
                            }
                        }
                    }
                    // 底栏在三个主标签显示；压栈时随动画收起，返回时升起
                    AnimatedVisibility(
                        visible = isTabRoot,
                        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(180)),
                        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(140)),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        BottomNav(
                            current = currentTab.value,
                            onSelect = { currentTab.value = it; backStack.clear() },
                        )
                    }
                }
            }

            dialog.value?.let { d ->
                when (d) {
                    DialogKind.Scan -> ScanDialog(
                        onDismiss = {
                            io.github.lswlc33.maibms.data.Bms.repository.stopScan()
                            dialog.value = null
                        },
                        onConnect = { addr ->
                            io.github.lswlc33.maibms.data.Bms.repository.stopScan()
                            dialog.value = null
                            scope.launch { io.github.lswlc33.maibms.data.Bms.repository.connectTo(addr) }
                        },
                    )
                    DialogKind.ProtectDetail -> ProtectDetailDialog(onDismiss = { dialog.value = null })
                    is DialogKind.ControlConfirmNamed -> ControlConfirmDialog(
                        d.command,
                        onDismiss = { dialog.value = null },
                        onConfirm = { name ->
                            cmdFor(name)?.let { cmd ->
                                scope.launch {
                                    val code = io.github.lswlc33.maibms.data.Bms.repository.control(cmd)
                                    MockBms.lastControlResult.value = cmd to code
                                }
                            }
                        })
                    is DialogKind.ParamEdit -> ParamEditDialog(
                        d.item,
                        onDismiss = { dialog.value = null },
                        onWrite = { raw ->
                            scope.launch {
                                val addr = d.item.addr.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                                MockBms.lastWriteResult.value = io.github.lswlc33.maibms.data.Bms.repository.writeParam(addr, raw)
                            }
                        })
                    DialogKind.PermLevels -> PermLevelsDialog(onDismiss = { dialog.value = null })
                }
            }
        }
    }
}

private fun androidx.compose.runtime.snapshots.SnapshotStateList<Route>.pop() {
    if (isNotEmpty()) removeAt(lastIndex)
}

/** 底栏占位高度：悬浮条 56dp + 底部外边距 10dp + 系统导航条 + 与内容之间 8dp 气口 */
val BottomNavHeight: Dp
    @Composable get() = 74.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * 悬浮大圆角底栏：左右留 16dp 边、底部让开系统导航条，白卡浮在页面上（带阴影）。
 * 视觉沿用定稿的 M3 语言：选中项是药丸指示器（缩到 44x22）。
 */
@Composable
fun BottomNav(current: Route, onSelect: (Route) -> Unit, modifier: Modifier = Modifier) {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 底栏要和卡片分得开：深色下用比卡面亮一档的灰，浅色下靠加强投影，否则白条压白卡会糊在一起
    val barColor = if (isDarkScheme()) MaterialTheme.colorScheme.surfaceContainerHighest
                   else MaterialTheme.colorScheme.surface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = inset + 10.dp)
            .shadow(16.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(barColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple(Route.Dashboard, "麻衣 BMS", BmsIcons.Gauge),
            Triple(Route.Config, "配置", BmsIcons.Tune),
            Triple(Route.Settings, "设置", BmsIcons.Gear),
        ).forEach { (r, label, icon) ->
            val selected = current::class == r::class
            val tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(r) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.width(44.dp).height(22.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, contentDescription = label, modifier = Modifier.size(17.dp), tint = tint) }
                Text(
                    label, fontSize = 10.sp, color = tint,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/* ---------- 通用屏骨架：大标题 + 返回 + 滚动 ---------- */

@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    bottomPadding: Dp = 20.dp,
    /** 卡片之间的间距；条目少的稀疏页面可以调大 */
    contentSpacing: Dp = 7.dp,
    /** 顶栏右侧插槽（权限等级标识等） */
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部只留状态栏安全区 + 4dp 呼吸位：固定 28dp 在无状态栏的窗口里纯属浪费
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            if (onBack != null) {
                Text("‹", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onBack() }.padding(end = 10.dp))
            }
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        if (subtitle != null) {
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = 2.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing)
        ) { content() }
    }
}

/* ---------- 危险确认弹窗（S04） ---------- */

@Composable
fun ControlConfirmDialog(command: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit = {}) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text(command + "？", fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "将向设备发送对应控制命令（0x51）。执行期间自动暂停实时轮询，完成后恢复。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
            )
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)
            ) {
                Text(
                    "7E A1 51 ·· ·· ·· ·· AA 55",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { onConfirm(command); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (command.contains("恢复") || command.contains("关闭")) BmsColors.BadRed else MaterialTheme.colorScheme.primary
                    )
                ) { Text("执行") }
            }
        }
    }
}

/* ---------- 权限换级弹窗（卡1 徽章入口） ---------- */

@Composable
fun PermLevelsDialog(onDismiss: () -> Unit) {
    val passwords by MockBms.passwords.collectAsState()
    val plain by MockBms.plainPasswords.collectAsState()
    val status by MockBms.status.collectAsState()
    val linkState = io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val canAuth = linkState.value == io.github.lswlc33.maibms.transport.LinkState.Connected
    var editingLevel by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text("切换权限等级", fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text("当前 ${status.permissionLevel} 级 · 设备 ${MockBms.deviceLabel}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Text(
                if (canAuth) "点已记住的等级直接校验；点未设置的等级可输入密码"
                else "未连接保护板 · 校验需要先连上设备",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            passwords.forEach { pw ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable {
                            val saved = plain[pw.level]
                            if (!status.hasData && linkState.value != io.github.lswlc33.maibms.transport.LinkState.Connected) {
                                // 未连接时校验必然失败，点了只会静默无反应
                                editingLevel = null
                                return@clickable
                            }
                            if (saved != null) {
                                scope.launch {
                                    val lvl = io.github.lswlc33.maibms.data.Bms.repository.auth(pw.level, saved)
                                    if (lvl > 0) onDismiss()
                                }
                            } else { editingLevel = pw.level; input = "" }
                        }
                        .padding(vertical = 9.dp)
                ) {
                    Text(if (pw.level == 9) "9 级厂家" else "${pw.level} 级",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(plain[pw.level]?.let { "已记住" } ?: "未设置", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    RadioDot(selected = pw.isCurrent)
                }
                if (editingLevel == pw.level) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            label = { Text("输入 ${pw.level} 级密码") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            val lv = pw.level
                            MockBms.plainPasswords.value = plain + (lv to input)
                            MockBms.passwords.value = MockBms.passwords.value.map {
                                if (it.level == lv) it.copy(masked = "••••••••") else it
                            }
                            scope.launch {
                                if (io.github.lswlc33.maibms.data.Bms.repository.auth(lv, input) > 0) onDismiss()
                            }
                        }) { Text("校验") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("关闭", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

