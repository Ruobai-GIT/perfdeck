import java.io.File

rootProject.name = "perfdeck"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // 显式钉住所有插件版本：Gradle 才能确认 build 里多个插件（kotlin.jvm / kotlin.android /
    // kotlin.compose / serialization / AGP）来自同一版本族，避免 CI 上因 include(:app) 而报
    // "plugin already on the classpath with an unknown version"。
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.kotlin.android") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
        id("com.android.application") version "8.9.1"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":core")

// :app 需要 Android SDK 与 Google Maven。为了让 :core 在没有 Android 环境的机器上
// （例如手机内的 arm64 容器）依然可以独立构建与测试，这里按条件纳入 Android 模块。
// Android Studio 打开工程时会生成 local.properties，CI 上设置 ANDROID_HOME，
// 两者都会自动把 :app 纳入；也可以显式传 -PperfdeckIncludeApp=true。
val androidAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(rootDir, "local.properties").exists() ||
    providers.gradleProperty("perfdeckIncludeApp").orNull == "true"

if (androidAvailable) {
    include(":app")
} else {
    println("[perfdeck] 未检测到 Android SDK，跳过 :app（设置 ANDROID_HOME 或 -PperfdeckIncludeApp=true 可纳入）")
}
