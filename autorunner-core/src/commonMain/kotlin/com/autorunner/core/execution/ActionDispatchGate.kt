package com.autorunner.core.execution

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行化多个执行器对同一个输入后端的动作派发。
 *
 * 组合运行时两个 [AutoRunnerScriptExecutor] 会同时工作。Android 的
 * `dispatchGesture` 在一次手势结束前会拒绝新的手势，直接并发派发会让其中一段
 * 脚本的首个动作失败、甚至被误判为服务掉线。两个执行器共享同一个
 * [ActionDispatchGate] 后，动作按「先到先执行」排队：
 *
 * * 手势本身仍然是逐条、原子地交给系统；
 * * 脚本之间的等待（循环间隔、首轮延迟）不占锁，因此不会互相拖慢；
 * * 单脚本运行时锁没有竞争，行为与之前完全一致。
 */
class ActionDispatchGate {

    private val mutex = Mutex()

    /** 在锁内执行 [block]；可重入调用需由调用方避免。 */
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
