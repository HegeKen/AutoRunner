package cn.helilab.autorunner.overlay

import com.autorunner.core.platform.DockEdge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayPlacementTest {

    private val screenWidth = 1080
    private val screenHeight = 2400
    private val ballWindow = 204 // 68dp @ 3x
    private val menuHeight = ballWindow * 4 + 72 // 球 + 3 个图标 + 间距
    private val contentWidth = ballWindow

    @Test
    fun closedMenuKeepsBallCoordinates() {
        val placement = placement(ballY = 800, menuOpen = false)
        assertEquals(800, placement.y)
        assertEquals(900, placement.x)
        assertFalse(placement.menuAbove)
    }

    @Test
    fun ballInLowerHalfExpandsMenuUpwards() {
        val ballY = screenHeight - ballWindow - 24
        val placement = placement(ballY = ballY, menuOpen = true)

        assertTrue(placement.menuAbove)
        // 窗口向上展开后球的屏幕位置保持原样：y + (内容高 - 球高) == ballY。
        assertEquals(ballY, placement.y + (menuHeight - ballWindow))
        // 菜单整体仍在屏幕内。
        assertTrue(placement.y >= 0)
    }

    @Test
    fun ballInUpperHalfExpandsMenuDownwards() {
        val placement = placement(ballY = 200, menuOpen = true)

        assertFalse(placement.menuAbove)
        assertEquals(200, placement.y)
    }

    @Test
    fun menuIsClampedWhenThereIsNotEnoughRoom() {
        // 球在屏幕上侧，但菜单比剩余空间还高：窗口贴顶，不被推出屏幕。
        val placement = placement(ballY = 120, menuOpen = true, contentHeight = screenHeight * 2)

        assertEquals(0, placement.y)
    }

    @Test
    fun windowIsKeptInsideScreenHorizontally() {
        // 贴右边缘的球：窗口按内容宽度回推，且不会越出右边界。
        val placement = placement(ballX = screenWidth - ballWindow, menuOpen = true)
        assertTrue(placement.x >= 0)
        assertTrue(placement.x + contentWidth <= screenWidth)

        // 菜单比屏幕还宽时贴左边缘，而不是出现负坐标。
        val wide = placement(menuOpen = true, contentWidth = screenWidth * 2)
        assertEquals(0, wide.x)
    }

    @Test
    fun rightDockedBallDoesNotMoveHorizontally() {
        val ballX = screenWidth - ballWindow - 30
        val placement = placement(ballX = ballX, menuOpen = true)

        assertEquals(ballX, placement.x + (contentWidth - ballWindow))
    }

    private fun placement(
        ballX: Int = 900,
        ballY: Int = 800,
        menuOpen: Boolean = true,
        contentWidth: Int = this.contentWidth,
        contentHeight: Int = menuHeight,
    ) = computeOverlayWindowPlacement(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        ballWindow = ballWindow,
        contentWidth = contentWidth,
        contentHeight = contentHeight,
        ballX = ballX,
        ballY = ballY,
        menuOpen = menuOpen,
        dockedEdge = DockEdge.RIGHT,
    )
}
