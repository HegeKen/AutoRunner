package com.autorunner.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import com.autorunner.core.model.ExecutionState
import com.autorunner.ui.theme.AutoRunnerColors
import com.autorunner.ui.theme.accentColor

/**
 * 悬浮控制窗的内容（`AutoRunnerOverlayService`，§6.4.3）。
 *
 * 编辑、录入、执行、标定手柄四处共用同一个悬浮球：单击触发当前状态的主操作，长按
 * 展开 [MiniActionMenu] 迷你快捷菜单。悬浮窗始终只有「球」与「球 + 菜单」两种形态，
 * 不再有输入框、模式选择或进度卡。
 *
 * 球的强调色与球内图标由 [state] 与录制标志决定；菜单内容同样随状态变化。
 *
 * @param alignEnd 球贴靠屏幕右侧时为 `true`，菜单与球一起右对齐；否则左对齐。
 * @param isLandscape 横屏时菜单按钮排成一行（竖屏排成一列）。
 * @param onContentSizeChanged 内容尺寸变化回调：宿主用它把悬浮窗收紧到实际大小，
 *   避免空白区域吞掉游戏里的触摸。
 * @param onPrimaryAction 单击球：空闲→开始执行，运行中→暂停，已暂停→恢复，
 *   已就绪→开始采集，录制中→停止录制，已完成→复位。
 */
@Composable
fun FloatingControlPanel(
    state: ExecutionState,
    menuOpen: Boolean,
    alignEnd: Boolean,
    onPrimaryAction: () -> Unit,
    onToggleMenu: () -> Unit,
    onStop: () -> Unit,
    onStopRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    recordingArmed: Boolean = false,
    isLandscape: Boolean = false,
    onContentSizeChanged: (IntSize) -> Unit = {},
    dragModifier: Modifier = Modifier,
) {
    val accent = if (recording || recordingArmed) {
        AutoRunnerColors.Recording
    } else {
        state.accentColor()
    }
    val glyph = when {
        // 录制中 → 自绘圆角方块（停止）
        recording -> null
        // 录制已就绪 → 录制图标
        recordingArmed -> MiuixIcons.AppRecording
        state == ExecutionState.PAUSED -> MiuixIcons.Play
        state == ExecutionState.COMPLETED -> MiuixIcons.Ok
        state.isActive -> MiuixIcons.Pause
        else -> MiuixIcons.Play
    }

    // 菜单里的动作全部统一成与主球（运行）同款的圆形图标按钮，不再有文字提示。
    val closeAction = MiniAction(onClick = onClose, glyph = MiuixIcons.Close, iconOnly = true)

    val menuActions = when {
        recording -> listOf(
            MiniAction(
                label = "停止录制",
                onClick = onStopRecording,
                glyph = null,
                danger = true,
                iconOnly = true,
            ),
            closeAction,
        )

        recordingArmed -> listOf(closeAction)

        state.isActive -> listOf(
            MiniAction(
                label = if (state == ExecutionState.PAUSED) "继续" else "暂停",
                onClick = onTogglePause,
                glyph = if (state == ExecutionState.PAUSED) MiuixIcons.Play else MiuixIcons.Pause,
                iconOnly = true,
            ),
            MiniAction(label = "停止", onClick = onStop, glyph = null, danger = true, iconOnly = true),
            closeAction,
        )

        else -> listOf(closeAction)
    }

    Column(
        modifier = modifier.onSizeChanged(onContentSizeChanged),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = if (menuOpen) Arrangement.spacedBy(8.dp) else Arrangement.Top,
    ) {
        FloatingBall(
            accent = accent,
            onClick = onPrimaryAction,
            onLongClick = onToggleMenu,
            modifier = dragModifier,
        ) { tint ->
            BallGlyph(glyph = glyph, tint = tint, size = 26.dp)
        }
        if (menuOpen) {
            MiniActionMenu(
                rows = listOf(menuActions),
                alignEnd = alignEnd,
                isLandscape = isLandscape,
            )
        }
    }
}

/** 窗口宿主使用的透明颜色常量。 */
val TransparentWindowColor: Color = Color.Transparent
