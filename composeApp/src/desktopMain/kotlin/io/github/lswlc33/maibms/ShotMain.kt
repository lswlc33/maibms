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
 */
private fun seedFrame(voltOffsetMilli: Int, currentTenth: Int): ByteArray {
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
    u32(t0 + 28, 8L)                                // 功率 8W
    u32(t0 + 32, 35_520_078L)                       // 运行时间 9:52
    u32(t0 + 36, 0L)                                // 均衡位图
    // 最高/最低/压差/平均按上面生成的单体算，图里高亮格才与数值自洽
    val maxIdx = cellMv.indices.maxByOrNull { cellMv[it] }!! + 1
    val minIdx = cellMv.indices.minByOrNull { cellMv[it] }!! + 1
    u16(t0 + 40, cellMv[maxIdx - 1]); u16(t0 + 42, maxIdx)
    u16(t0 + 44, cellMv[minIdx - 1]); u16(t0 + 46, minIdx)
    u16(t0 + 48, cellMv[maxIdx - 1] - cellMv[minIdx - 1]); u16(t0 + 50, cellMv.average().toInt())
    u16(t0 + 60, 0xFAF1)                            // 三元锂
    return Frame.build(Proto.ADDR_MAIN, Proto.RSP_REALTIME, 0, d, 0)
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
    seedParamsAndIdentity()
    listOf(seedFrame(0, 0) to 0, seedFrame(1, 3) to 1).forEach { (bytes, _) ->
        FrameParser().feed(bytes).filter { it.func == Proto.RSP_REALTIME }
            .forEach { MockBms.updateFromRealtime(RealtimeDecoder.decode(it.data)) }
    }
    MockBms.connected.value = true
}

private class Shot(
    val name: String,
    val dark: Boolean,
    val route: Route = Route.Dashboard,
    val dialog: DialogKind? = null,
    val prelude: () -> Unit = {},
)

private val withData = System.getenv("SHOT_SEED") != "0"   // 默认带数据（QA 看布局用）

private val shots = listOf(
    Shot("01-dash-light", dark = false),
    Shot("02-dash-dark", dark = true),
    Shot("03-config", dark = true, route = Route.Config),
    Shot("04-param-group", dark = true, route = Route.ParamGroup(0)),
    Shot("05-control-tools", dark = true, route = Route.ControlTools),
    Shot("06-settings", dark = true, route = Route.Settings),
    Shot("09-password", dark = true, route = Route.Password),
    Shot("11-developer", dark = true, route = Route.Developer),
    Shot("12-dialog-scan", dark = true, dialog = DialogKind.Scan),
    Shot("13-dialog-protect", dark = true, dialog = DialogKind.ProtectDetail),
    Shot("14-dialog-confirm", dark = true, dialog = DialogKind.ControlConfirmNamed("强制开启充电")),
    Shot("15-dialog-perm", dark = true, dialog = DialogKind.PermLevels),
    Shot("17-config-light", dark = false, route = Route.Config),
    Shot("18-settings-light", dark = false, route = Route.Settings),
)

@OptIn(ExperimentalComposeUiApi::class)
private fun shoot(shot: Shot, outDir: File) {
    if (withData) runCatching { seedUi() }.onFailure { println("seed failed: $it") }
    shot.prelude()
    val scene = ImageComposeScene(
        width = (shotW * DENSITY).toInt(),
        height = (shotH * DENSITY).toInt(),
        density = Density(DENSITY),
        coroutineContext = Dispatchers.Unconfined,
    ) {
        App(deepLink = shot.route, deepDialog = shot.dialog, forceDark = shot.dark)
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
