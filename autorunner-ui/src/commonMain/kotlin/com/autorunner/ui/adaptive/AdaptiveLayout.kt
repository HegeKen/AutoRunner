package com.autorunner.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * 由 Material 3 Adaptive 窗口尺寸等级驱动的粗粒度布局分档。
 *
 * `compact`   — 手机竖屏
 * `medium`    — 手机横屏、展开后较小的折叠屏
 * `expanded`  — 平板 / 大屏
 */
enum class LayoutMode { COMPACT, MEDIUM, EXPANDED }

/**
 * 页面适配窗口所需的全部信息。
 *
 * 见设计文档 §5：窗口尺寸等级是适配 UI 的唯一事实来源，
 * 因此任何页面都不会硬编码断点。
 */
@Immutable
data class AutoRunnerWindowSize(
    val layoutMode: LayoutMode,
    /** 为 `true` 时用 `NavigationRail` 替代 `NavigationBar`。 */
    val useNavigationRail: Boolean,
    /** 为 `true` 时可以显示顶部应用栏（窗口高度不足时会隐藏）。 */
    val showTopAppBar: Boolean,
    /** 脚本列表与编辑器并排显示（List-Detail 模式）。 */
    val useListDetail: Boolean,
    /** 编辑器与实时预览并排显示（Supporting Pane 模式）。 */
    val useSupportingPane: Boolean,
    /** 录制事件流可以与控制区并排显示。 */
    val useFeedLayout: Boolean,
    val minWidthDp: Int,
    val minHeightDp: Int,
) {
    val isTablet: Boolean get() = layoutMode == LayoutMode.EXPANDED
}

/** 从 Material 3 Adaptive 库读取当前的 [WindowSizeClass]。 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

/**
 * AutoRunner 基于 Material 3 Adaptive 的断点方案。
 *
 * 窗口尺寸等级把显示区域划分为 compact / medium / expanded 三档；
 * AutoRunner 将其映射为三种布局模式，并据此决定导航与窗格的排布。
 */
@Composable
fun rememberAutoRunnerWindowSize(
    windowSizeClass: WindowSizeClass = rememberWindowSizeClass(),
): AutoRunnerWindowSize = remember(windowSizeClass) {
    val width = windowSizeClass.minWidthDp
    val height = windowSizeClass.minHeightDp

    val isAtLeastMediumWidth = windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )
    val isExpandedWidth = windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
    val isExpandedHeight = windowSizeClass.isHeightAtLeastBreakpoint(
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )

    val mode = when {
        isExpandedWidth -> LayoutMode.EXPANDED
        isAtLeastMediumWidth -> LayoutMode.MEDIUM
        else -> LayoutMode.COMPACT
    }

    AutoRunnerWindowSize(
        layoutMode = mode,
        useNavigationRail = isAtLeastMediumWidth,
        showTopAppBar = isExpandedHeight,
        useListDetail = isExpandedWidth,
        // 只有窗口足够宽时才放得下预览窗格。
        useSupportingPane = windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        ) && width >= 840,
        useFeedLayout = isAtLeastMediumWidth,
        minWidthDp = width,
        minHeightDp = height,
    )
}

/**
 * 自定义断点辅助函数，供需要比标准 `expanded` 等级更高门槛的平板使用
 * （见 §5.3「Supporting Pane 模式」）。
 */
@Composable
fun isAtLeastWidthDp(width: Int): Boolean {
    val sizeClass = rememberWindowSizeClass()
    return sizeClass.minWidthDp >= width
}

/** 供只关心单一 dp 阈值的调用点使用的便捷封装。 */
@Composable
fun isWideEnoughForPane(paneWidthDp: Int, contentWidthDp: Int = 480): Boolean {
    val sizeClass = rememberWindowSizeClass()
    return sizeClass.minWidthDp >= paneWidthDp + contentWidthDp
}

/** 预览与测试用的平板友好型默认值。 */
internal val DefaultPhoneSizeClass: Pair<Int, Int> = 411 to 891

/** 对外暴露以便文档/测试使用：上面用到的 dp 阈值。 */
object Breakpoints {
    val Compact = 0.dp
    val Medium = 600.dp
    val Expanded = 840.dp
}
