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
 * 传给 MIUIX `ThemeController`（`ColorSchemeMode`）的外观模式。
 *
 * AutoRunner 只提供一套固定配色（品牌主色 `#2655FF`），分浅色与深色两种
 * 变体；此前的 Monet 动态取色模式和用户自选种子色已被有意移除。
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

/** 手柄映射列表中显示的按键命名预设（仅影响外观）。 */
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

/** 录制时使用的手势分类器微调参数。 */
@Serializable
data class RecordingConfig(
    /** 位移小于该像素数视为原地按压。 */
    @SerialName("tapSlopPx") val tapSlopPx: Float = 24f,
    /** 按压超过该时长即成为 `longPress` 动作。 */
    @SerialName("longPressThresholdMs") val longPressThresholdMs: Long = 500L,
    /** 按压短于该时长视为误触而丢弃。 */
    @SerialName("minTapDurationMs") val minTapDurationMs: Long = 20L,
    /** 采样点间距小于该距离时在构建滑动路径中被合并。 */
    @SerialName("minSampleDistancePx") val minSampleDistancePx: Float = 8f,
    /** 记录为 `multiTouch` 动作，而不是丢弃多指手势。 */
    @SerialName("captureMultiTouch") val captureMultiTouch: Boolean = true,
    /** 以归一化（`0.0..1.0`）坐标保存流程。 */
    @SerialName("normaliseCoordinates") val normaliseCoordinates: Boolean = false,
    /** 已录制动作数上限，超长录制时保护内存。 */
    @SerialName("maxSteps") val maxSteps: Int = 5_000,
    /** 每个已录制动作之后写入的默认延时。 */
    @SerialName("defaultDelayMs") val defaultDelayMs: Long = 300L,
) {
    companion object {
        val Default = RecordingConfig()
    }
}

/**
 * 用户可配置的全部内容；经
 * [com.autorunner.core.storage.KeyValueStore] 持久化。
 */
@Serializable
data class AppSettings(
    @SerialName("themeMode") val themeMode: ThemeMode = ThemeMode.Default,
    /** 应用到新录制脚本的执行默认值。 */
    @SerialName("defaultExecution") val defaultExecution: ExecutionConfig = ExecutionConfig(),
    @SerialName("failureStrategy") val failureStrategy: FailureStrategy = FailureStrategy.ABORT_SCRIPT,
    @SerialName("recording") val recording: RecordingConfig = RecordingConfig.Default,
    /** 应用启动时显示悬浮控制球。 */
    @SerialName("showFloatingBallOnStart") val showFloatingBallOnStart: Boolean = true,
    /** 脚本运行期间保持屏幕常亮。 */
    @SerialName("keepScreenOnWhileRunning") val keepScreenOnWhileRunning: Boolean = true,
    /** 把执行进度发布到 `autorunner_execution` 通知。 */
    @SerialName("executionNotification") val executionNotification: Boolean = true,
    /**
     * 脚本运行期间显示简短的 toast 提示（开始 / 暂停 / 恢复、
     * 节流后的循环进度以及最终报告）。
     */
    @SerialName("executionReminder") val executionReminder: Boolean = true,
    /** 在动作边界震动（录制期间的调试辅助）。 */
    @SerialName("hapticFeedback") val hapticFeedback: Boolean = true,
    /** 运行开始后立即把悬浮面板收起为小球。 */
    @SerialName("autoCollapsePanel") val autoCollapsePanel: Boolean = true,
    /** 手柄模拟总开关（可选功能）。 */
    @SerialName("gamepadEnabled") val gamepadEnabled: Boolean = false,
    /** 按键映射列表使用的命名预设（同时是新脚本的默认值）。 */
    @SerialName("gamepadMode") val gamepadMode: GamepadMode = GamepadMode.Default,
    /** 每个手柄按键在屏幕上的位置以及虚拟摇杆中心，按模式区分。 */
    @SerialName("gamepadMappingsByMode") val gamepadMappingsByMode: Map<GamepadMode, GamepadMappings> = emptyMap(),
    /**
     * 校准按模式拆分之前写入的旧版单手柄校准。
     *
     * 之所以保留为可空字段，只是为了让旧设置能无损解码；[sanitized] 会把它
     * 折叠进 [gamepadMappingsByMode] 中 [gamepadMode] 对应的条目并清空它
     * （由于 `explicitNulls = false`，它永远不会被写回）。
     */
    @SerialName("gamepadMappings") val legacyGamepadMappings: GamepadMappings? = null,
    /** 跳过厂商特定的权限引导流程。 */
    @SerialName("skipOemGuidance") val skipOemGuidance: Boolean = false,

    /** 模拟输入方式；Root 需安装 AutoRunner Root Bridge 模块。 */
    @SerialName("inputMode") val inputMode: InputMode = InputMode.Default,
    /** 脚本保留的天数；`0` 表示不清理。 */
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

    /** 为 [mode] 保存的按键布局 / 摇杆校准（从未校准过时为空）。 */
    fun gamepadMappingsFor(mode: GamepadMode): GamepadMappings =
        gamepadMappingsByMode[mode] ?: GamepadMappings.Empty

    /** 当前所选 [gamepadMode] 的校准。 */
    val currentGamepadMappings: GamepadMappings get() = gamepadMappingsFor(gamepadMode)

    companion object {
        val Default = AppSettings()
    }
}
