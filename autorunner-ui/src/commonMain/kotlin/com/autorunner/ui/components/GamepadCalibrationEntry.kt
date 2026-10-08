package com.autorunner.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autorunner.core.model.GamepadMode
import com.autorunner.ui.theme.Dimens
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 手柄按键标定入口（设置页与脚本编辑器共用）。
 *
 * 提示用户进入真实游戏页面后弹出标定悬浮层，把每个按键拖到实际按钮上；结果按
 * [mode] 这一手柄类型保存，之后新增 / 编辑该类型的手柄动作只需选按键。
 *
 * 抽取之前脚本编辑器与设置页各写了一份几乎相同的实现（入口行 + 确认弹窗），
 * 两处的文案与按钮排布已经开始漂移。
 *
 * @param summary 行说明；`null` 时使用编辑器场景的默认文案。
 * @param statusText 行尾状态药丸（设置页显示「已标定 / 未标定」）。
 * @param onGamepadCalibration 返回 `false` 表示无法开始（例如缺少悬浮窗权限），
 *   此时组件自己提示 [failureMessage]，调用方无需再重复报错。
 */
@Composable
fun GamepadCalibrationEntry(
    mode: GamepadMode,
    onGamepadCalibration: (GamepadMode) -> Boolean,
    modifier: Modifier = Modifier,
    summary: String? = null,
    statusText: String? = null,
    statusColor: Color = MiuixTheme.colorScheme.primary,
    statusContentColor: Color = Color.White,
    iconTint: Color = MiuixTheme.colorScheme.primary,
    /**
     * 行内边距：设置页直接放在 [SectionCard] 里，用默认的 16dp；脚本编辑器把它嵌在
     * 已有 16dp 页面边距的面板中，传 [Dimens.InsetPreferenceRowPadding] 避免双重缩进。
     */
    insideMargin: PaddingValues = Dimens.PreferenceRowPadding,
    failureMessage: String = "无法开始标定，请先在设置中授予「显示在其他应用上层」权限。",
) {
    var showConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trailing: (@Composable RowScope.() -> Unit)? = if (statusText != null) {
        { StatusPill(text = statusText, color = statusColor, contentColor = statusContentColor) }
    } else {
        null
    }

    BasicComponent(
        modifier = modifier,
        title = "标定手柄按键位置",
        summary = summary ?: "针对「${mode.displayName}」进入实际游戏页面，把每个按键拖到真实按钮上",
        startAction = {
            GamepadGlyph(
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
        },
        endActions = trailing,
        onClick = { showConfirm = true },
        insideMargin = insideMargin,
    )

    ConfirmDialog(
        show = showConfirm,
        title = "标定手柄按键位置",
        message = "App 会退到后台并显示手柄悬浮球。请在真实游戏页面点击悬浮球，把 Dpad、" +
            "ABXY 等按键拖到实际按钮上后保存。标定结果只作用于本设备。",
        confirmLabel = "开始标定",
        onConfirm = {
            showConfirm = false
            if (!onGamepadCalibration(mode)) error = failureMessage
        },
        onDismiss = { showConfirm = false },
    )

    MessageDialog(
        message = error,
        onDismiss = { error = null },
    )
}
