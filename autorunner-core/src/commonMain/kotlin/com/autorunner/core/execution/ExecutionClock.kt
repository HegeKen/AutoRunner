package com.autorunner.core.execution

import kotlin.time.TimeSource
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.yield

/**
 * [AutoRunnerScriptExecutor] 使用的时间源。
 *
 * 抽象出时间源能让引擎在测试下保持确定性：虚拟实现在每次调用 [delay] 时
 * 推进自己的时钟，于是「10 圈 × 2 秒间隔」的运行会瞬间完成，而不是真的
 * 耗费 20 秒挂钟时间。
 */
interface ExecutionClock {

    /** 自任意原点起的毫秒数；只有差值有意义。 */
    fun nowMs(): Long

    /** 挂起 [millis] 毫秒，不忙等。 */
    suspend fun delay(millis: Long)
}

/** 生产环境时钟：单调时间加 `kotlinx.coroutines.delay`。 */
object SystemExecutionClock : ExecutionClock {

    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds

    override suspend fun delay(millis: Long) {
        if (millis > 0L) coroutineDelay(millis)
    }
}

/** 瞬间推进但仍让出到调度器的测试时钟。 */
class VirtualExecutionClock(startMs: Long = 0L) : ExecutionClock {

    var currentMs: Long = startMs
        private set

    override fun nowMs(): Long = currentMs

    override suspend fun delay(millis: Long) {
        if (millis > 0L) currentMs += millis
        // 保持引擎协作式执行，测试才能与它交错运行。
        yield()
    }

    fun advanceBy(millis: Long) {
        currentMs += millis
    }
}
