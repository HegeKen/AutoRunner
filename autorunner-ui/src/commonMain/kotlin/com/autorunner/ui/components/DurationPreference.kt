package com.autorunner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import com.autorunner.ui.format.IntervalUnit
import com.autorunner.ui.theme.Dimens

/**
 * 可复用的时长偏好项：数字输入（可精确输入）+ 单位选择 + 滑块（可快速拖动）。
 *
 * 单位只影响输入与阅读方式，存储值始终是毫秒。循环间隔与组合运行的「首轮延迟」
 * 共用这一套控件，避免两处对「14 分钟」给出不同的输入方式。
 *
 * @param compact 悬浮面板等没有 `MiuixPopupHost` 的覆盖层传 `true`：单位选择改用
 *   铺满整行的分段选择器，而不是下拉弹层。
 */
@Composable
fun DurationPreference(
    title: String,
    valueMs: Long,
    onValueChange: (Long) -> Unit,
    summary: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    /** 时长为 0 时默认使用的单位（组合页的首轮延迟默认用「分」）。 */
    defaultUnit: IntervalUnit = IntervalUnit.forValue(valueMs),
    /**
     * 受控单位；为 `null` 时由组件内部维护。脚本编辑器把单位提升到 ViewModel，
     * 让顶部信息卡能按用户选择的同一单位展示循环总时长。
     */
    unit: IntervalUnit? = null,
    onUnitChange: ((IntervalUnit) -> Unit)? = null,
) {
    var internalUnit by remember {
        mutableStateOf(if (valueMs <= 0L) defaultUnit else IntervalUnit.forValue(valueMs))
    }
    val activeUnit = unit ?: internalUnit
    var unitPopupVisible by remember { mutableStateOf(false) }

    // 回调会被 Compose 缓存复用（DropdownImpl / AppSegmentedChoice 的 lambda），
    // 直接捕获参数会在悬浮窗等长生命周期场景里读到首次组合的旧值——滑块改到
    // 140s 后切「分」，回调仍按 2400ms 吸附为 0 就是这个原因。
    //
    // 把回调做成 remember 出来的稳定实例，并在调用时才从 State 里取值：闭包永远
    // 不会过期，也不需要依赖读者理解 rememberUpdatedState 的语义去维护正确性。
    val latestValue = rememberUpdatedState(valueMs)
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestUnitProp = rememberUpdatedState(unit)
    val latestOnUnitChange = rememberUpdatedState(onUnitChange)

    // 统一走这里切换单位：受控时通知外部，非受控时更新内部状态。
    // 用 remember 保持稳定实例，避免下拉弹层缓存旧回调。
    val setUnit: (IntervalUnit) -> Unit = remember {
        { next ->
            if (latestUnitProp.value == null) internalUnit = next
            latestOnUnitChange.value?.invoke(next)
        }
    }

    // 外部值跳到当前单位无法表示的量级时（例如切换脚本后从 0 变成 840000ms），
    // 自动切到能完整显示的单位；用户正在编辑时不强制跳走。
    LaunchedEffect(valueMs, unit) {
        val active = unit ?: internalUnit
        if (valueMs > 0L && valueMs / active.perUnitMs > active.maxUnits) {
            setUnit(IntervalUnit.forValue(valueMs))
        }
    }

    // 切换单位时把时长吸附为新单位的整数，否则 140s 在“分”下会显示成
    // 2.3333333 min，与整数输入框互相矛盾。
    val selectUnit: (IntervalUnit) -> Unit = remember {
        { next ->
            setUnit(next)
            val current = latestValue.value
            if (current > 0L) {
                val snapped = next.snap(current)
                if (snapped != current) latestOnValueChange.value(snapped)
            }
        }
    }

    BasicComponent(
        title = title,
        summary = summary,
        // 数值框和单位选择放在控件行里，而不是 BasicComponent 的 endActions：
        // 窄屏上 endActions 会被标题/摘要挤到只剩十几 dp，单位按钮的文字会被裁掉，
        // 而且 TextField 与 TextButton 的默认高度也不一致。两者固定同高（56dp），
        // 数值框占满剩余宽度，单位按钮保留最小可点击宽度。
        bottomAction = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DurationNumberInput(
                    value = valueMs,
                    unit = activeUnit,
                    enabled = enabled,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                )
                if (!compact) {
                    Box(modifier = Modifier.height(56.dp)) {
                        TextButton(
                            text = "${activeUnit.label} ▾",
                            onClick = { unitPopupVisible = true },
                            enabled = enabled,
                            modifier = Modifier
                                .height(56.dp)
                                .widthIn(min = 88.dp),
                        )
                        OverlayListPopup(
                            show = unitPopupVisible,
                            onDismissRequest = { unitPopupVisible = false },
                        ) {
                            ListPopupColumn {
                                IntervalUnit.entries.forEachIndexed { index, option ->
                                    DropdownImpl(
                                        text = option.label,
                                        optionSize = IntervalUnit.entries.size,
                                        isSelected = option == activeUnit,
                                        index = index,
                                        onSelectedIndexChange = {
                                            selectUnit(IntervalUnit.entries[it])
                                            unitPopupVisible = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (compact) {
                AppSegmentedChoice(
                    options = IntervalUnit.entries.map { it.label },
                    selectedIndex = activeUnit.ordinal,
                    onSelected = { index ->
                        IntervalUnit.entries.getOrNull(index)?.let(selectUnit)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    enabled = enabled,
                )
            }
            Slider(
                value = activeUnit.unitsOf(valueMs),
                onValueChange = { onValueChange(activeUnit.toMillis(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                enabled = enabled,
                valueRange = 0f..activeUnit.maxUnits,
                steps = activeUnit.steps,
            )
        },
        insideMargin = Dimens.PreferenceRowPadding,
        modifier = modifier,
    )
}

/**
 * 时长输入框。数值按当前选中的 [unit] 解读；超出该单位范围的编辑会被拒绝，
 * 保证输入框显示的值始终与存储的时长一致。
 */
@Composable
private fun DurationNumberInput(
    value: Long,
    unit: IntervalUnit,
    enabled: Boolean,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier.height(56.dp),
        label = "数值",
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
