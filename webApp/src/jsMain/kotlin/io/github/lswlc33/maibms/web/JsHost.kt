package io.github.lswlc33.maibms.web

/**
 * 动态属性访问的宿主桥（实现见 `docs/web/maibms-host.js`）。
 *
 * **为什么需要这一层**：Kotlin/JS 里"往 JS 对象上挂动态键"三条路都有坑——
 *  - `api.foo = x`（`dynamic` 静态名赋值）被编译成属性/设置器访问，本工程实测**静默丢弃**
 *    （对象存在、`Object.getOwnPropertyNames()` 却是空数组，且不报错）；
 *  - `js("(function(o,k,v){o[k]=v})")` 会被当**表达式求值**：编译期就把它调掉了，
 *    `o[k]=v` 根本不会出现在产物里，拿到的是 undefined；
 *  - `@JsFun` 在 Kotlin 2.1 的 stdlib 里是 internal，业务代码引不到。
 *
 * 所以把"动态写入"放进真正的手写 JS（`maibms-host.js` 里的 `MaibmsHost`），
 * Kotlin 侧只做 external 声明。几个函数足够小，不必为它引互操作库。
 *
 * **页面加载顺序**：先 `maibms-host.js`，再 `maibms-data.js`，最后主题脚本。
 */
private external object MaibmsHost {
    fun put(o: dynamic, k: String, v: Any?)
    fun get(o: dynamic, k: String): dynamic
    fun obj(): dynamic
    fun arr(): dynamic
    fun push(a: dynamic, v: Any?)
}

/** `o[k] = v` */
internal fun jsPut(o: dynamic, k: String, v: Any?) = MaibmsHost.put(o, k, v)

/** `o[k]` */
internal fun jsGet(o: dynamic, k: String): dynamic = MaibmsHost.get(o, k)

/** `{}` */
internal fun jsObject(): dynamic = MaibmsHost.obj()

/** `[]` */
internal fun jsArray(): dynamic = MaibmsHost.arr()

/** `a.push(v)` */
internal fun jsPush(a: dynamic, v: Any?) = MaibmsHost.push(a, v)

/** `window` */
internal fun jsWindow(): dynamic = js("window").unsafeCast<dynamic>()

/** 宿主是否就绪（缺 `maibms-host.js` 时给明确报错，而不是 undefined 崩） */
internal fun hostAvailable(): Boolean =
    js("!!(typeof MaibmsHost !== 'undefined')").unsafeCast<Boolean>()

/**
 * 装配 `window.Maibms`。幂等：已有对象就复用。
 *
 * @return 是否装配成功（宿主缺失时 false，主题页据此给出可读提示）
 */
internal fun installBridgeApi(): Boolean {
    if (!hostAvailable()) {
        js("console.error('[Maibms] 缺少 maibms-host.js：页面需先加载它，再加载 maibms-data.js')")
        return false
    }
    val win = jsWindow()
    val existing = jsGet(win, "Maibms")
    val api = if (existing == null || existing == undefined) jsObject() else existing
    installVersion(api)
    installStateAccess(api)
    installActions(api)
    jsPut(win, "Maibms", api)
    return true
}