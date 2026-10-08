package com.autorunner.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 驱动手柄标定的屏上流程。
 *
 * 当脚本编辑器需要手柄坐标时，用户会被带入真实游戏页面，并显示一个
 * 悬浮的手柄入口球。点按该球会展开所有按键为可拖动的标签，用户可以
 * 把它们对齐到屏幕上的实际控件；结果存入全局手柄映射。
 */
interface GamepadCalibrationController {

    /** 悬浮标定层存活期间为 `true`。 */
    val active: StateFlow<Boolean>

    /**
     * 布防标定悬浮层并把应用切到后台，让用户能打开真实游戏页面。
     *
     * @return 悬浮层无法显示（缺少悬浮权限）时为 `false`。
     */
    fun arm(): Boolean

    /** 拆除标定悬浮层。 */
    fun dismiss()
}

/** 平台无法显示悬浮层时使用的兜底实现。 */
class UnavailableGamepadCalibrationController(
    private val reason: String = "当前平台不支持手柄标定",
) : GamepadCalibrationController {

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    /** 填充该字段，让 UI 能解释标定为何不可用。 */
    var lastError: String? = reason
        private set

    override fun arm(): Boolean = false

    override fun dismiss() {
        _active.value = false
    }
}
