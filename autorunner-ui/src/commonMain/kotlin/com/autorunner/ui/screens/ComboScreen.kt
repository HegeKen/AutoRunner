package com.autorunner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.execution.ComboProgress
import com.autorunner.core.execution.ComboSlotProgress
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.script.ScriptRecord
import com.autorunner.ui.adaptive.AutoRunnerWindowSize
import com.autorunner.ui.components.AppButton
import com.autorunner.ui.components.AppButtonTone
import com.autorunner.ui.components.DurationPreference
import com.autorunner.ui.components.ExecutionConfigSection
import com.autorunner.ui.components.MessageDialog
import com.autorunner.ui.components.PageScaffold
import com.autorunner.ui.components.SectionCard
import com.autorunner.ui.components.StatusPill
import com.autorunner.ui.components.contentColor
import com.autorunner.ui.format.IntervalUnit
import com.autorunner.ui.theme.Dimens
import com.autorunner.ui.viewmodel.ComboSlotState
import com.autorunner.ui.viewmodel.ComboViewModel

/**
 * 「组合运行」页面（两段脚本并行、各自按自己的循环间隔运行）。
 *
 * 页面只负责选择脚本与参数；真正的启动沿用应用统一的「武装到悬浮球 → 回到
 * 桌面 → 单击悬浮球开始」流程，详见 [ComboViewModel.start]。
 */
@Composable
fun ComboScreen(
    viewModel: ComboViewModel,
    windowSize: AutoRunnerWindowSize,
) {
    val slotA by viewModel.slotA.collectAsStateWithLifecycle()
    val slotB by viewModel.slotB.collectAsStateWithLifecycle()
    val scripts by viewModel.scripts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastReport.collectAsStateWithLifecycle()
    val armedCombo by viewModel.armedCombo.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val horizontal = if (windowSize.isTablet) Dimens.ScreenPaddingWide else Dimens.ScreenPadding
    val busy = state.isActive

    PageScaffold(
        windowSize = windowSize,
        title = "组合运行",
        subtitle = "两段脚本并行，各自按自己的循环间隔运行",
    ) {
        // 滚动容器铺满整页，只把内容限制在 MaxContentWidth 内并居中。
        // 这样在平板上从左右留白处开始滑动也能滚动（此前 LazyColumn 只有
        // 中间 720dp 宽，边缘区域的触摸不会落到滚动容器上）。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sidePadding = if (maxWidth > Dimens.MaxContentWidth) {
                (maxWidth - Dimens.MaxContentWidth) / 2
            } else {
                horizontal
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    end = sidePadding,
                    top = 8.dp,
                    // 三个悬浮按钮还会占据底部高度，留出最后一张卡片的空间。
                    bottom = 280.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
            ) {
                item(key = "intro") { ComboIntroCard() }

                item(key = "slotA") {
                    ComboSlotCard(
                        role = ComboSlotRole.A,
                        slot = slotA,
                        scripts = scripts,
                        busy = busy,
                        onSelect = { viewModel.selectScript(ComboSlotRole.A, it) },
                        onModeChange = { viewModel.setMode(ComboSlotRole.A, it) },
                        onRepeatCountChange = { viewModel.setRepeatCount(ComboSlotRole.A, it) },
                        onIntervalChange = { viewModel.setIntervalMs(ComboSlotRole.A, it) },
                    )
                }

                item(key = "slotB") {
                    ComboSlotCard(
                        role = ComboSlotRole.B,
                        slot = slotB,
                        scripts = scripts,
                        busy = busy,
                        onSelect = { viewModel.selectScript(ComboSlotRole.B, it) },
                        onModeChange = { viewModel.setMode(ComboSlotRole.B, it) },
                        onRepeatCountChange = { viewModel.setRepeatCount(ComboSlotRole.B, it) },
                        onIntervalChange = { viewModel.setIntervalMs(ComboSlotRole.B, it) },
                        onStartDelayChange = viewModel::setStartDelayMs,
                    )
                }

                item(key = "schedule") { SchedulePreviewCard(slotA = slotA, slotB = slotB) }

                item(key = "control") {
                    ComboControlCard(
                        state = state,
                        sameScript = slotA.isSelected && slotA.scriptId == slotB.scriptId,
                        hasResult = lastReport != null,
                        armed = armedCombo != null,
                        onTogglePause = viewModel::togglePause,
                        onStop = viewModel::stop,
                        onReset = viewModel::reset,
                        onDisarm = viewModel::disarm,
                    )
                }

                // 实时状态单独订阅 comboProgress / lastReport：进度每秒变化时只
                // 重组这张卡片，而不是把上面的滑杆、输入框和整条 LazyColumn 一起重组。
                item(key = "live") { ComboLiveStatus(viewModel) }
            }
        }
    }

    MessageDialog(
        message = message,
        onDismiss = viewModel::dismissMessage,
        title = if (busy) "组合运行" else "组合运行提示",
    )
}

@Composable
private fun ComboIntroCard() {
    SectionCard(title = "使用说明") {
        Column(
            modifier = Modifier.padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "让两段脚本同时进入运行状态，各自按自己的循环间隔推进。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "例如 A 每 14 分钟一轮、B 每 50 分钟一轮，并让 B 在组合启动 10 分钟后" +
                    "才开始第一轮；之后 B 仍按自己的 50 分钟节奏循环。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "「循环间隔」是上一轮结束到下一轮开始之间的等待时间；两段脚本的动作会排队" +
                    "派发，不会因为同时触摸而互相取消。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ComboSlotCard(
    role: ComboSlotRole,
    slot: ComboSlotState,
    scripts: List<ScriptRecord>,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onModeChange: (ExecutionMode) -> Unit,
    onRepeatCountChange: (Int) -> Unit,
    onIntervalChange: (Long) -> Unit,
    onStartDelayChange: ((Long) -> Unit)? = null,
) {
    SectionCard(
        title = role.displayName,
        subtitle = if (role == ComboSlotRole.A) {
            "组合启动后立即开始第一轮"
        } else {
            "可以设置第一轮相对组合启动的延迟"
        },
    ) {
        Column {
            ScriptPickerRow(
                role = role,
                slot = slot,
                scripts = scripts,
                enabled = !busy,
                onSelect = onSelect,
            )
            ExecutionConfigSection(
                config = slot.execution,
                onModeChange = onModeChange,
                onRepeatCountChange = onRepeatCountChange,
                onIntervalChange = onIntervalChange,
                showFailureStrategy = false,
                title = "${role.displayName}的执行方式",
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (onStartDelayChange != null) {
                DurationPreference(
                    title = "首轮延迟",
                    valueMs = slot.execution.startDelayMs,
                    onValueChange = onStartDelayChange,
                    summary = when {
                        slot.execution.startDelayMs <= 0L -> "与脚本 A 同时开始第一轮"
                        else -> "组合启动后等待 ${formatDurationMs(slot.execution.startDelayMs)}，B 才开始第一轮"
                    },
                    enabled = !busy,
                    defaultUnit = IntervalUnit.MINUTES,
                )
            }
        }
    }
}

@Composable
private fun ScriptPickerRow(
    role: ComboSlotRole,
    slot: ComboSlotState,
    scripts: List<ScriptRecord>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Box {
        BasicComponent(
            title = "选择脚本",
            summary = when {
                slot.scriptName.isNotBlank() -> slot.scriptName
                slot.isSelected -> "正在读取脚本…"
                scripts.isEmpty() -> "还没有脚本，请先在「脚本」页新建或导入"
                else -> "尚未选择"
            },
            endActions = {
                TextButton(
                    text = "选择 ▾",
                    onClick = { showPicker = true },
                    enabled = enabled && scripts.isNotEmpty(),
                )
            },
            onClick = {
                if (enabled && scripts.isNotEmpty()) showPicker = true
            },
            insideMargin = Dimens.PreferenceRowPadding,
        )
        OverlayListPopup(
            show = showPicker,
            onDismissRequest = { showPicker = false },
        ) {
            ListPopupColumn {
                scripts.forEachIndexed { index, record ->
                    DropdownImpl(
                        text = record.name,
                        optionSize = scripts.size,
                        isSelected = record.id == slot.scriptId,
                        index = index,
                        onSelectedIndexChange = { selected ->
                            scripts.getOrNull(selected)?.let { onSelect(it.id) }
                            showPicker = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedulePreviewCard(
    slotA: ComboSlotState,
    slotB: ComboSlotState,
) {
    SectionCard(title = "运行节奏") {
        Column(
            modifier = Modifier.padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScheduleLine(role = ComboSlotRole.A, slot = slotA, withDelay = false)
            ScheduleLine(role = ComboSlotRole.B, slot = slotB, withDelay = true)
            Text(
                text = "首轮延迟只在组合启动后的第一轮生效；第一轮结束后，B 仍按自己的循环间隔继续。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ScheduleLine(
    role: ComboSlotRole,
    slot: ComboSlotState,
    withDelay: Boolean,
) {
    if (!slot.isSelected) {
        Text(
            text = "${role.displayName}：尚未选择脚本",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        return
    }
    val execution = slot.execution
    val loop = when {
        execution.mode == ExecutionMode.ONCE -> "执行 1 轮后结束"
        execution.repeatCount == ExecutionConfig.INFINITE_REPEAT -> "无限循环"
        else -> "循环 ${execution.repeatCount} 轮"
    }
    val interval = if (execution.mode == ExecutionMode.REPEAT && execution.intervalMs > 0L) {
        "，每轮间隔 ${formatDurationMs(execution.intervalMs)}"
    } else {
        ""
    }
    val delay = if (withDelay && execution.startDelayMs > 0L) {
        "组合启动 ${formatDurationMs(execution.startDelayMs)} 后开始第一轮，"
    } else {
        "组合启动后立即开始，"
    }
    Text(
        text = "${role.displayName}：$delay$loop$interval",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ComboControlCard(
    state: ExecutionState,
    sameScript: Boolean,
    hasResult: Boolean,
    armed: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onDisarm: () -> Unit,
) {
    SectionCard(title = "运行控制") {
        Column(
            modifier = Modifier.padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isActive) {
                    AppButton(onClick = onTogglePause) {
                        Text(
                            text = if (state == ExecutionState.PAUSED) "继续" else "暂停",
                            color = AppButtonTone.Primary.contentColor(),
                        )
                    }
                    AppButton(onClick = onStop, tone = AppButtonTone.Danger) {
                        Text(
                            text = "停止",
                            color = AppButtonTone.Danger.contentColor(),
                        )
                    }
                } else if (hasResult) {
                    TextButton(text = "清除结果", onClick = onReset)
                }
            }

            if (sameScript) {
                Text(
                    text = "脚本 A 与脚本 B 不能是同一个脚本，请更换其中一个。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            if (armed && !state.isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已就绪：打开目标应用后单击悬浮球开始",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    TextButton(text = "取消", onClick = onDisarm)
                }
            }

            val hint = when {
                state.isActive -> "运行中可在此暂停 / 停止，或回到目标应用通过悬浮球控制。"
                armed -> null
                else -> "点击右下角的「开始组合」悬浮按钮：会回到桌面并显示悬浮球；" +
                    "打开目标应用后单击悬浮球，两段脚本才会真正开始。"
            }
            hint?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ComboLiveStatus(viewModel: ComboViewModel) {
    val combo by viewModel.comboProgress.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastReport.collectAsStateWithLifecycle()
    combo?.let { snapshot ->
        ComboStatusCard(combo = snapshot, lastReport = lastReport)
    }
}

@Composable
private fun ComboStatusCard(
    combo: ComboProgress,
    lastReport: ExecutionReport?,
) {
    SectionCard(
        title = "实时状态",
        subtitle = "${combo.state.comboStateLabel()} · 已运行 ${combo.elapsedLabel}",
    ) {
        Column(
            modifier = Modifier.padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            combo.slots.forEach { slot -> ComboSlotStatusRow(slot) }
            lastReport?.let { report ->
                Text(
                    text = report.summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ComboSlotStatusRow(slot: ComboSlotProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${slot.role.displayName} · ${slot.scriptName}",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
            )
            val pillColor = when {
                slot.state.isActive -> MiuixTheme.colorScheme.primary
                slot.state == ExecutionState.COMPLETED -> MiuixTheme.colorScheme.secondary
                slot.state == ExecutionState.STOPPED -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            StatusPill(text = slot.progress.formattedState(), color = pillColor)
        }
        Text(
            text = "循环 ${slot.progress.loopLabel} · ${slot.describeState()}" +
                " · 耗时 ${slot.progress.elapsedLabel}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 组合整体状态的简短文案（用于状态卡副标题）。 */
private fun ExecutionState.comboStateLabel(): String = when (this) {
    ExecutionState.IDLE -> "空闲"
    ExecutionState.RUNNING -> "运行中"
    ExecutionState.PAUSED -> "已暂停"
    ExecutionState.STOPPED -> "已停止"
    ExecutionState.COMPLETED -> "已完成"
}

/** 把毫秒格式化为「14 分钟 / 30 秒 / 800 毫秒」；组合页只用于说明性文案。 */
private fun formatDurationMs(millis: Long): String = when {
    millis <= 0L -> "0"
    millis % 60_000L == 0L -> "${millis / 60_000L} 分钟"
    millis % 1_000L == 0L -> "${millis / 1_000L} 秒"
    else -> "$millis 毫秒"
}
