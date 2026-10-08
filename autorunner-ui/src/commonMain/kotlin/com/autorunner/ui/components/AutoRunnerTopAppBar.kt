package com.autorunner.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import com.autorunner.ui.adaptive.AutoRunnerWindowSize

/**
 * 全应用共用的 MIUIX 顶栏。
 *
 * 大标题只在窗口足够高时启用（[AutoRunnerWindowSize.showTopAppBar]）：矮 / 横屏窗口里
 * 它既占掉宝贵高度，又会把滚动手势吃掉（表现为页面无法滑动），此时退回 [SmallTopAppBar]。
 * 两种形态都设置 `defaultWindowInsetsPadding = true`，与外层 Shell `Scaffold` 置零的
 * `contentWindowInsets` 配套，避免页面顶部多出一段状态栏空白。
 *
 * 调用方需把 [scrollBehavior] 的 `nestedScrollConnection` 挂在“不滚动的外层容器”上，
 * 大标题才会随内容滚动折叠。
 */
@Composable
fun AutoRunnerTopAppBar(
    windowSize: AutoRunnerWindowSize,
    title: String,
    subtitle: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (windowSize.showTopAppBar) {
        TopAppBar(
            defaultWindowInsetsPadding = true,
            modifier = modifier,
            title = title,
            largeTitle = largeTitle,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        SmallTopAppBar(
            defaultWindowInsetsPadding = true,
            modifier = modifier,
            title = title,
            subtitle = subtitle,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}
