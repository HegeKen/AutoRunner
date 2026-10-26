package com.autorunner.core.execution

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionDispatchGateTest {

    /** 组合运行的两个执行器共享一把锁：动作可以排队，但不能同时进入输入后端。 */
    @Test
    fun serialisesConcurrentActions() = runTest {
        val gate = ActionDispatchGate()
        var active = 0
        var maxActive = 0

        coroutineScope {
            repeat(6) {
                launch {
                    gate.run {
                        active++
                        maxActive = maxOf(maxActive, active)
                        delay(10)
                        active--
                    }
                }
            }
        }

        assertEquals(1, maxActive, "同一时刻只允许一个动作进入输入后端")
        assertEquals(0, active)
    }
}
