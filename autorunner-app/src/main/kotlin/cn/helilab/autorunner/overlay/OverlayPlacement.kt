package cn.helilab.autorunner.overlay

import com.autorunner.core.platform.DockEdge

/**
 * 悬浮窗在屏幕上的最终位置，以及菜单的展开方向。
 *
 * @property x 窗口左上角的屏幕横坐标（像素）。
 * @property y 窗口左上角的屏幕纵坐标（像素）。
 * @property menuAbove 菜单在球的上方展开（球位于屏幕下半部分）。
 */
internal data class OverlayWindowPlacement(
    val x: Int,
    val y: Int,
    val menuAbove: Boolean,
)

/**
 * 计算“球 + 展开菜单”状态的窗口位置。
 *
 * 窗口坐标系与 `Gravity.TOP or Gravity.START` 一致，[ballX]/[ballY] 是球的绝对
 * 屏幕坐标。菜单展开方向按球心落在屏幕上下半屏决定——朝空间更大的一侧展开，
 * 避免球停在底部时菜单被屏幕边缘裁掉；窗口随后整体夹取到屏幕内，空间仍然不够
 * 时宁可把球临时挪一点，也不让按钮跑到屏幕外。
 *
 * 宽度方向沿用原有规则：球在右半屏时菜单向左展开，窗口 x 由球坐标按内容宽度
 * 回推，保证球不随窗口放宽而跳动。
 *
 * @param contentWidth 菜单内容测得的宽度（像素）；尚未测得时传球窗口宽度。
 * @param contentHeight 菜单内容测得的高度（像素）；尚未测得时传球窗口高度。
 */
internal fun computeOverlayWindowPlacement(
    screenWidth: Int,
    screenHeight: Int,
    ballWindow: Int,
    contentWidth: Int,
    contentHeight: Int,
    ballX: Int,
    ballY: Int,
    menuOpen: Boolean,
    dockedEdge: DockEdge,
): OverlayWindowPlacement {
    if (!menuOpen) {
        // 只有球：窗口与球等大，左上角即球坐标。
        return OverlayWindowPlacement(x = ballX, y = ballY, menuAbove = false)
    }

    val menuAbove = ballY + ballWindow / 2 > screenHeight / 2
    val rawX = if (dockedEdge == DockEdge.RIGHT) {
        ballX - (contentWidth - ballWindow)
    } else {
        ballX
    }
    val rawY = if (menuAbove) {
        // 菜单向上展开：窗口顶边按内容高度回推，球的屏幕位置保持不变。
        ballY - (contentHeight - ballWindow)
    } else {
        ballY
    }
    return OverlayWindowPlacement(
        x = rawX.coerceIn(0, (screenWidth - contentWidth).coerceAtLeast(0)),
        y = rawY.coerceIn(0, (screenHeight - contentHeight).coerceAtLeast(0)),
        menuAbove = menuAbove,
    )
}
