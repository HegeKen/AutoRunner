package com.autorunner.core.platform

import android.content.Context

/**
 * Android specific entry point for the shared object graph.
 *
 * `AutoRunnerApplication` calls [attach] once, after which every `expect`
 * factory in `commonMain` is able to return a real implementation.
 */
object AndroidPlatform {

    internal var applicationContext: Context? = null
        private set

    /** Registers the application context; safe to call more than once. */
    fun attach(context: Context) {
        applicationContext = context.applicationContext
    }

    /** `true` once [attach] ran. */
    val isAttached: Boolean get() = applicationContext != null

    /** Publishes a connected accessibility service. */
    fun registerAccessibilityController(controller: AccessibilityController?) {
        PlatformServices.accessibilityController = controller
    }

    /** Publishes the recording implementation backed by the accessibility service. */
    fun registerRecordingController(controller: RecordingController?) {
        PlatformServices.recordingController = controller
    }

    /** Publishes the floating window manager backed by the overlay service. */
    fun registerOverlayManager(manager: OverlayManager?) {
        PlatformServices.overlayManager = manager
    }

    /** Publishes the gamepad calibration implementation backed by its overlay service. */
    fun registerGamepadCalibrationController(controller: GamepadCalibrationController?) {
        PlatformServices.gamepadCalibrationController = controller
    }

    /** Clears everything again (used when the process shuts down). */
    fun detach() {
        PlatformServices.reset()
        applicationContext = null
    }
}
