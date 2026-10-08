package com.autorunner.core.platform

/**
 * `actual` factories for desktop / JVM.
 *
 * AutoRunner is an Android first application; the JVM target exists so that the
 * shared model, engine and UI can be unit tested and previewed without a
 * device. Gesture dispatch and window overlays therefore stay unavailable.
 */
actual fun createAccessibilityController(): AccessibilityController =
    PlatformServices.accessibilityController ?: UnavailableAccessibilityController

actual fun createOverlayManager(): OverlayManager =
    PlatformServices.overlayManager ?: UnavailableOverlayManager()

actual fun createRecordingController(): RecordingController =
    PlatformServices.recordingController ?: UnavailableRecordingController("桌面端不支持触摸录制")

actual fun createGamepadCalibrationController(): GamepadCalibrationController =
    PlatformServices.gamepadCalibrationController ?: UnavailableGamepadCalibrationController("桌面端不支持手柄标定")
