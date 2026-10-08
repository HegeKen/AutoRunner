# 更新日志

本文件记录 AutoRunner 的所有重要变更。

## v1.0.1 - 2026-10-08

修复手势回放失败被吞、横屏打孔屏坐标偏移两处真机问题，并校正手柄标定默认键位。

### Fixed

- **手势构建失败不再被静默吞掉**：`buildGesture` 此前用 `runCatching{}.getOrNull()` 把所有异常
  统一兜底成 `null`，坐标越界等真实失败会被误报成「不支持的动作」，真机难以排查；现在只有确实
  没有手势形态的动作（delay / gamepad / key、并发笔画超限）才返回 `null`，其余异常抛出并写入
  `WARN` 日志，向用户回报「手势构建失败：<原因>」。
- **手势坐标夹取到屏幕范围**：点击 / 长按 / 滑动的起点终点、多点触控各点均按屏幕宽高夹取到
  `[0, 屏幕尺寸]`，避免平台对负边界笔画的拒绝。
- **横屏打孔屏下悬浮球 / 标定层坐标偏移**：给悬浮球窗口（`ComposeWindowHost`）与手柄标定窗口
  （`GamepadCalibrationWindowHost`）补上 `layoutInDisplayCutoutMode`（API 30+ 用 `ALWAYS`，
  API 28/29 回退 `SHORT_EDGES`），窗口铺满含 cutout 的物理屏，内容原点固定为物理 `(0, 0)`；
  修掉此前窗口被 cutout letterbox、球的坐标与标定结果整体偏移一个 cutout inset（如 144px / 48dp）
  的问题。

### Changed

- **手柄标定默认键位贴合 PS Remote Play 原生虚拟手柄（DualSense）真实布局**：以相对屏幕宽高的
  比例铺开（取自 2608x1200 横屏全屏截图实测的按键中心），取代此前的顺序网格；左肩 L2/L1、
  右肩 R2/R1、左十字键、右 △□○×、底部 BACK/GUIDE/START、摇杆 L3/R3 及摇杆中心各就各位。
  三种手柄模式共用同一套物理落点，品牌差异只体现在标签上。
- **手柄动作默认延迟由 `100ms` 调整为 `300ms`**（`GamepadStep.DEFAULT_DELAY`），README 与 `ActionStep`
  文档示例同步更新，避免过快回放漏触发。
- **debug 构建复用 release 签名**：设备上若装过 GitHub Release 包，签名一致的 debug 包才能原地
  覆盖安装，避免跨签名报 `INSTALL_FAILED_DUPLICATE_PERMISSION`；未提供 `ANDROID_KEYSTORE_*`
  变量时仍回退到项目内 `debug.keystore`，本地行为保持不变。

### 构建 / CI

- Release 附件 APK 更名为带版本号的 `autorunner-<version>.apk`，并清理同一 Release 下可能残留的
  无版本号旧附件，避免重复；改动集中在 `.github/workflows/android-release.yml`。

## v1.0.0 - 2026-10-08

首个正式版本：基于 Kotlin Multiplatform + Compose Multiplatform 的原生 Android
自动化录制与回放应用，UI 采用 MIUIX（HyperOS 设计语言）。

### Added

- **手势录制**：透明 `TYPE_ACCESSIBILITY_OVERLAY` 捕获层，识别点击 / 长按 / 滑动 / 多点触控；
  录制时以 `dispatchGesture` 同步回放（触摸镜像），所见即所得。
- **手势回放**：基于 `dispatchGesture(GestureDescription)` 逐条动作回放。
- **脚本格式 `.arscript`**（内部 JSON）：含 `version` / `info` / `execution` / `flow`，
  支持 `tap` / `longPress` / `swipe` / `multiTouch` / `gamepad` / `delay` 多种动作。
- **可视化脚本编辑器**：动作增删改排序、参数编辑、absolute ↔ normalized 坐标换算、
  实时校验、导入导出。
- **单次 / 重复执行**：`mode=once` 或 `mode=repeat` + `repeatCount`（`0` = 无限）+ `intervalMs`，
  支持暂停 / 恢复 / 停止与失败策略（跳过 / 终止 / 重连重试）。
- **执行进度反馈**：循环次数、当前动作、已运行时长、状态，同步到悬浮窗与通知栏。
- **悬浮控制面板**：可自由拖动悬浮球 + 展开面板（录制、脚本选择、运行 / 暂停 / 停止、
  重复次数输入），并提供「到位再执行」流程（回桌面 → 悬浮球单击开始）。
- **多重停止入口**：录制期间常驻「停止录制」胶囊 + 悬浮球单击 + 通知栏停止。
- **多设备适配**：WindowSizeClass 驱动，手机底部导航 / 平板侧边导航，
  支持 List-Detail 与 Supporting Pane 布局。
- **主题**：浅色 / 深色 / 跟随系统三档（固定品牌色 `#2655FF`）。
- **权限适配**：无障碍、悬浮窗、通知、忽略电池优化，含 MIUI / ColorOS / OriginOS / EMUI 引导；
  缺权限时列表页浮标与设置首页摘要卡直接列出缺失项。
- **Root 注入（可选）**：设置 → 执行 可切换「无障碍服务 / Root 注入」；
  配套 Magisk / KernelSU 模块（开机自动开无障碍 + 默认授权 + 注入守护进程），
  未装模块时可从应用内导出 zip 到下载目录。
- **手柄模拟（可选）**：把脚本中的手柄按键映射为本机触摸 / 摇杆拖动并直接注入当前设备，
  无需任何蓝牙权限；支持按手柄类型保存独立映射。
- **首次启动示例脚本**：自动导入内置 `ar_paging_test.arscript` 等示例，便于快速上手。
- **UI 设计系统基座**：`ConfirmDialog` / `InputDialog` / `FormDialog`（统一确认 / 输入 / 表单弹窗）、
  `PageScaffold`（页面脚手架）、`PreferenceRow` / `MetricsRow`（设置行）、`CenteredText`（居中文字）、
  共享 `GamepadCalibrationEntry`（设置页与编辑器同一份标定入口），以及 `Dimens` 行内边距令牌；
  同类界面不再各写一套。
- **数值输入的防御性校验**：`parseNumberInput` 把「末尾小数点」（如 `540.`）当作合法中间状态解析，
  解析失败时输入框显示错误态并禁用「保存 / 应用」，不再用 `?: 0f` 把非法输入兜底成 `0`。
- 新增 `IntervalUnitTest`（循环间隔取整规则）与 `GamepadVocabularyTest`（手柄按键品牌命名映射）
  两组单元测试。

### Changed

- 版本号收敛为**单一来源**：根 `gradle.properties` 的 `autorunner.versionName` /
  `autorunner.versionCode`，联动 APK 版本、应用内「关于」页，并在打包 root-module 时
  注入其 `module.prop`（仓库内的 `module.prop` 仅作模板，构建时被覆盖）。
- Android 构建时自动打包最新版 root-module：`:autorunner-app:buildRootModule` 复用
  `root-module/build.sh` 生成 `autorunner_root.zip`，并经 Variant API 作为 assets 并入 APK，
  保证内置模块版本与 APK 版本始终一致。
- 手柄标定页的拖拽提示由常驻通知改为自动消失的 toast，避免遮挡标定按钮。
- 悬浮球长按菜单里的「关闭悬浮窗」取消文字提示，改为与主球（运行）同款的纯图标
  圆形按钮，交互与视觉统一。
- 悬浮球长按菜单全部图标化（暂停 / 继续、停止、停止录制、关闭），并按屏幕方向排布：
  竖屏上下排成一列，横屏排成一行；窗口随之按内容自适应收紧，避免空白区域吞掉触摸。
- 手柄标定页的「重置 / 取消 / 保存并退出」由并排文字按钮改为悬浮球长按后在球正上方
  弹出的三个独立圆形图标按钮，与悬浮球统一设计。
- 运行 / 录制悬浮球取消边缘吸附：拖动后停在屏幕任意位置，不再吸附到左 / 右边缘；
  球的绝对坐标为位置真源，所在半屏仅决定长按菜单的展开方向。
- 脚本编辑页把脚本级「执行模式」设置上移到脚本卡片（`ScriptMetaCard`）：以整段脚本为
  循环主体，可直接在卡片内设置单次 / 重复、循环次数、循环间隔与失败策略；动作详情面板
  不再重复提供同一组设置。
- 脚本库列表卡片（`ScriptListItem`）在标题右侧新增循环徽标（`重复 N 次` / `无限循环`），
  重复执行的脚本一眼可辨。
- **破坏性操作统一语气**：删除脚本 / 清空全部 / 恢复默认全部走 `ConfirmDialog` +
  `AppButtonTone.Danger`（此前删除脚本用主色、清空全部用危险色，同一个动作给出两种风险暗示）。
- **弹窗显隐统一由 `show` 驱动**（含 `MessageDialog`）：不再用 `target?.let { OverlayDialog(show = true, …) }`
  在外层摘节点，退场动画不再被截断；需要清空数据源时用 `onDismissFinished` 收尾。
- **设置页行内边距收敛为 `Dimens` 令牌**：权限行 / 存储行（12dp）、技术栈 / 关于行（10dp）、
  表单行（0×8dp）等 6 种手写值统一，同类行行高不再肉眼可见地不齐。
- **编辑器参数草稿改用稳定身份 key**（序号 + 动作类型）：普通重组不再清空用户正在输入的内容；
  ViewModel 从外部整体改写步骤（坐标空间转换、列表排序、录制回填）时同步新值并**明确提示**，
  不再静默丢弃未应用的修改。
- **循环间隔单位切换回调改为 `remember` 的稳定实例**（调用时读取最新 State），
  取整规则抽成纯函数 `IntervalUnit.snap`，不再依赖读者理解 `rememberUpdatedState` 才能改对。
- **手柄按键展示统一按脚本手柄类型渲染**（时间线 / 详情 / 执行进度 / 设置提示），
  `.arscript` 里仍存跨厂商中立标识；约定见 README §7.8。
- `LocalPageTitle` 收窄为模块内 `internal`，不再作为全局 API 暴露。

### Fixed

- 修复点击脚本列表的「运行」按钮后应用异常退出的问题：悬浮窗服务在属性初始化阶段
  （构造期）读取 `resources`，此时 base context 尚未附加，`getResources()` 返回 null 触发 NPE，
  导致 `Unable to create service AutoRunnerOverlayService`。改为在 `onCreate()` 中读取屏幕方向。
- 修复横屏下左侧导航栏图标变小的问题：Shell 之前用固定 80dp 宽的 `Column` 包裹 MIUIX
  `NavigationRail`，横屏刘海在左时约 48dp 的 displayCutout inset 被消耗在 80dp 之内，内容列
  仅剩约 32dp，图标被压缩成一个小点；改为直接把 `NavigationRail` 放入 `Row`，其总宽 =
  刘海 inset + 80dp，图标恢复 28dp，页面内容也不再压在刘海下方。
- 修复横竖屏切换后悬浮球跑到屏幕外的问题：方向变化时按新屏幕尺寸重新夹取球坐标，
  使球始终停留在可见区域内。
- 修复横屏下脚本库中间列表内容上升进状态栏被遮挡的问题：分栏（List-Detail）布局下中间
  列表列此前不自带顶栏，滚动内容会顶到状态栏下方；现为该列补上独立顶栏（标题「脚本」+
  副标题），并在分栏时给运行状态横幅补充顶部 systemBars inset，使中间列与右侧编辑器顶栏
  对齐、内容整体位于状态栏之下。
- 修复 Root 模式下 `am force-stop`（含 Gradle 安装调试）后无障碍权限丢失、且要等到重启
  才恢复的问题：系统强停会 unbind 并重写 `enabled_accessibility_services` 移除本服务，而
  模块此前仅在开机补写一次；现由 App 在启动/回前台检测到条目缺失时经命令桥下发 `a11y`，
  守护进程在既有循环里即时幂等补回，实现按需自愈（无周期性轮询，避免额外耗电）。
- 修复分段选择器**选中项文字贴着蓝色胶囊左边缘**的问题（真机截图可见「DualSense」顶到
  左边缘）：此前只依赖 `Box(contentAlignment = Center)`，而 Android 上 `BasicText` 会把节点
  撑满可用宽度，居中约束不再产生位移；新增 `CenteredText`（`fillMaxWidth` + `TextAlign.Center`）
  并同样修掉屏幕手柄按键字、摇杆圆盘提示、标定悬浮层标签、缺权限计数徽标等同类隐患。
- 修复手柄动作在列表 / 详情里显示 `.arscript` 的中立标识、与手柄图对不上的问题：
  例如 PS 手柄脚本里点中的「×」显示成「手柄 A click」，Switch 手柄出现 A/B 互换的错觉；
  现统一按脚本手柄类型渲染（`ActionStep.labelFor/displayLabel`、注入失败提示也用品牌命名）。
- 修复 DualSense 按键字形：叉由拉丁字母 `X`（U+0058）改为 `×`（U+00D7）、圈由全角 `〇`（U+3007）
  改为 `○`（U+25CB），避免 PS 的叉被看成 Xbox 的 X 键。
- 修复脚本编辑器数值输入的两处静默丢失：输入 `540.` 时输入框已更新但值未生效（现在按 `540`
  解析并写入），以及手柄坐标对话框里清空 / 非法输入被 `?: 0f` 兜底成 `0` 写回设置
  （现在显示错误态并禁用保存）。
- 修复「从实际操作录入」会吞掉用户为该步骤自定义的名称的问题（覆盖坐标与时长时保留名称与延迟）。

### 说明

- 桌面（desktop）target 仅用于编译校验、单元测试与 UI 预览，不提供手势回放与悬浮窗能力。
- 单元测试共 78 个用例（core 53 / gamepad 8 / ui 17），全部在 JVM/desktop target 上运行，无需模拟器。