package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import io.github.lswlc33.maibms.data.AppStore

/**
 * 仪表视图（横屏表盘）进出的唯一入口。
 *
 * **自动进入只按宽高比**（[ClusterMath.isWideViewport]：宽 ≥ 高 × 1.2）：手机横屏、平板横屏
 * （4:3 = 1.33）、拉宽的桌面窗口都会自动进；**正方形屏与大竖屏一律不进**——正方形不是"横屏"，
 * 表盘在那种窗口里只能压成一条 2.4:1 的横带、上下各留三分之一空白（800×800 实测如此）。
 *
 * 传感器方向在 commonMain / iOS / 桌面拿不到，Compose 里"横屏"本就是几何关系（宽 > 高），
 * 所以三端统一用宽高比判——手机上它与传感器完全等价。判定与界面缩放无关（宽高同乘，比值不变）。
 *
 * **手动进出三端都有**（不再依赖 Android 的锁横屏）：仪表盘顶栏「仪表视图」进、表盘内「退出仪表」出。
 */
private val clusterForced: MutableState<Boolean> = mutableStateOf(AppStore.clusterForced)

/**
 * 本次「宽窗口期间」用户按过退出：压住自动进入。
 *
 * 只在窗口仍然宽的时候有意义——一旦转回竖屏/窄窗就复位（见 [useClusterLayout]），
 * 于是"退出手持设备再横过来"仍会照旧自动进表盘（与旧的解锁转向手感一致）。
 * 刻意不落盘：它只是对"当下这个宽窗口"的表态，不是长期偏好。
 */
private val clusterSuppressed: MutableState<Boolean> = mutableStateOf(false)

/** 手动进入仪表视图：落盘记住（重启后仍进），Android 上顺带锁横屏让表盘铺满整屏 */
fun enterCluster() {
    clusterForced.value = true
    AppStore.clusterForced = true
    clusterSuppressed.value = false
    lockLandscape(true)
}

/** 退出仪表视图：回到卡片列；Android 上解除锁向（可以转回竖屏） */
fun exitCluster() {
    clusterForced.value = false
    AppStore.clusterForced = false
    clusterSuppressed.value = true
    lockLandscape(false)
}

/**
 * 当前窗口该不该显示仪表视图；[width] / [height] 传 `BoxWithConstraints` 的 maxWidth / maxHeight。
 *
 * 判据 = 手动强制 ∨（明显宽 ∧ 未按过退出）。手动强制优先：正方形屏/竖屏平板上用户主动要表盘时也显示。
 */
@Composable
fun useClusterLayout(width: Dp, height: Dp): Boolean {
    val wide = ClusterMath.isWideViewport(width.value, height.value)
    // 窗口不再宽 → 复位"退出"表态：转回竖屏再横过来，自动进入照旧生效
    LaunchedEffect(wide) { if (!wide) clusterSuppressed.value = false }
    return clusterForced.value || (wide && !clusterSuppressed.value)
}
