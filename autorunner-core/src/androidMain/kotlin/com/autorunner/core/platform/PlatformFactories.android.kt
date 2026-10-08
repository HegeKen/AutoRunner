package com.autorunner.core.platform

/**
 * Android 平台的 `actual` 工厂。
 *
 * 具体实现由平台服务（`AutoRunnerAccessibilityService`、`AutoRunnerOverlayService`）
 * 发布；在它们注册之前返回空实现，以便共享 UI 能够渲染有意义的
 * 「服务未启用」状态。
 */
actual fun createAccessibilityController(): AccessibilityController =
    PlatformServices.accessibilityController ?: UnavailableAccessibilityController

actual fun createOverlayManager(): OverlayManager =
    PlatformServices.overlayManager ?: UnavailableOverlayManager()

actual fun createRecordingController(): RecordingController =
    PlatformServices.recordingController ?: UnavailableRecordingController()

actual fun createGamepadCalibrationController(): GamepadCalibrationController =
    PlatformServices.gamepadCalibrationController ?: UnavailableGamepadCalibrationController()
