import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// 应用版本来自根 gradle.properties 的单一来源，生成 BuildInfo.kt 供 commonMain 使用
// （UI 层无法读取 Android BuildConfig，故把版本常量下沉到 core 的公共源集）。
val autorunnerVersionName: String = providers.gradleProperty("autorunner.versionName").get()
val autorunnerVersionCode: String = providers.gradleProperty("autorunner.versionCode").get()
val buildInfoOutputDir = layout.buildDirectory.dir("generated/buildinfo/commonMain/kotlin")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "把 gradle.properties 中的应用版本固化为 BuildInfo.kt。"
    group = "build"
    inputs.property("versionName", autorunnerVersionName)
    inputs.property("versionCode", autorunnerVersionCode)
    outputs.dir(buildInfoOutputDir)
    doLast {
        val pkgDir = buildInfoOutputDir.get().asFile.resolve("com/autorunner/core")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildInfo.kt").writeText(
            buildString {
                appendLine("// 由 autorunner-core 的 generateBuildInfo 任务生成，请勿手动编辑。")
                appendLine("// 版本来源：根 gradle.properties 的 autorunner.versionName / autorunner.versionCode。")
                appendLine("package com.autorunner.core")
                appendLine()
                appendLine("object BuildInfo {")
                appendLine("    const val VERSION_NAME: String = \"$autorunnerVersionName\"")
                appendLine("    const val VERSION_CODE: Int = $autorunnerVersionCode")
                appendLine("}")
            },
        )
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.autorunner.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        // commonTest 同时作为 Android 主机测试运行（不只是 desktop 目标），
        // 消除「commonTest 存在但未启用 android host test」的构建告警。
        withHostTest { }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        commonMain {
            // TaskProvider 会自动建立任务依赖；生成的目录位于 build/ 下，已被 .gitignore 忽略。
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}
