package com.autorunner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.FailureStrategy
import com.autorunner.ui.format.IntervalUnit
import com.autorunner.ui.theme.Dimens

/**
 * 可复用的执行配置编辑器：单次 / 重复模式、重复次数、循环间隔与失败策略。
 *
 * 由脚本编辑器（§6.5）、设置页（默认值）与悬浮控制面板（§6.4.3）共同渲染，
 * 同一套控件出现在所有地方。
 */
@Composable
fun ExecutionConfigSection(
    config: ExecutionConfig,
    onModeChange: (ExecutionMode) -> Unit,
    onRepeatCountChange: (Int) -> Unit,
    onIntervalChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onFailureStrategyChange: ((FailureStrategy) -> Unit)? = null,
    showFailureStrategy: Boolean = true,
    compact: Boolean = false,
    title: String = "执行模式",
    /** 受控的循环间隔显示单位；为 `null` 时由控件内部维护。 */
    intervalUnit: IntervalUnit? = null,
    onIntervalUnitChange: ((IntervalUnit) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallTitle(text = title)

        AppSegmentedChoice(
            options = listOf("单次执行", "重复执行"),
            selectedIndex = if (config.mode == ExecutionMode.ONCE) 0 else 1,
            onSelected = { index ->
                onModeChange(if (index == 0) ExecutionMode.ONCE else ExecutionMode.REPEAT)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val repeatEnabled = config.mode == ExecutionMode.REPEAT

        if (repeatEnabled) {
            NumberFieldPreference(
                title = "重复次数",
                value = config.repeatCount,
                onValueChange = onRepeatCountChange,
                summary = if (config.repeatCount == 0) {
                    "无限循环，直到手动停止"
                } else {
                    "完整执行 ${config.repeatCount} 轮"
                },
                label = "次数",
                allowInfinite = true,
                max = 9_999_999,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 摘要始终按存储值给出一个「最可读」的单位；控件本身可能由用户手动
        // 切到别的单位（例如 2 分钟切到秒），这不影响这里的说明文字。
        val summaryUnit = IntervalUnit.forValue(config.intervalMs)
        val intervalSummary = when {
            !repeatEnabled -> "仅重复执行模式生效"
            config.intervalMs <= 0L -> "每轮之间不额外等待"
            else -> "每轮之间等待 ${summaryUnit.format(summaryUnit.unitsOf(config.intervalMs))}" +
                "（${config.intervalMs} ms）"
        }
        // 循环间隔：数字输入（可精确输入）+ 单位选择 + 滑块（可快速拖动）。
        DurationPreference(
            title = "循环间隔",
            valueMs = config.intervalMs,
            onValueChange = onIntervalChange,
            summary = intervalSummary,
            enabled = repeatEnabled,
            compact = compact,
            unit = intervalUnit,
            onUnitChange = onIntervalUnitChange,
        )

        if (showFailureStrategy && onFailureStrategyChange != null) {
            FailureStrategyPreference(
                strategy = config.failureStrategy,
                onStrategyChange = onFailureStrategyChange,
                compact = compact,
            )
        }

        Text(
            text = config.describe(),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

/** [FailureStrategy] 的单选式选择器（§6.3.4）。 */
@Composable
fun FailureStrategyPreference(
    strategy: FailureStrategy,
    onStrategyChange: (FailureStrategy) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!compact) {
            SmallTitle(text = "动作失败时")
        }
        BasicComponent(
            title = "跳过该动作",
            summary = "记录失败并继续执行后续动作",
            endActions = {
                if (strategy == FailureStrategy.SKIP_ACTION) {
                    StatusPill(
                        text = "已选择",
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            },
            onClick = { onStrategyChange(FailureStrategy.SKIP_ACTION) },
            insideMargin = Dimens.CompactRowPadding,
        )
        BasicComponent(
            title = "终止脚本",
            summary = "立即停止整个任务并保留已完成的循环次数",
            endActions = {
                if (strategy == FailureStrategy.ABORT_SCRIPT) {
                    StatusPill(
                        text = "已选择",
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            },
            onClick = { onStrategyChange(FailureStrategy.ABORT_SCRIPT) },
            insideMargin = Dimens.CompactRowPadding,
        )
    }
}
