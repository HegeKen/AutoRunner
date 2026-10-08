package com.autorunner.core.recording

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.RecordingConfig
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.model.TouchPoint
import kotlin.math.sqrt

/**
 * Turns a stream of [RawTouchEvent]s into [ActionStep]s.
 *
 * Classification rules (thresholds come from [RecordingConfig]):
 *
 * | gesture | condition |
 * |---|---|
 * | `longPress` | travel ≤ `tapSlopPx` and duration ≥ `longPressThresholdMs` |
 * | `tap` | travel ≤ `tapSlopPx` and duration ≥ `minTapDurationMs` |
 * | `swipe` | travel > `tapSlopPx` (start point → last point) |
 * | `multiTouch` | more than one pointer was down simultaneously |
 *
 * The analyzer only depends on shared types, so the whole gesture vocabulary is
 * covered by `commonTest` without an emulator.
 */
class GestureAnalyzer(
    private val config: RecordingConfig = RecordingConfig.Default,
) {

    private class PointerTrack(val pointerId: Int) {
        var startX: Float = 0f
        var startY: Float = 0f
        var lastX: Float = 0f
        var lastY: Float = 0f
        var startTimeMs: Long = 0L
        var endTimeMs: Long = 0L
        var travel: Float = 0f
    }

    /** Every pointer that took part in the gesture currently being classified. */
    private val tracks = LinkedHashMap<Int, PointerTrack>()

    /** Pointers that are still pressed. */
    private val pressed = LinkedHashSet<Int>()

    private var gestureStartMs = 0L

    private var maxSimultaneousPointers = 0

    /** Number of raw frames fed into the analyzer since the last [reset]. */
    var eventCount: Int = 0
        private set

    /** `true` while at least one pointer is down. */
    val isGestureInProgress: Boolean get() = tracks.isNotEmpty()

    /** Drops any in-flight gesture and clears the counters. */
    fun reset() {
        tracks.clear()
        pressed.clear()
        maxSimultaneousPointers = 0
        gestureStartMs = 0L
        eventCount = 0
    }

    /**
     * Feeds one frame.
     *
     * @return actions completed by this frame: empty for `DOWN` / `MOVE`, the
     *   classified action for the `UP` / `CANCEL` that ends a gesture.
     */
    fun onTouchEvent(event: RawTouchEvent): List<ActionStep> {
        eventCount++
        return when (event.phase) {
            TouchPhase.DOWN -> {
                if (tracks.isEmpty()) gestureStartMs = event.timestampMs
                event.samples.forEach { startPointer(it, event.timestampMs) }
                emptyList()
            }

            TouchPhase.POINTER_DOWN -> {
                event.samples.forEach { startPointer(it, event.timestampMs) }
                emptyList()
            }

            TouchPhase.MOVE -> {
                event.samples.forEach { movePointer(it) }
                emptyList()
            }

            TouchPhase.POINTER_UP -> {
                event.samples.forEach {
                    movePointer(it)
                    releasePointer(it, event.timestampMs)
                }
                emptyList()
            }

            TouchPhase.UP -> {
                event.samples.forEach { movePointer(it) }
                event.samples.forEach { releasePointer(it, event.timestampMs) }
                finalizeGesture()
            }

            TouchPhase.CANCEL -> {
                discardGesture()
                emptyList()
            }
        }
    }

    /**
     * Completes a gesture that never received an `UP` frame (service
     * disconnected, window lost focus, …).
     */
    fun flush(timestampMs: Long): List<ActionStep> {
        if (tracks.isEmpty()) return emptyList()
        tracks.values.forEach { if (it.endTimeMs <= it.startTimeMs) it.endTimeMs = timestampMs }
        return finalizeGesture()
    }

    private fun startPointer(sample: TouchSample, timestampMs: Long) {
        if (tracks.isEmpty()) {
            pressed.clear()
            maxSimultaneousPointers = 0
            gestureStartMs = timestampMs
        }
        tracks.getOrPut(sample.pointerId) {
            PointerTrack(sample.pointerId).apply {
                startX = sample.x
                startY = sample.y
                lastX = sample.x
                lastY = sample.y
                startTimeMs = timestampMs
                endTimeMs = timestampMs
            }
        }
        pressed += sample.pointerId
        maxSimultaneousPointers = maxOf(maxSimultaneousPointers, pressed.size)
    }

    private fun movePointer(sample: TouchSample) {
        val track = tracks[sample.pointerId] ?: return
        val distance = distance(track.lastX, track.lastY, sample.x, sample.y)
        if (distance >= config.minSampleDistancePx) {
            track.travel += distance
            track.lastX = sample.x
            track.lastY = sample.y
        } else if (sample.pointerId !in pressed) {
            // Final position after the pointer lifted still counts.
            track.travel += distance
            track.lastX = sample.x
            track.lastY = sample.y
        }
    }

    private fun releasePointer(sample: TouchSample, timestampMs: Long) {
        val track = tracks[sample.pointerId] ?: return
        val distance = distance(track.lastX, track.lastY, sample.x, sample.y)
        track.travel += distance
        track.lastX = sample.x
        track.lastY = sample.y
        track.endTimeMs = timestampMs
        pressed -= sample.pointerId
    }

    private fun finalizeGesture(): List<ActionStep> {
        val gestureTracks = tracks.values.toList()
        val pointerCount = maxSimultaneousPointers
        val delay = config.defaultDelayMs
        tracks.clear()
        pressed.clear()
        maxSimultaneousPointers = 0

        if (gestureTracks.isEmpty()) return emptyList()
        if (gestureTracks.size > 1 && config.captureMultiTouch) {
            val origin = gestureTracks.minOf { it.startTimeMs }
            val end = gestureTracks.maxOf { it.endTimeMs }
            return listOf(
                MultiTouchStep(
                    points = gestureTracks.map {
                        TouchPoint(
                            x = it.startX,
                            y = it.startY,
                            startOffset = (it.startTimeMs - origin).coerceAtLeast(0L),
                        )
                    },
                    duration = (end - origin).coerceAtLeast(MIN_DURATION_MS),
                    delay = delay,
                ),
            )
        }

        if (pointerCount > 1 || gestureTracks.size > 1) {
            // Multi-touch capture disabled: keep the first contact only.
            val first = gestureTracks.minBy { it.startTimeMs }
            return classifySinglePointer(first, delay)
        }

        return classifySinglePointer(gestureTracks.first(), delay)
    }

    private fun classifySinglePointer(track: PointerTrack, delay: Long): List<ActionStep> {
        val rawDuration = (track.endTimeMs - track.startTimeMs).coerceAtLeast(0L)
        if (track.travel <= config.tapSlopPx && rawDuration < config.minTapDurationMs) {
            // Shorter than minTapDurationMs with no travel: accidental touch.
            return emptyList()
        }
        val duration = rawDuration.coerceAtLeast(MIN_DURATION_MS)
        return when {
            track.travel > config.tapSlopPx -> listOf(
                SwipeStep(
                    fromX = track.startX,
                    fromY = track.startY,
                    toX = track.lastX,
                    toY = track.lastY,
                    duration = duration,
                    delay = delay,
                ),
            )

            rawDuration >= config.longPressThresholdMs -> listOf(
                LongPressStep(x = track.startX, y = track.startY, duration = duration, delay = delay),
            )

            else -> listOf(
                TapStep(x = track.startX, y = track.startY, duration = duration, delay = delay),
            )
        }
    }

    private fun discardGesture() {
        tracks.clear()
        pressed.clear()
        maxSimultaneousPointers = 0
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        /** Gestures are never recorded with a zero duration. */
        const val MIN_DURATION_MS = 20L
    }
}
