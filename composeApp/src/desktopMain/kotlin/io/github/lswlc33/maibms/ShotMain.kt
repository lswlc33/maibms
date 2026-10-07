// 离屏截图工具（QA 专用，不进 APK）：把真实 App 组合渲染成 PNG，不弹窗口、不需要显示器。
// 用法：./gradlew :composeApp:shot   ->  build/shots/*.png
//      SHOT_SIZE=360  -> 再按 360x640dp（MI6 窄屏）渲一套，文件名带 -360 后缀
// 桌面端没有 BLE 后端，默认渲染「未连接」空态；需要看有数据的界面时用 seed=1：
// 按 docs/05 的布局拼一帧协议合法的 20S 三元锂实时数据（数值取自真机实测），
// 走 FrameParser → RealtimeDecoder → MockBms 这条与真机完全相同的解码/回填路径。
package io.github.lswlc33.maibms

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.lswlc33.maibms.data.MockBms
import io.github.lswlc33.maibms.ui.App
import io.github.lswlc33.maibms.ui.DialogKind
import io.github.lswlc33.maibms.ui.Route
import kotlinx.coroutines.Dispatchers
import io.github.lswlc33.maibms.protocol.Frame
import io.github.lswlc33.maibms.protocol.FrameParser
import io.github.lswlc33.maibms.protocol.Proto
import io.github.lswlc33.maibms.protocol.RealtimeDecoder
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** 默认 411x914 dp（主流安卓机），density 2 -> 822x1828 px；可用 SHOT_SIZE 切窄屏档 */
private var shotW = 411
private var shotH = 914
private var shotSuffix = ""
private const val DENSITY = 2f

/**
 * QA 数据：按 docs/05-实时数据.md 的字节布局拼一帧 0x11 应答（真机 ANT@BLE24CBUB-3547 的实测值：
 * 20 串三元锂 113Ah、单体 ~4.26V、MOS/均衡 22℃）。只用于离屏渲染，不进 Android 包。
 * [powerW] 默认真机实测的 8W；卡3 背景进度条验收时由调用方抬到可见档位的功率。
 * [remainDisMin]/[lastGapSec] 进扩展段 88/82：真机待机帧实测 10584min / 5037s；
 * 调用方按当拍电流换算成自洽值（113Ah ÷ 电流）。
 */
private fun seedFrame(
    voltOffsetMilli: Int, currentTenth: Int, powerW: Int = 8,
    remainDisMin: Int = 0, lastGapSec: Long = 0,
): ByteArray {
    val n = 20; val m = 4
    val t0 = 28 + 2 * n + 2 * m
    val d = ByteArray(t0 + 78 + 24)
    fun u8(i: Int, v: Int) { d[i] = v.toByte() }
    fun u16(i: Int, v: Int) { d[i] = (v and 0xFF).toByte(); d[i + 1] = ((v shr 8) and 0xFF).toByte() }
    fun s16(i: Int, v: Int) = u16(i, v and 0xFFFF)
    fun u32(i: Int, v: Long) {
        for (k in 0 until 4) d[i + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }
    u8(0, 2)                     // 当前权限 2 级
    u8(1, 3)                     // 待机
    u8(2, m); u8(3, n)
    u16(12, 0x0001)              // warn bit0 = 单体过压告警（真机实测值）
    val cellMv = (0 until n).map { 4262 + (it % 5) + voltOffsetMilli }
    for (i in 0 until n) u16(28 + 2 * i, cellMv[i] and 0x1FFF)
    for (i in 0 until m) s16(28 + 2 * n + 2 * i, 22)
    s16(t0, 22); s16(t0 + 2, 22)                    // MOS / 均衡温度
    val totalV = (4262 * n) / 10 + voltOffsetMilli / 10 * n   // 0.01V 单位
    u16(t0 + 4, totalV)
    s16(t0 + 6, 1 + currentTenth)                   // 电流 0.1A
    u16(t0 + 8, 100); u16(t0 + 10, 100)             // SOC / SOH
    u8(t0 + 12, 1); u8(t0 + 13, 1); u8(t0 + 14, 0)  // 放电MOS开 / 充电MOS开 / 均衡关
    u8(t0 + 15, 0)
    u32(t0 + 16, 113_000_000L)                      // 物理容量 113.0Ah
    u32(t0 + 20, 112_999_861L)                      // 剩余容量
    u32(t0 + 24, 4_644_008L)                        // 累计循环容量（/1000 => 4644Ah）
    u32(t0 + 28, powerW.toLong())                   // 功率（W）
    u32(t0 + 32, 35_520_078L)                       // 运行时间 9:52
    u32(t0 + 36, 0L)                                // 均衡位图
    // 最高/最低/压差/平均按上面生成的单体算，图里高亮格才与数值自洽
    val maxIdx = cellMv.indices.maxByOrNull { cellMv[it] }!! + 1
    val minIdx = cellMv.indices.minByOrNull { cellMv[it] }!! + 1
    u16(t0 + 40, cellMv[maxIdx - 1]); u16(t0 + 42, maxIdx)
    u16(t0 + 44, cellMv[minIdx - 1]); u16(t0 + 46, minIdx)
    u16(t0 + 48, cellMv[maxIdx - 1] - cellMv[minIdx - 1]); u16(t0 + 50, cellMv.average().toInt())
    u16(t0 + 60, 0xFAF1)                            // 三元锂
    // 扩展段 78~89：本次充电时长 0 / 上次充电间隔 / 充电剩余 0 / 放电剩余（docs/05 §5.6）
    u32(t0 + 78, 0L)
    u32(t0 + 82, lastGapSec)
    u16(t0 + 86, 0)
    u16(t0 + 88, remainDisMin)
    return Frame.build(Proto.ADDR_MAIN, Proto.RSP_REALTIME, 0, d, 0)
}

/**
 * QA 用「已连接但静默」的传输：桌面端没有 BLE 后端，界面会一直停在未连接态，
 * 而写权限指示（可编辑/只读/权限不足）恰恰要按连接态区分 —— 截图里必须能看出真机上会长什么样。
 * 只报告链路状态，不产生任何应答，seed 进去的数据不会被覆盖。
 */
private class ShotTransport : io.github.lswlc33.maibms.transport.BmsTransport {
    private val _link = kotlinx.coroutines.flow.MutableStateFlow(io.github.lswlc33.maibms.transport.LinkState.Idle)
    override val linkState: kotlinx.coroutines.flow.StateFlow<io.github.lswlc33.maibms.transport.LinkState> = _link
    private val _incoming = kotlinx.coroutines.flow.MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    override val incoming: kotlinx.coroutines.flow.SharedFlow<ByteArray> = _incoming
    override val supportsScan: Boolean get() = false
    override suspend fun connect(address: String?) { _link.value = io.github.lswlc33.maibms.transport.LinkState.Connected }
    override suspend fun disconnect() { _link.value = io.github.lswlc33.maibms.transport.LinkState.Disconnected }
    override suspend fun write(frame: ByteArray) { /* 静默：截图不依赖设备应答 */ }
    override suspend fun scan(onFound: (io.github.lswlc33.maibms.transport.ScanDevice) -> Unit) {}
}

/** 参数区/身份区取真机读回值（0x02 分块读的实测结果） */
private fun seedParamsAndIdentity() {
    MockBms.connectedDeviceName.value = "ANT@BLE24CBUB-3547"
    MockBms.connected.value = true
    MockBms.applyPermission(2)
    MockBms.identity.value = mapOf(
        "boot" to "BT24CBUB-240616A", "hwVersion" to "24ZHE6TB130A",
        "swVersion" to "24CBUB02-240623C", "packId" to "024S050AH096V000956WHMQ4K2F6J45S",
        "codeKey" to "",
    )
    MockBms.liveParams.value = mapOf(
        0 to 4300, 2 to 4250, 4 to 4400, 6 to 4300, 8 to 870, 10 to 860,
        12 to 2900, 14 to 3200, 16 to 2000, 18 to 2200, 20 to 10, 22 to 10,
        24 to 10, 26 to 10, 32 to 4250, 34 to 4200, 36 to 1008, 38 to 996,
        40 to 3200, 42 to 3300, 44 to 10, 46 to 10, 48 to 800, 50 to 700,
        56 to 60, 58 to 55, 60 to 60, 62 to 55, 64 to 80, 66 to 65,
        68 to 65534, 70 to 2, 72 to 65526, 74 to 65531,
        104 to 500, 106 to 5, 108 to 2000, 110 to 5, 112 to 3000, 114 to 1000,
        116 to 3000, 118 to 200, 124 to 45, 126 to 40, 128 to 150, 130 to 120,
        132 to 20, 134 to 10, 140 to 4300, 142 to 3900, 144 to 10, 146 to 2,
        148 to 180, 150 to 100, 152 to 0xFAF1, 154 to 20, 156 to 10, 158 to 3100,
        160 to 200, 162 to 15936, 164 to 1724, 174 to 4200, 176 to 4100,
        298 to 3900, 300 to 1800, 302 to 3547, 304 to 15, 306 to 0, 308 to 20,
        310 to 2998
    )
}

/** 注入两拍（略有差异）以便趋势图能成线 */
private fun seedUi() {
    // 先接上「已连接但静默」的 QA 传输，再灌数据：App() 里的 start() 会补上收集器，
    // 但因为没写 AppStore.savedAddress，不会再自发一次 connect（那会清空刚灌的数据）
    io.github.lswlc33.maibms.data.Bms.repository.setRealTransport(ShotTransport())
    kotlinx.coroutines.runBlocking { io.github.lswlc33.maibms.data.Bms.repository.connect("F9:99:1B:2B:1B:70") }
    seedParamsAndIdentity()
    // 第二拍把功率抬到 1.5kW（电流 17.6A 与 85.24V 自洽）：1 档满格 + 2 档半格，一屏看到已走/正在走/未走三种格子。
    // 剩余时间同样按当拍电流换算：待机拍放真机实测值（10584min），放电拍 113Ah÷17.6A≈384min
    listOf(
        seedFrame(0, 0, lastGapSec = 5037, remainDisMin = 10584) to 0,
        seedFrame(1, 175, powerW = 1500, lastGapSec = 5037, remainDisMin = 384) to 1,
    ).forEach { (bytes, _) ->
        FrameParser().feed(bytes).filter { it.func == Proto.RSP_REALTIME }
            .forEach { MockBms.updateFromRealtime(RealtimeDecoder.decode(it.data)) }
    }
    MockBms.connected.value = true
    seedLogs()
}

/** 开发者页截图用：注入各级别示例日志（走真实 BmsLog 管线） */
private fun seedLogs() {
    val log = io.github.lswlc33.maibms.data.BmsLog
    log.i("APP", "应用启动（Android BLE）")
    log.i("APP", "记忆设备：ANT@BLE24CBUB-3547 (F9:99:1B:2B:1B:70)")
    log.i("CONN", "发起连接 → F9:99:1B:2B:1B:70（自动重连）")
    log.d("BLE", "scan 发现 ANT@BLE24CBUB-3547 F9:99:1B:2B:1B:70 rssi=-58")
    log.i("CONN", "选择设备 ANT@BLE24CBUB-3547 (F9:99:1B:2B:1B:70)")
    log.i("BLE", "连接尝试 #1")
    log.i("BLE", "链路就绪（MTU 512）")
    log.i("AUTH", "自动升权成功：2 级")
    log.d("TX", "7E A1 01 00 00 F5 58 62 AA 55")
    log.d("RX", "func=11 reg=0 len=168 02 03 04 14 00 00 00 00 …")
    log.i("PARAM", "参数区读回 208 项，身份区 5 项")
    log.i("CTRL", "控制命令成功：充电开关")
    log.w("LINK", "实时帧停流 7200ms，判定失联（链路保持，继续轮询）")
    log.e("WRITE", "参数写入失败：权限不足（0x0）")
    log.i("AUTH", "权限回落，已静默重升到 2 级")
}

private class Shot(
    val name: String,
    val dark: Boolean,
    val route: Route = Route.Dashboard,
    /** 弹窗要在**灌数据之后**才构造（参数条目里的当前值来自 liveParams），所以用 lambda 而不是直接给值 */
    val dialog: () -> DialogKind? = { null },
    val prelude: () -> Unit = {},
)

private val withData = System.getenv("SHOT_SEED") != "0"   // 默认带数据（QA 看布局用）

private val shots = listOf(
    Shot("01-dash-light", dark = false),
    Shot("02-dash-dark", dark = true),
    Shot("03-config", dark = true, route = Route.Config),
    Shot("04-param-group", dark = true, route = Route.ParamGroup(0)),
    // 参数编辑弹窗（此前没有场景，改了排版也看不见）；3 级权限下弹窗与顶栏都应是「可编辑」
    Shot("04b-param-edit", dark = true, route = Route.ParamGroup(0), prelude = { MockBms.applyPermission(3) },
        dialog = { DialogKind.ParamEdit(paramItem("单体过压保护电压")) }),
    // 只读预览：2 级权限 / 设备状态量参数
    Shot("04c-param-preview-readonly", dark = true, route = Route.ParamGroup(0), prelude = { MockBms.applyPermission(2) },
        dialog = {
            DialogKind.ParamEdit(paramItem("单体过压保护电压"), readOnly = true,
                readOnlyNote = "当前运行权限 2 级只读：写入需 3 级及以上，点「去校验」升权")
        }),
    Shot("04d-param-enum", dark = true, route = Route.ParamGroup(4), prelude = { MockBms.applyPermission(3) },
        dialog = { DialogKind.ParamEdit(paramItem("电池类型选择")) }),
    // 3 级（可写）与控制页只读态对照
    Shot("05b-control-tools-level3", dark = true, route = Route.ControlTools, prelude = { MockBms.applyPermission(3) }),
    Shot("05-control-tools", dark = true, route = Route.ControlTools),
    Shot("06-settings", dark = true, route = Route.Settings),
    Shot("09-password", dark = true, route = Route.Password),
    Shot("11-developer", dark = true, route = Route.Developer),
    Shot("12-dialog-scan", dark = true, dialog = { DialogKind.Scan }),
    Shot("13-dialog-protect", dark = true, dialog = { DialogKind.ProtectDetail }),
    Shot("14-dialog-confirm", dark = true, dialog = { DialogKind.ControlConfirm(io.github.lswlc33.maibms.protocol.ControlCmd.FORCE_CHARGE) }),
    // 高危确认：需输入「确认」才可执行（恢复出厂/关机/蓝牙关闭这一类）
    Shot("14b-dialog-danger", dark = true, dialog = { DialogKind.ControlConfirm(io.github.lswlc33.maibms.protocol.ControlCmd.FACTORY_RESET) }),
    Shot("15-dialog-perm", dark = true, dialog = { DialogKind.PermLevels }),
    Shot("17-config-light", dark = false, route = Route.Config),
    Shot("18-settings-light", dark = false, route = Route.Settings),
)

/** 截图里用的参数条目：直接按 ParamTable 的定义与当前 seed 数据构造（与界面同一条路径） */
private fun paramItem(name: String): io.github.lswlc33.maibms.data.ParamItem {
    val d = io.github.lswlc33.maibms.protocol.ParamTable.defs.first { it.name == name }
    return io.github.lswlc33.maibms.data.ParamItem(
        name = d.name,
        value = io.github.lswlc33.maibms.protocol.ParamTable.format(MockBms.liveParams.value, d),
        unit = d.unit,
        addr = "0x%X".format(d.addr),
        scale = if (d.scale >= 1000) "1e${d.scale.toLong().toString().length - 1}" else d.scale.toLong().toString(),
        range = io.github.lswlc33.maibms.protocol.ParamTable.rangeText(d),
    )
}

/**
 * 卡3 背景进度条：固定配一套 1/2/3kW 阶梯并确保开启——否则卡面只有「双击关闭」提示、
 * 验收看不到换挡形态；空态截图（SHOT_SEED=0）也要能看出未连接时的中性灰格子。
 * 只在截图进程内存里改（ShotMain 不注入落盘 store），不影响桌面端真实偏好。
 */
private fun seedGaugePrefs() {
    io.github.lswlc33.maibms.data.AppStore.powerStagesW = listOf(1000, 2000, 3000)
    io.github.lswlc33.maibms.data.AppStore.powerGaugeEnabled = true
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shoot(shot: Shot, outDir: File) {
    if (withData) runCatching { seedUi() }.onFailure { println("seed failed: $it") }
    seedGaugePrefs()
    shot.prelude()
    val scene = ImageComposeScene(
        width = (shotW * DENSITY).toInt(),
        height = (shotH * DENSITY).toInt(),
        density = Density(DENSITY),
        coroutineContext = Dispatchers.Unconfined,
    ) {
        App(deepLink = shot.route, deepDialog = shot.dialog(), forceDark = shot.dark)
    }
    try {
        var t = 0L
        repeat(8) {
            t += 16_000_000L
            scene.render(t)
            Thread.sleep(30)
        }
        val img = scene.render(t)
        val data = img.encodeToData(EncodedImageFormat.PNG, 100) ?: error("PNG encode failed")
        File(outDir, "${shot.name}$shotSuffix.png").writeBytes(data.bytes)
        println("shot: ${shot.name}$shotSuffix.png")
    } catch (e: Throwable) {
        println("shot FAILED ${shot.name}: ${e::class.simpleName}: ${e.message}")
    } finally {
        scene.close()
    }
}

fun main(args: Array<String>) {
    val size = System.getenv("SHOT_SIZE")?.toIntOrNull()
    if (size != null) {
        // 窄屏档：MI6 实测 360x640dp，用于发现 411dp 下看不出来的挤压
        shotW = size
        shotH = (size * 640 / 360)
        shotSuffix = "-${size}"
    }
    val outDir = File(args.firstOrNull() ?: "build/shots").apply { mkdirs() }
    val only = args.drop(1)
    shots.filter { only.isEmpty() || it.name in only }.forEach { shoot(it, outDir) }
    println("done -> ${outDir.absolutePath}")
}
