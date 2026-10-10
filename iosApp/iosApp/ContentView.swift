import SwiftUI
import UIKit
import ComposeApp

/// 把 Kotlin 侧的 Compose 界面（MainViewController）挂进 SwiftUI。
/// framework 入口在 composeApp/src/iosMain/.../MainViewController.kt，
/// Kotlin 顶层函数在 Swift 里就是 `MainViewControllerKt.MainViewController()`。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// iOS 的状态栏「显隐 + 文字黑白」只有窗口根控制器（SwiftUI 的 hosting controller）说了算，
/// Kotlin 侧改不动它；而沉浸与外观取值又只在 Compose 里（横屏表盘 / 外观设置）。
/// 所以 Kotlin 把状态写进 `IosSystemChrome` 单例（iosMain/ui/SystemChrome.ios.kt），
/// 这里注册回调，用 SwiftUI 修饰符落地。
final class IosSystemChromeState: ObservableObject {
    /// 沉浸（横屏表盘）：隐藏状态栏与 Home 指示条
    @Published var immersive = false
    /// 状态栏配色："system" 跟随系统 / "light" 浅色底黑字 / "dark" 深色底白字
    @Published var appearance = "system"

    /// 从 Kotlin 侧的单例拉一次（Compose 首帧就可能已经写过状态了）
    func syncFromCompose() {
        immersive = IosSystemChrome.shared.immersive
        appearance = IosSystemChrome.shared.appearance
    }

    func startObserving() {
        syncFromCompose()
        // 尾闭包写法对 Kotlin 侧导出的选择器名不敏感（不管生成的是 observe(onChange:) 还是别的）
        IosSystemChrome.shared.observe { [weak self] in
            DispatchQueue.main.async { self?.syncFromCompose() }
        }
    }
}

struct ContentView: View {
    @StateObject private var chrome = IosSystemChromeState()

    var body: some View {
        ComposeView()
            // 窗口这一层要整屏铺满：只让出底部时，顶部（竖屏状态栏 62pt）与左右（横屏 62pt）
            // 会露出窗口底色，就是顶栏 / 横屏两侧「未沉浸」的黑边。安全区避让交给 Compose
            // 的 WindowInsets（statusBars / navigationBars / safeDrawing），它会拿到真实 inset
            // 并据此排内容。键盘同样交给 Compose，避免顶起整页。
            .ignoresSafeArea(.container)
            .ignoresSafeArea(.keyboard)
            // 状态栏文字跟随应用主题：选「跟随系统」时传 nil（不覆盖）。覆盖会把 Compose 的
            // isSystemInDarkTheme() 钉死，用户之后切回「跟随系统」就永远跟不上系统切换了。
            .preferredColorScheme(colorScheme)
            // 沉浸（横屏表盘）时隐藏状态栏；Home 指示条见 hideHomeIndicator
            .statusBarHidden(chrome.immersive)
            .hideHomeIndicator(chrome.immersive)
            .onAppear { chrome.startObserving() }
    }

    /// nil = 不覆盖，直接用系统外观
    private var colorScheme: ColorScheme? {
        switch chrome.appearance {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

}

private extension View {
    /// 沉浸时隐藏 Home 指示条（`persistentSystemOverlays` 需要 iOS 16；iOS 15 上保持系统默认）
    @ViewBuilder func hideHomeIndicator(_ hidden: Bool) -> some View {
        if #available(iOS 16.0, *) {
            self.persistentSystemOverlays(hidden ? .hidden : .automatic)
        } else {
            self
        }
    }
}
