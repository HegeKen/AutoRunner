package com.autorunner.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.autorunner.ui.components.GamepadGlyph
import com.autorunner.ui.theme.Dimens
import com.autorunner.ui.viewmodel.CalibrationTarget
import com.autorunner.ui.viewmodel.GamepadCalibrationViewModel
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import com.autorunner.ui.components.CenteredText
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay

/** 顶部提示 Toast 的停留时长：到点自动消失，避免常驻遮挡按键。 */
private const val HintToastDurationMs = 5_000L

/**
 * 手柄按键标定悬浮层。
 *
 * 标定层常驻全屏：把全部手柄按键（17 个 + 摇杆中心）铺在屏幕上，用户逐个拖到游戏里真实
 * 按钮的位置。底部是一枚与其他悬浮窗共用的悬浮球——点按显示/隐藏按键，长按展开
 * [MiniActionMenu]（重置 / 取消 / 保存并退出）。拖动本身不改动设置，「保存并退出」才把
 * 坐标写入全局手柄映射。
 *
 * 悬浮层为了让游戏画面透出而不加任何背景蒙层，因此每个按键用高对比的圆形胶囊显示。
 * 与执行面板一样，这里也刻意不使用 MIUIX 弹窗宿主：`WindowManager` 悬浮窗不在 `Scaffold`
 * 里，弹窗无法显示。
 */
@Composable
fun GamepadCalibrationPanel(
    viewModel: GamepadCalibrationViewModel,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val performSave: () -> Unit = {
        viewModel.save()
        onSave()
    }

    val scheme = MiuixTheme.colorScheme
    val density = LocalDensity.current
    val chipSize = 46.dp
    val chipSizePx = with(density) { chipSize.toPx() }
    val ballWindowPx = with(density) {
        (Dimens.FloatingBallSize + Dimens.FloatingBallWindowPadding).toPx()
    }
    val edgeInsetPx = with(density) { 12.dp.toPx() }
    val menuGapPx = with(density) { 8.dp.toPx() }

    var chipsVisible by remember { mutableStateOf(true) }
    var hintVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var ballX by remember { mutableStateOf(0f) }
    var ballY by remember { mutableStateOf(0f) }
    var ballReady by remember { mutableStateOf(false) }

    val targets by viewModel.targets.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                canvasSize = size
                if (!ballReady && size.width > 0 && size.height > 0) {
                    // 悬浮球默认停在屏幕底部居中。
                    ballX = (size.width - ballWindowPx) / 2f
                    ballY = (size.height - ballWindowPx - edgeInsetPx).coerceAtLeast(0f)
                    ballReady = true
                }
            },
    ) {
        LaunchedEffect(canvasSize) {
            if (canvasSize.width > 0 && canvasSize.height > 0) {
                viewModel.ensureLayout(
                    canvasSize.width.toFloat(),
                    canvasSize.height.toFloat(),
                )
            }
        }

        // 提示条只在露面后短暂停留，随后自动消失，避免长期遮挡按键位置。
        LaunchedEffect(Unit) {
            delay(HintToastDurationMs)
            hintVisible = false
        }

        if (chipsVisible) {
            targets.forEach { target ->
                CalibrationChip(
                    target = target,
                    size = chipSize,
                    onDrag = { dx, dy ->
                        // 用户一旦开始拖拽就收起提示，直到不再干扰标定。
                        hintVisible = false
                        viewModel.moveTarget(target.id, dx, dy)
                    },
                    modifier = Modifier.offset {
                        IntOffset(
                            (target.x - chipSizePx / 2f).roundToInt(),
                            (target.y - chipSizePx / 2f).roundToInt(),
                        )
                    },
                )
            }
        }

        if (hintVisible) {
            CalibrationHintToast(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 12.dp, end = 12.dp, top = 44.dp)
                    .fillMaxWidth(),
            )
        }

        if (menuOpen) {
            // 三个动作始终在球的正上方弹出：竖屏上下排成一列，横屏排成一行。
            val ballCenterX = ballX + ballWindowPx / 2f
            val menuX = (ballCenterX - menuSize.width / 2f).coerceIn(
                edgeInsetPx,
                (canvasSize.width - menuSize.width - edgeInsetPx).coerceAtLeast(edgeInsetPx),
            )
            val menuY = (ballY - menuSize.height - menuGapPx).coerceAtLeast(edgeInsetPx)
            MiniActionMenu(
                rows = listOf(
                    listOf(
                        MiniAction(
                            label = "重置",
                            onClick = {
                                viewModel.resetLayout()
                                menuOpen = false
                            },
                            glyph = MiuixIcons.Refresh,
                            iconOnly = true,
                        ),
                        MiniAction(
                            label = "取消",
                            onClick = onCancel,
                            glyph = MiuixIcons.Close,
                            iconOnly = true,
                        ),
                        MiniAction(
                            label = "保存并退出",
                            onClick = performSave,
                            glyph = MiuixIcons.Ok,
                            iconOnly = true,
                        ),
                    ),
                ),
                isLandscape = canvasSize.width > canvasSize.height,
                modifier = Modifier
                    .offset { IntOffset(menuX.roundToInt(), menuY.roundToInt()) }
                    .onSizeChanged { menuSize = it },
            )
        }

        FloatingBall(
            accent = scheme.primary,
            onClick = {
                menuOpen = false
                chipsVisible = !chipsVisible
            },
            onLongClick = { menuOpen = !menuOpen },
            modifier = Modifier
                .offset { IntOffset(ballX.roundToInt(), ballY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { menuOpen = false },
                    ) { change, drag ->
                        change.consume()
                        val maxX = (canvasSize.width - ballWindowPx).coerceAtLeast(0f)
                        val maxY = (canvasSize.height - ballWindowPx).coerceAtLeast(0f)
                        ballX = (ballX + drag.x).coerceIn(0f, maxX)
                        ballY = (ballY + drag.y).coerceIn(0f, maxY)
                    }
                },
        ) { tint ->
            GamepadGlyph(modifier = Modifier.size(30.dp), tint = tint)
        }
    }
}

/** 一个可拖拽的标定节点。 */
@Composable
private fun CalibrationChip(
    target: CalibrationTarget,
    size: Dp,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val fill = if (target.isStickCenter) scheme.secondaryContainer else scheme.primary
    val labelColor = if (target.isStickCenter) scheme.onSecondaryContainer else scheme.onPrimary
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fill.copy(alpha = 0.9f))
            .border(width = 2.dp, color = Color.White.copy(alpha = 0.85f), shape = CircleShape)
            .pointerInput(target.id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CenteredText(
            text = target.label,
            style = MiuixTheme.textStyles.footnote1,
            color = labelColor,
        )
    }
}

/** 顶部提示 Toast，短暂说明拖拽与悬浮球操作后自动消失。 */
@Composable
private fun CalibrationHintToast(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MiuixTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Text(
            text = "拖拽按键，对齐到游戏中的实际按钮位置；点按悬浮球显示/隐藏按键，长按可重置、取消或保存并退出",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
