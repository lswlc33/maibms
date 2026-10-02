package io.github.lswlc33.maibms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.MockBms
import kotlinx.coroutines.launch

/* ---------- S10 设置主页（外观/连接/关于 的设置项平铺在本页，不再进二级页） ---------- */

/** 历史设备入口的摘要：台数 + 生效中的重连目标名 */
private fun DeviceEntrySummary(): String {
    val profiles = io.github.lswlc33.maibms.data.DeviceProfiles.all()
    if (profiles.isEmpty()) return "无"
    val auto = AppStore.autoConnectAddress
    val target = auto?.takeIf { addr -> profiles.any { it.address == addr } }
        ?: AppStore.savedAddress
    val targetName = profiles.firstOrNull { it.address == target }?.displayName
    return "${profiles.size} 台 · 重连 " + (targetName ?: "上次连接")
}

@Composable
fun SettingsHomeScreen(
    onOpen: (Route) -> Unit,
    onOpenScan: () -> Unit = {},
    darkOverride: Boolean?,
    onDarkOverrideChange: (Boolean?) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    /** 外观板块「UI 预览」载入内置快照后回调（App 跳到仪表盘） */
    onPreviewUi: () -> Unit = {},
) {
    // 外观
    val realBle by MockBms.usingRealBle.collectAsState()
    val scope = rememberCoroutineScope()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val hint by io.github.lswlc33.maibms.data.Bms.repository.connectHint.collectAsState()
    val manual by io.github.lswlc33.maibms.data.Bms.repository.manualDisconnect.collectAsState()
    val saved by MockBms.plainPasswords.collectAsState()
    // 关于（身份区）
    val status by MockBms.status.collectAsState()
    val id by MockBms.identity.collectAsState()
    val reading by MockBms.paramsReading.collectAsState()
    val bleName by MockBms.connectedDeviceName.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    /** 身份区/状态里的值，空则显示 --（原设备信息页的逻辑） */
    fun v(real: String?, fallback: String) = real?.ifBlank { null } ?: fallback.ifBlank { "--" }

    ScreenScaffold(title = "设置", bottomPadding = bottomPadding) {
        // 边距与配置页一致：卡片内距 CardPadding、行距默认、卡片间隔 7dp
        SectionCard {
            SectionHeader("外观")
            listOf<Pair<String, Boolean?>>("跟随系统" to null, "浅色" to false, "深色" to true).forEach { (label, themeValue) ->
                SettingRow(
                    title = label,
                    trailing = { RadioDot(selected = darkOverride == themeValue) },
                    onClick = { onDarkOverrideChange(themeValue) },
                )
            }
            // 深色恒为纯黑（原来的 AMOLED 开关已去掉，默认即纯黑）
            // UI 预览：内置一张 72114 锂电池的静态快照（特殊快照，不入库），没接板子也能看真实排版
            val previewing by io.github.lswlc33.maibms.data.Bms.repository.previewActive.collectAsState()
            SettingRow(
                title = "UI 预览",
                inlineValue = when {
                    previewing -> "预览中"
                    connected -> "已连接 · 先断开"
                    else -> "72V·114Ah 预设快照"
                },
                trailing = { Chevron() },
                onClick = if (connected) null else {
                    {
                        scope.launch {
                            io.github.lswlc33.maibms.data.Bms.repository.enterPreview(
                                io.github.lswlc33.maibms.data.UiPreview.snapshot()
                            )
                            onPreviewUi()
                        }
                    }
                },
            )
        }
        SectionCard {
            SectionHeader("连接", tail = "BLE")
            SettingRow(title = "数据源", inlineValue = if (realBle) "真机 BLE" else "未就绪")
            SettingRow(
                title = "链路状态",
                inlineValue = when {
                    manual -> "已手动断开"
                    connected -> "已连接"
                    link == io.github.lswlc33.maibms.transport.LinkState.Connecting -> "连接中"
                    link == io.github.lswlc33.maibms.transport.LinkState.Disconnected -> "已断开"
                    else -> "未连接"
                },
            )
            // 历史设备入口：列表/备注/密码/删除/自动重连目标都在二级页管理
            SettingRow(
                title = "历史设备",
                inlineValue = DeviceEntrySummary(),
                trailing = { Chevron() },
                onClick = { onOpen(Route.Devices) },
            )
            SettingRow(title = "重新扫描连接", trailing = { Chevron() }, onClick = onOpenScan)
            if (connected) {
                SettingRow(title = "断开连接", danger = true, trailing = { Chevron() }, onClick = {
                    scope.launch { io.github.lswlc33.maibms.data.Bms.repository.disconnect() }
                })
            }
            hint?.let { InfoBanner(it, kind = "warn") }
        }
        SectionCard {
            SettingRow(
                title = "快照",
                inlineValue = if (io.github.lswlc33.maibms.data.Bms.repository.previewActive.value) "预览中" else {
                    val n = io.github.lswlc33.maibms.data.AppStore.snapshotIds().size
                    if (n > 0) "$n 张" else null
                },
                trailing = { Chevron() },
                onClick = { onOpen(Route.Snapshots) },
            )
            SettingRow(
                title = "权限与密码",
                inlineValue = if (saved.isEmpty()) "未设置" else "已记住 " + saved.keys.sorted().joinToString("/") + " 级",
                trailing = { Chevron() },
                onClick = { onOpen(Route.Password) },
            )
            SettingRow(title = "开发者", trailing = { Chevron() }, onClick = { onOpen(Route.Developer) })
        }
        SectionCard {
            SectionHeader("关于", tail = "身份区")
            if (reading) InfoBanner("身份区读取中…", kind = "info")
            else if (!connected && id.isEmpty()) InfoBanner("未连接保护板 · 连接后自动读取身份区", kind = "warn")
            else if (!connected) InfoBanner("未连接 · 以下为「${MockBms.deviceLabel}」上次成功连接的缓存", kind = "info")
            else if (id.isEmpty()) InfoBanner("身份区暂时读不到（权限不足或设备未就绪）", kind = "warn")
            SettingRow(title = "软件版本", inlineValue = v(id["swVersion"], status.swVersion))
            SettingRow(title = "硬件版本", inlineValue = v(id["hwVersion"], status.hwVersion))
            SettingRow(title = "Boot 版本", inlineValue = v(id["boot"], "--"))
            SettingRow(title = "设备地址", inlineValue = "A1")
            SettingRow(title = "电池组 ID", inlineValue = v(id["packId"], "--"))
            SettingRow(title = "Code Key", inlineValue = v(id["codeKey"], "--"))
            SettingRow(title = "蓝牙名称", inlineValue = (bleName ?: status.deviceName).trim())
            SettingRow(title = "蓝牙地址", inlineValue = MockBms.savedAddress ?: "--")
        }
        AboutAppCard()
    }
}

/** 设置页结尾的「关于本软件」卡：仓库地址 / 应用版本 / 更新渠道 / 检查更新（GitHub → 国内镜像回退） */
@Composable
private fun AboutAppCard() {
    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<io.github.lswlc33.maibms.data.UpdateChecker.Result?>(null) }
    var channelMenu by remember { mutableStateOf(false) }
    var channel by remember {
        mutableStateOf(io.github.lswlc33.maibms.data.UpdateChecker.UpdateChannel.fromKey(AppStore.updateChannel))
    }

    fun runCheck(ch: io.github.lswlc33.maibms.data.UpdateChecker.UpdateChannel) {
        if (checking) return
        checking = true; result = null
        scope.launch {
            result = io.github.lswlc33.maibms.data.UpdateChecker.check(ch)
            checking = false
        }
    }

    SectionCard {
        SectionHeader("关于本软件", tail = "MIT · 开源")
        SettingRow(
            title = "项目仓库",
            inlineValue = "GitHub · lswlc33/maibms",
            trailing = { Chevron() },
            onClick = { runCatching { uriHandler.openUri(io.github.lswlc33.maibms.data.UpdateChecker.REPO_URL) } },
        )
        SettingRow(title = "当前版本", inlineValue = io.github.lswlc33.maibms.data.AppVersion.name)
        // 更新渠道：稳定版=正式 Release；预览版=含 Prerelease 的最近一次发布
        Box {
            SettingRow(
                title = "更新渠道",
                inlineValue = channel.label,
                trailing = { Text("⌄", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { channelMenu = true },
            )
            DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                io.github.lswlc33.maibms.data.UpdateChecker.UpdateChannel.entries.forEach { ch ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(ch.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    when (ch) {
                                        io.github.lswlc33.maibms.data.UpdateChecker.UpdateChannel.STABLE -> "正式 Release · 适合日常使用"
                                        io.github.lswlc33.maibms.data.UpdateChecker.UpdateChannel.PREVIEW -> "含 Prerelease · 抢先体验新功能"
                                    },
                                    fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = {
                            if (ch == channel) Text("✓", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        },
                        onClick = {
                            channelMenu = false
                            if (ch != channel) {
                                channel = ch
                                AppStore.updateChannel = ch.key
                                result = null
                                runCheck(ch)   // 切渠道自动重查一次，省得用户再点
                            }
                        },
                    )
                }
            }
        }
        SettingRow(
            title = "检查更新",
            inlineValue = when (val r = result) {
                null -> null
                is io.github.lswlc33.maibms.data.UpdateChecker.Result.UpToDate -> "已是最新"
                is io.github.lswlc33.maibms.data.UpdateChecker.Result.Update ->
                    if (r.info.prerelease) "发现预览版 v${r.latest}" else "发现新版 v${r.latest}"
                is io.github.lswlc33.maibms.data.UpdateChecker.Result.Ahead -> "预览版领先正式版"
                is io.github.lswlc33.maibms.data.UpdateChecker.Result.Failed -> "检查失败"
            },
            trailing = {
                if (checking) Text("检查中…", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Chevron()
            },
            onClick = { runCheck(channel) },
        )
        when (val r = result) {
            is io.github.lswlc33.maibms.data.UpdateChecker.Result.Update -> InfoBanner(
                if (r.info.prerelease)
                    "发现预览版 v${r.latest}（当前 v${io.github.lswlc33.maibms.data.AppVersion.name}）· 预览版可能不稳定"
                else
                    "发现新版本 v${r.latest}（当前 v${io.github.lswlc33.maibms.data.AppVersion.name}）· 点这里去下载",
                kind = "info",
                action = "去下载",
                onAction = { runCatching { uriHandler.openUri(r.info.htmlUrl.ifBlank { io.github.lswlc33.maibms.data.UpdateChecker.REPO_URL + "/releases" }) } },
            )
            is io.github.lswlc33.maibms.data.UpdateChecker.Result.Ahead -> InfoBanner(
                "当前预览版 v${r.local} 比最新正式版 v${r.remote} 还新 · 预览版渠道才有更新的构建",
                kind = "info", action = "去 Releases",
                onAction = { runCatching { uriHandler.openUri(io.github.lswlc33.maibms.data.UpdateChecker.REPO_URL + "/releases") } },
            )
            is io.github.lswlc33.maibms.data.UpdateChecker.Result.Failed -> InfoBanner(
                "更新检查失败（网络不可达或被拦截）· 可直接到仓库 Releases 页查看",
                kind = "warn",
                action = "去 Releases",
                onAction = { runCatching { uriHandler.openUri(io.github.lswlc33.maibms.data.UpdateChecker.REPO_URL + "/releases") } },
            )
            else -> {}
        }
        InfoBanner("应用只与保护板通信；检查更新时仅访问 GitHub/镜像的公开接口", kind = "info", action = "了解")
    }
}

/* ---------- S13 权限与密码（设备密码库） ---------- */

@Composable
fun PasswordScreen(onBack: () -> Unit) {
    val passwords by MockBms.passwords.collectAsState()
    val autoLevel by MockBms.autoUpgradeLevel.collectAsState()
    val status by MockBms.status.collectAsState()
    val saved by MockBms.plainPasswords.collectAsState()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    var editing by remember { mutableStateOf<Int?>(null) }
    var levelMenu by remember { mutableStateOf(false) }
    // 未连接时实时数据里没有设备名，用记住的那台；否则会显示成「设备：--」
    val label = MockBms.deviceLabel
    val online = status.hasData
    ScreenScaffold(
        title = "权限与密码",
        subtitle = if (online) "设备：$label · 当前权限 ${status.permissionLevel} 级"
                   else "设备：$label · 未连接（权限 ${status.permissionLevel} 级）",
        onBack = onBack,
    ) {
        SectionCard {
            SectionHeader("该设备的密码库", tail = "点行设置/修改")
            passwords.forEach { pw ->
                SettingRow(
                    title = if (pw.level == 9) "9 级厂家" else "${pw.level} 级",
                    inlineValue = pw.masked ?: "未设置",
                    trailing = {
                        if (pw.isCurrent) Text("当前", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        else Chevron()
                    },
                    onClick = { editing = pw.level },
                )
            }
        }
        SectionCard {
            SectionHeader("连接行为", tail = "0x23")
            Box {
                SettingRow(
                    title = "连接后自动升级到",
                    inlineValue = if (autoLevel > 0) "$autoLevel 级" else "自动（最高可用）",
                    trailing = { Text("⌄", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { levelMenu = true },
                )
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    listOf(0, 1, 2, 3, 4, 5, 9).forEach { lv ->
                        val has = saved.containsKey(lv)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (lv == 0) "自动（最高可用）" else if (lv == 9) "9 级厂家" else "$lv 级",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingIcon = {
                                Text(
                                    when {
                                        lv == 0 -> ""
                                        has -> "已记住"
                                        else -> "未设置"
                                    },
                                    fontSize = 10.sp,
                                    color = if (lv != 0 && has) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                io.github.lswlc33.maibms.data.Bms.repository.setAutoUpgradeTarget(lv)
                                levelMenu = false
                            },
                        )
                    }
                }
            }
        }
        if (!connected) {
            InfoBanner("未连接保护板 · 密码可先存本地，连上后自动校验并升权", kind = "warn")
        } else if (saved.isEmpty()) {
            InfoBanner("尚未记住任何密码 · 点上方等级设置，保存时直接向设备校验", kind = "warn")
        } else {
            val savedLabel = saved.keys.sorted().joinToString("/") + " 级"
            InfoBanner("已记住 $savedLabel 密码 · 连接时自动升级，权限回落会自动重升", kind = "info")
        }
        InfoBanner("密码为明文存储，勿共用设备", kind = "err", action = "了解")
    }
    editing?.let { lv -> MockBms.savedAddress?.let { addr ->
        PasswordEditDialog(addr, lv, onDismiss = { editing = null })
    } }
}

/**
 * 某一级的密码设置弹窗：保存（连上时顺带校验，成功即升权）/ 清除。
 * @param address 密码归属的历史设备地址；当前连接/记忆设备走会话校验路径，其余设备离线写入档案
 */
@Composable
internal fun PasswordEditDialog(address: String, level: Int, onDismiss: () -> Unit) {
    val saved by MockBms.plainPasswords.collectAsState()
    val link by io.github.lswlc33.maibms.data.Bms.repository.linkState.collectAsState()
    val connected = link == io.github.lswlc33.maibms.transport.LinkState.Connected
    // 当前会话设备：可向设备校验，明文从会话流取；其他设备：离线写入档案
    val sessionDevice = address == MockBms.savedAddress
    val existing = if (sessionDevice) saved[level] else AppStore.loadPassword(address, level)
    // 已记住的直接带出来，省得重打一遍（本机就是明文存的，页脚也声明了）
    var input by remember { mutableStateOf(existing ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val slot = io.github.lswlc33.maibms.protocol.ParamTable.slotAddr(level)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp)
        ) {
            Text(
                if (level == 9) "9 级厂家密码（12 段点分十进制，如 0.0.0.…）"
                else "$level 级密码（槽 ${io.github.lswlc33.maibms.protocol.ParamTable.slotLen(level)} 字节）",
                fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "该设备的密码库 · 寄存器 $slot" +
                    if (connected && sessionDevice) " · 保存时向设备校验" else " · 离线写入设备档案",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = input, onValueChange = { input = it; error = null },
                label = { Text(if (level == 9) "点分十进制密码" else "密码") }, singleLine = true, enabled = !busy,
                isError = error != null,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            error?.let {
                Text(it, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 5.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    TextButton(enabled = !busy, onClick = {
                        if (sessionDevice) io.github.lswlc33.maibms.data.Bms.repository.forgetPassword(level)
                        else AppStore.removePassword(address, level)
                        onDismiss()
                    }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(4.dp))
                Button(
                    enabled = input.isNotBlank() && !busy,
                    onClick = {
                        val pw = input.trim()
                        // 槽长/格式先本地校验：12 字节槽发 8 字节帧、每段超 255 这类问题，
                        // 一旦发出去设备只会静默不认，界面还看不出为什么
                        val bad = io.github.lswlc33.maibms.protocol.PasswordCodec.validate(level, pw)
                        if (bad != null) { error = bad; return@Button }
                        scope.launch {
                            busy = true
                            val repo = io.github.lswlc33.maibms.data.Bms.repository
                            if (connected && sessionDevice) {
                                // 校验失败不存：错密码留在库里只会让每次连接都白试
                                val lv = repo.auth(level, pw)
                                busy = false
                                if (lv > 0) onDismiss() else error = "密码不正确 · 未保存"
                            } else {
                                if (sessionDevice) repo.savePassword(level, pw)
                                else AppStore.savePassword(address, level, pw)
                                busy = false
                                onDismiss()
                            }
                        }
                    },
                ) { Text(if (busy) "校验中…" else if (connected && sessionDevice) "保存并校验" else "保存") }
            }
        }
    }
}

/* ---------- S15 开发者 ---------- */

/** 级别在日志行里的着色（深底终端风） */
private fun levelColor(level: io.github.lswlc33.maibms.data.BmsLog.Level): androidx.compose.ui.graphics.Color = when (level) {
    io.github.lswlc33.maibms.data.BmsLog.Level.ERROR -> androidx.compose.ui.graphics.Color(0xFFFF7B72)
    io.github.lswlc33.maibms.data.BmsLog.Level.WARN -> androidx.compose.ui.graphics.Color(0xFFE3B341)
    io.github.lswlc33.maibms.data.BmsLog.Level.INFO -> androidx.compose.ui.graphics.Color(0xFF9BE8C4)
    io.github.lswlc33.maibms.data.BmsLog.Level.DEBUG -> androidx.compose.ui.graphics.Color(0xFF6E8A7C)
}

@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    val logOn by io.github.lswlc33.maibms.data.BmsLog.frameLogOn.collectAsState()
    val entries by io.github.lswlc33.maibms.data.BmsLog.entries.collectAsState()
    val minLevel by io.github.lswlc33.maibms.data.BmsLog.minLevel.collectAsState()
    var levelMenu by remember { mutableStateOf(false) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(actionNote) {
        if (actionNote != null) { kotlinx.coroutines.delay(2000); actionNote = null }
    }
    ScreenScaffold(title = "开发者", onBack = onBack) {
        SectionCard {
            SettingRow(
                title = "帧级日志（TX/RX 报文）",
                inlineValue = if (logOn) "开启" else "关闭",
                trailing = { AppSwitch(logOn) { io.github.lswlc33.maibms.data.BmsLog.frameLogOn.value = it; AppStore.logFrameOn = it } },
                onClick = { val v = !logOn; io.github.lswlc33.maibms.data.BmsLog.frameLogOn.value = v; AppStore.logFrameOn = v },
            )
            SettingRow(
                title = "显示级别",
                inlineValue = when (minLevel) {
                    io.github.lswlc33.maibms.data.BmsLog.Level.DEBUG -> "全部（含帧）"
                    io.github.lswlc33.maibms.data.BmsLog.Level.INFO -> "普通+"
                    io.github.lswlc33.maibms.data.BmsLog.Level.WARN -> "仅警告+"
                    io.github.lswlc33.maibms.data.BmsLog.Level.ERROR -> "仅错误"
                },
                trailing = { Text("⌄", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { levelMenu = true },
            )
            Box {
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    listOf(
                        "全部（含帧）" to io.github.lswlc33.maibms.data.BmsLog.Level.DEBUG,
                        "普通+" to io.github.lswlc33.maibms.data.BmsLog.Level.INFO,
                        "仅警告+" to io.github.lswlc33.maibms.data.BmsLog.Level.WARN,
                        "仅错误" to io.github.lswlc33.maibms.data.BmsLog.Level.ERROR,
                    ).forEach { (label, lv) ->
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                io.github.lswlc33.maibms.data.BmsLog.minLevel.value = lv
                                AppStore.logMinLevel = lv.name.first().toString()
                                levelMenu = false
                            },
                        )
                    }
                }
            }
        }
        // 操作行放在日志框「上面」：日志满时日志框近六屏高，放下面够不着
        SectionCard {
            SettingRow(
                title = "清空日志",
                trailing = { Chevron() },
                onClick = {
                    io.github.lswlc33.maibms.data.BmsLog.clear()
                    actionNote = "日志已清空"
                },
            )
            val copy = rememberClipboardWriter()
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) { kotlinx.coroutines.delay(1600); copied = false }
            }
            SettingRow(
                title = "复制日志",
                inlineValue = if (entries.isEmpty()) "暂无记录" else "${entries.size} 条",
                trailing = {
                    if (copied) Text("已复制", fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    else Chevron()
                },
                onClick = {
                    if (entries.isNotEmpty()) {
                        copied = copy(io.github.lswlc33.maibms.data.BmsLog.exportText())
                    }
                },
            )
            val export = rememberLogExporter()
            SettingRow(
                title = "导出日志文件",
                inlineValue = "txt",
                trailing = { Chevron() },
                onClick = {
                    val path = export(io.github.lswlc33.maibms.data.BmsLog.exportText())
                    actionNote = if (path != null) "已导出：$path" else "导出失败"
                    io.github.lswlc33.maibms.data.BmsLog.i("UI", "导出日志文件 → " + (path ?: "失败"))
                },
            )
            actionNote?.let {
                Text(it, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF0C100F)).padding(11.dp)
        ) {
            val shown = entries.filter { it.level.ordinal >= minLevel.ordinal }
            if (shown.isEmpty()) {
                Text(
                    "（暂无记录：连接保护板后此处显示收发帧与操作记录）",
                    fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                    color = androidx.compose.ui.graphics.Color(0xFF6E8A7C),
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
            shown.forEach { e ->
                // 墙钟时间：日志跨会话保留（按天文件、最多 3 天），相对毫秒已无法对齐两次启动
                val clock = io.github.lswlc33.maibms.data.formatTimeOfDay(e.atMs)
                Text(
                    "$clock " + e.render(),
                    fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                    color = levelColor(e.level),
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}
