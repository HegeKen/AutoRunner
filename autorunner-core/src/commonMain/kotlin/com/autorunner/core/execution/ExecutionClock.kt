package com.autorunner.core.execution

import kotlin.time.TimeSource
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.yield

/**
 * Time source used by [AutoRunnerScriptExecutor].
 *
 * Abstracting it keeps the engine deterministic under test: the virtual
 * implementation advances its own clock whenever [delay] is called, so a
 * "10 loops × 2 s interval" run completes instantly instead of taking 20
 * seconds of wall clock time.
 */
interface ExecutionClock {

    /** Milliseconds since an arbitrary origin; only differences are meaningful. */
    fun nowMs(): Long

    /** Suspends for [millis] without busy waiting. */
    suspend fun delay(millis: Long)
}

/** Production clock: monotonic time and `kotlinx.coroutines.delay`. */
object SystemExecutionClock : ExecutionClock {

    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds

    override suspend fun delay(millis: Long) {
        if (millis > 0L) coroutineDelay(millis)
    }
}

/** Test clock that advances instantly but still yields to the dispatcher. */
class VirtualExecutionClock(startMs: Long = 0L) : ExecutionClock {

    var currentMs: Long = startMs
        private set

    override fun nowMs(): Long = currentMs

    override suspend fun delay(millis: Long) {
        if (millis > 0L) currentMs += millis
        // Keeps the engine cooperative so that tests can interleave with it.
        yield()
    }

    fun advanceBy(millis: Long) {
        currentMs += millis
    }
}
