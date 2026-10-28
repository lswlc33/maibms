package io.github.lswlc33.maibms.data

/**
 * 应用版本（网页端）。
 *
 * 网页端没有"安装的版本"这回事——用户每次打开拿到的都是最新构建，因此
 * 「检查更新」在这里天然无意义（界面按 [PlatformInfo.canSelfUpdate] 隐藏入口）。
 * 这里报的版本号只是"这次构建对应的应用版本"，取构建时注入的值。
 */
actual object AppVersion {
    actual val name: String = WEB_BUILD_VERSION_NAME
    actual val code: Int = WEB_BUILD_VERSION_CODE

    /** 构建时刻（epoch 秒，由 Gradle 注入）——关于页显示"这份网页是什么时候生成的" */
    val builtAtEpochSec: Long = WEB_BUILD_EPOCH_SEC
}