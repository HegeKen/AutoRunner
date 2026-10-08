package cn.helilab.autorunner.accessibility

import android.os.SystemClock
import android.view.MotionEvent
import com.autorunner.core.recording.RawTouchEvent
import com.autorunner.core.recording.TouchPhase
import com.autorunner.core.recording.TouchSample

/**
 * Converts a platform [MotionEvent] into the shared [RawTouchEvent] model.
 *
 * Shared by both capture paths: the transparent
 * `TYPE_ACCESSIBILITY_OVERLAY` view (API < 34) and
 * `AccessibilityService.onMotionEvent` (API 34+).
 *
 * @return `null` for phases AutoRunner does not model.
 */
internal fun MotionEvent.toRawTouchEvent(uptimeMs: Long = SystemClock.uptimeMillis()): RawTouchEvent? {
    val phase = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> TouchPhase.DOWN
        MotionEvent.ACTION_POINTER_DOWN -> TouchPhase.POINTER_DOWN
        MotionEvent.ACTION_MOVE -> TouchPhase.MOVE
        MotionEvent.ACTION_POINTER_UP -> TouchPhase.POINTER_UP
        MotionEvent.ACTION_UP -> TouchPhase.UP
        MotionEvent.ACTION_CANCEL -> TouchPhase.CANCEL
        else -> return null
    }

    val samples = when (phase) {
        TouchPhase.POINTER_DOWN, TouchPhase.POINTER_UP -> {
            val index = actionIndex
            listOf(TouchSample(pointerId = getPointerId(index), x = getX(index), y = getY(index)))
        }

        TouchPhase.CANCEL -> emptyList()

        else -> (0 until pointerCount).map { index ->
            TouchSample(pointerId = getPointerId(index), x = getX(index), y = getY(index))
        }
    }

    return RawTouchEvent(
        phase = phase,
        samples = samples,
        timestampMs = uptimeMs,
        sourceDeviceId = deviceId,
    )
}
