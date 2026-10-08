package com.autorunner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.CoordinateSpace
import com.autorunner.core.model.DelayStep
import com.autorunner.core.model.displayLabel
import com.autorunner.core.model.GamepadAction
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.KeyStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.model.TouchPoint
import com.autorunner.core.model.withDelay
import com.autorunner.core.model.withName
import com.autorunner.ui.adaptive.AutoRunnerWindowSize
import com.autorunner.ui.components.ActionStepRow
import com.autorunner.ui.components.AppButton
import com.autorunner.ui.components.AppSegmentedChoice
import com.autorunner.ui.components.ExecutionConfigSection
import com.autorunner.ui.components.GamepadCalibrationEntry
import com.autorunner.ui.components.GamepadLayout
import com.autorunner.ui.components.MessageDialog
import com.autorunner.ui.components.NumberField
import com.autorunner.ui.components.PageScaffold
import com.autorunner.ui.components.RowAction
import com.autorunner.ui.components.SectionCard
import com.autorunner.ui.components.SliderPreference
import com.autorunner.ui.components.StickValueHint
import com.autorunner.ui.components.TagPill
import com.autorunner.ui.components.parseNumberInput
import com.autorunner.ui.theme.Dimens
import com.autorunner.ui.viewmodel.ScriptEditorViewModel

/**
 * 可视化脚本编辑器（§6.5）。
 *
 * * 手机（`compact`）——逐步导航：显示动作列表，或选中动作的参数编辑器。
 * * 平板（`expanded`）——Supporting Pane 布局：时间线列表在左侧滚动，
 *   参数编辑器 + 实时预览固定在右侧。
 */
@Composable
fun ScriptEditorScreen(
    viewModel: ScriptEditorViewModel,
    windowSize: AutoRunnerWindowSize,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onExport: ((fileName: String, content: String) -> Unit)? = null,
    /**
     * 捕获一次真实手势并上报分类后的动作。
     *
     * 由 app 层提供（`RecordingController.pickAction`）；无法采集时返回 `false`。
     */
    onCaptureRequest: ((ActionStep) -> Unit) -> Boolean = { false },
    /**
     * 进入手柄按键标定：把 App 退到后台并弹出标定悬浮层，让用户在实际游戏
     * 页面上对齐每个按键。由 app 层（`GamepadCalibrationController.arm`）提供，
     * 入参是要标定的手柄类型；返回 `false` 表示无法开始（例如缺少悬浮窗权限）。
     */
    onGamepadCalibration: (GamepadMode) -> Boolean = { false },
    /**
     * 已经完成标定的手柄类型集合（来自设置页）。脚本的 gamepad 动作使用该脚本
     * 自身的手柄类型，因此仅当脚本类型命中集合时才跳过就地标定引导。
     */
    calibratedGamepadModes: Set<GamepadMode> = emptySet(),
    /**
     * `true` 表示宿主已经分过窗（主从详情）：此时编辑器保持单栏，
     * 否则它自己的 supporting-pane 分栏会嵌套进一根狭窄的列里。
     */
    forceSinglePane: Boolean = false,
) {
    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val flow by viewModel.flow.collectAsState()
    val config by viewModel.execution.collectAsState()
    val coordinateSpace by viewModel.coordinateSpace.collectAsState()
    val gamepadMode by viewModel.gamepadMode.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val dirty by viewModel.dirty.collectAsState()
    val validation by viewModel.validation.collectAsState()
    val message by viewModel.message.collectAsState()

    val scriptId by viewModel.scriptId.collectAsState()

    val gamepadCalibrated = gamepadMode in calibratedGamepadModes

    val showDetailOnPhone = selectedIndex != null
    val sideBySide = windowSize.useSupportingPane && !forceSinglePane

    // 与其它页面共用同一个 MIUIX 大标题顶栏，标题字号/位置/左右内边距完全一致。
    val editorTitle = scriptId?.let { name.ifBlank { "未命名脚本" } } ?: "新建脚本"
    val editorSubtitle = if (flow.isEmpty()) {
        "还没有动作 · ${config.describe()}"
    } else {
        "${flow.size} 个动作 · ${config.describe()} · ${validation.summary()}"
    }

    // 与其它页面共用 PageScaffold：顶栏字号、insets 处理与滚动连接只有一份实现。
    PageScaffold(
        windowSize = windowSize,
        title = editorTitle,
        subtitle = editorSubtitle,
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack, minWidth = 40.dp, minHeight = 40.dp) {
                Icon(
                    imageVector = MiuixIcons.ChevronBackward,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        actions = { SaveAction(dirty = dirty, onSave = { viewModel.save() }) },
    ) {
        if (sideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
            ) {
                EditorTimeline(
                    viewModel = viewModel,
                    flow = flow,
                    selectedIndex = selectedIndex,
                    modifier = Modifier.weight(1f),
                    onExport = onExport,
                    onCaptureRequest = onCaptureRequest,
                    onGamepadCalibration = onGamepadCalibration,
                    gamepadMode = gamepadMode,
                    gamepadCalibrated = gamepadCalibrated,
                )
                EditorDetailPane(
                    viewModel = viewModel,
                    windowSize = windowSize,
                    modifier = Modifier
                        .width(Dimens.PreviewPaneWidth + 120.dp)
                        .fillMaxSize(),
                    onGamepadCalibration = onGamepadCalibration,
                    gamepadMode = gamepadMode,
                    gamepadCalibrated = gamepadCalibrated,
                )
            }
        } else if (showDetailOnPhone) {
            EditorDetailPane(
                viewModel = viewModel,
                windowSize = windowSize,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.ScreenPadding),
                showBackToList = true,
                onCaptureRequest = onCaptureRequest,
                onGamepadCalibration = onGamepadCalibration,
                gamepadMode = gamepadMode,
                gamepadCalibrated = gamepadCalibrated,
            )
        } else {
            EditorTimeline(
                viewModel = viewModel,
                flow = flow,
                selectedIndex = selectedIndex,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.ScreenPadding),
                onExport = onExport,
                onCaptureRequest = onCaptureRequest,
                onGamepadCalibration = onGamepadCalibration,
                gamepadMode = gamepadMode,
                gamepadCalibrated = gamepadCalibrated,
            )
        }
    }

    MessageDialog(
        message = message,
        onDismiss = viewModel::dismissMessage,
    )
}

/**
 * 顶栏右侧的保存动作：新建、编辑脚本都通过它落盘；没有改动时禁用。
 */
@Composable
private fun SaveAction(dirty: Boolean, onSave: () -> Unit) {
    TextButton(
        text = "保存",
        onClick = onSave,
        enabled = dirty,
    )
}

// ---------------------------------------------------------------------------
// 时间线（动作列表）
// ---------------------------------------------------------------------------

@Composable
private fun EditorTimeline(
    viewModel: ScriptEditorViewModel,
    flow: List<ActionStep>,
    selectedIndex: Int?,
    modifier: Modifier = Modifier,
    onExport: ((String, String) -> Unit)? = null,
    onCaptureRequest: ((ActionStep) -> Unit) -> Boolean = { false },
    onGamepadCalibration: (GamepadMode) -> Boolean = { false },
    gamepadMode: GamepadMode = GamepadMode.Default,
    gamepadCalibrated: Boolean = false,
) {
    var showAddPanel by remember { mutableStateOf(false) }
    val coordinateSpace by viewModel.coordinateSpace.collectAsState()
    val metrics = viewModel.screenMetrics
    val centerX = if (metrics.isValid) metrics.widthPx / 2f else 540f
    val centerY = if (metrics.isValid) metrics.heightPx / 2f else 1200f

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            item {
                ScriptMetaCard(viewModel = viewModel)
            }

            if (flow.isEmpty()) {
                item {
                    Card(insideMargin = Dimens.EmptyStatePadding) {
                        Text(
                            text = "还没有任何动作",
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "点击下方「添加动作」插入点击、滑动、长按、多点触控或手柄动作，" +
                                "也可以在「录制」页录制后再回到这里微调。",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    }
                }
            }

            itemsIndexed(flow, key = { index, _ -> index }) { index, step ->
                Card(insideMargin = PaddingValues(0.dp)) {
                                Column {
                        ActionStepRow(
                            index = index,
                            step = step,
                            selected = index == selectedIndex,
                            onClick = { viewModel.select(index) },
                            gamepadMode = gamepadMode,
                        )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        TextButton(
                                            text = "上移",
                                            onClick = { viewModel.moveStep(index, index - 1, followStep = false) },
                                            enabled = index > 0,
                                        )
                                        TextButton(
                                            text = "下移",
                                            onClick = { viewModel.moveStep(index, index + 1, followStep = false) },
                                            enabled = index < flow.lastIndex,
                                        )
                                        TextButton(
                                            text = "复制",
                                            onClick = { viewModel.duplicateStep(index, followStep = false) },
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        RowAction(
                                            imageVector = MiuixIcons.Delete,
                                            contentDescription = "删除该动作",
                                            tint = MiuixTheme.colorScheme.error,
                                            onClick = { viewModel.removeStep(index, followStep = false) },
                                        )
                                    }
                                }
                }
            }
        }

        if (showAddPanel) {
            AddActionPanel(
                centerX = centerX,
                centerY = centerY,
                onCaptureRequest = onCaptureRequest,
                onGamepadCalibration = onGamepadCalibration,
                gamepadMode = gamepadMode,
                gamepadCalibrated = gamepadCalibrated,
                onAdd = { step ->
                    viewModel.addStep(step)
                    showAddPanel = false
                },
                onDismiss = { showAddPanel = false },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(
                onClick = { showAddPanel = !showAddPanel },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
                Text(
                    text = "添加",
                    modifier = Modifier.padding(start = 6.dp),
                    color = MiuixTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
            TextButton(
                text = if (coordinateSpace == CoordinateSpace.ABSOLUTE) "转百分比" else "转绝对",
                onClick = { viewModel.toggleCoordinateSpace() },
                modifier = Modifier.weight(1f),
            )
            if (onExport != null) {
                TextButton(
                    text = "导出",
                    onClick = { onExport(viewModel.suggestedFileName(), viewModel.exportText()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 正在编辑的脚本的名称 / 描述 / 坐标空间。 */
@Composable
private fun ScriptMetaCard(viewModel: ScriptEditorViewModel) {
    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val coordinateSpace by viewModel.coordinateSpace.collectAsState()
    val gamepadMode by viewModel.gamepadMode.collectAsState()
    val config by viewModel.execution.collectAsState()
    val metrics = viewModel.screenMetrics
    val gamepadModes = GamepadMode.entries

    Card(insideMargin = Dimens.CardContentPadding) {
        TextField(
            value = name,
            onValueChange = viewModel::setName,
            label = "脚本名称",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TextField(
            value = description,
            onValueChange = viewModel::setDescription,
            label = "描述",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            maxLines = 3,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagPill(
                text = if (coordinateSpace == CoordinateSpace.ABSOLUTE) "绝对坐标" else "百分比坐标",
            )
            TagPill(
                text = if (metrics.isValid) "${metrics.widthPx}×${metrics.heightPx}" else "未知分辨率",
            )
        }
        Text(
            text = if (coordinateSpace == CoordinateSpace.ABSOLUTE) {
                "绝对坐标直接使用录制设备像素；转换为百分比后可在不同分辨率设备上复用。"
            } else {
                "百分比坐标以 0.0~1.0 存储，执行时按当前设备分辨率换算。"
            },
            modifier = Modifier.padding(top = 8.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "手柄类型",
            modifier = Modifier.padding(top = 12.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        AppSegmentedChoice(
            options = gamepadModes.map { it.displayName },
            selectedIndex = gamepadModes.indexOf(gamepadMode),
            onSelected = { viewModel.setGamepadMode(gamepadModes[it]) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        Text(
            text = "本脚本的手柄动作按所选类型的标定坐标执行；请先在设置页完成该类型的按键标定。",
            modifier = Modifier.padding(top = 6.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        // 脚本级「循环任务」设置：以整段脚本为循环主体，直接放在脚本卡片里，
        // 运行时作为该脚本的默认执行配置（写入脚本的 execution 字段）。
        ExecutionConfigSection(
            config = config,
            onModeChange = viewModel::setMode,
            onRepeatCountChange = viewModel::setRepeatCount,
            onIntervalChange = viewModel::setIntervalMs,
            onFailureStrategyChange = viewModel::setFailureStrategy,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "以整个脚本为循环主体：选择重复执行后可设置循环次数与间隔，运行时作为默认值。",
            modifier = Modifier.padding(top = 6.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------------------------------------------------------------------------
// 详情窗格：参数 + 执行配置 + 预览
// ---------------------------------------------------------------------------

@Composable
private fun EditorDetailPane(
    viewModel: ScriptEditorViewModel,
    windowSize: AutoRunnerWindowSize,
    modifier: Modifier = Modifier,
    showBackToList: Boolean = false,
    onCaptureRequest: ((ActionStep) -> Unit) -> Boolean = { false },
    onGamepadCalibration: (GamepadMode) -> Boolean = { false },
    gamepadMode: GamepadMode = GamepadMode.Default,
    gamepadCalibrated: Boolean = false,
) {
    val flow by viewModel.flow.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val validation by viewModel.validation.collectAsState()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
    ) {
        if (showBackToList) {
            TextButton(
                text = "← 返回动作列表",
                onClick = { viewModel.select(null) },
            )
        }

        val index = selectedIndex
        if (index != null && index in flow.indices) {
            ActionParameterEditor(
                index = index,
                step = flow[index],
                normalised = viewModel.coordinateSpace.value == CoordinateSpace.NORMALIZED,
                onApply = { viewModel.updateStep(index, it) },
                onDelete = { viewModel.removeStep(index) },
                onCaptureRequest = onCaptureRequest,
                onGamepadCalibration = onGamepadCalibration,
                gamepadMode = gamepadMode,
                gamepadCalibrated = gamepadCalibrated,
                canMoveUp = index > 0,
                canMoveDown = index < flow.lastIndex,
                onMoveUp = { viewModel.moveStep(index, index - 1) },
                onMoveDown = { viewModel.moveStep(index, index + 1) },
                onDuplicate = { viewModel.duplicateStep(index) },
            )
        } else {
            Card(insideMargin = Dimens.CardContentPadding) {
                Text(
                    text = "选择一个动作以编辑参数",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
        }

        SectionCard(title = "预览与校验", subtitle = validation.summary()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "共 ${flow.size} 个动作，预计单轮耗时 ${viewModel.currentScript.estimatedDurationMs} ms",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                validation.errors.take(5).forEach { issue ->
                    Text(
                        text = buildString {
                            issue.stepIndex?.let { append("第 ${it + 1} 个动作：") }
                            append(issue.message)
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                validation.warnings.take(4).forEach { issue ->
                    Text(
                        text = buildString {
                            issue.stepIndex?.let { append("第 ${it + 1} 个动作：") }
                            append(issue.message)
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 动作创建
// ---------------------------------------------------------------------------

/** 新增动作面板围绕的三类输入方式。 */
private enum class AddActionModule(val label: String) {
    Touch("触摸"),
    KeyInput("键盘输入"),
    Gamepad("手柄输入"),
}

/**
 * 创建新动作的底部面板。
 *
 * 围绕用户在屏幕上实际会做的三类操作组织——**触摸**、**键盘输入**、
 * **手柄输入**，用分段开关选择。手柄模块会渲染一块屏幕上的手柄盘
 * （[GamepadLayout]），让按钮*点在它所在的位置*，而不是从名称列表里挑。
 *
 * 以内联方式渲染（而不是放进 `WindowBottomSheet`），这样编辑器在
 * 悬浮窗宿主内也能正常工作。
 */
@Composable
private fun AddActionPanel(
    centerX: Float,
    centerY: Float,
    onAdd: (ActionStep) -> Unit,
    onDismiss: () -> Unit,
    onCaptureRequest: ((ActionStep) -> Unit) -> Boolean = { false },
    onGamepadCalibration: (GamepadMode) -> Boolean = { false },
    gamepadMode: GamepadMode = GamepadMode.Default,
    gamepadCalibrated: Boolean = false,
) {
    var moduleIndex by remember { mutableStateOf(0) }
    val modules = AddActionModule.entries

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column {
            AppSegmentedChoice(
                options = modules.map { it.label },
                selectedIndex = moduleIndex,
                onSelected = { moduleIndex = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (modules[moduleIndex]) {
                    AddActionModule.Touch -> TouchModule(
                        centerX = centerX,
                        centerY = centerY,
                        onAdd = onAdd,
                        onCaptureRequest = onCaptureRequest,
                    )

                    AddActionModule.KeyInput -> KeyInputModule(onAdd = onAdd)

                    AddActionModule.Gamepad -> GamepadAddModule(
                        onAdd = onAdd,
                        onGamepadCalibration = onGamepadCalibration,
                        gamepadMode = gamepadMode,
                        gamepadCalibrated = gamepadCalibrated,
                    )
                }

                TextButton(
                    text = "收起",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 触摸动作模块：点击 / 长按 / 滑动 / 多点触控 / 等待。 */
@Composable
private fun TouchModule(
    centerX: Float,
    centerY: Float,
    onAdd: (ActionStep) -> Unit,
    onCaptureRequest: ((ActionStep) -> Unit) -> Boolean,
) {
    Column {
        BasicComponent(
            title = "从实际操作录入",
            summary = "在屏幕上完成一次点击 / 长按 / 滑动，自动填入坐标与时长",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.AppRecording,
                    contentDescription = null,
                    tint = com.autorunner.ui.theme.AutoRunnerColors.Recording,
                )
            },
            onClick = { onCaptureRequest { captured -> onAdd(captured) } },
            insideMargin = Dimens.PreferenceRowPadding,
        )
        TouchPreset("点击", "在屏幕中心插入一次点击", onAdd) { TapStep(x = centerX, y = centerY) }
        TouchPreset("长按", "按住 800ms 后抬起", onAdd) { LongPressStep(x = centerX, y = centerY) }
        TouchPreset("滑动", "从下往上滑动 300ms", onAdd) {
            SwipeStep(fromX = centerX, fromY = centerY * 1.6f, toX = centerX, toY = centerY * 0.4f)
        }
        TouchPreset("左滑", "从右往左滑动 300ms（桌面翻页）", onAdd) {
            SwipeStep(fromX = centerX * 1.6f, fromY = centerY, toX = centerX * 0.4f, toY = centerY)
        }
        TouchPreset("右滑", "从左往右滑动 300ms（返回上一页）", onAdd) {
            SwipeStep(fromX = centerX * 0.4f, fromY = centerY, toX = centerX * 1.6f, toY = centerY)
        }
        TouchPreset("多点触控", "双指手势（可编辑每个触点）", onAdd) {
            MultiTouchStep(
                points = listOf(TouchPoint(centerX - 140f, centerY), TouchPoint(centerX + 140f, centerY)),
            )
        }
        TouchPreset("等待", "插入一段固定等待时间", onAdd) { DelayStep(duration = 1000L) }
    }
}

/** 触摸模块中的一行预设动作。 */
@Composable
private fun TouchPreset(
    title: String,
    summary: String,
    onAdd: (ActionStep) -> Unit,
    build: () -> ActionStep,
) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = { onAdd(build()) },
        insideMargin = Dimens.CompactRowPadding,
    )
}

/** 键盘输入模块：填一段文本，执行时由无障碍服务写入当前焦点输入框。 */
@Composable
private fun KeyInputModule(onAdd: (ActionStep) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "输入要发送到当前焦点输入框的文本；执行时由无障碍服务写入，运行前请先点选目标输入框。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextField(
            value = text,
            onValueChange = { text = it },
            label = "输入文本",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            maxLines = 4,
        )
        AppButton(
            onClick = { onAdd(KeyStep(text = text)) },
            enabled = text.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("添加键盘输入", color = MiuixTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * 手柄输入模块：在可视化手柄上点按按键 / 拖动左摇杆来生成动作。
 */
@Composable
private fun GamepadAddModule(
    onAdd: (ActionStep) -> Unit,
    onGamepadCalibration: (GamepadMode) -> Boolean = { false },
    gamepadMode: GamepadMode = GamepadMode.Default,
    gamepadCalibrated: Boolean = false,
) {
    var stickX by remember { mutableStateOf(0f) }
    var stickY by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "点按下方手柄上的按键位置即可添加对应动作；拖动摇杆设定方向后点「添加摇杆动作」。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        // 设备级标定已在设置页完成时，按键位置固定不变，无需再次引导用户标定。
        if (!gamepadCalibrated) {
            GamepadCalibrationEntry(
                mode = gamepadMode,
                onGamepadCalibration = onGamepadCalibration,
                modifier = Modifier.padding(top = 8.dp),
                insideMargin = Dimens.InsetPreferenceRowPadding,
            )
        }
        GamepadLayout(
            modifier = Modifier.padding(top = 12.dp),
            mode = gamepadMode,
            stickX = stickX,
            stickY = stickY,
            onButton = { button -> onAdd(GamepadStep(button = button, action = GamepadAction.CLICK)) },
            onStick = { x, y ->
                stickX = x
                stickY = y
            },
        )
        StickValueHint(
            x = stickX,
            y = stickY,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            onReset = {
                stickX = 0f
                stickY = 0f
            },
        )
        TextButton(
            text = "添加摇杆动作",
            onClick = { onAdd(GamepadStep(action = GamepadAction.STICK, x = stickX, y = stickY)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// 单个动作的参数编辑
// ---------------------------------------------------------------------------

/**
 * 编辑单个动作的参数：坐标、时长和其后的延迟。
 * 一切先保存在本地状态，点「应用」时才生效。
 */
@Composable
private fun ActionParameterEditor(
    index: Int,
    step: ActionStep,
    normalised: Boolean,
    onApply: (ActionStep) -> Unit,
    onDelete: () -> Unit,
    onCaptureRequest: ((ActionStep) -> Unit) -> Boolean = { false },
    onGamepadCalibration: (GamepadMode) -> Boolean = { false },
    gamepadMode: GamepadMode = GamepadMode.Default,
    gamepadCalibrated: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onDuplicate: () -> Unit = {},
) {
    // 「序号 + 动作类型」是这一步编辑器的稳定身份：切换动作时重置全部子状态
    // （输入框原文、错误态…），但同一动作上的普通重组不会清掉用户正在输入的内容。
    // 之前直接拿整个 ActionStep 当 key，任何外部改写都会重置草稿。
    key(index, step.typeName) {
        ActionParameterEditorBody(
            index = index,
            step = step,
            normalised = normalised,
            onApply = onApply,
            onDelete = onDelete,
            onCaptureRequest = onCaptureRequest,
            onGamepadCalibration = onGamepadCalibration,
            gamepadMode = gamepadMode,
            gamepadCalibrated = gamepadCalibrated,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDuplicate = onDuplicate,
        )
    }
}

@Composable
private fun ActionParameterEditorBody(
    index: Int,
    step: ActionStep,
    normalised: Boolean,
    onApply: (ActionStep) -> Unit,
    onDelete: () -> Unit,
    onCaptureRequest: ((ActionStep) -> Unit) -> Boolean,
    onGamepadCalibration: (GamepadMode) -> Boolean,
    gamepadMode: GamepadMode,
    gamepadCalibrated: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
) {
    var draft by remember { mutableStateOf(step) }
    // 单独保存名称原文：`withName` 会 trim，直接把它喂回输入框会让行尾空格被吞掉。
    var nameText by remember { mutableStateOf(step.name.orEmpty()) }

    // ViewModel 仍可能整体改写这个步骤（坐标空间转换、排序、录制回填）。那种情况
    // 必须用新值覆盖本地草稿，否则「应用」会把旧坐标写回已经转换过的脚本；覆盖时
    // 明确提示用户，不再静默丢弃未应用的修改。
    var syncedStep by remember { mutableStateOf(step) }
    var draftOverwritten by remember { mutableStateOf(false) }
    // 本地「应用」也会让 step 变化，不能误判成外部改写。
    var appliedLocally by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step != syncedStep) {
            draftOverwritten = !appliedLocally && draft != syncedStep
            appliedLocally = false
            syncedStep = step
            draft = step
            nameText = step.name.orEmpty()
        }
    }

    // 解析不出数值的输入（被清空 / 非法字符）必须阻止「应用」，否则用户以为改过了，
    // 实际写回的仍是上一次的旧值。
    val invalidInputs = remember { mutableStateMapOf<String, Boolean>() }
    val hasInvalidInput = invalidInputs.values.any { it }
    val reportValidity: (String, Boolean) -> Unit = { fieldKey, valid ->
        invalidInputs[fieldKey] = !valid
    }

    SectionCard(
        title = "动作 ${index + 1}",
        subtitle = draft.displayLabel(gamepadMode),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (draftOverwritten) {
                Text(
                    text = "该动作已被外部更新（例如坐标空间转换），尚未应用的本地修改已按新值同步。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            TextField(
                value = nameText,
                onValueChange = { raw ->
                    nameText = raw
                    draft = draft.withName(raw)
                },
                label = "步骤名称（可选）",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            when (val current = draft) {
                is TapStep -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberFieldRow("X 坐标", current.x, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(x = it)
                        }
                        NumberFieldRow("Y 坐标", current.y, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(y = it)
                        }
                    }
                }

                is LongPressStep -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberFieldRow("X 坐标", current.x, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(x = it)
                        }
                        NumberFieldRow("Y 坐标", current.y, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(y = it)
                        }
                    }
                }

                is SwipeStep -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberFieldRow("起点 X", current.fromX, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(fromX = it)
                        }
                        NumberFieldRow("起点 Y", current.fromY, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(fromY = it)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberFieldRow("终点 X", current.toX, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(toX = it)
                        }
                        NumberFieldRow("终点 Y", current.toY, normalised, Modifier.weight(1f), reportValidity) {
                            draft = current.copy(toY = it)
                        }
                    }
                }

                is MultiTouchStep -> {
                    NumberFieldRow("手势时长 (ms)", current.duration.toFloat(), false, onValidityChange = reportValidity) {
                        draft = current.copy(duration = it.toLong())
                    }
                    current.points.forEachIndexed { pointIndex, point ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NumberFieldRow(
                                title = "触点 ${pointIndex + 1} X",
                                value = point.x,
                                normalised = normalised,
                                modifier = Modifier.weight(1f),
                                onValidityChange = reportValidity,
                            ) { newX ->
                                val updated = current.points.toMutableList()
                                updated[pointIndex] = point.copy(x = newX)
                                draft = current.copy(points = updated)
                            }
                            NumberFieldRow(
                                title = "触点 ${pointIndex + 1} Y",
                                value = point.y,
                                normalised = normalised,
                                modifier = Modifier.weight(1f),
                                onValidityChange = reportValidity,
                            ) { newY ->
                                val updated = current.points.toMutableList()
                                updated[pointIndex] = point.copy(y = newY)
                                draft = current.copy(points = updated)
                            }
                        }
                    }
                    TextButton(
                        text = "添加触点",
                        onClick = { draft = current.copy(points = current.points + TouchPoint(540f, 1200f)) },
                    )
                }

                is GamepadStep -> {
                    // 设备级标定已完成时不再引导用户在此重复标定。
                    if (!gamepadCalibrated) {
                        GamepadCalibrationEntry(
                            mode = gamepadMode,
                            onGamepadCalibration = onGamepadCalibration,
                            insideMargin = Dimens.InsetPreferenceRowPadding,
                        )
                    }
                    AppSegmentedChoice(
                        options = listOf("按键", "摇杆"),
                        selectedIndex = if (current.action == GamepadAction.STICK) 1 else 0,
                        onSelected = { segment ->
                            draft = if (segment == 1) {
                                current.copy(action = GamepadAction.STICK)
                            } else {
                                current.copy(
                                    action = if (current.action == GamepadAction.STICK) {
                                        GamepadAction.CLICK
                                    } else {
                                        current.action
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (current.action == GamepadAction.STICK) {
                        GamepadLayout(
                            modifier = Modifier.padding(top = 8.dp),
                            mode = gamepadMode,
                            stickX = current.x,
                            stickY = current.y,
                            onButton = {},
                            onStick = { x, y -> draft = current.copy(x = x, y = y) },
                        )
                        StickValueHint(
                            x = current.x,
                            y = current.y,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            onReset = { draft = current.copy(x = 0f, y = 0f) },
                        )
                    } else {
                        Text(
                            text = "当前按键：${current.button.labelFor(gamepadMode)} · ${current.action.serialName}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                        GamepadLayout(
                            modifier = Modifier.padding(top = 8.dp),
                            selectedButton = current.button,
                            mode = gamepadMode,
                            onButton = { button ->
                                val nextAction = if (current.action == GamepadAction.STICK) {
                                    GamepadAction.CLICK
                                } else {
                                    current.action
                                }
                                draft = current.copy(button = button, action = nextAction)
                            },
                            onStick = { _, _ -> },
                        )
                    }
                    if (current.action == GamepadAction.TRIGGER) {
                        SliderPreference(
                            title = "触发强度",
                            value = current.value,
                            onValueChange = { draft = current.copy(value = it) },
                            valueRange = 0f..1f,
                            valueLabel = { "${(it * 100).toInt()}%" },
                        )
                    }
                }

                is KeyStep -> {
                    TextField(
                        value = current.text,
                        onValueChange = { draft = current.copy(text = it) },
                        label = "输入文本",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                    Text(
                        text = "执行时由无障碍服务写入当前焦点输入框，运行前请先点选目标输入框。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                is DelayStep -> Unit
            }

            // 时长与「动作后延迟」并排显示；没有时长的动作只占右侧一格。
            val durationLabel = when (draft) {
                is TapStep -> "按压时长 (ms)"
                is LongPressStep -> "按住时长 (ms)"
                is SwipeStep -> "滑动时长 (ms)"
                is DelayStep -> "等待时长 (ms)"
                else -> null
            }
            val durationValue = when (val current = draft) {
                is TapStep -> current.duration
                is LongPressStep -> current.duration
                is SwipeStep -> current.duration
                is DelayStep -> current.duration
                else -> null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (durationLabel != null && durationValue != null) {
                    NumberFieldRow(durationLabel, durationValue.toFloat(), false, Modifier.weight(1f), reportValidity) { newDuration ->
                        val millis = newDuration.toLong()
                        draft = when (val current = draft) {
                            is TapStep -> current.copy(duration = millis)
                            is LongPressStep -> current.copy(duration = millis)
                            is SwipeStep -> current.copy(duration = millis)
                            is DelayStep -> current.copy(duration = millis)
                            else -> current
                        }
                    }
                }
                NumberFieldRow("动作后延迟 (ms)", draft.delay.toFloat(), false, Modifier.weight(1f), reportValidity) { newDelay ->
                    draft = draft.withDelay(newDelay.toLong())
                }
            }

            // 单个动作的管理：调整顺序 / 复制 / 删除，与下方「应用」放在一起，
            // 手机上选中动作后进入本面板即可完成全部操作。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(text = "上移", enabled = canMoveUp, onClick = onMoveUp)
                TextButton(text = "下移", enabled = canMoveDown, onClick = onMoveDown)
                TextButton(text = "复制", onClick = onDuplicate)
            }

            TextButton(
                text = "从实际操作录入（覆盖当前坐标与时长）",
                onClick = {
                    onCaptureRequest { captured ->
                        // 保留这一步用户配置的名称与延迟；
                        // 刚分类出来的动作只携带坐标 / 时长。
                        draft = captured.withName(nameText).withDelay(draft.delay)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(
                    onClick = {
                        appliedLocally = true
                        draftOverwritten = false
                        onApply(draft)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !hasInvalidInput,
                ) {
                    Text("应用", color = MiuixTheme.colorScheme.onPrimary)
                }
                TextButton(
                    text = "删除该动作",
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
            if (hasInvalidInput) {
                Text(
                    text = "有数值输入为空或无法解析，补齐后「应用」才会生效。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 数值输入行；`normalised` 在像素与 0~1 归一化小数之间切换。
 *
 * 输入原文保存在本地，`onValueChange` 只在能解析出数值时回调；末尾小数点
 * （`540.`）按 `540` 解析，因此不会出现「界面显示已输入、实际值没变」的静默丢失。
 * 解析失败时 [onValidityChange] 上报 `false`，由父级禁用「应用」。
 */
@Composable
private fun NumberFieldRow(
    title: String,
    value: Float,
    normalised: Boolean,
    modifier: Modifier = Modifier,
    onValidityChange: ((fieldKey: String, valid: Boolean) -> Unit)? = null,
    onValueChange: (Float) -> Unit,
) {
    fun format(number: Float): String = if (normalised) number.toString() else number.toInt().toString()

    var text by remember { mutableStateOf(format(value)) }
    // 自己回传上去的值会原样回到这里，不能拿它覆盖用户正在输入的原文
    // （例如归一化坐标输入到一半的 `0.`）。
    var lastEmitted by remember { mutableStateOf(value) }
    var lastNormalised by remember { mutableStateOf(normalised) }
    LaunchedEffect(value, normalised) {
        if (value != lastEmitted || normalised != lastNormalised) {
            text = format(value)
        }
        lastEmitted = value
        lastNormalised = normalised
    }

    val parsed = parseNumberInput(text)
    LaunchedEffect(parsed == null) { onValidityChange?.invoke(title, parsed != null) }
    DisposableEffect(title) {
        onDispose { onValidityChange?.invoke(title, true) }
    }

    BasicComponent(
        modifier = modifier,
        title = title,
        summary = if (normalised) "0.0 ~ 1.0" else "像素",
        bottomAction = {
            NumberField(
                value = text,
                onValueChange = { raw ->
                    text = raw
                    parseNumberInput(raw)?.let { parsedValue ->
                        lastEmitted = parsedValue
                        onValueChange(parsedValue)
                    }
                },
                label = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                allowDecimal = normalised,
                isError = parsed == null,
                errorMessage = if (parsed == null) "请输入数值" else null,
            )
        },
        insideMargin = Dimens.InsetRowPadding,
    )
}
