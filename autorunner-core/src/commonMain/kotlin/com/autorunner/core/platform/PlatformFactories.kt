package com.autorunner.core.platform

/**
 * Expect/actual bridge that hands the platform specific implementations of the
 * AutoRunner services to the shared code.
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
 * Simple service locator the platform layer fills in once its singletons exist.
 *
 * Keeping it in shared code (instead of reaching for a DI framework) lets the
 * `expect fun` above stay parameterless, which matters because an
 * `AccessibilityService` is constructed by the system and has no injectable
 * constructor.
 */
object PlatformServices {

    var accessibilityController: AccessibilityController? = null

    var overlayManager: OverlayManager? = null

    var recordingController: RecordingController? = null

    var gamepadCalibrationController: GamepadCalibrationController? = null

    /** Called from tests to detach everything again. */
    fun reset() {
        accessibilityController = null
        overlayManager = null
        recordingController = null
        gamepadCalibrationController = null
    }
}
