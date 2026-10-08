package com.autorunner.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 共享的间距 / 尺寸令牌。集中放在一处，手机与平板布局才会看起来像同一个产品。
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

    /** Android 无障碍规范要求的最小触摸目标尺寸。 */
    val MinTouchTarget = 48.dp

    /** 悬浮球长按迷你快捷菜单的宽度。 */
    val OverlayMenuWidth = 208.dp

    /** 可拖动悬浮球的尺寸。 */
    val FloatingBallSize = 56.dp

    /** 悬浮球四周额外的窗口边距（触摸容差 / 发光效果预留空间）。 */
    val FloatingBallWindowPadding = 12.dp

    /** 平板上居中内容的首选最大宽度。 */
    val MaxContentWidth = 720.dp

    /** 辅助窗格布局中编辑器预览面板的宽度。 */
    val PreviewPaneWidth = 320.dp
}
