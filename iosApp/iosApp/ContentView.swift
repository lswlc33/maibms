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

struct ContentView: View {
    var body: some View {
        ComposeView()
            // Compose 自己处理安全区；键盘弹出时让 Compose 收到 inset，避免顶起整页
            .ignoresSafeArea(.container, edges: .bottom)
            .ignoresSafeArea(.keyboard)
    }
}
