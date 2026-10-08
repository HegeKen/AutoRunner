package com.autorunner.ui.overlay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.autorunner.ui.theme.Dimens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 悬浮球长按后展开的迷你快捷菜单项——纯按钮，不含任何输入框。
 *
 * @param label 按钮文字；[iconOnly] 为 `true` 时不再展示（仅保留无障碍语义）。
 * @param glyph 图标；为 `null` 时绘制一个圆角方块（用作「停止」）。
 * @param danger `true` 时使用错误色（停止 / 关闭等破坏性操作）。
 * @param iconOnly `true` 时不渲染文字与卡片，只给一个圆形图标按钮——与主悬浮球
 *   （运行）保持一致；用于「关闭悬浮窗」这类一眼可懂的次要动作。
 */
data class MiniAction(
    val label: String = "",
    val onClick: () -> Unit,
    val glyph: ImageVector? = null,
    val danger: Boolean = false,
    val iconOnly: Boolean = false,
)

/**
 * 迷你快捷菜单：文字动作每行放 1~3 个等宽纯按钮收在卡片里；[MiniAction.iconOnly]
 * 的动作则单独渲染成圆形图标按钮，与主悬浮球同款，不再有文字提示。
 *
 * @param alignEnd 与悬浮球贴靠同一侧：球在右侧时图标按钮也右对齐。
 * @param isLandscape 横屏时图标按钮排成一行（竖屏排成一列）——同一套动作在手机上
 *   竖着放、横屏时就横着放，避免纵向堆叠过高。
 */
@Composable
fun MiniActionMenu(
    rows: List<List<MiniAction>>,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    isLandscape: Boolean = false,
) {
    val textRows = rows
        .map { row -> row.filterNot { it.iconOnly } }
        .filter { it.isNotEmpty() }
    val iconActions = rows.flatten().filter { it.iconOnly }

    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (textRows.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(Dimens.PanelCorner),
                color = MiuixTheme.colorScheme.surface,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    textRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            row.forEach { action ->
                                MiniActionButton(action = action, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        // 图标动作：竖屏上下排成一列，横屏排成一行。
        if (iconActions.isNotEmpty()) {
            if (isLandscape) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    iconActions.forEach { action -> MiniIconAction(action = action) }
                }
            } else {
                iconActions.forEach { action -> MiniIconAction(action = action) }
            }
        }
    }
}

/** 独立的圆形图标按钮，与主悬浮球同款，只画图标、不带文字。 */
@Composable
private fun MiniIconAction(action: MiniAction) {
    FloatingBall(
        accent = if (action.danger) {
            MiuixTheme.colorScheme.error
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantActions
        },
        onClick = action.onClick,
    ) { tint ->
        BallGlyph(glyph = action.glyph, tint = tint, size = 26.dp)
    }
}

@Composable
private fun MiniActionButton(
    action: MiniAction,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val contentColor = if (action.danger) scheme.error else scheme.onSurface
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = action.onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        BallGlyph(glyph = action.glyph, tint = contentColor, size = 18.dp)
        Text(
            text = action.label,
            modifier = Modifier.padding(start = 6.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * 图标渲染器：传入矢量图标时用 [Icon]；为 `null` 时画一个圆角方块，作为 MIUIX 缺失的
 * 「停止」图标。
 */
@Composable
fun BallGlyph(
    glyph: ImageVector?,
    tint: Color,
    size: Dp,
) {
    if (glyph != null) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.22f))
                .background(tint),
        )
    }
}

/**
 * 统一悬浮球：浅色球体 + 状态色描边，单击触发主操作，长按展开 [MiniActionMenu]。
 *
 * 编辑、录入、执行、标定手柄四处共用同一外观，只有强调色与球内图标不同。
 *
 * @param accent 当前状态的强调色（描边与图标着色）。
 * @param onClick 单击。
 * @param onLongClick 长按；为 `null` 时退化为普通点击。
 * @param content 球内图标；接收 [accent] 作为 tint（手柄图标为 Canvas 绘制，需显式传色）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun FloatingBall(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable (tint: Color) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = modifier.size(Dimens.FloatingBallSize + Dimens.FloatingBallWindowPadding),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier
                .size(Dimens.FloatingBallSize)
                .clip(CircleShape)
                .border(width = 2.dp, color = accent.copy(alpha = 0.55f), shape = CircleShape)
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        Modifier.clickable(onClick = onClick)
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                content(accent)
            }
        }
    }
}
