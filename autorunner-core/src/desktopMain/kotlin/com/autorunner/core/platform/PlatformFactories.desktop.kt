package com.autorunner.core.platform

/**
 * 桌面 / JVM 平台的 `actual` 工厂。
 *
 * AutoRunner 是一款以 Android 优先的应用；提供 JVM 目标是为了让共享的
 * 模型、引擎和 UI 无需真机即可进行单元测试与预览。因此手势派发和窗口悬浮层
 * 在此平台上保持不可用。
 */
actual fun createAccessibilityController(): AccessibilityController =
    PlatformServices.accessibilityController ?: UnavailableAccessibilityController

actual fun createOverlayManager(): OverlayManager =
    PlatformServices.overlayManager ?: UnavailableOverlayManager()

actual fun createRecordingController(): RecordingController =
    PlatformServices.recordingController ?: UnavailableRecordingController("桌面端不支持触摸录制")

actual fun createGamepadCalibrationController(): GamepadCalibrationController =
    PlatformServices.gamepadCalibrationController ?: UnavailableGamepadCalibrationController("桌面端不支持手柄标定")
