package com.autorunner.core.model

/**
 * 录制时采集的物理屏幕描述。它被保存在脚本里，
 * 使坐标归一化在其他设备上仍可复现。
 */
data class ScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float = 1f,
) {
    val isValid: Boolean get() = widthPx > 0 && heightPx > 0

    val aspectRatio: Float get() = if (heightPx == 0) 0f else widthPx.toFloat() / heightPx

    fun normaliseX(x: Float): Float = if (widthPx <= 0) 0f else (x / widthPx).coerceIn(0f, 1f)

    fun normaliseY(y: Float): Float = if (heightPx <= 0) 0f else (y / heightPx).coerceIn(0f, 1f)

    companion object {
        val Unknown = ScreenMetrics(0, 0, 1f)
    }
}

/**
 * 脚本动作使用的坐标空间。
 *
 * * [ABSOLUTE] — `info.device` 中保存的设备像素（录制器产出的形式）。
 * * [NORMALIZED] — 屏幕的 `0.0..1.0` 比例值，与分辨率无关。
 *   编辑器可以把脚本转换到该空间，以便在不同分辨率的设备上回放。
 */
enum class CoordinateSpace {
    @kotlinx.serialization.SerialName("absolute")
    ABSOLUTE,

    @kotlinx.serialization.SerialName("normalized")
    NORMALIZED,
    ;

    companion object {
        val Default: CoordinateSpace = ABSOLUTE
    }
}

/**
 * 在脚本所存坐标空间与当前设备绝对像素之间转换动作坐标。
 */
object CoordinateResolver {

    /** 把动作转换为针对 [metrics] 的绝对像素。 */
    fun resolve(step: ActionStep, space: CoordinateSpace, metrics: ScreenMetrics): ActionStep =
        when (space) {
            CoordinateSpace.ABSOLUTE -> step
            CoordinateSpace.NORMALIZED -> when (step) {
                is TapStep -> step.copy(
                    x = scaleX(step.x, metrics),
                    y = scaleY(step.y, metrics),
                )

                is LongPressStep -> step.copy(
                    x = scaleX(step.x, metrics),
                    y = scaleY(step.y, metrics),
                )

                is SwipeStep -> step.copy(
                    fromX = scaleX(step.fromX, metrics),
                    fromY = scaleY(step.fromY, metrics),
                    toX = scaleX(step.toX, metrics),
                    toY = scaleY(step.toY, metrics),
                )

                is MultiTouchStep -> step.copy(
                    points = step.points.map { it.copy(x = scaleX(it.x, metrics), y = scaleY(it.y, metrics)) },
                )

                is GamepadStep, is DelayStep, is KeyStep -> step
            }
        }

    /** 以 [metrics] 为源，把动作转换为归一化坐标。 */
    fun normalise(step: ActionStep, metrics: ScreenMetrics): ActionStep = when (step) {
        is TapStep -> step.copy(x = metrics.normaliseX(step.x), y = metrics.normaliseY(step.y))
        is LongPressStep -> step.copy(x = metrics.normaliseX(step.x), y = metrics.normaliseY(step.y))
        is SwipeStep -> step.copy(
            fromX = metrics.normaliseX(step.fromX),
            fromY = metrics.normaliseY(step.fromY),
            toX = metrics.normaliseX(step.toX),
            toY = metrics.normaliseY(step.toY),
        )

        is MultiTouchStep -> step.copy(
            points = step.points.map { it.copy(x = metrics.normaliseX(it.x), y = metrics.normaliseY(it.y)) },
        )

        is GamepadStep, is DelayStep, is KeyStep -> step
    }

    /** 对整个流程应用 [resolve] 并切换 `info.coordinateSpace`。 */
    fun resolveScript(script: ScriptModel, metrics: ScreenMetrics): ScriptModel = script.copy(
        info = script.info.copy(coordinateSpace = CoordinateSpace.ABSOLUTE),
        flow = script.flow.map { resolve(it, script.info.coordinateSpace, metrics) },
    )

    /** 对整个流程应用 [normalise] 并切换 `info.coordinateSpace`。 */
    fun normaliseScript(script: ScriptModel, metrics: ScreenMetrics): ScriptModel = script.copy(
        info = script.info.copy(coordinateSpace = CoordinateSpace.NORMALIZED),
        flow = script.flow.map { normalise(it, metrics) },
    )

    private fun scaleX(value: Float, metrics: ScreenMetrics): Float =
        if (metrics.widthPx <= 0) value else value * metrics.widthPx

    private fun scaleY(value: Float, metrics: ScreenMetrics): Float =
        if (metrics.heightPx <= 0) value else value * metrics.heightPx
}
