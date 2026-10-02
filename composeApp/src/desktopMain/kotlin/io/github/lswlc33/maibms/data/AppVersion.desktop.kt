package io.github.lswlc33.maibms.data

/**
 * 桌面端与 iOS 都没有 AGP 的 BuildConfig，由 Gradle 在编译时生成 Version.kt
 * （composeApp/build.gradle.kts 的 generatePlatformVersion 任务，desktopMain 与 iosMain 共用）。
 */
actual object AppVersion {
    actual val name: String = PLATFORM_VERSION_NAME
    actual val code: Int = PLATFORM_VERSION_CODE
}
