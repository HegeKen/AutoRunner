package com.autorunner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.FailureStrategy
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

        // 间隔可以用毫秒、秒或分钟表示；脚本始终存储毫秒，
        // 只有编辑器会切换单位。
        var intervalUnit by remember { mutableStateOf(IntervalUnit.forValue(config.intervalMs)) }
        var unitPopupVisible by remember { mutableStateOf(false) }

        // 回调会被 Compose 缓存复用（DropdownImpl / AppSegmentedChoice 的 lambda），
        // 直接捕获 config 会在悬浮窗等长生命周期场景里读到首次组合的旧值——滑块改到
        // 140s 后切「分」，回调仍按 2400ms 吸附为 0 就是这个原因。
        //
        // 这里刻意把回调做成 remember 出来的稳定实例，并在调用时才从 State 里取值：
        // 读取发生在回调触发的那一刻，闭包永远不可能过期，也不需要依赖读者理解
        // rememberUpdatedState 的语义去维护正确性。
        val latestConfig = rememberUpdatedState(config)
        val latestOnIntervalChange = rememberUpdatedState(onIntervalChange)

        // 切换单位时把间隔吸附为新单位的整数，否则 140s 在“分”下会显示成
        // 2.3333333 min，与整数输入框互相矛盾。
        val selectIntervalUnit: (IntervalUnit) -> Unit = remember {
            { unit ->
                intervalUnit = unit
                val current = latestConfig.value
                if (current.intervalMs > 0L && current.mode == ExecutionMode.REPEAT) {
                    val snappedMs = unit.snap(current.intervalMs)
                    if (snappedMs != current.intervalMs) latestOnIntervalChange.value(snappedMs)
                }
            }
        }

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
        val intervalSummary = when {
            !repeatEnabled -> "仅重复执行模式生效"
            config.intervalMs <= 0L -> "每轮之间不额外等待"
            else -> "每轮之间等待 ${intervalUnit.format(intervalUnit.unitsOf(config.intervalMs))}（${config.intervalMs} ms）"
        }
        // 循环间隔：数字输入（可精确输入）+ 单位选择 + 滑块（可快速拖动）。
        // 单位选择器只挂在这一行——之前它和「重复次数」共处一行，看起来像是
        // 次数的单位，容易被误解。
        BasicComponent(
            title = "循环间隔",
            summary = intervalSummary,
            endActions = {
                IntervalNumberInput(
                    value = config.intervalMs,
                    unit = intervalUnit,
                    enabled = repeatEnabled,
                    onValueChange = onIntervalChange,
                )
                if (!compact) {
                    // 悬浮面板是 WindowManager 覆盖层，不在 Scaffold 里，拿不到
                    // MiuixPopupHost —— 那里不能用 OverlayListPopup，见下方
                    // compact 分支改用铺满整行的分段选择器。
                    Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                        TextButton(
                            text = "${intervalUnit.label} ▾",
                            onClick = { unitPopupVisible = true },
                            enabled = repeatEnabled,
                        )
                        OverlayListPopup(
                            show = unitPopupVisible,
                            onDismissRequest = { unitPopupVisible = false },
                        ) {
                            ListPopupColumn {
                                IntervalUnit.entries.forEachIndexed { index, unit ->
                                    DropdownImpl(
                                        text = unit.label,
                                        optionSize = IntervalUnit.entries.size,
                                        isSelected = unit == intervalUnit,
                                        index = index,
                                        onSelectedIndexChange = {
                                            selectIntervalUnit(IntervalUnit.entries[it])
                                            unitPopupVisible = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomAction = {
                if (compact) {
                    AppSegmentedChoice(
                        options = IntervalUnit.entries.map { it.label },
                        selectedIndex = intervalUnit.ordinal,
                        onSelected = { index ->
                            IntervalUnit.entries.getOrNull(index)?.let { selectIntervalUnit(it) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        enabled = repeatEnabled,
                    )
                }
                top.yukonga.miuix.kmp.basic.Slider(
                    value = intervalUnit.unitsOf(config.intervalMs),
                    onValueChange = { onIntervalChange(intervalUnit.toMillis(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    enabled = repeatEnabled,
                    valueRange = 0f..intervalUnit.maxUnits,
                    steps = intervalUnit.steps,
                )
            },
            insideMargin = Dimens.PreferenceRowPadding,
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

/**
 * 循环间隔的数字输入框。数值按当前选中的 [unit] 解读；超出该单位范围的
 * 编辑会被拒绝，保证输入框显示的值始终与存储的配置一致。
 */
@Composable
private fun IntervalNumberInput(
    value: Long,
    unit: IntervalUnit,
    enabled: Boolean,
    onValueChange: (Long) -> Unit,
) {
    val maxWhole = unit.maxUnits.toInt()
    val synced = remember(value, unit) {
        (value / unit.perUnitMs).coerceAtMost(maxWhole.toLong()).toString()
    }
    var text by remember { mutableStateOf(synced) }
    // 外部变更（滑块拖动、单位切换、新配置）接管输入框内容。
    LaunchedEffect(synced) { text = synced }

    top.yukonga.miuix.kmp.basic.TextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(5)
            val parsed = digits.toLongOrNull()
            if (digits.isEmpty() || (parsed != null && parsed <= maxWhole)) {
                text = digits
                if (parsed != null) onValueChange(parsed * unit.perUnitMs)
            }
        },
        modifier = Modifier.width(92.dp),
        label = "数值",
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/**
 * 循环间隔控件使用的单位。
 *
 * 脚本格式只存毫秒；单位纯粹影响用户的输入与阅读方式，
 * 「每 5 分钟」不必写成 300000。
 */
enum class IntervalUnit(val label: String, internal val perUnitMs: Long, val maxUnits: Float, val steps: Int) {
    MILLIS("毫秒", 1L, 5_000f, 49),
    SECONDS("秒", 1_000L, 300f, 59),
    MINUTES("分", 60_000L, 60f, 59),
    ;

    fun unitsOf(millis: Long): Float = (millis.toFloat() / perUnitMs).coerceIn(0f, maxUnits)

    fun toMillis(units: Float): Long = (units * perUnitMs).toLong().coerceAtLeast(0L)

    /**
     * 把毫秒值吸附为本单位的整数格：切换单位时避免出现 `2.3333333 min` 这种
     * 与整数输入框互相矛盾的显示。结果同时受本单位上限（[maxUnits]）约束。
     *
     * 取整用「四舍五入」的整数写法，避免浮点误差；纯函数，便于单测覆盖。
     */
    fun snap(millis: Long): Long {
        if (millis <= 0L) return 0L
        val rounded = ((millis * 2 + perUnitMs) / (perUnitMs * 2))
            .coerceIn(0L, maxUnits.toLong())
        return rounded * perUnitMs
    }

    fun format(units: Float): String = when (this) {
        MILLIS -> "${units.toInt()} ms"
        SECONDS -> "${units.toInt()} s"
        MINUTES -> "${trimTrailingZero(units)} min"
    }

    /** 选取能让存储值保持可读的最大单位。 */
    companion object {
        fun forValue(millis: Long): IntervalUnit = when {
            millis <= 0L -> SECONDS
            millis % 60_000L == 0L -> MINUTES
            millis >= 1_000L -> SECONDS
            else -> MILLIS
        }

        private fun trimTrailingZero(units: Float): String {
            val whole = units.toInt()
            return if (units == whole.toFloat()) whole.toString() else units.toString()
        }
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
