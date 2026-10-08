package com.autorunner.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Shared spacing / sizing tokens. Keeping them in one place is what makes the
 * phone and tablet layouts look like the same product.
 */
object Dimens {

    val ScreenPadding = 16.dp
    val ScreenPaddingWide = 24.dp

    /** 宽窗口下表单的最大宽度，避免输入框被拉伸到整屏。 */
    val FormMaxWidth = 760.dp

    // -------------------------------------------------------------- 行内边距
    //
    // `BasicComponent` 的 `insideMargin` 曾经在调用点散落成 6 种手写值，导致
    // 「权限行（12dp）」和「技术栈行（10dp）」这类相邻行肉眼可见地不齐。
    // 下面这些令牌覆盖全部行内边距用法，改一处即可全局对齐。

    /** 标准偏好行：权限行、存储行、开关行、可点击入口行。 */
    val PreferenceRowPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

    /** 紧凑只读行：技术栈、关于等纯信息展示行，行高略低于标准行。 */
    val CompactRowPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

    /** 带表单控件的复合行（底部有输入框 / 滑块的偏好项）。 */
    val FormFieldPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)

    /** 已经处在 16dp 页面边距内的数值行，不再叠加水平内边距。 */
    val InsetRowPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)

    /** 嵌在卡片内、只留一点水平内边距的偏好行（手柄标定入口）。 */
    val InsetPreferenceRowPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)

    /** 分区卡片内部的通栏内容边距（空状态说明、预览文本等）。 */
    val CardContentPadding = PaddingValues(16.dp)

    /** 空状态卡片的边距，比普通卡片更宽松。 */
    val EmptyStatePadding = PaddingValues(20.dp)

    /** 宽屏导航栏（NavigationRail）宽度；外壳顶栏需要按它缩进以对齐内容。 */
    val RailWidth = 80.dp

    val CardSpacing = 12.dp
    val SectionSpacing = 20.dp
    val ItemSpacing = 8.dp
    val TightSpacing = 4.dp

    val CardCorner = 16.dp
    val PanelCorner = 20.dp

    /** Minimum touch target mandated by the Android accessibility guidelines. */
    val MinTouchTarget = 48.dp

    /** Width of the floating ball's long-press mini action menu. */
    val OverlayMenuWidth = 208.dp

    /** Size of the draggable floating ball. */
    val FloatingBallSize = 56.dp

    /** Extra window margin around the floating ball (touch slop / glow room). */
    val FloatingBallWindowPadding = 12.dp

    /** Preferred maximum width of centred content on tablets. */
    val MaxContentWidth = 720.dp

    /** Width of the editor's preview pane in the supporting pane layout. */
    val PreviewPaneWidth = 320.dp
}
