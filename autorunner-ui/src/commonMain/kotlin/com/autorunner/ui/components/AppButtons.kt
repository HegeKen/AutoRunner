package com.autorunner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Colour families available to [AppButton]. */
enum class AppButtonTone { Primary, Neutral, Danger }

/**
 * Filled action button with **explicit** theme colours.
 *
 * MIUIX' `Button` default colours are extremely low contrast on a Monet palette
 * (light container on a light surface), which made every primary action on a real
 * device look disabled — reported for the floating panel, the editor and the
 * recording screen. This component pins the fill to a theme role and keeps the
 * disabled state legible (a mid grey that still contrasts with the white label the
 * call sites use).
 *
 * The parameter list mirrors `top.yukonga.miuix.kmp.basic.Button` so call sites can
 * be migrated by swapping the symbol only.
 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: AppButtonTone = AppButtonTone.Primary,
    cornerRadius: Dp = 14.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val fill = when {
        // Mid grey: still contrasts with the white label, but clearly inactive.
        !enabled -> scheme.onSurface.copy(alpha = 0.62f)
        tone == AppButtonTone.Primary -> scheme.primary
        tone == AppButtonTone.Neutral -> scheme.secondaryContainer
        else -> scheme.errorContainer
    }
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        // The click target is the whole surface, so the content below is free to
        // wrap: an unbounded call site must not stretch to the whole row (that
        // squeezed the editor header's title column to one character per line).
        modifier = modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = fill,
        shadowElevation = if (enabled) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth(Alignment.CenterHorizontally)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Two-or-more-way segmented switch.
 *
 * Replaces the MIUIX `TabRow`, whose selected indicator did not match the
 * container's corner radius (square outer corners with a rounded inner pill leaked
 * odd wedges on device). Both shapes here are rounded and the indicator is inset,
 * so the geometry is always consistent.
 *
 * 标签一律用 [CenteredText]（`fillMaxWidth` + `TextAlign.Center`）渲染，
 * **不要**退回到「`Box(contentAlignment = Center) { Text(...) }`」：真机上出现过
 * 选中项的 DualSense 贴着蓝色胶囊左边缘的问题——只要文字所在节点被容器约束撑满，
 * `contentAlignment` 就不再产生位移。见 [CenteredText] 的说明。
 */
@Composable
fun AppSegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * `true`：选项均分整行宽度（调用方应给满宽 modifier）。
     * `false`：各选项按文字自然宽度排列，用于 wrap 父容器——此时不能用 weight：
     * wrap 模式下 Row 仍会把传入的有界 max 按 weight 分光，把同行的其他组件
     * 压成零宽（悬浮窗里"重复次数"被挤成竖排就是这个原因）。
     */
    equalSegments: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
    ) {
        Row(
            // 不强制 fillMaxWidth：外部通过 modifier 决定宽度。否则在未指定宽度的
            // wrap 父容器中，分段器会撑满有界约束（把同行其他内容压成零宽）。
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val shape = RoundedCornerShape(11.dp)
                Box(
                    modifier = Modifier
                        .then(if (equalSegments) Modifier.weight(1f) else Modifier)
                        .clip(shape)
                        .background(if (selected) scheme.primary else Color.Transparent)
                        .clickable(enabled = enabled) { onSelected(index) }
                        .padding(
                            horizontal = if (equalSegments) 4.dp else 16.dp,
                            vertical = 10.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // 均分模式下每段宽度有界，用 CenteredText 明确居中；只靠 Box 的
                    // contentAlignment 在 Android 上会让文字贴住胶囊左边缘（真机反馈）。
                    // wrap 模式下必须保留 Text 的自然宽度，否则分段会被撑到父级宽度。
                    if (equalSegments) {
                        CenteredText(
                            text = label,
                            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.button,
                        )
                    } else {
                        Text(
                            text = label,
                            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.button,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Vertical stack helper used by dialogs that mix buttons and fields. */
@Composable
fun ButtonColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * 与 [AppButton] 的填充色对比度足够的文字色。
 *
 * 三种 tone 的填充分别是 primary / secondaryContainer / errorContainer，
 * 调用方此前各写各的 `onPrimary`，危险按钮因此出现过「深底浅字」的误用；
 * 统一走这里可以保证语气与文字色始终配套。
 */
@Composable
fun AppButtonTone.contentColor(): Color = when (this) {
    AppButtonTone.Primary -> MiuixTheme.colorScheme.onPrimary
    AppButtonTone.Neutral -> MiuixTheme.colorScheme.onSecondaryContainer
    AppButtonTone.Danger -> MiuixTheme.colorScheme.onErrorContainer
}
