package com.autorunner.core.platform

/**
 * `actual` factories for Android.
 *
 * The concrete implementations are published by the platform services
 * (`AutoRunnerAccessibilityService`, `AutoRunnerOverlayService`); until they are
 * registered the no-op implementations are returned so that the shared UI can
 * render meaningful "service not enabled" states.
 */
actual fun createAccessibilityController(): AccessibilityController =
    PlatformServices.accessibilityController ?: UnavailableAccessibilityController

actual fun createOverlayManager(): OverlayManager =
    PlatformServices.overlayManager ?: UnavailableOverlayManager()

actual fun createRecordingController(): RecordingController =
    PlatformServices.recordingController ?: UnavailableRecordingController()

actual fun createGamepadCalibrationController(): GamepadCalibrationController =
    PlatformServices.gamepadCalibrationController ?: UnavailableGamepadCalibrationController()
