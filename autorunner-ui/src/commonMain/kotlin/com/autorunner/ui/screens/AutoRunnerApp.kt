package com.autorunner.ui.screens

import com.autorunner.core.model.InputMode
import com.autorunner.ui.platform.AutoRunnerPermission
import com.autorunner.ui.components.AppButtonTone
import com.autorunner.ui.components.AutoRunnerTopAppBar
import com.autorunner.ui.components.ConfirmDialog
import com.autorunner.ui.components.MessageDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.icon.extended.Report
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import com.autorunner.ui.components.CenteredText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.di.AppContainer
import com.autorunner.core.model.ExecutionState
import com.autorunner.ui.adaptive.AutoRunnerWindowSize
import com.autorunner.ui.adaptive.rememberAutoRunnerWindowSize
import com.autorunner.ui.platform.GamepadStatusProvider
import com.autorunner.ui.platform.PermissionController
import com.autorunner.ui.platform.ScriptTransferController
import com.autorunner.ui.state.Destination
import com.autorunner.ui.theme.AutoRunnerTheme
import com.autorunner.ui.theme.Dimens
import com.autorunner.ui.viewmodel.ExecutionViewModel
import com.autorunner.ui.viewmodel.RecordingViewModel
import com.autorunner.ui.viewmodel.ScriptEditorViewModel
import com.autorunner.ui.viewmodel.ScriptListViewModel
import com.autorunner.ui.viewmodel.SettingsViewModel

/**
 * AutoRunner 的根组合函数。
 *
 * 处理 §5 要求的三个层次的适配：
 *
 * 1. **窗口尺寸等级**（Material 3 Adaptive 的 [WindowSizeClass]）决定使用
 *    compact、medium 还是 expanded 布局。
 * 2. **导航**在 MIUIX `NavigationBar`（手机）与 `NavigationRail`（平板）之间
 *    切换——最常见的 MIUIX 自适应模式。
 * 3. **面板布局**在逐步导航（手机）与 List-Detail / Supporting Pane 模式（平板）
 *    之间切换。
 *
 * 每个页面都是同一套；只有外壳形态在变。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AutoRunnerApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    permissionController: PermissionController? = null,
    transferController: ScriptTransferController? = null,
    gamepadStatusProvider: GamepadStatusProvider? = null,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass,
    /** 让自动化 UI 检查（或分享 Intent）能直接打开指定页面。 */
    initialDestination: Destination = Destination.Scripts,
) {
    // 生命周期感知的收集：activity 停止时外壳会停止观察，
    // 这对长生命周期的服务流很重要。
    val settings by container.settings.collectAsStateWithLifecycle()

    // 每个手柄类型各自标定；编辑器据此判断脚本类型是否已标定。
    val calibratedGamepadModes = settings.gamepadMappingsByMode
        .filterValues { it.calibrated }
        .keys

    AutoRunnerTheme(themeMode = settings.themeMode) {
        val windowSize = rememberAutoRunnerWindowSize(windowSizeClass)

        val scriptListViewModel = remember(container) { ScriptListViewModel(container, transferController) }
        val editorViewModel = remember(container) { ScriptEditorViewModel(container) }
        val recordingViewModel = remember(container) { RecordingViewModel(container) }
        val settingsViewModel = remember(container) {
            SettingsViewModel(container, permissionController, gamepadStatusProvider)
        }
        val executionViewModel = remember(container) { ExecutionViewModel(container) }

        var destination by remember { mutableStateOf(initialDestination) }

        val permissionStatus by settingsViewModel.permissionStatus.collectAsStateWithLifecycle()
        val recordingStatus by recordingViewModel.status.collectAsStateWithLifecycle()
        val highlightedScriptId by scriptListViewModel.selectedId.collectAsStateWithLifecycle()
        val storedScripts by scriptListViewModel.scripts.collectAsStateWithLifecycle()
        var showClearAllDialog by remember { mutableStateOf(false) }
        // 驱动脚本库顶栏可折叠的大标题。
        val scrollBehavior = MiuixScrollBehavior()

        // 轻量"上一级"返回栈：不引入导航库，只记住上一页，供系统返回键回退。
        val backStack = remember { mutableStateListOf<Destination>() }

        // 切换到目标 destination 并执行对应的加载副作用。
        val open: (Destination) -> Unit = { target ->
            destination = target
            when (target) {
                is Destination.Editor -> editorViewModel.load(target.scriptId)
                Destination.Scripts -> scriptListViewModel.refresh()
                Destination.Record, Destination.Settings -> Unit
            }
        }

        val navigate: (Destination) -> Unit = { target ->
            if (target != destination) {
                // 顶部导航（脚本/录制/设置）是同级切换，进入时清空返回栈；
                // 进入二级页面（编辑器）则记录当前页，供返回键回退。
                if (target in Destination.topLevel) backStack.clear() else backStack.add(destination)
            }
            open(target)
        }

        // 系统返回键：优先回到上一级；栈空且不在首页时回到首页，避免在二级页面
        // 直接退出到桌面；只有在首页（脚本库）时才放行给系统退出应用。
        BackHandler(enabled = backStack.isNotEmpty() || destination != Destination.Scripts) {
            open(if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else Destination.Scripts)
        }

        // 响应 intent extra 指定的目标页（自动化 UI 测试用，因为 MIUI 屏蔽了
        // `adb shell input tap`）。
        androidx.compose.runtime.LaunchedEffect(initialDestination) {
            navigate(initialDestination)
        }


        Scaffold(
            modifier = modifier.fillMaxSize(),
            // 有意置零：否则 MIUIX 会按状态栏给*内容*加内边距。
            // 顶栏并不位于那块内边距区域内，于是各页面自己的顶栏都会比
            // 应在位置低一个状态栏。改为由每个顶栏自行应用该 inset
            //（defaultWindowInsetsPadding = true）。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                // 只有脚本库复用 Shell 顶栏；录制页与设置页各自持有带更丰富内容的
                // Scaffold 顶栏，两处同时渲染会出现两个叠起来的标题。
                //
                // List-Detail（列表 + 编辑器并排）时改由左右两个面板各自持有顶栏
                // （列表见 ScriptListScreen、编辑器见 PageScaffold），这里不再渲染，
                // 否则会在编辑器上方叠出第二条标题。
                //
                // 注意这里不再用 showTopAppBar 作为开关：矮窗口（横屏）里它会让整个
                // 顶栏消失，脚本列表便没有任何顶栏，内容直接顶进状态栏被遮挡。
                // AutoRunnerTopAppBar 自己会在矮窗口退回 SmallTopAppBar。
                if (destination == Destination.Scripts && !windowSize.useListDetail) {
                    AutoRunnerTopAppBar(
                        windowSize = windowSize,
                        // 只剩导航栏偏移：顶栏自身带标题内边距，各页内容自带屏幕边距，
                        // 两者叠加即可对齐（再补屏幕边距会右移约 20dp）。
                        modifier = Modifier.padding(
                            start = if (windowSize.useNavigationRail) Dimens.RailWidth else 0.dp,
                        ),
                        title = Destination.labelOf(destination),
                        subtitle = "无障碍自动化录制与回放",
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            bottomBar = {
                if (!windowSize.useNavigationRail) {
                    NavigationBar {
                        Destination.topLevel.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { navigate(item) },
                                icon = item.icon(),
                                label = Destination.labelOf(item),
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                // 缺权限提醒与新建/导入等按钮同侧堆叠：平板上放在左下角会压住
                // 左侧列表的内容（用户已反馈）。
                // Root 注入就绪时不再把无障碍算作缺失项：注入不需要无障碍服务，
                // 继续提示会让用户以为模块没生效（只有"录制"必须依赖无障碍采集）。
                val rootInputReady = executionViewModel.rootInputAvailable &&
                    executionViewModel.inputMode == InputMode.ROOT
                val missing = permissionStatus.missingCore()
                    .filterNot { rootInputReady && it == AutoRunnerPermission.ACCESSIBILITY }
                if (missing.isNotEmpty()) {
                    PermissionFab(
                        missingCount = missing.size,
                        onClick = { navigate(Destination.Settings) },
                    )
                }
                when (destination) {
                    // 脚本库的新建 / 导入 / 整理类操作放在这里，
                    // 作为右下角的悬浮按钮。
                    Destination.Scripts -> {
                        FloatingActionButton(
                            onClick = scriptListViewModel::launchImportPicker,
                            containerColor = MiuixTheme.colorScheme.secondaryContainer,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Import,
                                contentDescription = "导入 .arscript",
                                tint = MiuixTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (storedScripts.isNotEmpty()) {
                            FloatingActionButton(
                                onClick = { showClearAllDialog = true },
                                containerColor = MiuixTheme.colorScheme.errorContainer,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = "清空全部脚本",
                                    tint = MiuixTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        FloatingActionButton(
                            onClick = { navigate(Destination.Editor(null)) },
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = "新建空白脚本",
                                tint = MiuixTheme.colorScheme.onPrimary,
                            )
                        }
                    }

                    Destination.Record -> FloatingActionButton(
                        onClick = {
                            if (recordingStatus == com.autorunner.core.platform.RecordingStatus.RECORDING) {
                                recordingViewModel.stop()
                            } else {
                                recordingViewModel.start()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (recordingStatus == com.autorunner.core.platform.RecordingStatus.RECORDING) {
                                MiuixIcons.Timer
                            } else {
                                MiuixIcons.AppRecording
                            },
                            contentDescription = "录制",
                            tint = MiuixTheme.colorScheme.onPrimary,
                        )
                    }

                    else -> Unit
                }
                }
            },
        ) { padding ->
            // 破坏性操作与脚本列表里的删除共用同一套弹窗与语气（Danger），
            // 避免同一个「删除」在不同位置呈现不同的风险等级。
            ConfirmDialog(
                show = showClearAllDialog,
                title = "清空全部脚本",
                message = "该操作会删除全部 ${storedScripts.size} 个 .arscript 文件，且无法撤销。",
                confirmLabel = "确认清空",
                confirmTone = AppButtonTone.Danger,
                onConfirm = {
                    scriptListViewModel.deleteAll()
                    showClearAllDialog = false
                },
                onDismiss = { showClearAllDialog = false },
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (windowSize.useNavigationRail) {
                    // 直接交给 MIUIX 的 NavigationRail：它的根节点自带 surface 背景与分隔线，
                    // 并且会在内部先消耗 displayCutout / 侧边系统栏的 start inset（横屏刘海在左
                    // 时约 48dp），再放下 80dp 宽的内容列。
                    //
                    // 之前这里套了一层固定 `.width(Dimens.RailWidth)` 的 Column，把 rail 的可用
                    // 宽度锁死在 80dp；扣掉 48dp 的刘海 inset 后内容列只剩约 32dp，图标被压成
                    // 一个小点 —— 这就是横屏下左侧图标变小的原因。去掉外层固定宽度后，rail 的
                    // 总宽度 = 刘海 inset + 80dp，图标恢复 28dp。
                    NavigationRail {
                        Destination.topLevel.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = { navigate(item) },
                                icon = item.icon(),
                                label = Destination.labelOf(item),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // 从列表运行脚本过去是不可见的：进度只存在于悬浮面板。
                    // 下面的横幅加对话框让每个页面都能立刻看到反馈。
                    if (destination == Destination.Scripts) {
                        RunStatusBanner(
                            executionViewModel = executionViewModel,
                            windowSize = windowSize,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (windowSize.showTopAppBar) {
                                    Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        when (val current = destination) {
                            Destination.Scripts -> {
                                if (windowSize.useListDetail) {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        ScriptListScreen(
                                            viewModel = scriptListViewModel,
                                            windowSize = windowSize,
                                            onOpen = { navigate(Destination.Editor(it)) },
                                            onCreate = { navigate(Destination.Editor(null)) },
                                            onImport = scriptListViewModel::launchImportPicker,
                                            onRun = { executionViewModel.armAndGoHome(it) },
                                            selectedId = highlightedScriptId,
                                            onSelect = { navigate(Destination.Editor(it)) },
                                            // 固定主栏宽度，编辑器独占剩余空间；否则
                                            // 列表 + 编辑器 + 编辑器参数三栏互相挤压。
                                            modifier = Modifier
                                                .width(360.dp)
                                                .fillMaxHeight(),
                                        )
                                        ScriptEditorScreen(
                                            viewModel = editorViewModel,
                                            windowSize = windowSize,
                                            onBack = { navigate(Destination.Scripts) },
                                            onExport = { fileName, content ->
                                                transferController?.exportScript(fileName, content)
                                            },
                                            onCaptureRequest = { onPicked ->
                                                container.recordingController.pickAction(onPicked)
                                            },
                                            onGamepadCalibration = { mode ->
                                                container.settingsRepository.setGamepadMode(mode)
                                                container.gamepadCalibrationController.arm()
                                            },
                                            calibratedGamepadModes = calibratedGamepadModes,
                                            forceSinglePane = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                } else {
                                    ScriptListScreen(
                                        viewModel = scriptListViewModel,
                                        windowSize = windowSize,
                                        onOpen = { navigate(Destination.Editor(it)) },
                                        onCreate = { navigate(Destination.Editor(null)) },
                                        onImport = scriptListViewModel::launchImportPicker,
                                        onRun = { executionViewModel.armAndGoHome(it) },
                                    )
                                }
                            }

                            is Destination.Editor -> ScriptEditorScreen(
                                viewModel = editorViewModel,
                                windowSize = windowSize,
                                onBack = { navigate(Destination.Scripts) },
                                onExport = { fileName, content ->
                                    transferController?.exportScript(fileName, content)
                                },
                                onCaptureRequest = { onPicked ->
                                    container.recordingController.pickAction(onPicked)
                                },
                                onGamepadCalibration = { mode ->
                                    container.settingsRepository.setGamepadMode(mode)
                                    container.gamepadCalibrationController.arm()
                                },
                                calibratedGamepadModes = calibratedGamepadModes,
                            )

                            Destination.Record -> RecordingScreen(
                                viewModel = recordingViewModel,
                                windowSize = windowSize,
                                onOpenEditor = { id -> navigate(Destination.Editor(id)) },
                            )

                            Destination.Settings -> SettingsScreen(
                                viewModel = settingsViewModel,
                                windowSize = windowSize,
                            )
                        }

                    }
                }
            }
        }
    }
}

/**
 * 显示在脚本库上方的紧凑运行状态：循环计数、当前动作以及暂停 / 停止控件，
 * 让从列表启动脚本不再是一次悄无声息的空操作。
 */
@Composable
private fun RunStatusBanner(
    executionViewModel: ExecutionViewModel,
    windowSize: AutoRunnerWindowSize,
) {
    val progress by executionViewModel.progress.collectAsStateWithLifecycle()
    val state by executionViewModel.state.collectAsStateWithLifecycle()
    val message by executionViewModel.message.collectAsStateWithLifecycle()

    val horizontal = if (windowSize.isTablet) Dimens.ScreenPaddingWide else Dimens.ScreenPadding

    val visible = state.isActive || message != null ||
        (progress.completedLoops > 0 && progress.scriptName.isNotBlank())

    if (visible) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // List-Detail 时外壳不再为脚本页渲染顶栏，运行状态卡需要自己避让状态栏，
                // 否则横屏下会被状态栏遮挡。
                .then(
                    if (windowSize.useListDetail) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars.only(WindowInsetsSides.Top),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = horizontal, vertical = 8.dp),
            insideMargin = PaddingValues(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = progress.scriptName.ifBlank { "未选择脚本" },
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "循环 ${progress.loopLabel} · 耗时 ${progress.elapsedLabel}" +
                            if (progress.totalActions > 0 && progress.currentActionIndex >= 0) {
                                " · 动作 ${progress.currentActionIndex + 1}/${progress.totalActions}"
                            } else {
                                ""
                            },
                        modifier = Modifier.padding(top = 2.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    progress.message?.let {
                        Text(
                            text = it,
                            modifier = Modifier.padding(top = 2.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isActive) {
                        TextButton(
                            text = if (state == ExecutionState.PAUSED) "继续" else "暂停",
                            onClick = { executionViewModel.togglePause() },
                        )
                        TextButton(
                            text = "停止",
                            onClick = { executionViewModel.stop() },
                        )
                    } else {
                        TextButton(
                            text = "清除",
                            onClick = { executionViewModel.reset() },
                        )
                    }
                }
            }
        }
    }

    MessageDialog(
        message = message,
        onDismiss = executionViewModel::dismissMessage,
        title = if (state.isActive) "执行提示" else "执行结果",
    )
}

/** 每个导航项对应的 MIUIX 图标。 */
private fun Destination.icon() = when (this) {
    Destination.Scripts -> MiuixIcons.Tasks
    Destination.Record -> MiuixIcons.AppRecording
    Destination.Settings -> MiuixIcons.Settings
    is Destination.Editor -> MiuixIcons.Play
}

/** 仍缺少必要权限时显示的提醒（§9）。 */
@Composable
private fun PermissionFab(
    missingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MiuixTheme.colorScheme.errorContainer,
        ) {
            Icon(
                imageVector = MiuixIcons.Report,
                contentDescription = "还缺少 $missingCount 项必要权限，点击前往开启",
                tint = MiuixTheme.colorScheme.onErrorContainer,
            )
        }
        // 数量角标，让按钮一眼就能看出还缺多少项权限。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.error),
            contentAlignment = Alignment.Center,
        ) {
            CenteredText(
                text = missingCount.toString(),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onError,
            )
        }
    }
}
