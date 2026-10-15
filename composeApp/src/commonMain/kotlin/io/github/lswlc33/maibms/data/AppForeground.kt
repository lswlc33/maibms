package io.github.lswlc33.maibms.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App 是否在前台。
 *
 * 平台侧写入（Android 在 `MaibmsApp` 里注册 `ActivityLifecycleCallbacks`），公共代码只读。
 * 这里刻意**不用 expect/actual**：默认 `true`，桌面与 iOS 不写就是"一直前台"，
 * 于是"退后台暂停轮询"这条策略天然只对 Android 生效，另外两端零改动、零风险
 * （iOS 侧连编译都无法在 Windows 上验证，不写就不会写错）。
 *
 * 用途：轮询循环据此决定退到后台是否停发请求（见 `BmsRepository.pollLoop`）。
 */
object AppForeground {
    private val _active = MutableStateFlow(true)

    /** true = 有界面在前台 */
    val active: StateFlow<Boolean> = _active

    fun setActive(value: Boolean) {
        _active.value = value
    }
}
