package com.autorunner.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.ui.theme.Dimens

/**
 * 数值偏好项：标题 + 说明 + 数值输入框。
 *
 * 输入框复用 [NumberField]，数字过滤只有一份实现；本地文本状态让用户可以先把
 * 内容清空再重新输入，不会在清空的瞬间把配置写成 0。
 *
 * @param allowInfinite 为 `true` 时数值 `0` 显示为「无限循环」。
 */
@Composable
fun NumberFieldPreference(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    label: String = title,
    enabled: Boolean = true,
    min: Int = 0,
    max: Int = 100_000,
    allowInfinite: Boolean = false,
    suffix: String? = null,
) {
    // 正在编辑的原文；外部值变化（滑杆、预设、重置）时重新同步。
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { text = value.toString() }

    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        bottomAction = {
            NumberField(
                value = text,
                onValueChange = { raw ->
                    text = raw
                    raw.toIntOrNull()?.let { onValueChange(it.coerceIn(min, max)) }
                },
                label = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = enabled,
                maxDigits = 7,
            )
            if (allowInfinite || suffix != null) {
                Text(
                    text = when {
                        allowInfinite && value == 0 -> "0 表示无限循环"
                        suffix != null -> suffix
                        else -> ""
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
        },
        insideMargin = Dimens.FormFieldPadding,
    )
}
