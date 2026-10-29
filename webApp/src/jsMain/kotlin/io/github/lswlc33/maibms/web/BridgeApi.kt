package io.github.lswlc33.maibms.web

import io.github.lswlc33.maibms.data.AppStore
import io.github.lswlc33.maibms.data.Bms
import io.github.lswlc33.maibms.data.BmsLog
import io.github.lswlc33.maibms.protocol.ResultCodes
import io.github.lswlc33.maibms.transport.BleChannel
import io.github.lswlc33.maibms.transport.webBluetoothAvailable
import kotlinx.browser.window
import kotlinx.coroutines.promise

/**
 * 把桥的成员挂到 `window.Maibms`。
 *
 * 全部经 [jsPut]（真正在 JS 侧执行的 `o[k] = v`）。**不能**直接写 `api.connect = ...`：
 * Kotlin/JS IR 会把那编译成一次属性/设置器访问，对本工程实测会**静默丢弃**
 * （对象存在、`Object.getOwnPropertyNames` 却是空），排查成本极高。
 *
 * 返回值也不用 Kotlin 对象：主题页是普通 JS，不认识 `KString`/Kotlin List。
 * 动作要么返回原生 `Boolean`/`String`，要么返回 **Promise<普通对象>**（DTO 经 JSON 往返）。
 */

internal fun installVersion(api: dynamic) {
    jsPut(api, "version", parseJson(
        """{"name":"${io.github.lswlc33.maibms.data.AppVersion.name}","code":${io.github.lswlc33.maibms.data.AppVersion.code}}"""
    ))
    jsPut(api, "prefersDark", { prefersDark() })
}

internal fun installStateAccess(api: dynamic) {
    jsPut(api, "state", Bridge.currentState())
    jsPut(api, "onState", { fn: (dynamic) -> Unit -> Bridge.subscribeState(fn) })
    jsPut(api, "onLog", { fn: (dynamic) -> Unit -> Bridge.subscribeLog(fn) })
    jsPut(api, "unsubscribe", { Bridge.clearListeners() })
    jsPut(api, "refresh", { Bridge.pushState() })
    jsPut(api, "meta", { Bridge.metaPublic().toJs(ThemeMeta.serializer()) })
    jsPut(api, "themes", {
        parseJson(
            BridgeJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ThemeInfo.serializer()),
                Bridge.themes()
            )
        )
    })
    jsPut(api, "logText", { BmsLog.exportText() })
    jsPut(api, "clearLog", { BmsLog.clear() })
}

internal fun installActions(api: dynamic) {
    // ---- 连接 ----
    jsPut(api, "connect", { uiScope.launchRaw { startConnectFlowFromBridge() } })
    jsPut(api, "disconnect", { uiScope.launchRaw { Bms.repository.disconnect() } })
    jsPut(api, "supportsWebBluetooth", { webBluetoothAvailable() })
    jsPut(api, "canScan", { Bms.repository.canScan })

    // ---- 设置 ----
    jsPut(api, "setThemeMode", { mode: String -> setThemeModeAction(mode) })
    jsPut(api, "themeMode", { AppStore.themeMode })
    jsPut(api, "isDark", { isDarkNow() })
    jsPut(api, "setPollInterval", { ms: Int ->
        AppStore.pollIntervalMs = ms
        Bridge.pushState()
    })
    jsPut(api, "setAutoUpgradeTarget", { lv: Int ->
        Bms.repository.setAutoUpgradeTarget(lv)
        Bridge.pushState()
    })
    jsPut(api, "setLogFrameOn", { on: Boolean ->
        AppStore.logFrameOn = on
        // AppStore 只是 KV 落盘；真正控制 BmsLog 记录的是这个 StateFlow（启动时才同步一次），
        // 不在这里同步写的话开关就是"看起来翻了、实际不生效"
        BmsLog.frameLogOn.value = on
        Bridge.pushState()
    })

    // ---- 权限 ----
    jsPut(api, "auth", { level: Int, pw: String -> authAction(level, pw) })
    jsPut(api, "savePassword", { level: Int, pw: String ->
        Bms.repository.savePassword(level, pw)
        Bridge.pushState()
    })
    jsPut(api, "forgetPassword", { level: Int ->
        Bms.repository.forgetPassword(level)
        Bridge.pushState()
    })

    // ---- 参数 / 控制 ----
    jsPut(api, "refreshParams", { uiScope.launchRaw { Bms.repository.refreshParams(force = true) } })
    jsPut(api, "writeParam", { addr: Int, raw: Int -> writeParamAction(addr, raw) })
    jsPut(api, "saveAllParams", { saveAllParamsAction() })
    jsPut(api, "control", { cmd: Int -> controlAction(cmd) })

    // ---- 设备 / 通道 / 预览 ----
    jsPut(api, "deleteDevice", { addr: String ->
        Bms.repository.deleteDevice(addr)
        Bridge.pushState()
    })
    jsPut(api, "switchChannel", { id: String ->
        BleChannel.byId(id)?.let { ch -> uiScope.launchRaw { Bms.repository.switchChannel(ch) } }
    })
    jsPut(api, "exitPreview", { uiScope.launchRaw { Bms.repository.exitPreview() } })

    // ---- 主题系统 ----
    jsPut(api, "gotoTheme", { file: String -> window.location.href = file })
}

/* ---------------- 实现 ---------------- */

private fun isDarkNow(): Boolean = when (AppStore.themeMode) {
    "dark" -> true
    "light" -> false
    else -> prefersDark()
}

private fun setThemeModeAction(mode: String): Boolean {
    if (mode !in setOf("system", "light", "dark")) return false
    AppStore.themeMode = mode
    Bridge.pushState()
    return true
}

/**
 * 连接流程：`startScan()` → Web Bluetooth 设备选择器（阻塞到用户选完）→ `connectTo()`。
 *
 * `requestDevice()` 必须在**用户手势**里调用，所以只能由主题页的点击事件触发；
 * 页面加载时自动调用会被浏览器拒绝（这是浏览器安全模型，主题页不要试）。
 */
internal suspend fun startConnectFlowFromBridge() {
    val repo = Bms.repository
    runCatching { repo.startScan() }.onFailure { err ->
        BmsLog.e("WEB", "扫描失败：$err")
        Bridge.pushState()
        return
    }
    val found = repo.scanResults.value.firstOrNull()
    repo.stopScan()
    if (found == null) {
        // 用户在选择器里点了取消：静默收尾（不是错误）
        Bridge.pushState()
        return
    }
    repo.connectTo(found.address)
}

private fun authAction(level: Int, password: String): dynamic = uiScope.promise {
    val got = runCatching { Bms.repository.auth(level, password) }.getOrElse { 0 }
    Bridge.pushState()
    ThemeAuthResult(ok = got > 0, level = got).toJs(ThemeAuthResult.serializer())
}

private fun writeParamAction(addr: Int, raw: Int): dynamic = uiScope.promise {
    val code = runCatching { Bms.repository.writeParam(addr, raw) }.getOrElse { -1 }
    Bridge.pushState()
    ThemeResult(
        ok = ResultCodes.writeOk(code),
        code = code,
        text = ResultCodes.writeResult(code),
    ).toJs(ThemeResult.serializer())
}

private fun saveAllParamsAction(): dynamic = uiScope.promise {
    val code = runCatching { Bms.repository.saveAllParams() }.getOrElse { -1 }
    Bridge.pushState()
    // 保存参数走 0x61 控制码表（docs/07 §7.3）：1 成功、0 "无提示"是"无结论"不是成功
    // （以前把 0 也标 ok，界面会播报"已保存"——与 controlAction 的口径对齐，只认 1）
    ThemeResult(
        ok = code == 1,
        code = code,
        text = ResultCodes.controlResult(code),
    ).toJs(ThemeResult.serializer())
}

private fun controlAction(cmd: Int): dynamic = uiScope.promise {
    val code = runCatching { Bms.repository.control(cmd) }.getOrElse { -1 }
    Bridge.pushState()
    ThemeResult(
        ok = code == 1,
        code = code,
        text = ResultCodes.controlResult(code),
    ).toJs(ThemeResult.serializer())
}