package io.github.lswlc33.maibms.data

/**
 * 平台时钟底层（JS 互操作）：直接从浏览器取，不引 kotlinx-datetime 的 Clock，
 * 免得在这条最热的路径上多一层间接。
 */
internal fun performanceNow(): Long =
    js("(typeof performance !== 'undefined' ? performance.now() : Date.now())").unsafeCast<Double>().toLong()

/**
 * 页面开始加载那一刻，在 [performanceNow] 时间基准上的读数。
 *
 * `performance.now()` 的 0 点**就是** `performance.timeOrigin`（页面开始导航的时刻），
 * 所以这里恒为 0——语义上与 Android 用 `Process.getStartUptimeMillis()` 拿到的"系统记录的
 * 进程创建时刻"等价：首屏计时都是"从这个真实起点到上屏"的耗时，而不是我们自己随手打的点。
 *
 * 缺少 `performance` 的极老浏览器返回 null（启动计时退化成 arm 时刻）。
 */
internal fun pageLoadUptimeMs(): Long? =
    if (js("typeof performance !== 'undefined' && !!performance.now").unsafeCast<Boolean>()) 0L else null