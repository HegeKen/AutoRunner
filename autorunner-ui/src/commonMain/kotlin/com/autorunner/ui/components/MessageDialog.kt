package com.autorunner.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一的“提示”弹窗：所有页面反馈消息都经它渲染，保证观感一致
 * （标题 + 消息正文 + 一个「知道了」按钮），不再出现有的页面只有纯文本、
 * 有的页面带按钮的分歧。
 *
 * `message == null` 时不渲染任何内容，调用方可以直接透传可空的反馈状态；
 * 显隐由 `show` 驱动（而不是在 `message` 为空时直接把节点摘掉），并在退场动画
 * 期间沿用最后一条消息，因此正文不会先于弹窗消失。
 */
@Composable
fun MessageDialog(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "提示",
) {
    // 退场动画期间 message 已经变成 null，这里保留最后一条正文。
    var lastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) lastMessage = message }
    val shownMessage = message ?: lastMessage
    if (shownMessage == null) return

    OverlayDialog(
        show = message != null,
        title = title,
        summary = shownMessage,
        onDismissRequest = onDismiss,
        onDismissFinished = { lastMessage = null },
        modifier = modifier,
    ) {
        AppButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("知道了", color = MiuixTheme.colorScheme.onPrimary)
        }
    }
}
