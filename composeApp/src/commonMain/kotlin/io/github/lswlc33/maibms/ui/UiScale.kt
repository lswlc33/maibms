package io.github.lswlc33.maibms.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.nearestUiScale

/**
 * 界面大小（整体缩放）：文字、间距、图标、卡高一起变，**不是只改字号**。
 *
 * 做法就是覆盖 [LocalDensity] 一个值——Compose 里 dp 与 sp 都要经它换算成像素
 * （sp 还要再乘 fontScale），所以改密度即整体缩放：Material 组件的内距、`Canvas` 绘制、
 * `Text` 字号、`Dp` 取整全部跟着走，不需要逐处改尺寸（这就是"现成办法"，框架自带）。
 *
 * - 落盘：`AppStore.uiScale`（四档预设，读写都吸附到最近档位）
 * - 应用：[ApplyUiScale]（由 [BmsTheme] 调用）。所有入口都渲染在主题之下，所以改一处即处处生效；
 *   离屏截图工具不注入落盘 store，截图恒为标准档，可复现
 */
internal val uiScaleState: MutableState<Float> = mutableStateOf(AppStore.uiScale)

/** 设置界面大小：吸附到最近档位 + 落盘 + 立即生效（读 [uiScaleState] 的组合会重组） */
fun setUiScale(v: Float) {
    val s = nearestUiScale(v)
    uiScaleState.value = s
    AppStore.uiScale = s
}

/**
 * 在压缩放后的密度上再记一份"基础密度"。
 *
 * [BmsTheme] 在本项目里是**嵌套调用**的（App 先套一层默认主题，再套一层选定深浅色），
 * 若每次都拿当前密度乘系数，1.15 会被乘成 1.3225。记下进入缩放前的密度、每次都从它重算，
 * 嵌套几层结果都一样。
 */
private val LocalBaseDensity = compositionLocalOf<Density?> { null }

/** 按当前档位包一层密度（由 [BmsTheme] 调用；重复调用结果相同，可安全嵌套） */
@Composable
internal fun ApplyUiScale(content: @Composable () -> Unit) {
    val scale = uiScaleState.value
    val current = LocalDensity.current
    val base = LocalBaseDensity.current ?: current
    CompositionLocalProvider(
        LocalBaseDensity provides base,
        LocalDensity provides Density(density = base.density * scale, fontScale = base.fontScale),
        content = content,
    )
}
