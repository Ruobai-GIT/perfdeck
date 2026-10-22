// 根构建脚本：所有插件（AGP + Kotlin 全家）在这里 apply false 统一声明。
// 关键：AGP 必须和 KGP 在同一个 classpath 中，否则 :app 中 kotlin.android
// 装饰 KotlinAndroidTarget 时找不到 com/android/build/gradle/api/BaseVariant
// （实测 :app:help 在本地/CI 均以该错误崩溃）。把 AGP 也放根 apply false 后，
// Gradle 会把 AGP 与 KGP 放到同一个 plugin classpath，BaseVariant 可见。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}