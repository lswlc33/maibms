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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
    data object Snapshots : Route("snapshots")       // S16 快照管理与预览
    data object Devices : Route("devices")           // S17 历史设备（档案/密码/自动重连目标）
    data object Channels : Route("channels")         // 通信通道（默认 / 备用 A / 备用 B，docs/02 §2.2）
}

/** 弹窗种类 */
sealed class DialogKind {
    data object Scan : DialogKind()          // S01
    data object ProtectDetail : DialogKind() // S03
    /** 控制命令确认：直接带命令号，显示名从 ControlCmd.name 取（不再按中文名反查命令号） */
    data class ControlConfirm(val cmd: Int) : DialogKind()
    /**
     * S07 参数编辑；readOnly=true 时降级为预览。
     * 只读的两种来源：运行权限不足（note 说明需要几级），或参数本身是设备状态量。
     */
    data class ParamEdit(
        val item: io.github.lswlc33.maibms.data.ParamItem,
        val readOnly: Boolean = false,
        val readOnlyNote: String? = null,
    ) : DialogKind()
    data object PermLevels : DialogKind()          // 权限换级
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
    // 兜底启动数据管线：Android 那边已经在 MaibmsApp.onCreate 里启动过了（与界面首帧并行，
    // 不再是"等组合完才开始连"）；这里保证桌面端与离屏截图工具也能挂上收集器。
    // start() 幂等：重复调用直接返回，不会双挂收集器
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
            // 运行权限与链路：参数编辑的只读判定、控制命令拦截都用这两个（判据集中在 Perm）
            val bmsStatus by MockBms.status.collectAsState()
            val bmsLink by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
            val connected = bmsLink == io.github.lswlc33.maibms.transport.LinkState.Connected
            val canWrite = io.github.lswlc33.maibms.protocol.Perm.canWrite(bmsStatus.permissionLevel) && connected
            // 电量计（中继器）模式：隐藏"配置"标签（参数/控制/升级对电量计无意义），深链也重定向到仪表盘
            val family by io.github.lswlc33.maibms.data.Bms.repository.currentFamily.collectAsState()
            val meterMode = family.isMeter
            LaunchedEffect(meterMode) {
                if (meterMode) {
                    if (currentTab.value == Route.Config) currentTab.value = Route.Dashboard
                    backStack.removeAll { it is Route.ParamGroup || it == Route.ControlTools }
                }
            }
            // 底栏在三个主标签（含仪表盘变体状态）显示
            val isTabRoot = backStack.isEmpty() && route.isTabRoot()

            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
            // 横屏表盘（宽屏 Dashboard）不显示底栏，表盘自己的底条取而代之
            val onCluster = maxWidth >= 600.dp && route == Route.Dashboard
            // 有底栏时，滚动内容末尾要留出底栏 + 系统导航栏的高度，否则最后一行被压在底栏下
            val navBarPad = if (isTabRoot && !onCluster) BottomNavHeight else 20.dp
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
                                    canWrite = canWrite,
                                    permissionLevel = bmsStatus.permissionLevel,
                                    onControlConfirm = { cmd -> dialog.value = DialogKind.ControlConfirm(cmd) },
                                    onForceCharge = { dialog.value = DialogKind.ControlConfirm(io.github.lswlc33.maibms.protocol.ControlCmd.FORCE_CHARGE) },
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
                                    onPreviewUi = { currentTab.value = Route.Dashboard; backStack.clear() },
                                )
                                is Route.ParamGroup -> ParamGroupScreen(
                                    groupIndex = r.index,
                                    onBack = popBack,
                                    // 只读判定：权限不够 → 预览并说明需要几级；参数本身是状态量 → 预览并说明怎么改
                                    onEdit = { item ->
                                        val def = io.github.lswlc33.maibms.protocol.ParamTable
                                            .byAddr(item.addr.removePrefix("0x").toIntOrNull(16) ?: 0)
                                        dialog.value = when {
                                            !canWrite -> DialogKind.ParamEdit(
                                                item, readOnly = true,
                                                readOnlyNote = if (!connected)
                                                    "未连接保护板：连接后才能写入；运行权限需 ${io.github.lswlc33.maibms.protocol.Perm.WRITE_MIN_LEVEL} 级及以上"
                                                else "当前运行权限 ${bmsStatus.permissionLevel} 级只读：写入需 ${io.github.lswlc33.maibms.protocol.Perm.WRITE_MIN_LEVEL} 级及以上，点「去校验」升权",
                                            )
                                            def?.readOnly == true -> DialogKind.ParamEdit(
                                                item, readOnly = true,
                                                readOnlyNote = def.note ?: "该参数由设备自行维护，不可直接写入",
                                            )
                                            else -> DialogKind.ParamEdit(item)
                                        }
                                    },
                                    onOpenPerm = { dialog.value = DialogKind.PermLevels },
                                    onSave = { scope.launch { io.github.lswlc33.maibms.data.Bms.repository.saveAllParams() } },
                                )
                                Route.ControlTools -> ControlToolsScreen(
                                    onBack = popBack,
                                    onCommand = { cmd -> dialog.value = DialogKind.ControlConfirm(cmd) },
                                    onOpenPerm = { dialog.value = DialogKind.PermLevels },
                                )
                                Route.Password -> PasswordScreen(onBack = popBack)
                                Route.Developer -> DeveloperScreen(onBack = popBack)
                                Route.Devices -> DeviceScreen(onBack = popBack)
                                Route.Channels -> ChannelScreen(onBack = popBack)
                                Route.Snapshots -> SnapshotScreen(
                                    onBack = popBack,
                                    onPreviewed = { currentTab.value = Route.Dashboard; backStack.clear() },
                                )
                            }
                        }
                    }
                    // 底栏在三个主标签显示；横屏表盘态随之隐藏；压栈时随动画收起，返回时升起
                    AnimatedVisibility(
                        visible = isTabRoot && !onCluster,
                        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(180)),
                        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(140)),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        BottomNav(
                            current = currentTab.value,
                            onSelect = { currentTab.value = it; backStack.clear() },
                            showConfig = !meterMode,
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
                    is DialogKind.ControlConfirm -> ControlConfirmDialog(
                        d.cmd,
                        onDismiss = { dialog.value = null },
                        onConfirm = { cmd ->
                            io.github.lswlc33.maibms.data.BmsLog.i("UI", "用户确认执行：${io.github.lswlc33.maibms.protocol.ControlCmd.name(cmd)}")
                            scope.launch {
                                val code = io.github.lswlc33.maibms.data.Bms.repository.control(cmd)
                                MockBms.lastControlResult.value = cmd to code
                            }
                        })
                    is DialogKind.ParamEdit -> ParamEditDialog(
                        d.item,
                        readOnly = d.readOnly,
                        readOnlyNote = d.readOnlyNote,
                        onOpenPerm = if (d.readOnly && canWrite.not() && connected)
                            ({ dialog.value = DialogKind.PermLevels }) else null,
                        onDismiss = { dialog.value = null },
                        onWrite = { raw ->
                            io.github.lswlc33.maibms.data.BmsLog.i("UI", "用户提交参数写入：${d.item.name} = $raw ${d.item.unit}")
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

/** 底栏占位高度：悬浮条 64dp + 底部外边距 10dp + 系统导航条 + 与内容之间 8dp 气口 */
val BottomNavHeight: Dp
    @Composable get() = 82.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * 悬浮大圆角底栏：左右留 16dp 边、底部让开系统导航条，白卡浮在页面上（带阴影）。
 * 视觉沿用定稿的 M3 语言：选中项是药丸指示器（缩到 44x22）。
 */
@Composable
fun BottomNav(
    current: Route,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
    /** 电量计模式隐藏"配置"标签 */
    showConfig: Boolean = true,
) {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 底栏要和卡片分得开：深色下用比卡面亮一档的灰，浅色下靠加强投影，否则白条压白卡会糊在一起
    val barColor = if (isDarkScheme()) MaterialTheme.colorScheme.surfaceContainerHighest
                   else MaterialTheme.colorScheme.surface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, bottom = inset + 10.dp)
            .shadow(16.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(barColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOfNotNull(
            Triple(Route.Dashboard, "麻衣 BMS", BmsIcons.Gauge),
            Triple(Route.Config, "配置", BmsIcons.Tune).takeIf { showConfig },
            Triple(Route.Settings, "设置", BmsIcons.Gear),
        ).forEach { (r, label, icon) ->
            val selected = current::class == r::class
            val tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            // 整块底栏不响应点击高亮：只有图标药丸自己随选中态变色，点按不再泛起水波纹
            val noRipple = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = noRipple, indication = null) { onSelect(r) },
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
fun ControlConfirmDialog(cmd: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit = {}) {
    val name = io.github.lswlc33.maibms.protocol.ControlCmd.name(cmd)
    val dangerous = io.github.lswlc33.maibms.protocol.ControlCmd.isDangerous(cmd)
    val warning = io.github.lswlc33.maibms.protocol.ControlCmd.warning(cmd)
    // 最高危的四个要手输「确认」：误触一次就会关机或抹掉配置，普通点一下不够
    val typed = io.github.lswlc33.maibms.protocol.ControlCmd.requiresTypedConfirm(cmd)
    var input by remember(cmd) { mutableStateOf("") }
    val confirmed = !typed || input.trim().let { it == "确认" || it.equals("ok", ignoreCase = true) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text(name + "？", fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold,
                color = if (dangerous) BmsColors.BadRed else MaterialTheme.colorScheme.onSurface)
            Text(
                "将向设备发送对应控制命令（0x51）。执行期间自动暂停实时轮询，完成后恢复。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
            )
            warning?.let {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f)).padding(10.dp)
                ) {
                    Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(6.dp))
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)
            ) {
                // 直接显示这条命令的真实报文，排查时对得上日志
                Text(
                    io.github.lswlc33.maibms.data.BmsLog.hex(
                        io.github.lswlc33.maibms.protocol.Frame.control(cmd)
                    ),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (typed) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("输入「确认」以执行") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(4.dp))
                Button(
                    enabled = confirmed,
                    onClick = { onConfirm(cmd); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dangerous) BmsColors.BadRed else MaterialTheme.colorScheme.primary
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
    var pwError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text("切换权限等级", fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text("当前 ${status.permissionLevel} 级 · 设备 ${MockBms.deviceLabel}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            // 374 地址冲突待实测：新参数表把 374 定义为「系统基准电压偏移」，旧版按管理员密码槽写 12 字节
            // （docs/附录A、docs/09 都标注了这处冲突）。校验失败会自动删除已存的 9 级密码，为防误删先警示
            if (editingLevel == 9) {
                Text(
                    "注意：9 级（管理员）槽地址 374 与新版参数表「系统基准电压偏移」冲突（固件代际有关），校验不通过会移除已记住的密码",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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
                                io.github.lswlc33.maibms.data.BmsLog.i("UI", "用户点已记住的 ${pw.level} 级直接校验")
                                scope.launch {
                                    val lvl = io.github.lswlc33.maibms.data.Bms.repository.auth(pw.level, saved)
                                    if (lvl > 0) onDismiss()
                                }
                            } else {
                                io.github.lswlc33.maibms.data.BmsLog.i("UI", "用户输入 ${pw.level} 级密码")
                                editingLevel = pw.level; input = ""; pwError = null
                            }
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
                            value = input, onValueChange = { input = it; pwError = null },
                            label = { Text(if (pw.level == 9) "点分十进制（12 段）" else "输入 ${pw.level} 级密码（槽 ${io.github.lswlc33.maibms.protocol.ParamTable.slotLen(pw.level)} 字节）") },
                            singleLine = true, isError = pwError != null,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            val lv = pw.level
                            val bad = io.github.lswlc33.maibms.protocol.PasswordCodec.validate(lv, input.trim())
                            if (bad != null) { pwError = bad; return@Button }
                            io.github.lswlc33.maibms.data.BmsLog.i("UI", "用户提交 $lv 级新密码，保存并校验")
                            MockBms.plainPasswords.value = plain + (lv to input.trim())
                            MockBms.passwords.value = MockBms.passwords.value.map {
                                if (it.level == lv) it.copy(masked = "••••••••") else it
                            }
                            scope.launch {
                                if (io.github.lswlc33.maibms.data.Bms.repository.auth(lv, input.trim()) > 0) onDismiss()
                            }
                        }) { Text("校验") }
                    }
                    pwError?.let {
                        Text(it, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp))
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

