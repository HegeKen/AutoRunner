package com.autorunner.core.platform

import android.content.Context

/**
 * 共享对象图的 Android 专用入口。
 *
 * `AutoRunnerApplication` 会调用一次 [attach]，此后 `commonMain` 中的每个
 * `expect` 工厂都能返回真实实现。
 */
object AndroidPlatform {

    internal var applicationContext: Context? = null
        private set

    /** 注册应用上下文；可安全地多次调用。 */
    fun attach(context: Context) {
        applicationContext = context.applicationContext
    }

    /** [attach] 执行过之后为 `true`。 */
    val isAttached: Boolean get() = applicationContext != null

    /** 发布已连接的无障碍服务。 */
    fun registerAccessibilityController(controller: AccessibilityController?) {
        PlatformServices.accessibilityController = controller
    }

    /** 发布由无障碍服务支撑的录制实现。 */
    fun registerRecordingController(controller: RecordingController?) {
        PlatformServices.recordingController = controller
    }

    /** 发布由悬浮窗服务支撑的悬浮窗口管理器。 */
    fun registerOverlayManager(manager: OverlayManager?) {
        PlatformServices.overlayManager = manager
    }

    /** 发布由其悬浮窗服务支撑的手柄标定实现。 */
    fun registerGamepadCalibrationController(controller: GamepadCalibrationController?) {
        PlatformServices.gamepadCalibrationController = controller
    }

    /** 清空全部注册（进程关闭时使用）。 */
    fun detach() {
        PlatformServices.reset()
        applicationContext = null
    }
}
