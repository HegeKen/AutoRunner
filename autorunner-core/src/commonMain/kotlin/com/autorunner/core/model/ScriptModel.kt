package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `.arscript` 文件的根对象。
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

    /** 流程是否驱动游戏手柄（至少含一个 [GamepadStep]）。 */
    val usesGamepad: Boolean get() = flow.any { it is GamepadStep }

    /** 所有动作时长加上各自结尾延时的总和。 */
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

    /** 整个运行的预估挂钟时长（尽力估算）。 */
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
 * `info` 块：录制时采集的元数据，加上 `flow` 使用的坐标空间。
 */
@Serializable
data class ScriptInfo(
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("device") val device: DeviceInfo = DeviceInfo(),
    /** ISO-8601 UTC 时间戳，例如 `2026-01-15T10:30:00Z`。 */
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("coordinateSpace") val coordinateSpace: CoordinateSpace = CoordinateSpace.ABSOLUTE,
    @SerialName("tags") val tags: List<String> = emptyList(),
    /**
     * 本脚本的 `gamepad` 动作编写时所针对的手柄预设，执行器运行时据此使用
     * 对应的校准。该字段出现之前录制的脚本默认为 Xbox。
     */
    @SerialName("gamepadMode") val gamepadMode: GamepadMode = GamepadMode.Default,
)

/** 录制时所在的屏幕。 */
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

/** `.arscript` 容器的格式常量。 */
object ArScriptConventions {
    /** 不带前导点的扩展名。 */
    const val FILE_EXTENSION = "arscript"

    /** 带前导点的扩展名，文件选择器使用的形式。 */
    const val DOT_EXTENSION = ".$FILE_EXTENSION"

    const val MIME_TYPE = "application/json"

    /** 本构建写入的版本号。 */
    const val FORMAT_VERSION = "1.0"

    /** 本构建能够读取的版本集合。 */
    val SUPPORTED_VERSIONS: Set<String> = setOf("1.0")

    /** 脚本的推荐文件名。 */
    fun fileNameFor(scriptName: String): String {
        val safe = scriptName
            .trim()
            .ifBlank { "autorunner_script" }
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return "$safe$DOT_EXTENSION"
    }
}
