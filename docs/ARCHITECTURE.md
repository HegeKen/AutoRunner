# 架构说明

本文把设计文档中的概念映射到实际代码，便于二次开发与评审。

## 1. 分层

```
┌──────────────────────────────────────────────────────────────────────┐
│ autorunner-app (Android)                                             │
│  MainActivity · AutoRunnerApplication · AppGraph                     │
│  AutoRunnerAccessibilityService · AutoRunnerOverlayService           │
│  AndroidPermissionController · AndroidScriptTransferController       │
│  AndroidAccessibilityController · AndroidRecordingController         │
│  ComposeWindowHost · ExecutionNotifications                          │
└───────────────▲───────────────────────────────────▲──────────────────┘
                │ Compose UI                        │ AppContainer
┌───────────────┴───────────────────────────────────┴──────────────────┐
│ autorunner-ui (commonMain, Compose Multiplatform + MIUIX)            │
│  AutoRunnerApp · ScriptList/Editor/Recording/SettingsScreen          │
│  FloatingControlPanel · ExecutionControlPanel · MIUIX 组件封装        │
│  ViewModels: ScriptList · ScriptEditor · Recording · Settings ·      │
│              Execution                                               │
│  platform/: PermissionController · ScriptTransferController ·        │
│             GamepadStatusProvider  (接口，由 app 实现)                │
└───────────────▲──────────────────────────────────────────────────────┘
                │ 脚本模型 / 执行引擎 / 录制引擎 / 仓储 / 设置
┌───────────────┴──────────────────────────────────────────────────────┐
│ autorunner-core (commonMain 平台无关 + androidMain / desktopMain)     │
│  model/   ScriptModel · ActionStep · ExecutionConfig · AppSettings    │
│  serialization/ ArScriptCodec · ScriptValidator                       │
│  recording/ GestureAnalyzer · RecordedScriptBuilder · RawTouchEvent   │
│  execution/ AutoRunnerScriptExecutor · ExecutionController · Clock    │
│  script/  ScriptRepository · FileScriptRepository                     │
│  settings/ SettingsRepository                                         │
│  platform/ AccessibilityController · RecordingController ·            │
│            OverlayManager · PlatformServices · expect 工厂            │
│  storage/ ScriptStorage · KeyValueStore                               │
│  di/      AppContainer · AutoRunnerCore                               │
└───────────────▲──────────────────────────────────────────────────────┘
                │ GamepadGateway（本机输入注入）
┌───────────────┴──────────────────────────────────────────────────────┐
│ autorunner-gamepad (可选)                                            │
│  LocalGamepadGateway：把 gamepad 动作翻译成本机手势                  │
└──────────────────────────────────────────────────────────────────────┘
```

`commonMain` 中不存在任何 `android.*` 类型；平台能力通过 `expect`/`actual`
（`createAccessibilityController`、`createOverlayManager`、`createRecordingController`、
`createScriptStorage`、`createKeyValueStore`、`currentTimeMillis` …）或接口注入。

## 2. 关键对象生命周期

| 对象 | 创建者 | 生命周期 |
|------|--------|---------|
| `AppContainer` | `AppGraph.initialize()`（Application） | 进程 |
| `AndroidAccessibilityController` | `AutoRunnerCore.createContainer` | 进程（内部引用可能为 null 的服务） |
| `AutoRunnerAccessibilityService` | 系统 | 由用户开关决定；`AccessibilityServiceHolder` 发布/撤销 |
| `AndroidRecordingController` | `createContainer` | 进程 |
| `AutoRunnerOverlayService` | `AndroidOverlayManager.show()` | 用户开关；销毁时移除悬浮窗 |
| `ComposeWindowHost` | `AutoRunnerOverlayService.onCreate` | 与悬浮窗服务一致 |
| ViewModel | Compose `remember`（App 内）/ 服务字段（悬浮窗） | 组合生命周期 |

## 3. 服务定位（为什么不用 DI 框架）

`AccessibilityService` 由系统实例化、没有可注入的构造函数，因此 `commonMain` 中的
`PlatformServices` 作为轻量登记处：

```kotlin
// core/androidMain
actual fun createAccessibilityController(): AccessibilityController =
    PlatformServices.accessibilityController ?: UnavailableAccessibilityController
```

`AppGraph.initialize()` 在 `Application.onCreate` 中登记实现；
测试则直接向 `PlatformServices` 注入假实现（见 `ViewModelTest`）。
这套机制让 `expect fun` 保持无参，同时避免引入 Koin 之类的运行时依赖（§8.3 允许手动 DI）。

## 4. 录制链路

```
MotionEvent
  └─ TouchCaptureView (TYPE_ACCESSIBILITY_OVERLAY, 全屏透明)
       └─ RawTouchEvent(phase, samples, timestamp)
            └─ AndroidRecordingController.onTouchEvent
                 ├─ GestureAnalyzer.onTouchEvent  →  0..1 个 ActionStep
                 ├─ 记录到 steps / 原始事件流（供录制页 feed）
                 └─ mirror: accessibilityController.perform(step)  ← 让底层应用仍收到触摸
```

`GestureAnalyzer` 是纯函数式状态机，阈值全部来自 `RecordingConfig`：

| 手势 | 判定 |
|------|------|
| `longPress` | 位移 ≤ `tapSlopPx` 且时长 ≥ `longPressThresholdMs` |
| `tap` | 位移 ≤ `tapSlopPx` 且时长 ≥ `minTapDurationMs` |
| `swipe` | 位移 > `tapSlopPx`，取起点 → 末点 |
| `multiTouch` | 曾同时按下 > 1 个触点 |

## 5. 执行链路

```
ExecutionViewModel.start(scriptId)
  └─ ExecutionController.start(script, config, scriptId)   →  scope.launch
       └─ AutoRunnerScriptExecutor.execute(...)
            ├─ CoordinateResolver.resolve(step, info.coordinateSpace, screenMetrics)
            ├─ loop {                    // totalLoops = 1 | repeatCount | ∞
            │    awaitResumeOrStop()      // PAUSED 时挂起在 state.first { … }
            │    dispatch(step)           // AccessibilityController / GamepadGateway
            │    clock.delay(step.delay)
            │  }
            ├─ clock.delay(intervalMs)    // 轮间间隔
            └─ publish(ExecutionProgress) → StateFlow + 回调
                 ├─ FloatingControlPanel 实时渲染
                 └─ ExecutionNotifications 更新前台通知
```

状态机：`IDLE → RUNNING ⇄ PAUSED → { COMPLETED | STOPPED }`，
`stop()` 在动作边界优雅退出，`reset()` 把终态复位为 `IDLE`。

## 6. 数据持久化

| 数据 | 位置 | 实现 |
|------|------|------|
| 脚本 | `filesDir/scripts/<id>.arscript` | `AndroidScriptStorage` + `FileScriptRepository` |
| 设置 | `SharedPreferences("autorunner_settings")` 中的一段 JSON | `AndroidKeyValueStore` + `SettingsRepository` |
| 导入/导出 | SAF（`OpenDocument` / `CreateDocument`） | `AndroidScriptTransferController` |

`ScriptRepository` 通过 `StateFlow<List<ScriptRecord>>` 暴露列表，任何一处保存后
所有界面自动刷新。

## 7. UI 适配矩阵

| 关注点 | compact | medium | expanded |
|--------|---------|--------|----------|
| 主导航 | `NavigationBar` | `NavigationRail` | `NavigationRail` |
| 脚本库 | 全屏列表 | 列表 + 过渡 | List-Detail（列表 + 编辑器同屏） |
| 编辑器 | 分步导航 | 分步导航 | Supporting Pane（编辑 + 预览/校验） |
| 录制 | 单 `LazyColumn` | 单列 | 控制区 + 事件流双列 |
| 顶栏 | 高度不足时隐藏 | `SmallTopAppBar` | `TopAppBar` |

## 8. 新增一个动作类型

1. 在 `com.autorunner.core.model.ActionStep` 中新增 `@Serializable @SerialName("…")` 数据类，
   实现 `delay` / `label` / `typeName`。
2. 在 `CoordinateResolver` 的 `when` 中补上坐标换算分支（有坐标时）。
3. 在 `ScriptValidator.validateStep` 中补充校验规则。
4. 需要回放时在 `AndroidAccessibilityController.buildGesture` 中生成对应
   `GestureDescription`（手柄类动作走 `GamepadGateway`）。
5. 在 `ScriptEditorScreen` 的 `AddActionPanel` 与 `ActionParameterEditor` 中补充入口与参数表单。
6. 在 `ArScriptCodecTest` / `GestureAnalyzerTest` 中补充用例。
