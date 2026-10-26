package cn.helilab.autorunner.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HangDialogGuardTest {

    private val ownPackage = "cn.helilab.autorunner"
    private val gamePackage = "com.example.game"

    @Test
    fun ownWindowIsIgnored() {
        assertFalse(candidate(packageName = ownPackage, confirmTexts = listOf("确定", "取消")))
    }

    @Test
    fun systemWindowIsIgnored() {
        // 通知栏 / 音量条 / 权限框等系统窗口正文里也可能出现「确定」，
        // 一律不作为挂机弹窗处理。
        assertFalse(
            candidate(
                windowType = DialogWindowType.SYSTEM,
                isFullscreen = false,
                confirmTexts = listOf("确定"),
            ),
        )
    }

    @Test
    fun existingWindowIsNotTreatedAsDialog() {
        // 挂机开始前就存在的窗口（游戏主界面）不应误报，即使它有确认按钮。
        assertFalse(
            candidate(
                isNewWindow = false,
                isFullscreen = false,
                confirmTexts = listOf("取消", "确定"),
                titleTexts = listOf("提示"),
            ),
        )
    }

    @Test
    fun smallNewWindowWithOneConfirmButtonIsDialog() {
        assertTrue(
            candidate(
                isFullscreen = false,
                confirmTexts = listOf("确定"),
            ),
        )
    }

    @Test
    fun smallNewWindowWithOnlyTitleIsDialog() {
        // 自绘按钮可能拿不到文字，标题命中也要退出挂机。
        assertTrue(
            candidate(
                isFullscreen = false,
                titleTexts = listOf("提示"),
            ),
        )
    }

    @Test
    fun fullscreenWindowNeedsStrongerEvidence() {
        // 全屏广告 / 公告页：只有一个「确定」不足以判定，避免把游戏内 UI 当弹窗。
        assertFalse(
            candidate(
                isFullscreen = true,
                confirmTexts = listOf("确定"),
                titleTexts = listOf("公告"),
            ),
        )
        // 双按钮 + 标题类文案才放行。
        assertTrue(
            candidate(
                isFullscreen = true,
                confirmTexts = listOf("取消", "确定"),
                titleTexts = listOf("公告"),
            ),
        )
    }

    @Test
    fun packageChangeWithTwoConfirmButtonsIsDialog() {
        // 前台被换成浏览器 / 支付页：即使窗口 id 恰好撞上基线，也按弹窗处理。
        assertTrue(
            candidate(
                packageName = "com.android.chrome",
                isNewWindow = false,
                isFullscreen = true,
                confirmTexts = listOf("取消", "确认支付"),
            ),
        )
    }

    @Test
    fun packageChangeWithSingleConfirmButtonIsTooWeak() {
        // 用户主动切到新应用时，其主页上的单个「关闭」按钮不应触发挂机退出。
        assertFalse(
            candidate(
                packageName = "com.android.chrome",
                isNewWindow = false,
                isFullscreen = true,
                confirmTexts = listOf("关闭"),
            ),
        )
    }

    @Test
    fun toastClassIsIgnored() {
        assertFalse(
            candidate(
                className = "android.widget.Toast",
                isFullscreen = false,
                confirmTexts = listOf("确定"),
            ),
        )
    }

    @Test
    fun confirmTextMatchingUsesWordBoundariesForEnglish() {
        assertTrue(isConfirmText("确定"))
        assertTrue(isConfirmText("OK"))
        assertTrue(isConfirmText("Confirm purchase"))
        assertTrue(isConfirmText("立即购买"))
        assertFalse(isConfirmText("TikTok"))
        assertFalse(isConfirmText("Look at this"))
    }

    @Test
    fun titleTextMatching() {
        assertTrue(isDialogTitleText("系统提示"))
        assertTrue(isDialogTitleText("Warning"))
        assertFalse(isDialogTitleText("开始游戏"))
    }

    private fun candidate(
        packageName: String = gamePackage,
        className: String = "android.app.Dialog",
        windowType: DialogWindowType = DialogWindowType.APPLICATION,
        isFullscreen: Boolean = false,
        isNewWindow: Boolean = true,
        confirmTexts: List<String> = emptyList(),
        titleTexts: List<String> = emptyList(),
    ) = isHangDialogCandidate(
        signal = DialogSignal(
            packageName = packageName,
            className = className,
            windowId = 42,
            windowType = windowType,
            isFullscreen = isFullscreen,
            isNewWindow = isNewWindow,
            confirmTexts = confirmTexts,
            titleTexts = titleTexts,
        ),
        ownPackage = ownPackage,
        baselinePackage = gamePackage,
    )
}
