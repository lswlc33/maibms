import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.application")
}

/**
 * 发布签名：本地读仓库根的 keystore.properties（不入库），CI 读环境变量
 * （工作流把 secrets 里的 keystore base64 解成文件后导出 SIGNING_KEYSTORE_FILE）。
 * 两边用的是同一个密钥，所以手动构建与自动构建的签名完全一致，可以直接覆盖安装。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingValue("storeFile", "SIGNING_KEYSTORE_FILE")
val releaseStorePassword: String? = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
val releaseKeyAlias: String? = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null &&
        rootProject.file(releaseStoreFile!!).exists()

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }
    jvm("desktop")

    // iOS：只做编译目标（Android 为第一交付平台，iOS 不做真机联调）。
    // framework 供将来接入 Xcode 壳工程用；CI 在 macOS runner 上跑 linkDebugFramework* 验证能编能链。
    // 同时编 arm64 真机与 arm64 模拟器两档（CI 的 macos-14 runner 是 Apple Silicon，模拟器档可直接链）
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    // 显式应用默认层级模板：iosMain 等中间源集因此**立刻**创建，下面的 sourceSets 块才能按名访问
    //（不调用的话模板在构建脚本求值之后才应用，配置期 getByName/named 都会报 not found）
    applyDefaultHierarchyTemplate()

    // 把 versionName/versionCode 注入 BuildConfig，应用内「关于/检查更新」读取
    // （Release 工作流自增 versionName 后，这里与代码不用再同步）
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.animation)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // 跨平台日期时间：commonMain 不能用 JVM 的 SimpleDateFormat/java.time
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                // 跨平台文件：日志落盘/导出统一走 okio，不在公共代码碰 java.io.File、
                // 也不用为 iOS 单独写 NSFileManager 互操作（Android/桌面/iOS 都有实现）
                implementation("com.squareup.okio:okio:3.9.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "io.github.lswlc33.maibms"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.lswlc33.maibms"
        minSdk = 26
        targetSdk = 35
        // CI 的 Release 工作流按「文件里第一处 versionCode/versionName」grep 自增——
        // 保持数字字面量写法，别改成变量引用（会打断自动发版）
        versionCode = 5
        versionName = "0.1.4"
        // 供给应用内「关于/检查更新」读取（commonMain 无法直接读 android.defaultConfig）
        buildConfigField("String", "APP_VERSION_NAME", "\"$versionName\"")
        buildConfigField("int", "APP_VERSION_CODE", "$versionCode")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // R8 混淆 + 资源收缩：未开时 release 包 6.4MB（Compose 运行时全量进 dex）；
            // kotlinx-serialization 与 Compose 1.7 都自带 consumer proguard 规则，无需手写
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 有签名材料就用发布签名（本地/CI 同一密钥），否则退回 debug 签名只为能跑起来
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.lswlc33.maibms.MainKt"
        // 打包用的图标（窗口图标在 Main.kt 里用 resources/app_icon.png 设置）。
        // 图标由 tools/icon/generate_icons.py 从 tools/icon/source.jpg 生成。
        nativeDistributions {
            windows {
                iconFile.set(layout.projectDirectory.file("src/desktopMain/resources/app.ico"))
            }
        }
    }
}

// 离屏截图：渲染真实 UI 到 build/shots/*.png（无需窗口/设备）
val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
tasks.register<JavaExec>("shot") {
    group = "verification"
    description = "Render the real UI to build/shots/*.png offscreen"
    dependsOn("desktopMainClasses")
    mainClass.set("io.github.lswlc33.maibms.ShotMainKt")
    classpath = files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    args = listOf(layout.buildDirectory.dir("shots").get().asFile.absolutePath)
}

// 桌面端与 iOS 都没有 AGP 的 BuildConfig：生成一个 Version.kt，让 AppVersion.actual 与 Android 同源。
// 版本值从上面 defaultConfig 的字面量解析（grep 同款正则），避免多处手改不同步
val nonAndroidVersionName = file("build.gradle.kts").readText()
    .let { Regex("versionName = \"([^\"]*)\"").find(it)!!.groupValues[1] }
val nonAndroidVersionCode = file("build.gradle.kts").readText()
    .let { Regex("versionCode = ([0-9]+)").find(it)!!.groupValues[1].toInt() }
val generatePlatformVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/platformVersion")
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("io/github/lswlc33/maibms/data")
        dir.mkdirs()
        dir.resolve("Version.kt").writeText(
            """
            |package io.github.lswlc33.maibms.data
            |
            |internal const val PLATFORM_VERSION_NAME = "$nonAndroidVersionName"
            |internal const val PLATFORM_VERSION_CODE = $nonAndroidVersionCode
            """.trimMargin()
        )
    }
}
kotlin.sourceSets.getByName("desktopMain") { kotlin.srcDir(generatePlatformVersion.map { it.outputs.files.first() }) }
// iOS 的源集（含 iosMain 中间源集）由默认层级模板**延迟创建**：配置期这里只能用 named() 惰性取，
// 直接 by getting / getByName 会报 "KotlinSourceSet with name 'iosMain' not found"。
// 生成目录挂在 iosMain 上，两个 iOS 目标（iosArm64Main / iosSimulatorArm64Main）经 dependsOn 继承
// iOS 的 iosMain 中间源集由 applyDefaultHierarchyTemplate() 显式创建（见上面的 kotlin 块）；
// 生成目录挂在它上面，两个 iOS 目标（iosArm64Main / iosSimulatorArm64Main）经 dependsOn 继承
kotlin.sourceSets.getByName("iosMain") {
    kotlin.srcDir(generatePlatformVersion.map { it.outputs.files.first() })
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generatePlatformVersion)
}
// 链接阶段（framework）也依赖生成文件：iOS 的 link 任务不在上面的 compile 前缀匹配里
tasks.matching { it.name.startsWith("link") && it.name.contains("Framework") }.configureEach {
    dependsOn(generatePlatformVersion)
}

