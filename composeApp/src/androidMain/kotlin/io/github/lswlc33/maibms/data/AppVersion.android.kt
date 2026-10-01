package io.github.lswlc33.maibms.data

import io.github.lswlc33.maibms.BuildConfig

/** Android：版本来自 Gradle 注入的 BuildConfig（Release 工作流自增后自动跟随） */
actual object AppVersion {
    actual val name: String = BuildConfig.APP_VERSION_NAME
    actual val code: Int = BuildConfig.APP_VERSION_CODE
}
