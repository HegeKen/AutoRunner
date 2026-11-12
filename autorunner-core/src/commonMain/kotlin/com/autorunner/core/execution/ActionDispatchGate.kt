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
 * * 一轮动作以 [runRound] 整体占用派发权：另一段脚本到点时若这一轮还没跑完，
 *   会顺延到本轮动作全部执行完毕再开始，不会在两条动作之间穿插；
 * * 脚本之间的等待（循环间隔、首轮延迟）不占锁，因此不会互相拖慢；
 * * 单脚本运行时锁没有竞争，行为与之前完全一致。
 */
class ActionDispatchGate {

    private val mutex = Mutex()

    /**
     * 以「整轮」为单位占用派发权。
     *
     * 一轮动作中间常夹着动作间延时；如果只按单个动作加锁，另一段脚本会在两条
     * 动作之间插进来，屏幕上看起来两段脚本的手势互相穿插。执行器把一轮的
     * 全部动作（含轮内延时）放进 [runRound]，另一段脚本即使已到执行时间，
     * 也要等这一轮动作执行完毕、派发权释放后再开始。
     */
    suspend fun <T> runRound(block: suspend () -> T): T = mutex.withLock { block() }
}
