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
 * 将 [RawTouchEvent] 流转换为 [ActionStep]。
 *
 * 分类规则（阈值取自 [RecordingConfig]）：
 *
 * | 手势 | 条件 |
 * |---|---|
 * | `longPress` | 位移 ≤ `tapSlopPx` 且时长 ≥ `longPressThresholdMs` |
 * | `tap` | 位移 ≤ `tapSlopPx` 且时长 ≥ `minTapDurationMs` |
 * | `swipe` | 位移 > `tapSlopPx`（起点 → 终点） |
 * | `multiTouch` | 同时按下超过一个触点 |
 *
 * 该分析器只依赖共享类型，因此整套手词语汇都能在无需模拟器的
 * `commonTest` 中被覆盖。
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

    /** 参与当前正在分类手势的每一个触点。 */
    private val tracks = LinkedHashMap<Int, PointerTrack>()

    /** 仍处于按下状态的触点。 */
    private val pressed = LinkedHashSet<Int>()

    private var gestureStartMs = 0L

    private var maxSimultaneousPointers = 0

    /** 自上次 [reset] 以来喂给分析器的原始帧数。 */
    var eventCount: Int = 0
        private set

    /** 至少有一个触点按下时为 `true`。 */
    val isGestureInProgress: Boolean get() = tracks.isNotEmpty()

    /** 丢弃任何进行中的手势并清空计数器。 */
    fun reset() {
        tracks.clear()
        pressed.clear()
        maxSimultaneousPointers = 0
        gestureStartMs = 0L
        eventCount = 0
    }

    /**
     * 喂入一帧。
     *
     * @return 本帧完成的动作：`DOWN` / `MOVE` 返回空列表；结束手势的
     *   `UP` / `CANCEL` 返回分类后的动作。
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
     * 完成一个从未收到 `UP` 帧的手势（服务断开、窗口失焦等）。
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
            // 抬起后指针的最终位置也要计入。
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
            // 多点触控采集被禁用：只保留最早的接触点。
            val first = gestureTracks.minBy { it.startTimeMs }
            return classifySinglePointer(first, delay)
        }

        return classifySinglePointer(gestureTracks.first(), delay)
    }

    private fun classifySinglePointer(track: PointerTrack, delay: Long): List<ActionStep> {
        val rawDuration = (track.endTimeMs - track.startTimeMs).coerceAtLeast(0L)
        if (track.travel <= config.tapSlopPx && rawDuration < config.minTapDurationMs) {
            // 时长不足 minTapDurationMs 且无位移：误触。
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
        /** 手势的录制时长永远不会为零。 */
        const val MIN_DURATION_MS = 20L
    }
}
