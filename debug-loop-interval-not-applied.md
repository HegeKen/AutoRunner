# Debug Session: loop-interval-not-applied

- **Status**: [RESOLVED]
- **Issue**: 真机上正在运行的脚本（`XXXX`，`execution.mode=repeat`、`repeatCount=30`、`intervalMs=840000` 即 14 分钟）未按设定的 14 分钟循环间隔执行；实测循环节律在「卡住数十秒」与「十几秒连跳数轮」之间剧烈跳变。
- **Debug Server**: N/A —— 本应用 `AndroidManifest.xml` **未声明 `android.permission.INTERNET`**，无法使用 TRAE-debugger 默认的 HTTP 上报通道。适配方案：插桩使用 `android.util.Log`（TAG=`AutoRunner`），证据经 `adb logcat` 采集。
- **Log File**: `.dbg/trae-debug-log-loop-interval-not-applied.ndjson`（等效落盘：`/tmp/ar_probe*.txt`）

## Environment

- 设备：小米 `25102RKBEC`（myron），adb 序列号 `3111e069`，横屏 2608×1200，density 3.0
- 应用进程：`cn.helilab.autorunner`，pid=13379
- 已安装包：`1.0.0` / versionCode 2（DEBUGGABLE，含 `DebugCommandReceiver`）；当前源码为 `1.0.1` / versionCode 3
- 调试通道：`adb shell am broadcast -n cn.helilab.autorunner/.debug.DebugCommandReceiver -a cn.helilab.autorunner.DEBUG_COMMAND --es command state`（**必须显式组件**，隐式广播收不到）

## Reproduction Steps

1. 应用内将脚本 `XXXX` 的运行间隔设为 14 分钟（`intervalMs=840000`），模式 repeat / 30 次。
2. 从库列表点 ▶ 上悬浮球，单击悬浮球开始执行。
3. 观察前台通知「循环 N / 30 · 耗时 mm:ss」与日志中的执行状态。
4. 预期：每轮间隔 ≈14 分钟；实际：节律剧烈跳变（见「Log Evidence」）。

## 运行时证据采集方式（非侵入探针，未改任何业务逻辑）

- `adb shell am broadcast ... --es command state` → `DebugCommandReceiver.dumpState()` 打印 `execState=` 与 `progress=<completedLoops> / <totalLoops>`，可作为「循环计数随时间」时间序列。
- 前台通知轮询：`dumpsys notification` 取 `title/text`（含 `completedLoops` 与 `elapsedMs`）。

## Hypotheses & Verification

| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | `ExecutionController.start()` 仅在入口检查一次 `executor.isActive`，且 `container.scope` 未指定调度器（默认 `Dispatchers.Default` 多线程），两次快速 `start()` 均通过检查 → **并发运行两个 `execute()`**，共享 `completedLoops`/`startMs` 可变字段 → 循环计数暴跳、耗时错乱 | High | Low | **Confirmed（间接但机理闭环）**：①`ExecutionViewModel.start()` 的 `controller.isActive` 预检查在调用线程，真正的 `controller.start()` 在 `scope.launch{}`（Default 线程）内，双触发必然双入队；②`ExecutionController.start()` 与 `AutoRunnerScriptExecutor.execute()` 的 `isActive` 检查均非原子 → 可并发进入；③观测同一脚本 6 秒内两次「开始执行」、13 秒连跳 2 轮（见 Log Evidence） |
| B | `ExecutionViewModel.start()` 在 `_selectedScriptId == id` 时**不调用** `adoptExecution`，而 `selectScript` 的 `adoptExecution` 是异步的；若竞争，`buildConfig()` 用默认 `_intervalMs=0` → executor 收到 `intervalMs=0`，循环以最快速度跑 | Med | Low | **Rejected**：实测 ≈160 s/轮（42:00÷16、56:00÷21），非 0，也非「秒级跑完 30 轮」；`intervalMs` 未被替换为 0 |
| C | `ExecutionToastNotifier` 的 `MIN_LOOP_INTERVAL_MS=5000` 节流 + MIUI 对 Toast 的配额拦截（`above allowed toast quota`），导致「看起来」循环提示稀疏/错位，而实际循环其实正常 | Med | Low | **Rejected（非根因）**：`state` 探针（不依赖 Toast）给出 `completedLoops` 时间序列，与通知一致呈现「13 秒连跳 2 轮 + 长平台期」，说明节律异常真实存在，非观感偏差；Toast 节流只影响提示不影响计数 |
| D | 悬浮球被重复触发 / `AutoRunnerOverlayService` 被创建两次（存在两个 `ExecutionViewModel` + 两个 `ExecutionToastNotifier`，`_isRunning` 为静态单例），导致重复启动或重复提示 | Med | Med | **Confirmed（与 A 同源，是其触发入口之一）**：Overlay FGS 记录到两次启动 17:11:08、17:12:38；同一脚本 6 秒内两次「开始执行」；`onPrimaryAction` 的 `else -> startFirstAvailable()` 兜底分支在 `state` 尚未变 RUNNING 时可被连点再次触发 |
| E | 存储层/单位换算错误导致 `intervalMs` 丢失（如存成秒或未持久化） | Low | Low | **Rejected**：`adb exec-out run-as ... cat files/scripts/XXXX.arscript` 显示 `intervalMs: 840000`，存储正确 |

## Log Evidence

### 脚本存储（决定性，排除 E）
```json
"execution": { "mode":"repeat", "repeatCount":30, "intervalMs":840000,
               "failureStrategy":"skip", "reconnectTimeoutMs":15000, "restoreDelayMs":0 }
"flow": [ { "type":"gamepad", "button":"A", "action":"click", "value":1.0, "delay":100, "name":"测试" } ]
```
→ `intervalMs=840000` 正确；脚本仅 1 个动作（step `delay=100ms`），30 轮。

### 执行状态探针（state 命令，每 10s 一次，18:12:07–18:22:31）
```
18:12:07  execState=RUNNING  progress=21 / 30
18:12:17  execState=RUNNING  progress=21 / 30
...（每 10s 采样，恒为 21 / 30）...
18:21:00  execState=RUNNING  progress=21 / 30
18:21:10  execState=RUNNING  progress=22 / 30   ← 12 分钟后 +1
```
→ 结合通知：`21` 首次出现于 18:08:47，直到 18:21:10 才变 `22` → **21→22 耗时 ≈12 分 23 秒**（≤14 分钟）。这一段是「单实例按 ≈14 分钟等待」的正常表象。

### 前台通知轮询（25s 采样）
```
18:06:15  运行中 · 循环 16 / 30 · 耗时 42:00
18:06:41  运行中 · 循环 16 / 30 · 耗时 42:00
18:07:06  运行中 · 循环 16 / 30 · 耗时 42:00
18:07:31  运行中 · 循环 17 / 30 · 耗时 54:28   ← 跳变 +12:28 且换基线
18:07:56  运行中 · 循环 17 / 30 · 耗时 54:28
18:08:21  运行中 · 循环 19 / 30 · 耗时 55:35   ← 2.5 分钟内 16→21
18:08:47  运行中 · 循环 21 / 30 · 耗时 56:00
```
→ 两个决定性异常：
1. **循环速率量级错误**：`耗时/循环` = 42:00÷16 = **157.5 s**、56:00÷21 = **160 s**，即 ≈**160 秒/轮（2.67 分钟）**；而 `intervalMs=840000` 预期为 **840 秒/轮（14 分钟）** → 实测约为设定值的 **5.25 倍速**。
2. **瞬时爆发不可能由单实例产生**：`18:08:21→18:08:47` 仅 26 秒内 `completedLoops` 从 19 → 21（**13 秒/轮**），远超单实例 840 s 间隔的可能。
3. `耗时`(=`elapsedMs`, `now - startMs`) 从 `42:00` 跳到 `54:28`，说明前后快照的 **`startMs` 基线不同**（42:00 ⇒ `startMs≈17:24:15`；54:28 ⇒ `startMs≈17:13:03`）→ 存在多个执行体写同一 `_progress`。

### Toast（存在重复启动迹象）
```
17:12:16.459  开始执行「XXXX」
17:12:16.625  循环 1 / 30 完成
17:12:22.133  开始执行「XXXX」   ← 6 秒后再次「开始执行」
17:12:22.299  循环 1 / 30 完成
```

## Root Cause（决定性，插桩取证后修订）

**`stop()` 不终止在执行协程：间隔等待不可被中断 + `ExecutionController.stop()` 不 cancel job → 每次「停止→再启动」留下一个僵尸执行体，新旧执行体并行推进同一份循环计数，循环速度成倍变快（跑得太快）。** 这是确定性缺陷，无需亚毫秒竞态即可复现。

机理链（真机插桩实证）：

1. `AutoRunnerScriptExecutor.stop()` 只把 `_state.value` 置 `STOPPED` 并 `cancelPendingGestures()`，**不中断正在挂起的 `clock.delay(intervalMs)`**；`ExecutionController.stop()` 只调 `executor.stop()`，**不 cancel `job`**（仅 `forceStop()` 会 cancel）。
2. 用户「停止」时，若旧执行体正处在循环间隔等待中，其协程仍存活。间隔到期后，循环内 `if (_state.value == STOPPED)` 是**一次性读**：若期间新 run 已把 `_state` 置回 `RUNNING`，旧协程读到 RUNNING → 不退出 → 继续执行动作并 `completedLoops++`。
3. 旧 run 与新 run 共享实例可变字段 `completedLoops`/`executedActions`/`startMs`（均非线程安全，且 `applicationScope` 未指定调度器 → `Dispatchers.Default` 多线程）→ 两个执行体交替递增同一计数 → 循环推进速度 **N 倍**（实测 2 倍）。
4. 现实触发路径全部可达：`AutoRunnerOverlayService.kt`（通知 `ACTION_STOP` / `onStop`）、UI 停止按钮、`DebugCommandReceiver` 的 `stop` 均调用 `ExecutionController.stop()`。

早前假设的「重复/并发启动竞态」（Hypothesis A）被降级：真机实测 6 路并发 `run` 中 5/6 在 7ms 内被入口守卫拒绝，adb 广播无法命中亚毫秒窗口；生产主因是上述 stop 留下的僵尸执行体（每次「停止→再启动」必然产生）。

## 修复（已实施并验证）

[AutoRunnerScriptExecutor.kt](file:///Users/hegeken/Desktop/Codes/AutoRunner/autorunner-core/src/commonMain/kotlin/com/autorunner/core/execution/AutoRunnerScriptExecutor.kt)：

1. **每次 run 独有的停止信号**：`execute()` 入口生成 `runId` 与 `CompletableDeferred`（`stopSignal`）；`stop()` 完成本 run 的信号。
2. **循环检查改读本 run 信号**：两处 `if (_state.value == STOPPED)` 改为 `if (stopRequested.isCompleted)` —— 不再依赖可被新 run 覆盖的全局 `_state` 读取。
3. **间隔等待可被中断**：`restoreDelayMs` 与 `intervalMs` 等待改为 `awaitGapInterruptedByStop()`（`select` 竞争「时钟到期」与「停止信号」），stop 后**毫秒级**退出间隔等待。
4. **run 归属守卫**：尾部 `_state`/`_progress`/`reports` 写回仅当 `currentRun == runId`，被淘汰的旧 run 不得污染新 run 的状态与报告。
5. 不 cancel job、不引入双重 STOPPED 报告（避免与 `forceStop()` 的 `emitStoppedReport` 冲突），报告语义与耗时断言不变。

`:autorunner-core:test` 全部通过（含 `repeatsTheFlowAndHonoursTheInterval`、`infiniteRepeatRunsUntilStopped`、`pauseParksTheRunUntilResumed`、`refusesToStartTwice`）。

## 验证（真机，同一探针）

**探针 1 —— 僵尸复现（修复前，18:34，interval=15000）**：`stop → run_repeat(count=8) → sleep 3 → stop → 立即再 run_repeat` → 两个 job（`fd8ad8d`/`d2bf2f9`）交替递增同一 `completedLoops`，旧 job 在 `INTERVAL_DONE` 后复活继续 LOOP，**8 轮 58 秒跑完（应为 120 秒，恰好 2 倍速）**。

**探针 2 —— 完整节律（修复后，interval=15000，count=8）**：单一 job `6489787`，每轮 `INTERVAL_WAIT→INTERVAL_DONE` 间隔精确 **15.002 s**，8 轮共 **106 s**（7×15s + 动作时间），`outcome=COMPLETED isCurrentRun=true`，无任何第二 job。

**探针 3 —— stop 中断间隔（修复后，interval=60000）**：
```
18:46:58.977  execute#INTERVAL_WAIT job=59e54e4 afterLoop=1 waitingMs=60000
18:47:03.853  debug command: stop
18:47:03.855  execute#INTERVAL_ABORT job=59e54e4        ← 2ms 内中断，不再等 60s
18:47:03.855  execute#EXIT outcome=STOPPED isCurrentRun=true
18:47:03.856  debug run_repeat finished: 已手动停止 · 1/8 轮 · 耗时 00:05
```
EXIT 后无任何旧 job 的 LOOP（修复前 60s 后僵尸会复活）。

## Verification Conclusion

- **根因确认**：`stop()` 不中断间隔等待 / 不 cancel job → 停止→再启动必产生僵尸执行体 → 循环计数成倍推进 →「跑得太快」。已排除：B、C、E；A（并发启动竞态）降级为次要路径。
- **修复已实施并在真机验证**：stop 中断毫秒级生效、无僵尸、循环节律 = 设定间隔。
- **待办**：无。插桩日志（`[ARDBG]` 系列 println）已于验证完成后清理，修复代码保留；graphify 图谱已刷新。
