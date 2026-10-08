# AutoRunner

原生 Android 自动化操作录制与回放应用，基于 **Kotlin Multiplatform + Compose Multiplatform**，
UI 全面采用 **MIUIX**（小米 HyperOS 设计语言）。

| 项目 | 值 |
|------|-----|
| 应用名称 | AutoRunner |
| 应用 ID / 包名 | `cn.helilab.autorunner` |
| 模块前缀 | `autorunner-` |
| 脚本扩展名 | `.arscript`（内部 JSON） |
| 无障碍服务类 | `AutoRunnerAccessibilityService` |
| 悬浮窗服务类 | `AutoRunnerOverlayService` |
| 前台通知渠道 | `autorunner_execution` |
| 脚本执行引擎 | `AutoRunnerScriptExecutor` |

---

## 一、功能概览

| 功能 | 说明 | 主要实现 |
|------|------|---------|
| 手势录制 | 透明 `TYPE_ACCESSIBILITY_OVERLAY` 捕获层，识别点击 / 长按 / 滑动 / 多点触控 | `AutoRunnerAccessibilityService`、`TouchCaptureView`、`GestureAnalyzer` |
| 手势回放 | `dispatchGesture(GestureDescription)`，逐条动作回放 | `AndroidAccessibilityController` |
| 脚本格式 | `.arscript`（JSON），含 `version` / `info` / `execution` / `flow` | `ScriptModel`、`ArScriptCodec` |
| 可视化编辑器 | 动作增删改排序、参数编辑、坐标百分比换算、实时校验、导入导出 | `ScriptEditorScreen`、`ScriptEditorViewModel` |
| 单次 / 重复执行 | `mode=once` 或 `mode=repeat` + `repeatCount`（`0` = 无限）+ `intervalMs` | `AutoRunnerScriptExecutor`、`ExecutionController` |
| 进度反馈 | 循环次数、当前动作、已运行时长、状态，同步到悬浮窗与通知栏 | `ExecutionProgress`、`ExecutionNotifications` |
| 悬浮控制面板 | 可自由拖动悬浮球 + 展开面板：录制、脚本选择、运行/暂停/停止、重复次数输入 | `AutoRunnerOverlayService`、`FloatingControlPanel` |
| 多设备适配 | WindowSizeClass 驱动，手机底部导航 / 平板侧边导航，List-Detail 与 Supporting Pane | `AutoRunnerApp`、`rememberAutoRunnerWindowSize` |
| 主题 | 仅浅色 / 深色 / 跟随系统三档（无 Monet 动态取色、无自定义种子色） | `AutoRunnerTheme` |
| 权限适配 | 无障碍、悬浮窗、通知 + 忽略电池优化，含 MIUI / ColorOS / OriginOS / EMUI 引导；缺权限时列表页浮标与设置首页摘要卡直接列出缺失项 | `AndroidPermissionController`、`PermissionFab`、`MissingPermissionsCard` |
| Root 注入（可选） | 设置 → 执行 可切换「无障碍服务 / Root 注入」；Root 下由 `su -c input` 注入，不依赖无障碍；配套 Magisk/KernelSU 模块（开机自动开无障碍 + 默认授权 + 注入守护进程），未装模块时可从应用内导出 zip 到下载目录 | `AndroidRootInputBackend`、`root-module/` |
| 到位再执行 | 点「运行 / 开始录制」→ 自动回到桌面并显示悬浮窗（此时尚未开始）→ 用户打开目标页面后**单击悬浮球**才开始（图标变为停止），跑完自动结束且不返回应用；长按悬浮球展开面板 | `ExecutionViewModel.armAndGoHome`、`RecordingViewModel.armAndGoHome`、`FloatingControlPanel` |
| 多重停止入口 | 采集期间屏幕常驻「停止录制」胶囊 + 悬浮球单击 + 通知栏「停止录制」；运行中可由悬浮球/面板/通知停止 | `TouchCaptureView`、`ExecutionNotifications` |
| 手柄模拟（可选） | 把脚本中的手柄按键**映射为本机触摸/摇杆拖动**，直接注入当前设备；按键在界面上按脚本的手柄类型显示（§7.8） | `autorunner-gamepad` 模块、`LocalGamepadGateway` |

---

## 二、模块结构

```
AutoRunner/
├── autorunner-core/       平台无关核心（无 Compose 依赖）
│   ├── commonMain         脚本模型、JSON 编解码、校验、手势分类、执行状态机、设置、仓储
│   ├── androidMain        SharedPreferences / 内部存储 / 时间（actual 实现）
│   ├── desktopMain        JVM 对应实现（预览与测试用）
│   └── commonTest         53 个单元测试
├── autorunner-gamepad/    手柄「本机注入」模块（LocalGamepadGateway，无蓝牙依赖）
├── autorunner-ui/         Compose Multiplatform + MIUIX 界面层
│   ├── theme              MiuixTheme / ThemeController / 固定品牌色 #2655FF / Dimens 令牌
│   ├── adaptive           WindowSizeClass → 布局模式
│   ├── components         设计系统基座（弹窗、脚手架、设置行、数值输入…）
│   ├── overlay            悬浮控制面板内容（App 内与悬浮窗共用）
│   ├── screens            脚本列表 / 编辑器 / 录制 / 设置 / 应用外壳
│   ├── viewmodel          平台无关 ViewModel（StateFlow）
│   └── commonTest         17 个测试（ViewModel + 组件规则）
└── autorunner-app/        Android 应用（KMP androidTarget + com.android.application）
    └── src/androidMain    AndroidManifest、资源、无障碍服务、悬浮窗服务、权限、SAF
```

依赖方向：`app → ui → core`，`app → gamepad → core`。`autorunner-ui` 不依赖 gamepad，
手柄能力通过 `GamepadStatusProvider` / `GamepadGateway` 接口在 app 层注入。

---

## 三、快速开始

### 环境要求

| 要求 | 版本 |
|------|------|
| JDK | 21（`jvmToolchain(21)`） |
| Gradle | 9.4.1（随 wrapper 提供） |
| Android compileSdk / targetSdk | 37 / 36 |
| Android minSdk | 28（Android 9） |
| Kotlin | 2.4.20 |
| Compose Multiplatform | 1.12.1 |
| MIUIX | 0.9.4 |
| Android Gradle Plugin | 9.2.1（临时兼容开关见 `gradle.properties`） |

### 构建与测试

```bash
# 单元测试（JVM/desktop target，无需设备）
./gradlew :autorunner-core:desktopTest :autorunner-gamepad:desktopTest :autorunner-ui:desktopTest

# 编译全部 Android 代码（debug）
./gradlew :autorunner-app:assembleDebug
# 产物：autorunner-app/build/outputs/apk/debug/autorunner-app-debug.apk

# 生成 release APK
./gradlew :autorunner-app:assembleRelease
# 产物：autorunner-app/build/outputs/apk/release/autorunner-app-release-unsigned.apk
```

> release 构建默认未配置签名（见 `autorunner-app/build.gradle.kts` 的 `signingConfigs`，其中只声明了 `debug`），
> 因此 `assembleRelease` 产出的是**未签名** APK，需自行签名后才能安装，见下文「编译到实机（release）」。

`local.properties` 需要指向本机 Android SDK：

```properties
sdk.dir=/path/to/Android/sdk
```

更多环境细节（含沙箱 / CI 下的 `GRADLE_USER_HOME` 处理）见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

### 首次使用

1. 打开应用 → 「设置」→ 开启 **无障碍服务**（设置 → 无障碍 → AutoRunner 自动化服务）。
2. 授予 **悬浮窗权限**（显示在其他应用上层），并关闭「每次使用时询问」。
3. 允许 **通知权限**（Android 13+），用于前台执行通知。
4. 进入「录制」页开始录制，或「脚本」页导入已有的 `.arscript` 文件。
5. 运行脚本：列表项上的 ▶、悬浮窗面板，或脚本编辑器的保存后运行。

> 首次启动会自动导入内置示例脚本（如 `ar_paging_test.arscript`，见
> `autorunner-app/src/androidMain/assets/samples/`），可直接在「脚本」页打开、运行或另存修改，
> 用于快速了解 `.arscript` 的写法。该导入只在首次启动执行一次。

---

### 编译到实机（debug）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew :autorunner-app:installDebug                 # 编译并装到当前设备
# 多设备/指定设备：
adb -s <serial> install -r autorunner-app/build/outputs/apk/debug/autorunner-app-debug.apk
adb -s <serial> shell am start -n cn.helilab.autorunner/.MainActivity --es destination scripts
```

### 编译到实机（release）

`assembleRelease` 会直接产出已签名 APK：

- **本地**：未提供签名环境变量时回退到项目内 `debug.keystore`，仅用于本地自测，切勿对外分发。
- **CI / 正式发布**：由 GitHub Secrets 注入真实 keystore，产出正式签名 APK。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew :autorunner-app:assembleRelease
# 产物：autorunner-app/build/outputs/apk/release/autorunner-app-release.apk
adb -s <serial> install -r autorunner-app/build/outputs/apk/release/autorunner-app-release.apk
```

#### 正式签名（CI）

[.github/workflows/android-release.yml](.github/workflows/android-release.yml) 在手动触发或推送
`v*` tag 时构建并上传已签名 APK。需在仓库 **Settings → Secrets and variables → Actions**
添加以下 Secrets（沿用本项目其他仓库的命名；GitHub Secrets 按仓库隔离，需在本仓库重新配置）：

| Secret | 说明 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64，如 `base64 -i release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥口令 |

[autorunner-app/build.gradle.kts](autorunner-app/build.gradle.kts) 的 `release` signingConfig 在检测到
`ANDROID_KEYSTORE_BASE64` 时，会将其解码到 `build/release-signing/keystore.jks` 并用于签名；
否则回退到 `debug.keystore`。本地如需用真实密钥签名，导出这四个环境变量后运行 `assembleRelease` 即可。

调试通道（状态查询、直接运行、拉起导入、悬浮窗控制、注入点击等）、MIUI/HyperOS 的坑与对策、
模拟器尺寸/朝向模拟、以及 Root 模式与 Magisk/KernelSU 模块的排查方法，见
**[docs/DEBUGGING.md](docs/DEBUGGING.md)**。

## 四、脚本格式（`.arscript`）

```json
{
  "version": "1.0",
  "info": {
    "name": "示例脚本",
    "description": "录制于 2026-01-15",
    "device": { "width": 1080, "height": 2400, "density": 2.75 },
    "createdAt": "2026-01-15T10:30:00Z",
    "coordinateSpace": "absolute",
    "tags": []
  },
  "execution": {
    "mode": "repeat",
    "repeatCount": 10,
    "intervalMs": 2000,
    "failureStrategy": "abort",
    "reconnectTimeoutMs": 15000
  },
  "flow": [
    { "type": "tap", "x": 540, "y": 1200, "duration": 50, "delay": 500 },
    { "type": "swipe", "fromX": 540, "fromY": 1800, "toX": 540, "toY": 600, "duration": 300, "delay": 200 },
    { "type": "longPress", "x": 540, "y": 1200, "duration": 1500, "delay": 300 },
    { "type": "multiTouch", "points": [{"x": 400, "y": 1200}, {"x": 680, "y": 1200, "startOffset": 0}], "duration": 300, "delay": 300 },
    { "type": "gamepad", "button": "A", "action": "press", "delay": 300 },
    { "type": "delay", "duration": 1000, "delay": 0 }
  ]
}
```

* `flow[].type` 为多态判别字段：`tap` / `longPress` / `swipe` / `multiTouch` / `gamepad` / `delay`。
* `execution.mode`：`once` 单次执行 / `repeat` 重复执行；`repeatCount` 为 `0` 表示无限循环。
* `info.coordinateSpace`：`absolute`（录制设备像素）或 `normalized`（`0.0~1.0` 比例），
  编辑器可在两者之间换算，换算后脚本可在不同分辨率设备上复用。
* 完整规范、字段表与校验规则见 [docs/SCRIPT_FORMAT.md](docs/SCRIPT_FORMAT.md)。

---

## 五、多设备适配

| 窗口尺寸类别 | 断点 | 导航 | 布局 |
|-------------|------|------|------|
| compact | < 600dp | `NavigationBar` 底部导航 | 分步导航（列表 → 编辑器） |
| medium | ≥ 600dp | `NavigationRail` 侧边导航 | 列表/编辑同屏过渡 |
| expanded | ≥ 840dp | `NavigationRail` | List-Detail（脚本列表 + 编辑器）、Supporting Pane（编辑 + 预览/校验） |

* 断点来源：`androidx.compose.material3.adaptive.currentWindowAdaptiveInfo().windowSizeClass`。
* 顶部应用栏在窗口高度不足时自动隐藏（`isHeightAtLeastBreakpoint(HEIGHT_DP_MEDIUM_LOWER_BOUND)`）。
* `MainActivity` 声明了 `configChanges` 且未锁定方向，`android:resizeableActivity="true"`，
  可自由缩放/旋转/折叠展开 —— 满足 Android 17 对可调整大小与方向变化的强制要求。
* MIUIX `Scaffold` 自动处理状态栏、导航栏与刘海 insets，代码中无需手动处理。

---

## 六、权限说明

| 权限 | 用途 | 申请方式 |
|------|------|---------|
| `BIND_ACCESSIBILITY_SERVICE` | 手势录制与回放 | 系统设置引导（`ACTION_ACCESSIBILITY_SETTINGS`） |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗显示 | `ACTION_MANAGE_OVERLAY_PERMISSION` + `Settings.canDrawOverlays` |
| `FOREGROUND_SERVICE(_SPECIAL_USE)` | 悬浮窗前台服务 | 运行时自动（`startForeground`） |
| `POST_NOTIFICATIONS` | 执行进度通知 | 运行时申请（API 33+） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 长时间循环不被回收 | 设置引导 |
| `VIBRATE` / `WAKE_LOCK` | 触感反馈、执行期间保持唤醒 | 自动 |

> 手柄模拟**不需要任何蓝牙权限**：它不模拟蓝牙外设，而是把按键位置映射成本机触摸（见 §7.6）。

厂商 ROM 差异（小米 / OPPO / vivo / 华为）的自启动、省电策略、后台弹出界面、悬浮窗路径
在设置页以引导卡片形式给出，见 `AndroidPermissionController.oemGuidance()`。

---

## 七、关键实现说明

### 7.1 录制时同步回放（触摸镜像）

`TYPE_ACCESSIBILITY_OVERLAY` 捕获层必然消费触摸事件，若不加处理，被录制应用在录制过程中
将不再响应。AutoRunner 在每次手势识别完成后立即用 `dispatchGesture` 把该手势回放一次，
使录制过程所见即所得。实现见 `AndroidRecordingController.onTouchEvent`。

为避免"回放被再次录制"的自环，捕获层带两道闸门：

1. **输入设备过滤**：只接受真实触摸屏（`InputDevice.SOURCE_TOUCHSCREEN`）的帧；
2. **镜像在途抑制**：自身 `dispatchGesture` 正在派发期间到达的帧一律丢弃。

> **为什么不用 `AccessibilityServiceInfo.setMotionEventSources`**
> 该 API 表面上能"只监听不拦截"，但平台文档明确写着
> *"MotionEvents from sources in `getMotionEventSources()` are not sent to the rest of the
> system"* —— 一旦在 `onServiceConnected()` 里请求 `SOURCE_TOUCHSCREEN`，服务会吞掉整机触摸，
> 用户授权无障碍后屏幕立即完全失去响应（实测踩过该坑）。因此录制**只使用**设计文档 §6.1.1
> 描述的透明覆盖层方案：捕获层是一个窗口，窗口命中逻辑仍然生效，悬浮面板（`TYPE_APPLICATION_OVERLAY`，
> 层级更高）始终可点，用户总能用它停止录制。

### 7.2 录制期间如何停止

捕获层会挡住其下方的一切窗口（包括 AutoRunner 自己的主界面），因此：

* 开始录制时若未授予悬浮窗权限，录制会被**拒绝启动**（避免把设备锁死在录制状态）；
* 开始录制时自动显示并展开悬浮控制面板，面板中的「停止录制 / 停止」始终可用；
* 服务重连时会强制卸载残留捕获层，避免异常退出后屏幕被持续占用。

### 7.3 服务被回收后的恢复

* 悬浮窗以前台服务运行，并持续更新 `autorunner_execution` 通知，降低被回收概率；
* 执行器在捕获到可恢复失败（服务断开）时，按 `reconnectTimeoutMs` 轮询等待服务重连，
  重连成功后**重试当前动作**，保持执行位置不丢失（`AutoRunnerScriptExecutor.awaitServiceReconnect`）。

### 7.4 循环间隔不忙等待

暂停通过 `state.first { it != PAUSED }` 挂起协程，循环间隔与动作延迟通过
`ExecutionClock.delay()`（生产环境为 `kotlinx.coroutines.delay`）实现，CPU 占用与耗电最低。

### 7.5 手柄模拟 = 本机输入（不是蓝牙外设）

设计文档 §7 最初建议用 `BluetoothHidDevice` 把手机模拟成手柄，但那是把按键**输出到其他设备**（PC／主机）。
AutoRunner 的场景是驱动**当前设备**上的游戏，因此实现改为本机注入：

* 每个手柄按键在设置页保存一个**屏幕位置**（可用「拾取」直接在游戏里点一下该按钮，`RecordingController.pickPoint`），
  外加一个虚拟摇杆中心与半径；
* 执行脚本中的 `gamepad` 动作时，`LocalGamepadGateway` 把它翻译成普通手势交给无障碍服务：
  `press`/`click` → 该位置点击；`trigger` → 时长按比例的长按；`stick` → 从摇杆中心向 `(x, y)·半径` 拖动；
  `release` → 空操作（点击本身就是瞬时的）；
* 未映射的按键会抛出可读错误（如「手柄按键 △ 尚未映射屏幕位置」，按键名按脚本手柄类型显示，
  见 §7.8），配合 `skip`/`abort` 策略决定跳过还是终止；
* 因此 **Manifest 中不再声明任何蓝牙权限**，也不需要配对流程。

### 7.6 关于 MIUIX `miuix-blur`

`autorunner-ui` 已按文档声明 `miuix-blur` 依赖。该库的 `RenderEffect` 实时模糊需要 Android 12
（其 AAR 声明 `minSdk 31`），AutoRunner 通过 `tools:overrideLibrary` 保留 `minSdk 28`，
运行期由库自身的 `isRenderEffectSupported()` 降级（悬浮窗是独立 Window，无法捕获其后的应用内容，
因此悬浮面板使用实色高层面板而非背景模糊）。

### 7.7 坐标换算

`CoordinateResolver` 负责 absolute ↔ normalized 转换；编辑器切换坐标空间时会**立即重写整条
flow**，保证 `info.coordinateSpace` 与动作数据始终一致。

### 7.8 手柄按键：文件里存中立标识，界面按手柄类型显示

`.arscript` 的 `button` 字段是**跨厂商中立标识**（`A` `B` `X` `Y` `LB` `RB` `LT` `RT` `BACK`
`START` `GUIDE` `L3` `R3` `DPAD_*`），这样同一份脚本在任何手柄类型下都能复用；
**中立标识只属于文件格式，不得出现在界面文案里**。界面一律通过
`GamepadButton.labelFor(mode)` / `ActionStep.labelFor(mode)` / `ActionStep.displayLabel(mode)`
渲染，`mode` 取脚本的 `info.gamepadMode`（执行进度用当次执行的 mode）。

三家手柄按**物理键位**对应，而不是按名字字面翻译：

| 物理位置 | Xbox | PS（DualSense） | Switch Pro |
|---------|------|----------------|-----------|
| 下 / 右 / 左 / 上 | `A` `B` `X` `Y` | `×` `○` `□` `△` | `B` `A` `Y` `X` |
| 肩键 | `LB` `RB` `LT` `RT` | `L1` `R1` `L2` `R2` | `L` `R` `ZL` `ZR` |
| 系统键 | `View` `Menu` `Guide` | `Share` `Options` `PS` | `Minus` `Plus` `Home` |
| 摇杆按压 | `LS` `RS` | `L3` `R3` | `L3` `R3` |

> ⚠️ PS 的叉是 `×`（U+00D7）不是拉丁字母 `X`，圈是 `○`（U+25CB）不是全角 `〇`（U+3007）。
> 早期实现用了拉丁 `X`，导致 PS 手柄脚本里被点中的「×」看起来像 Xbox 的 X 键
> （`GamepadVocabularyTest` 现已锁死这组字形）。

调用点：时间线 / 动作详情（`ActionStepRow`、`displayLabel`）、执行进度文案
（`AutoRunnerScriptExecutor`）、设置页手柄行与映射对话框、标定悬浮层标签、屏幕手柄按键字、
注入失败提示（`LocalGamepadGateway`）。

### 7.9 UI 设计系统与约定

界面层收敛在一套共享组件上（`autorunner-ui/.../components/`），新增页面请优先复用：

| 组件 | 用途 |
|------|------|
| `AppButton` / `AppButtonTone` | 填充按钮 + 语气（`Primary` / `Neutral` / `Danger`），`contentColor()` 给出配套文字色 |
| `ConfirmDialog` / `InputDialog` / `FormDialog` | 统一的确认 / 单输入 / 多字段表单弹窗（`show` 驱动 + `onDismissFinished`） |
| `MessageDialog` | 单按钮提示弹窗（页面反馈消息的唯一出口） |
| `PageScaffold` | 页面脚手架：大标题顶栏 + 零 insets + 滚动连接（只维护一处） |
| `SectionCard` | 分区卡片 + 去重标题（`LocalPageTitle` 为模块内 `internal`） |
| `PreferenceRow` / `MetricsRow` | 图标+标题+摘要(+状态药丸) 设置行 / 只读指标行 |
| `NumberField` / `NumberFieldPreference` | 数值输入（含错误态）与数值偏好行，解析助手 `parseNumberInput` |
| `SliderPreference` / `AppSegmentedChoice` | 滑块偏好项 / 分段选择器 |
| `StatusPill` / `TagPill` / `ActionStepRow` | 状态药丸 / 类型标签 / 动作行（时间线、录制流共用） |
| `GamepadCalibrationEntry` | 手柄标定入口（设置页与编辑器共用一份实现） |
| `CenteredText` | 宽度已定的容器里的居中文字 |

约定：

* **间距走令牌**：行内边距用 `Dimens.PreferenceRowPadding` / `CompactRowPadding` /
  `FormFieldPadding` / `InsetRowPadding` / `InsetPreferenceRowPadding`，不要在调用点手写
  `PaddingValues(...)`（曾散落出 6 种值，同屏相邻行高不齐）。
* **文字色角色**：页面 / 分区标题 `onBackground`，卡片正文标题 `onSurface`，
  次级正文（`body1` / `body2`）`onSurfaceSecondary`，脚注（`footnote1` / `footnote2`）
  `onSurfaceVariantSummary`；禁用态用组件自身的 `disabled*` 颜色。
* **居中文字用 `CenteredText`**：Android 与桌面端 `BasicText` 的测量行为不同，只写
  `Box(contentAlignment = Center) { Text(...) }` 在真机上会出现文字贴容器左边缘。
* **弹窗显隐由 `show` 驱动**：不要用 `target?.let { OverlayDialog(show = true, …) }`，
  那会在动画播放中直接摘掉节点；需要清空数据源时用 `onDismissFinished`。
* **破坏性操作统一 `AppButtonTone.Danger`**（删除 / 清空 / 恢复默认），不要用主色。
* **数值输入不许 `?: 0f` 兜底**：解析失败时显示错误态并禁用「保存 / 应用」，
  否则「清空输入框」会被静默写成 `0`。
* `equalSegments = false` 的分段器只能放在 wrap 容器里（均分模式才可配 `fillMaxWidth`）。

---

## 八、测试与验证

```bash
./gradlew :autorunner-core:desktopTest :autorunner-gamepad:desktopTest \
          :autorunner-ui:desktopTest :autorunner-app:assembleDebug
```

当前结果（JVM/desktop target 上运行，无需模拟器）：

| 测试套件 | 用例数 | 覆盖内容 |
|---------|-------|---------|
| `ArScriptCodecTest` | 8 | 文档示例解析、判别字段、未知字段兼容、往返一致性、损坏载荷 |
| `ScriptValidatorTest` | 12 | 空 flow、版本、时长、多点触控、模拟量范围、百分比坐标越界、设备信息缺失 |
| `GestureAnalyzerTest` | 10 | 点击 / 长按 / 滑动 / 多点触控分类、微触摸丢弃、取消、flush、阈值可配 |
| `AutoRunnerScriptExecutorTest` | 11 | 单次 / 重复 / 无限循环、暂停恢复、重连重试、跳过与终止策略、进度序列、手柄分发 |
| `FileScriptRepositoryTest` | 7 | 保存/加载/列表、原地更新、重命名、复制、删除、导入校验、唯一命名、导出 |
| `LocalGamepadGatewayTest` | 8 | 按键→本机点击、trigger 时长缩放、摇杆拖动、未映射/未启用/服务断开时的快速失败 |
| `ViewModelTest` | 12 | 编辑器增删改排序、坐标换算、校验联动、执行控制、列表过滤与导入、设置写穿 |
| `IntervalUnitTest` | 5 | 循环间隔的单位吸附取整（四舍五入、毫秒/秒/分上限、非正值） |
| `GamepadVocabularyTest` | 5 | 三家手柄按键的物理键位换算、肩键/系统键命名、展示文案不得出现中立标识 |
| **合计** | **78** | 全部通过 |

---

## 九、已知限制

* **`dispatchGesture` 时序精度**受系统调度影响（约 ±10ms 量级），高精度场景需要额外的补偿策略。
* **手柄模拟依赖无障碍服务**：它把按键翻译成本机触摸，因此无障碍未连接时手柄动作会按
  失败策略跳过或终止；每种手柄类型需各自在本机标定一次按键位置，未标定的按键会给出可读
  错误并跳过（不再声明任何蓝牙权限，也不做蓝牙外设模拟）。
* **应用私有目录存储**：导入/导出通过 SAF 完成，脚本本体存于 `filesDir/scripts`，卸载即清除。
* **桌面 target** 仅用于编译校验、单元测试与 UI 预览，不提供手势回放与悬浮窗能力。
* MIUIX 仍处于快速迭代阶段，项目锁定 `0.9.4`，升级前请先跑通全部测试。
* **AGP 9.x 临时兼容开关**：AGP 9.0 起 `com.android.application` / `com.android.library` 不再与 KMP 插件同模块兼容，
  当前通过 `gradle.properties` 里的 `android.builtInKotlin=false` 与 `android.newDsl=false` 临时绕过。这两个开关会在
  AGP 10 移除，届前需迁移到 `com.android.kotlin.multiplatform.library` 并拆分 Android 入口模块。

---

## 十、许可

本项目为示例工程，代码可自由参考使用。
