package cn.helilab.autorunner.accessibility

import android.os.SystemClock
import android.view.MotionEvent
import com.autorunner.core.recording.RawTouchEvent
import com.autorunner.core.recording.TouchPhase
import com.autorunner.core.recording.TouchSample

/**
 * 将平台的 [MotionEvent] 转换为共享的 [RawTouchEvent] 模型。
 *
 * 两条采集路径共用本函数：透明的
 * `TYPE_ACCESSIBILITY_OVERLAY` 视图（API < 34）以及
 * `AccessibilityService.onMotionEvent`（API 34+）。
 *
 * @return 对于 AutoRunner 未建模的阶段返回 `null`。
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
