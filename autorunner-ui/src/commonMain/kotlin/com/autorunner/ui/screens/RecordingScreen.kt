/*
 * AutoRunner — 录制页面（共享 UI，commonMain）
 *
 * 覆盖设计文档中的以下章节：
 *  - §6.1.1 录制原理：说明透明覆盖层（TYPE_ACCESSIBILITY_OVERLAY）如何捕获触摸，
 *    以及被分类后的手势如何通过 dispatchGesture 回放到下层应用；
 *  - §5.3 Feed 模式：录制过程中的事件流实时展示为动作列表；
 *  - §5.1 / §5.2 多设备适配：窄屏使用单一 LazyColumn 串联控制区与事件流，
 *    宽屏使用「控制区 + 实时动作流」左右分栏；
 *  - §6.2 脚本格式：录制结束后可直接保存为 .arscript 并跳转编辑器。
 *
 * 页面通过 `RecordingController` 驱动实际的采集过程，本身不依赖任何平台 API。
 */
package com.autorunner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autorunner.core.model.ActionStep
import com.autorunner.core.platform.RecordingStatus
import com.autorunner.ui.adaptive.AutoRunnerWindowSize
import com.autorunner.ui.adaptive.LayoutMode
import com.autorunner.ui.components.ActionStepRow
import com.autorunner.ui.components.AppButton
import com.autorunner.ui.components.MessageDialog
import com.autorunner.ui.components.MetricsRow
import com.autorunner.ui.components.PageScaffold
import com.autorunner.ui.components.SectionCard
import com.autorunner.ui.components.StatusPill
import com.autorunner.ui.theme.AutoRunnerColors
import com.autorunner.ui.theme.Dimens
import com.autorunner.ui.viewmodel.RecordingViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Recording
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 录制中状态使用的强调色。 */
private val RecordingAccent: Color get() = AutoRunnerColors.Recording

/**
 * AutoRunner 的“录制”页。
 *
 * 页面由三部分组成：
 *
 *  1. **会话卡片** —— 目标脚本名、会话状态、已捕获的原始触摸帧数量、
 *     当前屏幕尺寸与无障碍服务连接状态；
 *  2. **传输控制** —— 开始 / 停止 / 放弃 / 清空，以及录制期间是否保持屏幕常亮；
 *  3. **实时动作流** —— 每一条被分类出来的动作（点击 / 长按 / 滑动 / 多点触控 / 手柄）
 *     都会立即出现在列表末尾，录制结束后可直接保存为脚本或送入编辑器。
 *
 * 布局随窗口尺寸切换：`expanded` 时控制区与动作流左右并排，其余情况统一为
 * 单列滚动列表。
 *
 * @param viewModel 录制页状态持有者，内部驱动 `RecordingController`。
 * @param windowSize 当前窗口的尺寸类别。
 * @param onOpenEditor 保存成功后跳转编辑器的回调，参数为脚本 id。
 * @param modifier 由外层 Shell 传入的修饰符。
 */
@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel,
    windowSize: AutoRunnerWindowSize,
    onOpenEditor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by viewModel.status.collectAsState()

    // 与设置页 / 编辑器共用 PageScaffold：顶栏与滚动连接只有一份实现。
    PageScaffold(
        windowSize = windowSize,
        title = "录制",
        subtitle = status.label(),
        modifier = modifier,
    ) {
        RecordingBody(
            viewModel = viewModel,
            windowSize = windowSize,
            onOpenEditor = onOpenEditor,
        )
    }
}

@Composable
private fun RecordingBody(
    viewModel: RecordingViewModel,
    windowSize: AutoRunnerWindowSize,
    onOpenEditor: (String) -> Unit,
) {
    // 显示状态直接订阅 ViewModel，避免在调用链上逐层透传。
    val status by viewModel.status.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val message by viewModel.message.collectAsState()
    val recording = status == RecordingStatus.RECORDING

    Box(modifier = Modifier.fillMaxSize()) {
        if (windowSize.layoutMode == LayoutMode.EXPANDED) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = Dimens.ScreenPaddingWide,
                        vertical = Dimens.CardSpacing,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
                ) {
                    RecordingControls(
                        viewModel = viewModel,
                        onOpenEditor = onOpenEditor,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
                ) {
                    SmallTitle(text = "实时动作流")
                    RecordingFeed(
                        steps = steps,
                        recording = recording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = Dimens.MaxContentWidth)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(
                        horizontal = Dimens.ScreenPadding,
                        vertical = Dimens.CardSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
                ) {
                    item(key = "controls") {
                        RecordingControls(
                            viewModel = viewModel,
                            onOpenEditor = onOpenEditor,
                        )
                    }

                    item(key = "feed-title") {
                        SmallTitle(text = "实时动作流")
                    }

                    if (steps.isEmpty()) {
                        item(key = "feed-empty") {
                            EmptyFeedCard(recording = recording)
                        }
                    } else {
                        itemsIndexed(
                            items = steps,
                            key = { index, _ -> "step-$index" },
                        ) { index, step ->
                            ActionStepRow(index = index, step = step)
                        }
                    }
                }
            }
        }

        MessageDialog(
            message = message,
            onDismiss = viewModel::dismissMessage,
        )
    }
}

// ---------------------------------------------------------------------------
// 控制区
// ---------------------------------------------------------------------------

/**
 * 录制会话控制区：会话信息、脚本名输入、传输按钮与实时动作流入口。
 *
 * 同一份实现同时服务于窄屏（作为 LazyColumn 的首个 item）与宽屏
 * （作为左栏的滚动内容），因此不依赖任何父级作用域。
 */
@Composable
private fun RecordingControls(
    viewModel: RecordingViewModel,
    onOpenEditor: (String) -> Unit,
) {
    // 直接订阅 ViewModel；recording / canRecord / metrics / stepCount 均由此派生。
    val status by viewModel.status.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val scriptName by viewModel.name.collectAsState()
    val eventCount by viewModel.eventCount.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val lastSaved by viewModel.lastSaved.collectAsState()

    val recording = status == RecordingStatus.RECORDING
    val canRecord = viewModel.canRecord
    val metrics = viewModel.screenMetrics
    val stepCount = steps.size
    val lastSavedId = lastSaved?.id

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
    ) {
        if (!canRecord) {
            RecordingUnavailableCard()
        }

        Card(insideMargin = Dimens.CardContentPadding) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Recording,
                        contentDescription = null,
                        tint = if (recording) RecordingAccent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "录制会话",
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                StatusPill(text = status.label(), color = status.accent())
            }

            TextField(
                value = scriptName,
                onValueChange = viewModel::setName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                label = "脚本名称",
                enabled = !recording,
                singleLine = true,
            )

            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MetricsRow(
                    icon = MiuixIcons.Timer,
                    label = "已捕获触摸帧",
                    value = "$eventCount 帧 · $stepCount 个动作",
                )
                MetricsRow(
                    icon = MiuixIcons.AppRecording,
                    label = "屏幕尺寸",
                    value = if (metrics.isValid) {
                        "${metrics.widthPx} × ${metrics.heightPx} px · 密度 ${metrics.density}"
                    } else {
                        "尚未获取到屏幕信息"
                    },
                )
                MetricsRow(
                    icon = MiuixIcons.Recording,
                    label = "无障碍服务",
                    value = if (viewModel.accessibilityConnected) "已连接" else "未连接，请先在设置中开启",
                    accent = if (viewModel.accessibilityConnected) AutoRunnerColors.Running else MiuixTheme.colorScheme.error,
                )
            }
        }

        Card(insideMargin = Dimens.CardContentPadding) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            ) {
                AppButton(
                    onClick = {
                        when (status) {
                            RecordingStatus.RECORDING -> viewModel.stop()
                            RecordingStatus.FINALISING -> Unit
                            else -> viewModel.armAndGoHome()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canRecord && status != RecordingStatus.FINALISING,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Play,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                    Text(
                        text = when (status) {
                            RecordingStatus.RECORDING -> "停止录制"
                            RecordingStatus.FINALISING -> "正在收尾…"
                            else -> "开始录制"
                        },
                        modifier = Modifier.padding(start = 6.dp),
                        color = MiuixTheme.colorScheme.onPrimary,
                        style = MiuixTheme.textStyles.button,
                    )
                }

                TextButton(
                    text = "放弃",
                    onClick = viewModel::cancel,
                    modifier = Modifier.weight(1f),
                    enabled = recording || status == RecordingStatus.FINALISING,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.ItemSpacing),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            ) {
                TextButton(
                    text = "清空",
                    onClick = viewModel::clear,
                    modifier = Modifier.weight(1f),
                    enabled = stepCount > 0 && !recording,
                )
                TextButton(
                    text = "恢复默认名称",
                    onClick = { viewModel.setName(viewModel.defaultName()) },
                    modifier = Modifier.weight(1f),
                    enabled = !recording,
                )
            }

            SwitchPreference(
                checked = keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
                title = "录制时保持屏幕常亮",
                summary = "避免息屏中断采集，录制结束后记得关闭",
                modifier = Modifier.padding(top = Dimens.ItemSpacing),
            )

            if (stepCount > 0 && !recording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.ItemSpacing),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                ) {
                    AppButton(
                        onClick = viewModel::saveRecorded,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = "保存脚本",
                            modifier = Modifier.padding(start = 6.dp),
                            color = MiuixTheme.colorScheme.onPrimary,
                            style = MiuixTheme.textStyles.button,
                        )
                    }

                    if (lastSavedId != null) {
                        TextButton(
                            text = "在编辑器中打开",
                            onClick = { onOpenEditor(lastSavedId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (lastSavedId != null && !recording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "已保存脚本，可继续编辑动作参数或执行模式",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 实时动作流（Feed 模式，§5.3）
// ---------------------------------------------------------------------------

/** 录制过程中的动作事件流。 */
@Composable
private fun RecordingFeed(
    steps: List<ActionStep>,
    recording: Boolean,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) {
        EmptyFeedCard(recording = recording, modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = Dimens.CardSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimens.TightSpacing),
    ) {
        itemsIndexed(
            items = steps,
            key = { index, _ -> "feed-step-$index" },
        ) { index, step ->
            ActionStepRow(index = index, step = step)
        }
    }
}

/** 尚未捕获到动作时的占位说明，同时解释录制的实现方式。 */
@Composable
private fun EmptyFeedCard(
    recording: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = if (recording) "等待输入" else "尚未开始录制",
        subtitle = "录制期间请在目标应用内正常操作，AutoRunner 会同步回放手势",
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            ) {
                Icon(
                    imageVector = MiuixIcons.AppRecording,
                    contentDescription = null,
                    tint = if (recording) RecordingAccent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = if (recording) "正在采集触摸事件…" else "点击「开始录制」以创建会话",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "录制层使用 TYPE_ACCESSIBILITY_OVERLAY 覆盖在目标应用之上，" +
                    "只读取触摸坐标与时间戳，不需要额外的 SYSTEM_ALERT_WINDOW 权限。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "每个手势分类完成后会立刻通过 dispatchGesture 回放，因此下层应用" +
                    "仍能正常响应你的操作，脚本内容与真实操作保持一致。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 当前平台无法录制时的显著提示。 */
@Composable
private fun RecordingUnavailableCard() {
    Card(insideMargin = Dimens.CardContentPadding) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Icon(
                imageVector = MiuixIcons.AppRecording,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.error,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "当前环境不支持录制",
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.error,
                )
                Text(
                    text = "录制依赖 Android 无障碍服务提供的覆盖层；桌面预览或无障碍服务" +
                        "未绑定时无法采集触摸。请在手机上运行并开启 AutoRunner 无障碍服务。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 状态映射
// ---------------------------------------------------------------------------

/** [RecordingStatus] 的中文标签。 */
private fun RecordingStatus.label(): String = when (this) {
    RecordingStatus.IDLE -> "空闲"
    RecordingStatus.RECORDING -> "录制中"
    RecordingStatus.FINALISING -> "正在收尾"
    RecordingStatus.ERROR -> "不可用"
}

/** [RecordingStatus] 对应的状态色。 */
@Composable
private fun RecordingStatus.accent(): Color = when (this) {
    RecordingStatus.IDLE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    RecordingStatus.RECORDING -> RecordingAccent
    RecordingStatus.FINALISING -> AutoRunnerColors.Paused
    RecordingStatus.ERROR -> MiuixTheme.colorScheme.error
}
