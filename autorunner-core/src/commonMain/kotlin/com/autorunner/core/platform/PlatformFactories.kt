package com.autorunner.core.platform

/**
 * expect/actual 桥梁，把 AutoRunner 服务的平台特定实现交给共享代码。
 *
 * ```
 * // commonMain
 * expect fun createAccessibilityController(): AccessibilityController
 * expect fun createOverlayManager(): OverlayManager
 *
 * // androidMain — backed by AutoRunnerAccessibilityService / AutoRunnerOverlayService
 * actual fun createAccessibilityController(): AccessibilityController =
 *     AndroidPlatformRegistry.accessibilityController
 * ```
 */
expect fun createAccessibilityController(): AccessibilityController

/** @see OverlayManager */
expect fun createOverlayManager(): OverlayManager

/** @see RecordingController */
expect fun createRecordingController(): RecordingController

/** @see GamepadCalibrationController */
expect fun createGamepadCalibrationController(): GamepadCalibrationController

/**
 * 简单的服务定位器，由平台层在单例创建后填入。
 *
 * 把它放在共享代码里（而不是引入 DI 框架）可以让上面的
 * `expect fun` 保持无参数，这很重要，因为 `AccessibilityService`
 * 由系统构造，没有可注入的构造函数。
 */
object PlatformServices {

    var accessibilityController: AccessibilityController? = null

    var overlayManager: OverlayManager? = null

    var recordingController: RecordingController? = null

    var gamepadCalibrationController: GamepadCalibrationController? = null

    /** 测试调用它来把所有注册清空。 */
    fun reset() {
        accessibilityController = null
        overlayManager = null
        recordingController = null
        gamepadCalibrationController = null
    }
}
