package com.autorunner.core.recording

/** Pointer phase reported by the capture layer. */
enum class TouchPhase {
    DOWN,
    MOVE,
    UP,
    CANCEL,
    POINTER_DOWN,
    POINTER_UP,
}

/** A single contact inside a [RawTouchEvent]. */
data class TouchSample(
    val pointerId: Int,
    val x: Float,
    val y: Float,
)

/**
 * Platform independent description of one touch frame.
 *
 * The Android capture layer converts `MotionEvent`s into this type, which keeps
 * [GestureAnalyzer] unit-testable without an emulator.
 */
data class RawTouchEvent(
    val phase: TouchPhase,
    val samples: List<TouchSample>,
    /** Milliseconds on an arbitrary but monotonic clock. */
    val timestampMs: Long,
    /**
     * `InputDevice` the frame came from, `-1` when unknown.
     *
     * The capture layer uses it to tell a real finger apart from a gesture the
     * app itself injected through `dispatchGesture`; without that check the
     * "mirror the recorded gesture back to the app" step would be captured
     * again and the recorder would feed itself forever.
     */
    val sourceDeviceId: Int = -1,
) {
    companion object {
        fun single(phase: TouchPhase, pointerId: Int, x: Float, y: Float, timestampMs: Long): RawTouchEvent =
            RawTouchEvent(phase, listOf(TouchSample(pointerId, x, y)), timestampMs)
    }
}
