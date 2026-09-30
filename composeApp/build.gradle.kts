import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
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

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.animation)
                implementation(compose.components.resources)
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
        versionCode = 2
        versionName = "0.1.1"
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
            isMinifyEnabled = false
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
