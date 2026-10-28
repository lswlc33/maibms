package io.github.lswlc33.maibms.web


import io.github.lswlc33.maibms.data.AppForeground
import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.AppVersion
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.data.LocalStorageStore
import io.github.lswlc33.maibms.transport.WebBleTransport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 网页端入口 —— **只做数据层，不画界面**。
 *
 * 页面由各个主题自己写（`docs/web/index.html`、`docs/web/mdui.html`…），
 * Kotlin 侧负责：持久化 → 注入 Web Bluetooth 传输 → 启动轮询管线 → 通过 [Bridge]
 * 把状态与动作暴露到 `window.Maibms`。
 *
 * 这样一来"换一套主题"就是换一个 HTML + JS 文件，不用重编 Kotlin；
 * 而协议解析、参数表、命令语义仍然只有一份（各主题都吃同一份 state/meta）。
 */
private val transport = WebBleTransport()

/** 幂等：主题页可能重复调用 start()（如热重载），不能让收集器装两套 */
private var started = false

/** 全局作用域：主题页的动作都往这里扔协程 */
internal val uiScope = MainScope()

fun main() {
    startDataLayer()
}

/**
 * 启动数据层。**主题页显式调用它**（`MaibmsBoot.start()`）。
 *
 * 为什么不靠 `main()` 自动执行：webpack 以 UMD library 形式输出（`output.library`），
 * 那种模式下 bundle 只导出模块、**不会自动跑 main()**——页面里 `window.Maibms` 会是
 * undefined，看着像"数据层没加载"。显式导出一个启动函数最稳，也方便主题页决定时机。
 */
fun startDataLayer() {
    if (started) return
    started = true

    // 1) 持久化：localStorage（设备档案/密码/快照/界面偏好）
    AppStore.store = LocalStorageStore()

    // 2) 日志：网页端不落盘（见 PlatformFileSystem.web.kt），只恢复级别与帧开关
    runCatching {
        BmsLog.frameLogOn.value = AppStore.logFrameOn
        BmsLog.minLevel.value = BmsLog.Level.entries.firstOrNull { it.tag == AppStore.logMinLevel }
            ?: BmsLog.Level.INFO
    }
    BmsLog.i("WEB", "网页端启动：v${AppVersion.name}")

    // 3) 真实 BLE 传输注入（未注入时仓库退化成 NoopTransport）
    runCatching { Bms.repository.setRealTransport(transport) }
        .onFailure { BmsLog.e("WEB", "BLE 传输初始化失败：$it") }

    // 4) 前后台信号：切走标签页当"后台"（与 Android 的 onStop 同语义）。
    //    网页端没有后台监控（浏览器不允许常驻 BLE），于是轮询自动暂停、回来立刻恢复。
    runCatching {
        document.addEventListener("visibilitychange", {
            AppForeground.setActive(!isDocumentHidden())
            BmsLog.d("WEB", "页面可见性 → ${if (isDocumentHidden()) "后台" else "前台"}")
        })
        window.addEventListener("pagehide", { transport.shutdown() })
    }

    // 5) 数据管线：恢复记忆设备、装配收集器；**不会自动连**——
    //    Web Bluetooth 的 requestDevice 必须在用户手势里调用，页面加载即弹窗会被浏览器拒绝。
    runCatching { Bms.repository.start() }
        .onFailure { BmsLog.e("WEB", "start() 失败：$it") }

    // 6) `?preview=1`：载入内置预设快照，没有保护板也能看到完整界面。
    //    给用户"先看看长什么样"、也给无硬件时的界面回归用；不写任何持久化状态。
    if (wantsPreview()) {
        runCatching {
            val snap = io.github.lswlc33.maibms.data.UiPreview.snapshot()
            io.github.lswlc33.maibms.data.MockBms.restoreSnapshot(snap)
            BmsLog.i("WEB", "已载入界面预览快照（?preview=1）：${snap.deviceName}")
        }.onFailure { BmsLog.w("WEB", "预览快照载入失败：$it") }
    }

    // 7) 桥：把状态与动作暴露给主题页面
    val bridgeOk = runCatching { Bridge.install() }.getOrElse {
        BmsLog.e("WEB", "数据桥装配失败：$it")
        false
    }
    if (!bridgeOk) BmsLog.e("WEB", "数据桥未装配：请确认页面先加载了 maibms-host.js")

    // 8) 状态推送：所有 StateFlow 汇入一条，变化即推整份快照。
    //    订阅放在 MainScope 里，与主题页的渲染在同一事件循环。
    startStatePusher()

    // 9) 通知主题页"数据层就绪"（有的主题要等这个事件才开始渲染）
    uiScope.launch {
        runCatching { js("window.dispatchEvent(new CustomEvent('maibms-ready'))") }
    }
}

/**
 * 把所有相关 StateFlow 汇成一条推送。
 *
 * 用"合并触发"而不是逐个 collect 后各自 push：一拍之内多个流会接连变化
 * （status + 参数 + 通道…），逐个推会让主题页重画好几次。这里用一个短窗口合并成一次。
 */
private fun startStatePusher() {
    fun watch(flow: kotlinx.coroutines.flow.Flow<*>) {
        uiScope.launch { flow.collect { schedulePush() } }
    }
    watch(io.github.lswlc33.maibms.data.MockBms.status)
    watch(io.github.lswlc33.maibms.data.MockBms.caps)
    watch(io.github.lswlc33.maibms.data.MockBms.liveParams)
    watch(io.github.lswlc33.maibms.data.MockBms.identity)
    watch(io.github.lswlc33.maibms.data.MockBms.paramsReading)
    watch(io.github.lswlc33.maibms.data.MockBms.paramsFromCache)
    watch(io.github.lswlc33.maibms.data.MockBms.connectedDeviceName)
    watch(io.github.lswlc33.maibms.data.MockBms.plainPasswords)
    watch(Bms.repository.linkState)
    watch(Bms.repository.connectHint)
    watch(Bms.repository.stalled)
    watch(Bms.repository.scanning)
    watch(Bms.repository.scanError)
    watch(Bms.repository.activeChannel)
    watch(Bms.repository.availableChannels)
    watch(Bms.repository.currentFamily)
    watch(Bms.repository.previewActive)
    watch(Bms.repository.previewLabel)
    // 日志单独走（频率高、体积大，主题页按需订阅）
    uiScope.launch {
        BmsLog.lines.collect { Bridge.pushLog(it.takeLast(300)) }
    }
}

/**
 * 合并窗口：16ms 内的多次变化只推一次（约一帧，主题页不会看到中间态）。
 *
 * 用协程 `delay` 而不是 `setTimeout(js 字符串)`：后者拿不到 Kotlin 函数引用，
 * 只能靠字符串求值，既脆又难调试；而 MainScope 本身就是 JS 微任务/定时器驱动，
 * `delay` 的语义与 setTimeout 一致。
 */
private var pushPending = false

private fun schedulePush() {
    if (pushPending) return
    pushPending = true
    uiScope.launch {
        kotlinx.coroutines.delay(16)
        pushPending = false
        Bridge.pushState()
    }
}

/** 页面是否被切到后台（`document.hidden` 在 DOM 绑定里没有直接暴露，走 JS） */
private fun isDocumentHidden(): Boolean = js("document.hidden").unsafeCast<Boolean>()

/** URL 上是否带 `?preview=1` */
private fun wantsPreview(): Boolean =
    js("new URLSearchParams(location.search).get('preview')").unsafeCast<String?>() == "1"