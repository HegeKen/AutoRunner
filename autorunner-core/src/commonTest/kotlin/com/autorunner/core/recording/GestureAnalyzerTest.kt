package com.autorunner.core.recording

import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.RecordingConfig
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GestureAnalyzerTest {

    private val config = RecordingConfig.Default.copy(defaultDelayMs = 120L)

    private fun analyzer() = GestureAnalyzer(config)

    private fun down(x: Float, y: Float, t: Long, id: Int = 0) =
        RawTouchEvent.single(TouchPhase.DOWN, id, x, y, t)

    private fun move(x: Float, y: Float, t: Long, id: Int = 0) =
        RawTouchEvent.single(TouchPhase.MOVE, id, x, y, t)

    private fun up(x: Float, y: Float, t: Long, id: Int = 0) =
        RawTouchEvent.single(TouchPhase.UP, id, x, y, t)

    @Test
    fun stationaryShortPressBecomesATap() {
        val analyzer = analyzer()

        assertTrue(analyzer.onTouchEvent(down(100f, 200f, 0)).isEmpty())
        assertTrue(analyzer.onTouchEvent(move(101f, 200f, 20)).isEmpty())

        val steps = analyzer.onTouchEvent(up(101f, 200f, 80))

        val tap = steps.single() as TapStep
        assertEquals(100f, tap.x)
        assertEquals(200f, tap.y)
        assertEquals(80L, tap.duration)
        assertEquals(120L, tap.delay)
    }

    @Test
    fun stationaryLongPressBecomesALongPress() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(10f, 20f, 0))
        val steps = analyzer.onTouchEvent(up(10f, 20f, 1_500))

        val longPress = steps.single() as LongPressStep
        assertEquals(10f, longPress.x)
        assertEquals(20f, longPress.y)
        assertEquals(1_500L, longPress.duration)
    }

    @Test
    fun travelTurnsTheGestureIntoASwipe() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(0f, 0f, 0))
        analyzer.onTouchEvent(move(40f, 0f, 50))
        analyzer.onTouchEvent(move(80f, 10f, 100))

        val steps = analyzer.onTouchEvent(up(120f, 20f, 200))

        val swipe = steps.single() as SwipeStep
        assertEquals(0f, swipe.fromX)
        assertEquals(0f, swipe.fromY)
        assertEquals(120f, swipe.toX)
        assertEquals(20f, swipe.toY)
        assertEquals(200L, swipe.duration)
    }

    @Test
    fun twoPointersProduceAMultiTouchAction() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(100f, 100f, 0, id = 0))
        analyzer.onTouchEvent(
            RawTouchEvent(
                phase = TouchPhase.POINTER_DOWN,
                samples = listOf(TouchSample(1, 300f, 100f)),
                timestampMs = 10,
            ),
        )

        val steps = analyzer.onTouchEvent(
            RawTouchEvent(
                phase = TouchPhase.UP,
                samples = listOf(TouchSample(0, 100f, 100f), TouchSample(1, 300f, 100f)),
                timestampMs = 400,
            ),
        )

        val multiTouch = steps.single() as MultiTouchStep
        assertEquals(2, multiTouch.points.size)
        assertEquals(100f, multiTouch.points[0].x)
        assertEquals(10L, multiTouch.points[1].startOffset)
        assertTrue(!analyzer.isGestureInProgress)
    }

    @Test
    fun microTouchesAreDropped() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(5f, 5f, 0))

        assertTrue(analyzer.onTouchEvent(up(5f, 5f, 5)).isEmpty())
    }

    @Test
    fun cancelDiscardsTheGestureInFlight() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(5f, 5f, 0))
        assertTrue(analyzer.onTouchEvent(RawTouchEvent(TouchPhase.CANCEL, emptyList(), 10)).isEmpty())
        assertTrue(!analyzer.isGestureInProgress)
        assertTrue(analyzer.onTouchEvent(up(5f, 5f, 20)).isEmpty())
    }

    @Test
    fun flushCompletesAGestureThatNeverGotAnUpFrame() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(1f, 2f, 0))
        val steps = analyzer.flush(300)

        val tap = steps.single() as TapStep
        assertEquals(1f, tap.x)
        assertEquals(2f, tap.y)
        assertEquals(300L, tap.duration)
    }

    @Test
    fun flushClassifiesALongHoldWithoutAnUpFrame() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(7f, 8f, 0))

        val longPress = analyzer.flush(900).single() as LongPressStep
        assertEquals(900L, longPress.duration)
    }

    @Test
    fun multiTouchCaptureCanBeDisabled() {
        val analyzer = GestureAnalyzer(config.copy(captureMultiTouch = false))

        analyzer.onTouchEvent(down(10f, 10f, 0, id = 0))
        analyzer.onTouchEvent(RawTouchEvent(TouchPhase.POINTER_DOWN, listOf(TouchSample(1, 200f, 200f)), 5))
        val steps = analyzer.onTouchEvent(
            RawTouchEvent(
                TouchPhase.UP,
                listOf(TouchSample(0, 10f, 10f), TouchSample(1, 200f, 200f)),
                200,
            ),
        )

        assertTrue(steps.single() is TapStep)
    }

    @Test
    fun resetClearsCountersAndInFlightState() {
        val analyzer = analyzer()

        analyzer.onTouchEvent(down(1f, 1f, 0))
        assertEquals(1, analyzer.eventCount)

        analyzer.reset()

        assertEquals(0, analyzer.eventCount)
        assertTrue(!analyzer.isGestureInProgress)
    }
}
