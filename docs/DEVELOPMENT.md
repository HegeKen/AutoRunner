# 开发环境与构建

## 1. 工具链

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | **21** | `kotlin.jvmToolchain(21)`，Android `compileOptions` 同样为 21 |
| Gradle | **9.4.1** | 通过 `./gradlew` 使用（`gradle/wrapper/gradle-wrapper.properties`） |
| Android Gradle Plugin | 8.13.2 | `gradle/libs.versions.toml` |
| Kotlin | 2.3.20 | KMP + `kotlin.plugin.serialization` + `kotlin.plugin.compose` |
| Compose Multiplatform | 1.10.3 | UI 与 adaptive |
| MIUIX | 0.9.4 | `miuix-ui` / `miuix-preference` / `miuix-icons` / `miuix-blur` |
| Android SDK | compileSdk 37、targetSdk 36、minSdk 28 | `local.properties` 中的 `sdk.dir` |

> `compileSdk` 必须为 37：MIUIX 0.9.4 与 `androidx.compose.material3.adaptive` 的 AAR
> 元数据要求调用方至少编译到 API 37。

## 2. 依赖版本约束（重要）

AGP 8.13.2 无法满足部分最新 AndroidX 工件声明的 `minAgpVersion`，因此版本目录中锁定了：

| 依赖 | 版本 | 原因 |
|------|------|------|
| `androidx.core:core-ktx` | 1.16.0 | 1.19.x 要求 AGP ≥ 9.1.0 |
| `org.jetbrains.compose.material3.adaptive:adaptive` | 1.2.0 | 1.3.0-alpha07 解析到 androidx adaptive 1.3.0-alpha10，要求 AGP ≥ 9.1.0 |

若将来升级到 AGP 9.x，需要同时迁移到 `com.android.kotlin.multiplatform.library` 插件与
`androidLibrary { }` DSL，并重新评估 `androidTarget()` 的用法。

## 3. 构建命令

```bash
export JAVA_HOME=/path/to/jdk-21

# 全部单元测试（无需设备）
./gradlew :autorunner-core:desktopTest :autorunner-gamepad:desktopTest :autorunner-ui:desktopTest

# 编译 Android 目标
./gradlew :autorunner-app:assembleDebug

# 仅编译（快速验证）
./gradlew :autorunner-core:compileKotlinDesktop :autorunner-ui:compileDebugKotlinAndroid
```

## 4. 沙箱 / CI 注意事项

* **`GRADLE_USER_HOME` 必须可写。** 若 `~/.gradle` 不可写（容器、受限沙箱），请显式指定：

  ```bash
  export GRADLE_USER_HOME=/tmp/autorunner-gh
  ```

* **Kotlin 编译进程。** 某些受限环境无法启动 Kotlin daemon，`gradle.properties` 已设置
  `kotlin.compiler.execution.strategy=in-process` 回退到进程内编译。

* **调试签名。** `autorunner-app/debug.keystore` 是工程内的临时调试密钥
  （`storePassword=android`、`alias=androiddebugkey`），用于在 `~/.android` 不可写的环境中
  也能完成 `assembleDebug`。**切勿用于正式发布。**

* **Gradle Wrapper 分发。** `gradle-wrapper.properties` 指向官方 `gradle-9.4.1-bin.zip`。
  若网络受限，可把本地 Gradle 发行版放进
  `$GRADLE_USER_HOME/wrapper/dists/gradle-9.4.1-bin/<hash>/` 并使用现成的 `bin/` 目录。

## 5. 目录约定

| 目录 | 内容 |
|------|------|
| `autorunner-*/src/commonMain` | 平台无关代码（Kotlin Multiplatform） |
| `autorunner-*/src/androidMain` | Android 平台实现（expect/actual，Java/Kotlin） |
| `autorunner-*/src/desktopMain` | JVM 实现（仅测试与预览） |
| `autorunner-*/src/commonTest` | 跨平台单元测试（在 JVM 上执行） |
| `autorunner-app/src/androidMain/res` | Android 资源（通过 `sourceSets["main"]` 重定向） |

> KMP 模块使用 `jvm("desktop")` 生成 `desktopMain` / `desktopTest` 源集，因此桌面变体在
> Gradle 中的任务名是 `compileKotlinDesktop`、`desktopTest` 等。

## 6. 代码风格

* 所有公开声明都有 KDoc；涉及设计文档的类注明章节（如 “§6.3 单次与重复执行”）。
* 用户可见字符串为中文，标识符为英文。
* 平台差异一律通过 `expect`/`actual` 或接口注入表达，`commonMain` 中不出现 Android 类型。
* 不要在校验通过前提交：至少跑通第 3 节的全部命令。

## 7. 调试技巧

```bash
# 查看无障碍服务是否已连接
adb shell settings get secure enabled_accessibility_services

# 查看悬浮窗权限
adb shell appops get cn.helilab.autorunner SYSTEM_ALERT_WINDOW

# 直接推送一个脚本到应用私有目录（需 run-as 或 debug 包）
adb shell run-as cn.helilab.autorunner ls files/scripts
```

日志 TAG 统一为 `AutoRunner`（见 `AutoRunnerApplication.TAG`）。

---

## 调试

实机/模拟器的编译安装、调试广播通道、MIUI/HyperOS 限制与对策、Root 模式与模块排查，
统一整理在 **[DEBUGGING.md](DEBUGGING.md)**。
