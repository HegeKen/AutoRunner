package com.autorunner.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.ui.theme.Dimens

/**
 * MIUIX flavoured slider row.
 *
 * `miuix-preference` ships `SwitchPreference`, `ArrowPreference`,
 * `CheckboxPreference`, `RadioButtonPreference` and the dropdown / spinner
 * preferences but no slider, so AutoRunner composes one from [BasicComponent]
 * and [Slider] to keep the settings page visually consistent with the rest of
 * HyperOS.
 */
@Composable
fun SliderPreference(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    valueLabel: (Float) -> String = { it.toString() },
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val coerced = value.coerceIn(valueRange.start, valueRange.endInclusive)
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        endActions = {
            Text(
                text = valueLabel(coerced),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        },
        bottomAction = {
            Slider(
                value = coerced,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                enabled = enabled,
                valueRange = valueRange,
                steps = steps,
                onValueChangeFinished = onValueChangeFinished,
            )
        },
        insideMargin = Dimens.PreferenceRowPadding,
    )
}
