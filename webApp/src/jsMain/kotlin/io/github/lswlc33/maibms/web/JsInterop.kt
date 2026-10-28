package io.github.lswlc33.maibms.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * JS 互操作小工具。
 *
 * 一条硬规矩：`js("...")` 里的代码**看不到外层的 Kotlin 局部变量**
 * （它只能看到自身作用域里的东西），所以"把 Kotlin 值塞进 JS 对象"不能靠 `js()` 拼字符串。
 * 这里只用两条绝对可靠的通道：
 *
 * 1. **结构化数据 → JSON 文本 → `JSON.parse`**：得到普通 JS 对象/数组，
 *    主题侧 `s.device.soc` 直接读，没有 `KString` 之类包装。
 *    状态每秒最多推两次、几十个字段，JSON 往返是微秒级。
 * 2. **函数 → 静态属性赋值**（`api.connect = ...`）：Kotlin lambda 自动转 JS 函数；
 *    动态键名赋值不可靠，所以动态键一律走第 1 条。
 */

/**
 * JS 全局 `JSON`。
 *
 * **名字必须与全局一致**：`external object Foo` 会被编译成对全局 `Foo` 的引用。
 * 这里曾误写成 `JsJson`，于是运行时抛 `ReferenceError: JsJson is not defined`，
 * 又被外层 runCatching 吞掉，表现成"数据层加载了但 window.Maibms 不存在"——极难查。
 */
private external object JSON {
    fun parse(text: String): dynamic
    fun stringify(value: dynamic): String
}

/** JSON 文本 → 普通 JS 对象（解析失败给空对象，主题页面不至于整个崩） */
internal fun parseJson(text: String): dynamic = try {
    JSON.parse(text)
} catch (e: Throwable) {
    js("console.error('[Maibms] JSON.parse 失败', e)")
    JSON.parse("{}")
}

/** JS 对象 → JSON 文本 */
internal fun toJsonText(value: dynamic): String = JSON.stringify(value)

/** 协程作用域的 launch（主题页的动作都在 MainScope 里跑，与渲染同一事件循环） */
internal fun CoroutineScope.launchRaw(block: suspend CoroutineScope.() -> Unit): Job = launch { block() }

/** 浏览器是否偏好深色（`themeMode = system` 时用） */
internal fun prefersDark(): Boolean =
    js("!!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)")
        .unsafeCast<Boolean>()

/** 生成 UUID（浏览器原生；主题侧可用它给表单项起 id） */
internal fun randomUuid(): String =
    if (js("!!(typeof crypto !== 'undefined' && crypto.randomUUID)").unsafeCast<Boolean>()) {
        js("crypto.randomUUID()").unsafeCast<String>()
    } else {
        js("'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36)").unsafeCast<String>()
    }