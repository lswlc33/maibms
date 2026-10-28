plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

/**
 * 网页端（安卓 Chrome + Web Bluetooth）：**复用 composeApp 的引擎源码**（data/protocol/transport），
 * 配一层自写的 DOM 界面。
 *
 * 产物直接落进 `docs/`（GitHub Pages 的发布目录就是它），页面名 `web.html`——
 * 用户可见地址是 `<官网>/web.html`，与官网首页同目录，所以资源都带 `maibms-web-` 前缀避免撞名。
 *
 * 为什么是"共用源码目录"而不是"依赖 composeApp"：
 *  - composeApp 是 com.android.application 模块，Gradle 不允许别的模块依赖它；
 *  - 引擎里的 `internal` 声明只对同模块可见，另开模块就必须把它重构成独立 core 模块
 *    （那会牵动 Android/iOS/桌面的既有构建，收益不抵风险）。
 * 于是这里把引擎的三个包目录直接挂进本模块源码集——编译产物在 webApp/build 下，
 * 与 composeApp 互不干扰，既有平台一行都不用改。
 *
 * 用 DOM 而不是 Compose-for-Web，理由见 memory:web-target-architecture
 * （Compose 1.7.3 的 Web 端没有中文回退字体＝满屏豆腐块，且 Skiko 运行时 8.3MB，
 * 与控制台"打开即用、不用下载东西"的诉求相冲）。
 */

/* ---------- 版本号与构建时刻（在 kotlin{} 之前注册，供源码集引用） ---------- */

/**
 * 版本号来源与 composeApp 完全一致（正则取第一处字面量），所以 Web 端「关于」显示的版本
 * 与同期 APK/ipa 对得上；构建时刻让用户知道"这份网页是什么时候生成的"。
 */
val webBuildVersionName: String = rootProject.file("composeApp/build.gradle.kts").readText()
    .let { Regex("versionName = \"([^\"]*)\"").find(it)!!.groupValues[1] }
val webBuildVersionCode: Int = rootProject.file("composeApp/build.gradle.kts").readText()
    .let { Regex("versionCode = ([0-9]+)").find(it)!!.groupValues[1].toInt() }

val generateWebBuild: TaskProvider<Task> = tasks.register("generateWebBuild") {
    val outDir = layout.buildDirectory.dir("generated/webBuild")
    val builtAt = System.currentTimeMillis() / 1000
    // 构建时刻参与输入：每次构建都重新生成（否则增量构建会一直显示旧的生成时间）
    inputs.property("versionName", webBuildVersionName)
    inputs.property("builtAt", builtAt)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("io/github/lswlc33/maibms/data")
        dir.mkdirs()
        dir.resolve("WebBuild.kt").writeText(
            """
            |package io.github.lswlc33.maibms.data
            |
            |// 由 webApp/build.gradle.kts 生成：Web 端版本号与构建时刻
            |internal const val WEB_BUILD_VERSION_NAME: String = "$webBuildVersionName"
            |internal const val WEB_BUILD_VERSION_CODE: Int = $webBuildVersionCode
            |internal const val WEB_BUILD_EPOCH_SEC: Long = ${builtAt}L
            """.trimMargin()
        )
    }
}

kotlin {
    js(IR) {
        moduleName = "maibms-web"
        browser {
            commonWebpackConfig {
                outputFileName = "maibms-data.js"
                // 关键：不要 library 包装。
                // KGP 默认会把产物打成 UMD library（output.library/libraryTarget），
                // 那样 bundle **只导出模块、不执行 main()** —— 页面里 window.Maibms 会是
                // undefined，看起来像"数据层没加载"，极难查。这里清掉 library 相关设置，
                // 让 main() 随脚本加载立即执行，数据层自动把 window.Maibms 挂好。
                output?.library = null
                output?.libraryTarget = null
                output?.globalObject = "globalThis"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val engineMain by creating {
            // 引擎源码：与 composeApp/src/commonMain 是**同一批文件**（不是副本），
            // 协议改动天然同步；只挂这三个包目录，从而排除 ui/ 与各平台 actual。
            kotlin.srcDir("../composeApp/src/commonMain/kotlin/io/github/lswlc33/maibms/data")
            kotlin.srcDir("../composeApp/src/commonMain/kotlin/io/github/lswlc33/maibms/protocol")
            kotlin.srcDir("../composeApp/src/commonMain/kotlin/io/github/lswlc33/maibms/transport")
        }

        // jsMain 由 js target 创建：用 named() 惰性取（by getting 在配置期会报 not found）
        named("jsMain") {
            dependsOn(engineMain)
            kotlin.srcDir(generateWebBuild.map { it.outputs.files.first() })
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                // 显式声明 DOM 绑定（kotlinx.browser.* 与 org.w3c.dom.*）：
                // stdlib-js 不再自带全套 DOM 声明，靠这个 compat 包提供
                implementation("org.jetbrains.kotlin:kotlin-dom-api-compat:2.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("com.squareup.okio:okio:3.9.0")
                // 不引 kotlinx-browser：`kotlinx.browser.window/document` 与 org.w3c.dom 类型
                // 本来就在 kotlin-stdlib-js 里。而且它的 0.5.0 会把 stdlib 拉到 2.2.20-Beta2，
                // 2.1.0 的序列化编译器插件撞上一个内部 NPE（generateSerializerImplClass）——
                // 网页端直接编译不过，报的是 AppStore.kt 这种无辜文件，很难查。
            }
        }
    }
}

/** 生成目录必须在编译/打包之前就绪 */
tasks.matching { it.name.startsWith("compile") || it.name.startsWith("js") || it.name.startsWith("webpack") }
    .configureEach { dependsOn(generateWebBuild) }
/**
 * 把**数据层**产物同步进 `docs/`。
 *
 * 网页端现在是"一个数据层 + 多套主题页"的结构：
 *   docs/maibms-data.js   ← Kotlin/JS 编译产物（BLE + 协议 + 状态桥），由本任务生成
 *   docs/web/index.html   ← 默认主题（纯静态，手写）
 *   docs/web/mdui.html    ← mdui 主题（纯静态，手写）
 *   docs/web/switch.html  ← 主题切换页（纯静态，手写）
 *
 * 主题页是**手写静态文件**，不经过构建；只有 maibms-data.js 是产物。
 * 这样加一套主题不用重新编译 Kotlin，也不必碰 Gradle。
 *
 * 不拷 .map（体积大且对用户无用）。
 */
val syncWebToDocs by tasks.registering(Copy::class) {
    group = "distribution"
    description = "把数据层产物同步到 docs/（GitHub Pages 发布目录）"
    val distDir = layout.buildDirectory.dir("dist/js/productionExecutable")
    dependsOn("jsBrowserDistribution")
    from(distDir)
    into(rootProject.layout.projectDirectory.dir("docs"))
    include("maibms-data.js")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doLast {
        val docs = rootProject.layout.projectDirectory.dir("docs").asFile
        // 旧命名（单页时代）留下的产物要清掉：它们会和现在的数据层同名混淆
        listOf("web.html", "maibms-web.js", "maibms-web.css", "app.css", "icon.png", "maibms-web-icon.png")
            .forEach { stale -> docs.resolve(stale).takeIf { it.exists() }?.delete() }
        val data = docs.resolve("maibms-data.js")
        require(data.exists()) { "同步失败：docs/maibms-data.js 不存在" }
        val webDir = docs.resolve("web")
        // 主题页是手工维护的静态文件，构建不生成它们——但要在同步时确认它们仍在
        listOf("index.html", "mdui.html", "daisy.html", "switch.html").forEach { name ->
            require(webDir.resolve(name).exists()) { "缺少主题页 docs/web/$name" }
        }
        println("数据层已同步 → docs/maibms-data.js（${data.length() / 1024} KB）")
    }
}
