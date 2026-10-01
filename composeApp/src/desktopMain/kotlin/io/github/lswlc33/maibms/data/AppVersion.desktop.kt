package io.github.lswlc33.maibms.data

/**
 * 桌面端：没有 AGP 的 BuildConfig，由 Gradle 在编译时生成 Version.kt
 * （composeApp/build.gradle.kts 的 desktopMain source set 任务注入同名值）。
 */
actual object AppVersion {
    actual val name: String = DESKTOP_VERSION_NAME
    actual val code: Int = DESKTOP_VERSION_CODE
}
