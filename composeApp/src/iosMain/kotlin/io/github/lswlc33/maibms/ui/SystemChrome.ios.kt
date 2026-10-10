package io.github.lswlc33.maibms.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * iOS 系统栏（状态栏显隐 + 文字配色）控制。
 *
 * iOS 上「状态栏隐不隐、文字是黑是白」只有**窗口根视图控制器**说话算数，而根控制器是
 * SwiftUI 壳工程持有的 hosting controller——Kotlin 侧改不动它。所以这里只把状态写进单例，
 * 由壳工程（`iosApp/iosApp/ContentView.swift`）注册回调后用 SwiftUI 修饰符落地：
 * `.statusBarHidden` / `.persistentSystemOverlays` / `.preferredColorScheme`。
 *
 * 注意 `AppAppearance.System`（跟随系统）**不能**覆盖颜色方案：覆盖等于把 trait collection
 * 钉死，Compose 的 `isSystemInDarkTheme()` 读回来的就是被覆盖后的值，之后用户切回「跟随系统」
 * 会永远跟不上系统切换。壳工程只在 Light/Dark 时覆盖。
 */
object IosSystemChrome {
    /** 沉浸（横屏表盘）：隐藏状态栏与 Home 指示条 */
    var immersive: Boolean = false
        private set

    /** 状态栏配色："system" 跟随系统 / "light" 浅色底黑字 / "dark" 深色底白字 */
    var appearance: String = "system"
        private set

    private var callback: (() -> Unit)? = null

    /** 壳工程启动时注册一次（Swift 侧 `IosSystemChrome.shared.observe { ... }`） */
    fun observe(onChange: () -> Unit) {
        callback = onChange
    }

    /** Compose 侧写入；值没变就不回调，避免无谓的 SwiftUI 刷新 */
    fun update(immersive: Boolean = this.immersive, appearance: String = this.appearance) {
        if (this.immersive == immersive && this.appearance == appearance) return
        this.immersive = immersive
        this.appearance = appearance
        callback?.invoke()
    }
}

actual fun systemBarsImmersive(immersive: Boolean) = IosSystemChrome.update(immersive = immersive)

actual fun systemBarsAppearance(appearance: AppAppearance) = IosSystemChrome.update(
    appearance = when (appearance) {
        AppAppearance.System -> "system"
        AppAppearance.Light -> "light"
        AppAppearance.Dark -> "dark"
    }
)

/** iOS 没有公开 API 读屏幕圆角（`_displayCornerRadius` 是私有 API），避让交给安全区 */
actual fun screenCornerRadius(): Dp = 0.dp
