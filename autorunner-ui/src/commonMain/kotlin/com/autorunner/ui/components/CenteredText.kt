package com.autorunner.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 宽度已由容器决定的「居中单行文字」：分段选择器标签、手柄按键字、徽标数字等。
 *
 * 只依赖 `Box(contentAlignment = Alignment.Center) { Text(...) }` 是不够的：
 * Android 上 `BasicText` 会把自身节点撑满可用宽度（节点宽度 = 约束宽度），
 * 子节点已经占满时 `contentAlignment` 不再产生位移，文字就贴在容器左边缘——
 * 真机上脚本编辑器「手柄类型」分段选择器的选中项（DualSense 贴着蓝色胶囊
 * 左边缘）正是这个问题；桌面端 `BasicText` 返回文字固有宽度，看上去又是好的，
 * 所以这种平台差异在开发机上很难发现。
 *
 * 这里显式 `fillMaxWidth()` + [TextAlign.Center]，两种测量行为下结果一致；
 * 超长文案由 [overflow] 省略，不会顶到容器边缘或被硬裁。
 *
 * 注意：容器宽度必须有界（`weight` / `size` / 父级已定宽）。在 wrap-content 容器里
 * `fillMaxWidth` 会把容器撑到父级宽度，那时请改用普通的 `Text`。
 */
@Composable
fun CenteredText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MiuixTheme.textStyles.main,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = color,
        style = style,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = overflow,
    )
}
