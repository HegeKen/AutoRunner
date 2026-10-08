package com.autorunner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单行数值输入框：键盘类型、字符过滤、小数支持与错误态集中在这里，
 * 供设置页的手柄坐标输入与脚本编辑器的数值行共用。
 *
 * @param allowDecimal 允许输入小数点（坐标归一化后的 0~1 分值与时长）；
 *   关闭时只保留数字并截断到 [maxDigits] 位。
 * @param isError 解析失败时置真：底色换成 errorContainer，避免用户以为输入已被接受。
 * @param errorMessage 错误说明；与 [isError] 同时给出时渲染在输入框下方。
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowDecimal: Boolean = false,
    maxDigits: Int = 6,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
) {
    val colors = if (isError) {
        TextFieldDefaults.textFieldColors(
            backgroundColor = MiuixTheme.colorScheme.errorContainer,
            labelColor = MiuixTheme.colorScheme.onErrorContainer,
            borderColor = MiuixTheme.colorScheme.error,
        )
    } else {
        TextFieldDefaults.textFieldColors()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextField(
            value = value,
            onValueChange = { raw ->
                onValueChange(if (allowDecimal) raw.toDecimalInput() else raw.filter { it.isDigit() }.take(maxDigits))
            },
            label = label,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            colors = colors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 解析输入框原文。
 *
 * 末尾小数点（用户正在输入 `540.` 或 `0.`）是合法的中间状态，按去掉小数点后的
 * 整数解析；空串与孤立的小数点返回 `null`，由调用方决定是禁用按钮还是提示错误。
 * 这里刻意不做 `?: 0f` 兜底——那会把「解析失败」和「用户真的输入了 0」混为一谈。
 */
fun parseNumberInput(text: String): Float? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val withoutTrailingDot = trimmed.trimEnd('.')
    if (withoutTrailingDot.isEmpty()) return null
    return withoutTrailingDot.toFloatOrNull()
}

/** 只保留数字与最多一个小数点，过滤掉 IME 可能带进来的其它字符。 */
private fun String.toDecimalInput(): String {
    val builder = StringBuilder(length)
    var hasDot = false
    for (ch in this) {
        when {
            ch.isDigit() -> builder.append(ch)
            ch == '.' && !hasDot -> {
                hasDot = true
                builder.append(ch)
            }
        }
    }
    return builder.toString()
}
