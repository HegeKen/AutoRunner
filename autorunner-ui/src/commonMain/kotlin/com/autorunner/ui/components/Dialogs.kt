package com.autorunner.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 全应用统一的弹窗骨架：标题 + 说明 +（可选）表单内容 + 主按钮 + 取消。
 *
 * 抽取之前，项目里有 6 处手写的 `OverlayDialog + Column + AppButton + TextButton`，
 * 按钮间距、语气（`tone`）与文字色各不相同——最典型的是「删除脚本」用主色、
 * 「清空全部」用危险色，同一个破坏性操作给出两种风险暗示。
 *
 * 显隐统一由 [show] 驱动（而不是 `target?.let { OverlayDialog(show = true, …) }`），
 * 这样 MIUIX 的进出场动画不会被外层 `let` 直接摘掉节点而截断；需要在该动画结束后
 * 再清空数据源的调用方，用 [onDismissFinished] 收尾。
 */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "确认",
    /** 破坏性操作（删除 / 清空 / 恢复默认）一律传 [AppButtonTone.Danger]。 */
    confirmTone: AppButtonTone = AppButtonTone.Primary,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "取消",
    onDismissFinished: (() -> Unit)? = null,
) {
    DialogShell(
        show = show,
        title = title,
        summary = message,
        modifier = modifier,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        confirmLabel = confirmLabel,
        confirmTone = confirmTone,
        confirmEnabled = confirmEnabled,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel,
        fields = null,
    )
}

/**
 * 「一个输入框 + 保存 / 取消」的弹窗（重命名脚本等）。
 *
 * 输入内容由调用方持有：弹窗本身不缓存文本，避免和外部状态产生第二份真相。
 */
@Composable
fun InputDialog(
    show: Boolean,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    confirmLabel: String = "保存",
    confirmEnabled: Boolean = true,
    dismissLabel: String = "取消",
    singleLine: Boolean = true,
    onDismissFinished: (() -> Unit)? = null,
) {
    DialogShell(
        show = show,
        title = title,
        summary = summary,
        modifier = modifier,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        confirmLabel = confirmLabel,
        confirmTone = AppButtonTone.Primary,
        confirmEnabled = confirmEnabled,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel,
        fields = {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
            )
        },
    )
}

/**
 * 多字段表单弹窗（手柄按键映射、摇杆中心等）。
 *
 * [fields] 渲染在说明与按钮之间；[confirmEnabled] 让调用方在解析失败时禁用保存，
 * 而不是用 `?: 0f` 把非法输入兜底成 0 写进设置。
 */
@Composable
fun FormDialog(
    show: Boolean,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    confirmLabel: String = "保存",
    confirmEnabled: Boolean = true,
    dismissLabel: String = "取消",
    onDismissFinished: (() -> Unit)? = null,
    fields: @Composable ColumnScope.() -> Unit,
) {
    DialogShell(
        show = show,
        title = title,
        summary = summary,
        modifier = modifier,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        confirmLabel = confirmLabel,
        confirmTone = AppButtonTone.Primary,
        confirmEnabled = confirmEnabled,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel,
        fields = fields,
    )
}

/** [ConfirmDialog] / [InputDialog] / [FormDialog] 共用的渲染骨架。 */
@Composable
private fun DialogShell(
    show: Boolean,
    title: String,
    summary: String?,
    onDismiss: () -> Unit,
    onDismissFinished: (() -> Unit)?,
    confirmLabel: String,
    confirmTone: AppButtonTone,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    dismissLabel: String,
    modifier: Modifier,
    fields: (@Composable ColumnScope.() -> Unit)?,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        modifier = modifier,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        ButtonColumn {
            fields?.invoke(this)
            AppButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = confirmEnabled,
                tone = confirmTone,
            ) {
                Text(text = confirmLabel, color = confirmTone.contentColor())
            }
            TextButton(
                text = dismissLabel,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
