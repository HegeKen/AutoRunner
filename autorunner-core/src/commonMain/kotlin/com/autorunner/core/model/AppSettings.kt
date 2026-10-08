package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 模拟输入的实现方式。
 *
 * [ACCESSIBILITY] 用无障碍 `dispatchGesture`（默认，兼容性最好）；
 * [ROOT] 把动作写进 Magisk/KSU 模块提供的命令文件，由 root 调 `input` 注入。
 */
@Serializable
enum class InputMode(val displayName: String) {
    ACCESSIBILITY("无障碍服务"),
    ROOT("Root 注入"),
    ;

    companion object {
        val Default: InputMode = ACCESSIBILITY
    }
}

/**
 * Appearance mode handed to MIUIX' `ThemeController` (`ColorSchemeMode`).
 *
 * AutoRunner ships a single fixed palette (brand primary `#2655FF`) in a light and
 * a dark variant; the previous Monet dynamic-colour modes and the user picked seed
 * colour were removed on purpose.
 */
enum class ThemeMode(val displayName: String) {
    @SerialName("system") SYSTEM("跟随系统"),
    @SerialName("light") LIGHT("浅色"),
    @SerialName("dark") DARK("深色"),
    ;

    companion object {
        val Default: ThemeMode = SYSTEM
    }
}

/** Button naming preset shown in the gamepad mapping list (cosmetic only). */
@Serializable
enum class GamepadMode(val displayName: String, val hidName: String) {
    @SerialName("xbox") XBOX("Xbox 手柄", "AutoRunner Gamepad"),
    @SerialName("dualsense") DUALSENSE("PS 手柄", "AutoRunner DualSense"),
    @SerialName("switch_pro") SWITCH_PRO("Switch 手柄", "AutoRunner Pro Controller"),
    ;

    companion object {
        val Default: GamepadMode = XBOX
    }
}

/** Fine tuning of the gesture classifier used while recording. */
@Serializable
data class RecordingConfig(
    /** Movement below this many pixels is treated as a stationary press. */
    @SerialName("tapSlopPx") val tapSlopPx: Float = 24f,
    /** Presses longer than this become `longPress` actions. */
    @SerialName("longPressThresholdMs") val longPressThresholdMs: Long = 500L,
    /** Presses shorter than this are dropped as accidental touches. */
    @SerialName("minTapDurationMs") val minTapDurationMs: Long = 20L,
    /** Samples closer than this distance are merged while building a swipe path. */
    @SerialName("minSampleDistancePx") val minSampleDistancePx: Float = 8f,
    /** Records a `multiTouch` action instead of dropping multi-finger gestures. */
    @SerialName("captureMultiTouch") val captureMultiTouch: Boolean = true,
    /** Stores the flow in normalised (`0.0..1.0`) coordinates. */
    @SerialName("normaliseCoordinates") val normaliseCoordinates: Boolean = false,
    /** Upper bound of recorded actions, protects memory on very long sessions. */
    @SerialName("maxSteps") val maxSteps: Int = 5_000,
    /** Default delay written after each recorded action. */
    @SerialName("defaultDelayMs") val defaultDelayMs: Long = 300L,
) {
    companion object {
        val Default = RecordingConfig()
    }
}

/**
 * Everything the user can configure; persisted through
 * [com.autorunner.core.storage.KeyValueStore].
 */
@Serializable
data class AppSettings(
    @SerialName("themeMode") val themeMode: ThemeMode = ThemeMode.Default,
    /** Execution defaults applied to newly recorded scripts. */
    @SerialName("defaultExecution") val defaultExecution: ExecutionConfig = ExecutionConfig(),
    @SerialName("failureStrategy") val failureStrategy: FailureStrategy = FailureStrategy.ABORT_SCRIPT,
    @SerialName("recording") val recording: RecordingConfig = RecordingConfig.Default,
    /** Shows the floating control ball when the app starts. */
    @SerialName("showFloatingBallOnStart") val showFloatingBallOnStart: Boolean = true,
    /** Keeps the screen awake while a script runs. */
    @SerialName("keepScreenOnWhileRunning") val keepScreenOnWhileRunning: Boolean = true,
    /** Posts execution progress to the `autorunner_execution` notification. */
    @SerialName("executionNotification") val executionNotification: Boolean = true,
    /**
     * Shows short toast reminders while a script runs (start / pause / resume,
     * throttled loop progress and the final report).
     */
    @SerialName("executionReminder") val executionReminder: Boolean = true,
    /** Vibrates on action boundaries (debug aid while recording). */
    @SerialName("hapticFeedback") val hapticFeedback: Boolean = true,
    /** Collapses the floating panel to a ball right after a run starts. */
    @SerialName("autoCollapsePanel") val autoCollapsePanel: Boolean = true,
    /** Gamepad simulation master switch (optional feature). */
    @SerialName("gamepadEnabled") val gamepadEnabled: Boolean = false,
    /** Naming preset used by the button mapping list (and the default for new scripts). */
    @SerialName("gamepadMode") val gamepadMode: GamepadMode = GamepadMode.Default,
    /** Where each gamepad button lives on screen, plus the virtual stick centre, per mode. */
    @SerialName("gamepadMappingsByMode") val gamepadMappingsByMode: Map<GamepadMode, GamepadMappings> = emptyMap(),
    /**
     * Legacy single-pad calibration written before calibration became per-mode.
     *
     * Kept as a nullable field only so old settings decode without losing the
     * data; [sanitized] folds it into [gamepadMappingsByMode] for [gamepadMode]
     * and clears it (it is never written back because `explicitNulls = false`).
     */
    @SerialName("gamepadMappings") val legacyGamepadMappings: GamepadMappings? = null,
    /** Skip the manufacturer specific permission wizard. */
    @SerialName("skipOemGuidance") val skipOemGuidance: Boolean = false,

    /** 模拟输入方式；Root 需安装 AutoRunner Root Bridge 模块。 */
    @SerialName("inputMode") val inputMode: InputMode = InputMode.Default,
    /** Number of days scripts are kept; `0` disables pruning. */
    @SerialName("scriptRetentionDays") val scriptRetentionDays: Int = 0,
) {
    fun sanitized(): AppSettings {
        val folded = legacyGamepadMappings
            ?.takeIf { !gamepadMappingsByMode.containsKey(gamepadMode) }
            ?.let { gamepadMappingsByMode + (gamepadMode to it) }
            ?: gamepadMappingsByMode
        return copy(
            defaultExecution = defaultExecution.sanitized(),
            recording = recording.copy(
                tapSlopPx = recording.tapSlopPx.coerceIn(1f, 200f),
                longPressThresholdMs = recording.longPressThresholdMs.coerceIn(100L, 10_000L),
                minTapDurationMs = recording.minTapDurationMs.coerceIn(0L, 1_000L),
                minSampleDistancePx = recording.minSampleDistancePx.coerceIn(0f, 200f),
                maxSteps = recording.maxSteps.coerceIn(100, 100_000),
                defaultDelayMs = recording.defaultDelayMs.coerceIn(0L, 60_000L),
            ),
            scriptRetentionDays = scriptRetentionDays.coerceAtLeast(0),
            gamepadMappingsByMode = folded.mapValues { (_, mappings) ->
                mappings.copy(
                    stickRadius = mappings.stickRadius.coerceIn(20f, 2000f),
                    buttons = mappings.buttons.map { it.copy(durationMs = it.durationMs.coerceIn(20L, 10_000L)) },
                )
            },
            legacyGamepadMappings = null,
        )
    }

    /** Button layout / stick calibration saved for [mode] (empty when never calibrated). */
    fun gamepadMappingsFor(mode: GamepadMode): GamepadMappings =
        gamepadMappingsByMode[mode] ?: GamepadMappings.Empty

    /** Calibration of the currently selected [gamepadMode]. */
    val currentGamepadMappings: GamepadMappings get() = gamepadMappingsFor(gamepadMode)

    companion object {
        val Default = AppSettings()
    }
}
