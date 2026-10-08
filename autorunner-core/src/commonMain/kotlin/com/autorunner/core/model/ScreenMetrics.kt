package com.autorunner.core.model

/**
 * Physical screen description captured while recording. It is stored in the
 * script so that coordinate normalisation stays reproducible on other devices.
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
 * Coordinate space used by the actions of a script.
 *
 * * [ABSOLUTE] — pixels of the device stored in `info.device` (what the
 *   recorder produces).
 * * [NORMALIZED] — `0.0..1.0` fractions of the screen, resolution
 *   independent. The editor can convert a script to this space so that it can
 *   be replayed on a device with a different resolution.
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
 * Converts action coordinates between the space stored in a script and
 * absolute pixels of the current device.
 */
object CoordinateResolver {

    /** Converts an action into absolute pixels for [metrics]. */
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

    /** Converts an action to normalised coordinates using [metrics] as source. */
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

    /** Applies [resolve] to the whole flow and switches `info.coordinateSpace`. */
    fun resolveScript(script: ScriptModel, metrics: ScreenMetrics): ScriptModel = script.copy(
        info = script.info.copy(coordinateSpace = CoordinateSpace.ABSOLUTE),
        flow = script.flow.map { resolve(it, script.info.coordinateSpace, metrics) },
    )

    /** Applies [normalise] to the whole flow and switches `info.coordinateSpace`. */
    fun normaliseScript(script: ScriptModel, metrics: ScreenMetrics): ScriptModel = script.copy(
        info = script.info.copy(coordinateSpace = CoordinateSpace.NORMALIZED),
        flow = script.flow.map { normalise(it, metrics) },
    )

    private fun scaleX(value: Float, metrics: ScreenMetrics): Float =
        if (metrics.widthPx <= 0) value else value * metrics.widthPx

    private fun scaleY(value: Float, metrics: ScreenMetrics): Float =
        if (metrics.heightPx <= 0) value else value * metrics.heightPx
}
