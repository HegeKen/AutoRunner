package com.autorunner.core.recording

/** 采集层上报的指针阶段。 */
enum class TouchPhase {
    DOWN,
    MOVE,
    UP,
    CANCEL,
    POINTER_DOWN,
    POINTER_UP,
}

/** [RawTouchEvent] 中的单个接触点。 */
data class TouchSample(
    val pointerId: Int,
    val x: Float,
    val y: Float,
)

/**
 * 一帧触摸事件的平台无关描述。
 *
 * Android 采集层把 `MotionEvent` 转换成该类型，使得 [GestureAnalyzer]
 * 无需模拟器也能单元测试。
 */
data class RawTouchEvent(
    val phase: TouchPhase,
    val samples: List<TouchSample>,
    /** 任意但单调的时钟上的毫秒数。 */
    val timestampMs: Long,
    /**
     * 该帧来自哪个 `InputDevice`，未知时为 `-1`。
     *
     * 采集层用它区分真实手指与应用自身通过 `dispatchGesture` 注入的
     * 手势；没有这个检查，“把录制的手势回放给应用”这一步会被再次
     * 采集，录制器就会无限地喂给自己。
     */
    val sourceDeviceId: Int = -1,
) {
    companion object {
        fun single(phase: TouchPhase, pointerId: Int, x: Float, y: Float, timestampMs: Long): RawTouchEvent =
            RawTouchEvent(phase, listOf(TouchSample(pointerId, x, y)), timestampMs)
    }
}
