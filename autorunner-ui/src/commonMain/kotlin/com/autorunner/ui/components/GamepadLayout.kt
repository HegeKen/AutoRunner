package com.autorunner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import com.autorunner.ui.components.CenteredText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.model.GamepadButton
import com.autorunner.core.model.GamepadMode

/**
 * 屏幕上的可视化手柄。
 *
 * 用户直接点按画面中的按键位置来生成手柄动作——与真机上手柄的物理布局一致，
 * 而不是从列表里挑一个名称。方向键（十字）、ABXY（菱形）、肩键、扳机、功能键
 * 都渲染在对应位置；左下/右下各有一个摇杆区，拖动后松手即得到方向向量。
 *
 * @param selectedButton 当前选中的按键（编辑已有动作时高亮）
 * @param mode 手柄类型，用于显示对应品牌的按键名称
 * @param stickX / stickY 摇杆当前方向（-1.0 ~ 1.0），用于回显
 * @param onButton 点按某个按键时回调
 * @param onStick 摇杆拖动结束后回调方向向量
 */
@Composable
fun GamepadLayout(
    modifier: Modifier = Modifier,
    selectedButton: GamepadButton? = null,
    mode: GamepadMode = GamepadMode.Default,
    stickX: Float = 0f,
    stickY: Float = 0f,
    onButton: (GamepadButton) -> Unit,
    onStick: (Float, Float) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部肩键 / 扳机
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GamepadKey(GamepadButton.LT.labelFor(mode), selectedButton == GamepadButton.LT) {
                    onButton(GamepadButton.LT)
                }
                GamepadKey(GamepadButton.LB.labelFor(mode), selectedButton == GamepadButton.LB) {
                    onButton(GamepadButton.LB)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GamepadKey(GamepadButton.RB.labelFor(mode), selectedButton == GamepadButton.RB) {
                    onButton(GamepadButton.RB)
                }
                GamepadKey(GamepadButton.RT.labelFor(mode), selectedButton == GamepadButton.RT) {
                    onButton(GamepadButton.RT)
                }
            }
        }

        // 主体：方向键 / 功能键 / ABXY
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DpadCluster(selectedButton, mode, onButton, modifier = Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GamepadKey(GamepadButton.GUIDE.labelFor(mode), selectedButton == GamepadButton.GUIDE, width = 60.dp) {
                    onButton(GamepadButton.GUIDE)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GamepadKey(GamepadButton.BACK.labelFor(mode), selectedButton == GamepadButton.BACK, width = 52.dp) {
                        onButton(GamepadButton.BACK)
                    }
                    GamepadKey(GamepadButton.START.labelFor(mode), selectedButton == GamepadButton.START, width = 52.dp) {
                        onButton(GamepadButton.START)
                    }
                }
            }

            AbxyCluster(selectedButton, mode, onButton, modifier = Modifier.weight(1f))
        }

        // 底部：左右摇杆
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StickZone(
                    x = stickX,
                    y = stickY,
                    onStick = onStick,
                    hint = "左摇杆",
                )
                GamepadKey(GamepadButton.L3.labelFor(mode), selectedButton == GamepadButton.L3, width = 52.dp) {
                    onButton(GamepadButton.L3)
                }
            }
            GamepadKey(GamepadButton.R3.labelFor(mode), selectedButton == GamepadButton.R3, width = 52.dp) {
                onButton(GamepadButton.R3)
            }
        }
    }
}

/** 单个按键：圆形（或胶囊）可点按按钮。 */
@Composable
private fun GamepadKey(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    width: Dp = size,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Surface(
        modifier = modifier
            .size(width = width, height = if (width == size) size else 30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CenteredText(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected) scheme.onPrimary else scheme.onSurface,
            )
        }
    }
}

/** 十字方向键。 */
@Composable
private fun DpadCluster(
    selectedButton: GamepadButton?,
    mode: GamepadMode,
    onButton: (GamepadButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(112.dp)) {
        GamepadKey(GamepadButton.DPAD_UP.labelFor(mode), selectedButton == GamepadButton.DPAD_UP, Modifier.align(Alignment.TopCenter)) {
            onButton(GamepadButton.DPAD_UP)
        }
        GamepadKey(GamepadButton.DPAD_DOWN.labelFor(mode), selectedButton == GamepadButton.DPAD_DOWN, Modifier.align(Alignment.BottomCenter)) {
            onButton(GamepadButton.DPAD_DOWN)
        }
        GamepadKey(GamepadButton.DPAD_LEFT.labelFor(mode), selectedButton == GamepadButton.DPAD_LEFT, Modifier.align(Alignment.CenterStart)) {
            onButton(GamepadButton.DPAD_LEFT)
        }
        GamepadKey(GamepadButton.DPAD_RIGHT.labelFor(mode), selectedButton == GamepadButton.DPAD_RIGHT, Modifier.align(Alignment.CenterEnd)) {
            onButton(GamepadButton.DPAD_RIGHT)
        }
    }
}

/** ABXY 菱形布局：Y 上 / A 下 / X 左 / B 右。 */
@Composable
private fun AbxyCluster(
    selectedButton: GamepadButton?,
    mode: GamepadMode,
    onButton: (GamepadButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(112.dp)) {
        GamepadKey(GamepadButton.Y.labelFor(mode), selectedButton == GamepadButton.Y, Modifier.align(Alignment.TopCenter)) {
            onButton(GamepadButton.Y)
        }
        GamepadKey(GamepadButton.A.labelFor(mode), selectedButton == GamepadButton.A, Modifier.align(Alignment.BottomCenter)) {
            onButton(GamepadButton.A)
        }
        GamepadKey(GamepadButton.X.labelFor(mode), selectedButton == GamepadButton.X, Modifier.align(Alignment.CenterStart)) {
            onButton(GamepadButton.X)
        }
        GamepadKey(GamepadButton.B.labelFor(mode), selectedButton == GamepadButton.B, Modifier.align(Alignment.CenterEnd)) {
            onButton(GamepadButton.B)
        }
    }
}

/**
 * 摇杆区：拖动中间的圆点，松手后回调方向向量（-1.0 ~ 1.0）。
 */
@Composable
private fun StickZone(
    x: Float,
    y: Float,
    hint: String,
    onStick: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    var knob by remember { mutableStateOf(Offset(x, y)) }
    LaunchedEffect(x, y) { knob = Offset(x, y) }

    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onStick(knob.x, knob.y) },
                ) { change, drag ->
                    change.consume()
                    // 振幅按圆盘半径归一：半径的一半即 ±1.0 满偏
                    val maxRadius = size.width / 2f * 0.62f
                    val next = Offset(knob.x + drag.x / maxRadius, knob.y + drag.y / maxRadius)
                    knob = Offset(next.x.coerceIn(-1f, 1f), next.y.coerceIn(-1f, 1f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CenteredText(
            text = hint,
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onSurfaceVariantSummary,
        )
        Box(
            modifier = Modifier
                .offset(x = (knob.x * 30).dp, y = (knob.y * 30).dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(scheme.primary),
        )
    }
}

/** 摇杆方向向量的小提示文本，配合 [GamepadLayout] 使用。 */
@Composable
fun StickValueHint(
    x: Float,
    y: Float,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    val percentX = (x * 100).toInt()
    val percentY = (y * 100).toInt()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "摇杆方向 X $percentX% · Y $percentY%",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        if (onReset != null) {
            Text(
                text = "复位",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 手绘手柄图标。
 *
 * MIUIX 没有现成的手柄图形，这里用 [Canvas] 画一个通用手柄轮廓：主体 + 左右握把 +
 * 肩键（半透明），十字方向键、ABXY 与功能键（实心）。用于标定悬浮球与编辑器标定入口。
 */
@Composable
fun GamepadGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    Canvas(modifier = modifier) { drawGamepadGlyph(tint) }
}

private fun DrawScope.drawGamepadGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val s = minOf(w, h)
    if (s <= 0f) return

    val soft = color.copy(alpha = 0.38f)

    // 主体
    val bodyLeft = 0.08f * w
    val bodyTop = 0.30f * h
    val bodyWidth = 0.84f * w
    val bodyHeight = 0.56f * h
    val bodyCorner = 0.30f * s
    drawRoundRect(
        color = soft,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(bodyCorner, bodyCorner),
    )

    // 左右握把
    val gripRadius = 0.20f * s
    drawCircle(soft, gripRadius, Offset(bodyLeft + 0.12f * w, bodyTop + bodyHeight - 0.06f * h))
    drawCircle(soft, gripRadius, Offset(bodyLeft + bodyWidth - 0.12f * w, bodyTop + bodyHeight - 0.06f * h))

    // 肩键
    val shoulderCorner = CornerRadius(0.08f * s, 0.08f * s)
    drawRoundRect(
        color = soft,
        topLeft = Offset(0.16f * w, 0.12f * h),
        size = Size(0.24f * w, 0.20f * h),
        cornerRadius = shoulderCorner,
    )
    drawRoundRect(
        color = soft,
        topLeft = Offset(0.60f * w, 0.12f * h),
        size = Size(0.24f * w, 0.20f * h),
        cornerRadius = shoulderCorner,
    )

    // 十字方向键（左）
    val dpadX = 0.30f * w
    val dpadY = 0.56f * h
    drawRoundRect(
        color = color,
        topLeft = Offset(dpadX - 0.035f * w, dpadY - 0.125f * h),
        size = Size(0.07f * w, 0.25f * h),
        cornerRadius = CornerRadius(0.02f * s, 0.02f * s),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(dpadX - 0.105f * w, dpadY - 0.045f * h),
        size = Size(0.21f * w, 0.09f * h),
        cornerRadius = CornerRadius(0.02f * s, 0.02f * s),
    )

    // ABXY（右，菱形）
    val abxyX = 0.70f * w
    val abxyY = 0.56f * h
    val dot = 0.035f * s
    drawCircle(color, dot, Offset(abxyX, abxyY - 0.10f * h))
    drawCircle(color, dot, Offset(abxyX, abxyY + 0.10f * h))
    drawCircle(color, dot, Offset(abxyX - 0.09f * w, abxyY))
    drawCircle(color, dot, Offset(abxyX + 0.09f * w, abxyY))

    // 中央功能键
    val centerCorner = CornerRadius(0.015f * s, 0.015f * s)
    drawRoundRect(
        color = color,
        topLeft = Offset(0.455f * w, 0.52f * h),
        size = Size(0.035f * w, 0.05f * h),
        cornerRadius = centerCorner,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(0.51f * w, 0.52f * h),
        size = Size(0.035f * w, 0.05f * h),
        cornerRadius = centerCorner,
    )
}
