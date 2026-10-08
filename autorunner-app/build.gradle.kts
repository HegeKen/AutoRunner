import java.util.Base64
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project(":autorunner-core"))
            implementation(project(":autorunner-ui"))
            implementation(project(":autorunner-gamepad"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)

            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

// ---------------------------------------------------------------------------
// root-module：把 Magisk / KernelSU 模块按当前版本打包为 zip，并作为 assets 并入 APK。
// 复用 root-module/build.sh 以保持打包逻辑单一来源；产物落在 build/ 下，不污染源码树。
//
// 说明：AGP 9 起 SourceSet API 不再接受 Provider（无法判断生成/静态），必须改用
// Variant API 的 addGeneratedSourceDirectory 注册，才能自动携带任务依赖。
// ---------------------------------------------------------------------------
abstract class PackageRootModuleTask : DefaultTask() {
    /** root-module 目录（含 build.sh）。 */
    @get:Internal
    abstract val moduleDir: DirectoryProperty

    /** 参与打包的源文件，用于增量判断。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleFiles: ConfigurableFileCollection

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<String>

    /** 生成目录，其中的 autorunner_root.zip 会被作为 assets 并入 APK。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun packageModule() {
        val outFile = outputDir.get().file("autorunner_root.zip").asFile
        outFile.parentFile.mkdirs()
        execOperations.exec {
            // build.sh 内部会自行 cd 到脚本所在目录，因此无需设置工作目录。
            commandLine(
                "bash",
                moduleDir.get().file("build.sh").asFile.absolutePath,
                outFile.absolutePath,
            )
        }
    }
}

val rootModuleDir = rootProject.layout.projectDirectory.dir("root-module")

val buildRootModule = tasks.register<PackageRootModuleTask>("buildRootModule") {
    group = "build"
    description = "打包 root-module 并输出 build/generated/rootModuleAssets/autorunner_root.zip"
    moduleDir.set(rootModuleDir)
    moduleFiles.from(
        rootModuleDir.file("module.prop"),
        rootModuleDir.file("customize.sh"),
        rootModuleDir.file("service.sh"),
        rootModuleDir.file("uninstall.sh"),
        rootModuleDir.file("build.sh"),
        rootModuleDir.dir("scripts"),
    )
    versionName.set(providers.gradleProperty("autorunner.versionName"))
    versionCode.set(providers.gradleProperty("autorunner.versionCode"))
    outputDir.set(layout.buildDirectory.dir("generated/rootModuleAssets"))
}

android {
    namespace = "cn.helilab.autorunner"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "cn.helilab.autorunner"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("autorunner.versionCode").get().toInt()
        versionName = providers.gradleProperty("autorunner.versionName").get()

        // The adb debug command receiver only exists in debug builds.
        manifestPlaceholders["debugReceiverEnabled"] = "false"
    }

    buildTypes {
        getByName("debug") {
            manifestPlaceholders["debugReceiverEnabled"] = "true"
        }
    }

    // AutoRunner is a KMP module: Android sources live in src/androidMain.
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].assets.srcDirs("src/androidMain/assets")

    // A project local debug key is used instead of `~/.android/debug.keystore` so
    // that `assembleDebug` also works in sandboxed/CI environments where the home
    // directory is not writable. It is a throwaway key with the well known
    // "android" password and must never be used for a release build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // release 签名材料由环境变量注入（CI 里来自 GitHub Secrets），沿用仓库其他
        // 项目的命名：
        //   ANDROID_KEYSTORE_BASE64 / ANDROID_KEYSTORE_PASSWORD /
        //   ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD
        // 命中时把 base64 解码到 build 目录下的临时 keystore 并使用，避免把密钥写进仓库；
        // 本地未提供这些变量时回退到项目内 debug.keystore，保证 `assembleRelease` 仍能产出
        // 可安装的已签名 APK（仅供本地自测，切勿用于正式分发）。
        create("release") {
            val keystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
            if (!keystoreBase64.isNullOrBlank()) {
                val keystoreFile = layout.buildDirectory
                    .file("release-signing/keystore.jks")
                    .get()
                    .asFile
                keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getMimeDecoder().decode(keystoreBase64))
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // debug 复用 release 签名：设备上若装过 GitHub Release 包，debug 包只有签名一致
        // 才能原地覆盖安装，否则跨签名会报 INSTALL_FAILED_DUPLICATE_PERMISSION。
        // 未提供 ANDROID_KEYSTORE_* 变量时 release signingConfig 会回退到项目内
        // debug.keystore，因此本地无变量时的行为与以前完全一致。
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// 把生成的 root-module zip 目录注册为 assets 的「生成源目录」。
// Variant API 会自动建立 buildRootModule → 各打包任务 的任务依赖。
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            buildRootModule,
            PackageRootModuleTask::outputDir,
        )
    }
}
