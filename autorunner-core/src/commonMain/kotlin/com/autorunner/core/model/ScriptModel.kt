package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root object of an `.arscript` file.
 *
 * ```json
 * {
 *   "version": "1.0",
 *   "info": { "name": "示例脚本", "device": { "width": 1080, "height": 2400, "density": 2.75 } },
 *   "execution": { "mode": "repeat", "repeatCount": 10, "intervalMs": 2000 },
 *   "flow": [ { "type": "tap", "x": 540, "y": 1200, "duration": 50, "delay": 500 } ]
 * }
 * ```
 */
@Serializable
data class ScriptModel(
    @SerialName("version") val version: String = ArScriptConventions.FORMAT_VERSION,
    @SerialName("info") val info: ScriptInfo = ScriptInfo(),
    @SerialName("execution") val execution: ExecutionConfig = ExecutionConfig(),
    @SerialName("flow") val flow: List<ActionStep> = emptyList(),
) {
    val stepCount: Int get() = flow.size

    val isEmpty: Boolean get() = flow.isEmpty()

    /** Whether the flow drives a gamepad (contains at least one [GamepadStep]). */
    val usesGamepad: Boolean get() = flow.any { it is GamepadStep }

    /** Sum of every action duration plus its trailing delay. */
    val estimatedDurationMs: Long
        get() = flow.sumOf { step ->
            val own = when (step) {
                is TapStep -> step.duration
                is LongPressStep -> step.duration
                is SwipeStep -> step.duration
                is MultiTouchStep -> step.duration
                is DelayStep -> step.duration
                is GamepadStep -> 0L
                is KeyStep -> 0L
            }
            own + step.delay
        }

    /** Estimated wall clock duration of the whole run (best effort). */
    fun estimatedTotalDurationMs(): Long {
        val loops = execution.totalLoops
        if (loops == ExecutionConfig.INFINITE_LOOPS) return Long.MAX_VALUE
        val perLoop = estimatedDurationMs
        val interval = execution.intervalMs.coerceAtLeast(0L)
        return (perLoop * loops) + (interval * (loops - 1).coerceAtLeast(0))
    }

    fun withFlow(newFlow: List<ActionStep>): ScriptModel = copy(flow = newFlow)

    fun displayName(): String = info.name.ifBlank { "未命名脚本" }
}

/**
 * `info` block: metadata captured at recording time plus the coordinate space
 * the `flow` uses.
 */
@Serializable
data class ScriptInfo(
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("device") val device: DeviceInfo = DeviceInfo(),
    /** ISO-8601 UTC timestamp, e.g. `2026-01-15T10:30:00Z`. */
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("coordinateSpace") val coordinateSpace: CoordinateSpace = CoordinateSpace.ABSOLUTE,
    @SerialName("tags") val tags: List<String> = emptyList(),
    /**
     * Gamepad preset this script's `gamepad` actions were authored against, so the
     * executor uses the matching calibration at run time. Defaults to Xbox for
     * scripts recorded before the field existed.
     */
    @SerialName("gamepadMode") val gamepadMode: GamepadMode = GamepadMode.Default,
)

/** Screen the script was recorded on. */
@Serializable
data class DeviceInfo(
    @SerialName("width") val width: Int = 0,
    @SerialName("height") val height: Int = 0,
    @SerialName("density") val density: Float = 1f,
) {
    val metrics: ScreenMetrics get() = ScreenMetrics(width, height, density)

    companion object {
        fun of(metrics: ScreenMetrics): DeviceInfo =
            DeviceInfo(metrics.widthPx, metrics.heightPx, metrics.density)
    }
}

/** Format constants for the `.arscript` container. */
object ArScriptConventions {
    /** Extension without the leading dot. */
    const val FILE_EXTENSION = "arscript"

    /** Extension with the leading dot, as used by file pickers. */
    const val DOT_EXTENSION = ".$FILE_EXTENSION"

    const val MIME_TYPE = "application/json"

    /** Version written by this build. */
    const val FORMAT_VERSION = "1.0"

    /** Versions this build is able to read. */
    val SUPPORTED_VERSIONS: Set<String> = setOf("1.0")

    /** Recommended file name for a script. */
    fun fileNameFor(scriptName: String): String {
        val safe = scriptName
            .trim()
            .ifBlank { "autorunner_script" }
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return "$safe$DOT_EXTENSION"
    }
}
