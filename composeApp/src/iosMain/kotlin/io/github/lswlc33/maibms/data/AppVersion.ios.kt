package io.github.lswlc33.maibms.data

/**
 * iOS：版本来自 Gradle 生成并注入 iosMain source set 的 Version.kt
 * （与桌面端同一份实现，见 build.gradle.kts 的 generatePlatformVersion）。
 */
actual object AppVersion {
    actual val name: String = PLATFORM_VERSION_NAME
    actual val code: Int = PLATFORM_VERSION_CODE
}
