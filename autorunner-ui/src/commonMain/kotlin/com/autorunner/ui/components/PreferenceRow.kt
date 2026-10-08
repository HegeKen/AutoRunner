package com.autorunner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.ui.theme.Dimens

/**
 * 「图标 + 标题 + 摘要 +（可选）状态药丸」设置行。
 *
 * 这个组合在设置页出现过十几次，每处都自己拼 `BasicComponent + startAction +
 * endActions + insideMargin`，结果行内边距出现 10dp / 12dp 两种写法，同类行在
 * 相邻位置就肉眼可见地不齐。统一走这里后，行高由 [Dimens.PreferenceRowPadding]
 * 决定，只读信息行传 [Dimens.CompactRowPadding]。
 *
 * @param icon 行首图标；`null` 时不渲染 `startAction`。
 * @param statusText 行尾状态药丸文案；需要更复杂的行尾内容时改用 [endActions]。
 * @param endActions 自定义行尾内容，优先级高于 [statusText]。
 * @param onClick `null` 表示只读行（无点击反馈）。
 */
@Composable
fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MiuixTheme.colorScheme.primary,
    statusText: String? = null,
    statusColor: Color = MiuixTheme.colorScheme.primary,
    statusContentColor: Color = Color.White,
    endActions: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    insideMargin: PaddingValues = Dimens.PreferenceRowPadding,
) {
    val leading: (@Composable () -> Unit)? = if (icon != null) {
        { Icon(imageVector = icon, contentDescription = null, tint = iconTint) }
    } else {
        null
    }
    val trailing: (@Composable RowScope.() -> Unit)? = when {
        endActions != null -> endActions
        statusText != null -> {
            {
                StatusPill(
                    text = statusText,
                    color = statusColor,
                    contentColor = statusContentColor,
                )
            }
        }

        else -> null
    }

    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        startAction = leading,
        endActions = trailing,
        onClick = onClick,
        insideMargin = insideMargin,
    )
}

/**
 * 一行「图标 + 标签 + 数值」的诊断信息。
 *
 * 与 [PreferenceRow] 的区别是不带卡片行语义：没有标题层级、没有点击态，
 * 只在一张卡片内部罗列若干只读指标（录制页的会话信息）。
 */
@Composable
fun MetricsRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MiuixTheme.colorScheme.onSurfaceSecondary,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = accent,
        )
    }
}
