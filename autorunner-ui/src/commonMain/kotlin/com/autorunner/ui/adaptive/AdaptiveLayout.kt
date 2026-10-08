package com.autorunner.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * Coarse layout bucket driven by the Material 3 Adaptive window size class.
 *
 * `compact`   — phone portrait
 * `medium`    — phone landscape, small unfolded foldable
 * `expanded`  — tablet / large screen
 */
enum class LayoutMode { COMPACT, MEDIUM, EXPANDED }

/**
 * Everything the screens need to know about the current window.
 *
 * See §5 of the design document: the window size class is the single source of
 * truth for adapting the UI, so no screen ever hard codes a breakpoint.
 */
@Immutable
data class AutoRunnerWindowSize(
    val layoutMode: LayoutMode,
    /** `true` when a `NavigationRail` should replace the `NavigationBar`. */
    val useNavigationRail: Boolean,
    /** `true` when a top app bar can be afforded (short windows hide it). */
    val showTopAppBar: Boolean,
    /** Script list + editor side by side (List-Detail pattern). */
    val useListDetail: Boolean,
    /** Editor + live preview side by side (Supporting Pane pattern). */
    val useSupportingPane: Boolean,
    /** Recording event feed can be shown next to the controls. */
    val useFeedLayout: Boolean,
    val minWidthDp: Int,
    val minHeightDp: Int,
) {
    val isTablet: Boolean get() = layoutMode == LayoutMode.EXPANDED
}

/** Reads the current [WindowSizeClass] from the Material 3 Adaptive library. */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

/**
 * Material 3 Adaptive based breakpoints for AutoRunner.
 *
 * Window size classes divide the display area into compact / medium / expanded
 * buckets; AutoRunner maps them onto its three layout modes and derives the
 * navigation and pane decisions from them.
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
        // The preview pane only fits once the window is comfortably wide.
        useSupportingPane = windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        ) && width >= 840,
        useFeedLayout = isAtLeastMediumWidth,
        minWidthDp = width,
        minHeightDp = height,
    )
}

/**
 * Custom breakpoint helper for tables that need a slightly higher bar than the
 * standard `expanded` class (see §5.3 "Supporting Pane 模式").
 */
@Composable
fun isAtLeastWidthDp(width: Int): Boolean {
    val sizeClass = rememberWindowSizeClass()
    return sizeClass.minWidthDp >= width
}

/** Convenience for call sites that only care about a single dp threshold. */
@Composable
fun isWideEnoughForPane(paneWidthDp: Int, contentWidthDp: Int = 480): Boolean {
    val sizeClass = rememberWindowSizeClass()
    return sizeClass.minWidthDp >= paneWidthDp + contentWidthDp
}

/** Tablet friendly fallback for previews and tests. */
internal val DefaultPhoneSizeClass: Pair<Int, Int> = 411 to 891

/** Exposed for documentation/tests: the dp thresholds used above. */
object Breakpoints {
    val Compact = 0.dp
    val Medium = 600.dp
    val Expanded = 840.dp
}
