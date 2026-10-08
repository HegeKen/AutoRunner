package com.autorunner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import com.autorunner.ui.adaptive.AutoRunnerWindowSize

/**
 * 页面级脚手架：大标题顶栏 + 零 insets 的 Scaffold + 滚动连接。
 *
 * 这个结构此前在脚本编辑器、设置页、录制页各写了一遍（三份几乎逐字相同），
 * 任何一处漏改都会让某个页面的大标题不跟随滚动折叠。收敛到一处后：
 *
 *  - `contentWindowInsets` 固定为零：外壳 Scaffold 已经消费过状态栏 / 挖孔区域，
 *    页面自己再加一次会在顶部多出一条空白带；
 *  - 滚动连接挂在「不滚动的外层 Column」上，内部滚动体（`verticalScroll` /
 *    `LazyColumn`）把事件向上派发；挂在内侧滚动节点上则永远收不到事件。
 *
 * @param content 页面内容，参数为 Scaffold 给出的内容内边距。
 */
@Composable
fun PageScaffold(
    windowSize: AutoRunnerWindowSize,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize(),
        topBar = {
            AutoRunnerTopAppBar(
                windowSize = windowSize,
                title = title,
                subtitle = subtitle,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .then(
                    if (windowSize.showTopAppBar) {
                        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    } else {
                        Modifier
                    },
                ),
        ) {
            content(contentPadding)
        }
    }
}
