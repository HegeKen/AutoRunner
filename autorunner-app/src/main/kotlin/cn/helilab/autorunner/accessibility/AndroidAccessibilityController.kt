package cn.helilab.autorunner.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import cn.helilab.autorunner.AutoRunnerApplication
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.DelayStep
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.KeyStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 持有当前已绑定的 [AutoRunnerAccessibilityService]。
 *
 * 控制器在服务存在之前就会被创建（用户需要先在设置中启用它），
 * 因此它延迟读取持有者，而不是捕获某个实例。
 */
object AccessibilityServiceHolder {

    @Volatile
    private var service: AutoRunnerAccessibilityService? = null

    val isConnected: Boolean get() = service != null

    fun current(): AutoRunnerAccessibilityService? = service

    fun attach(service: AutoRunnerAccessibilityService) {
        this.service = service
        Log.i(AutoRunnerApplication.TAG, "accessibility service connected")
    }

    fun detach(service: AutoRunnerAccessibilityService) {
        if (this.service === service) {
            this.service = null
            Log.i(AutoRunnerApplication.TAG, "accessibility service disconnected")
        }
    }
}

/**
 * 基于 `dispatchGesture` 的 `AccessibilityController` 实现（§6.1.2）。
 *
 * 每个手势都在主循环上派发，挂起函数只在系统报告完成或取消后才返回，
 * 从而保证执行器的计时是准确的。
 */
class AndroidAccessibilityController(
    private val context: Context,
) : AccessibilityController {

    override val isConnected: Boolean get() = AccessibilityServiceHolder.isConnected

    override val supportsGestures: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override val screenMetrics: ScreenMetrics get() = measureScreen(context)

    override fun refreshScreenMetrics(): ScreenMetrics = measureScreen(context)

    override suspend fun perform(step: ActionStep): ActionResult = when (step) {
        // 纯等待和手柄上报在手势 API 之外处理。
        is DelayStep -> ActionResult.Success
        is GamepadStep -> ActionResult.Unsupported("手柄动作由手柄模块处理")
        // 文本输入走 `ACTION_SET_TEXT`，不是手势。
        is KeyStep -> performKeyInput(step)
        else -> dispatch(step)
    }

    /**
     * 通过 `ACTION_SET_TEXT` 将 [KeyStep.text] 输入到当前聚焦的输入框。
     *
     * 没有对应的手势形式，因此这里绕过 [dispatch]：查找当前持有输入焦点的节点，
     * 并在一次操作中设置其文本。
     */
    private suspend fun performKeyInput(step: KeyStep): ActionResult {
        val service = AccessibilityServiceHolder.current()
            ?: return ActionResult.Failure("无障碍服务未连接", recoverable = true)

        return withContext(Dispatchers.Main) {
            val root = service.rootInActiveWindow
                ?: return@withContext ActionResult.Failure("无法读取当前窗口内容", recoverable = true)
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return@withContext ActionResult.Failure("没有找到可输入的输入框，请先点选目标", recoverable = true)

            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    step.text,
                )
            }
            val accepted = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)

            if (accepted) {
                ActionResult.Success
            } else {
                ActionResult.Failure("文本写入被拒绝（目标可能不支持设置文本）", recoverable = true)
            }
        }
    }

    override fun cancelPendingGestures() {
        // 在当前手势结束之前，`dispatchGesture` 对新手势返回 false；
        // 请求服务卸载采集层也会中止正在执行的录制手势。
        AccessibilityServiceHolder.current()?.abortCurrentGesture()
    }

    private suspend fun dispatch(step: ActionStep): ActionResult {
        val service = AccessibilityServiceHolder.current()
            ?: return ActionResult.Failure("无障碍服务未连接", recoverable = true)

        val gesture = runCatching { buildGesture(step) }
            .getOrElse { error ->
                // 坐标越界等构建失败曾在这里被吞掉、误报成「不支持的动作」，导致真机排查困难。
                Log.w(AutoRunnerApplication.TAG, "构建手势失败：${step.typeName}", error)
                return ActionResult.Failure("手势构建失败：${error.message ?: "未知错误"}", recoverable = false)
            }
            ?: return ActionResult.Unsupported("不支持的动作用于手势回放：${step.typeName}")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(ActionResult.Success)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(ActionResult.Failure("手势被系统取消", recoverable = false))
                        }
                    }
                }

                val accepted = runCatching {
                    service.dispatchGesture(gesture, callback, null)
                }.getOrElse { error ->
                    Log.w(AutoRunnerApplication.TAG, "dispatchGesture failed", error)
                    false
                }

                if (!accepted && continuation.isActive) {
                    continuation.resume(ActionResult.Failure("手势派发失败（可能有手势正在执行）", recoverable = true))
                }
            }
        }
    }

    /**
     * 将 [ActionStep] 转换为 [GestureDescription]。
     *
     * 只有当该步骤确实没有手势形式时才返回 `null`
     * （延迟/手柄/按键，或同时的笔画过多）。坐标会被钳制到屏幕内，
     * 因为平台会拒绝带有负值边界的笔画；其他任何错误都会抛给调用方，
     * 使真正的故障暴露出来，而不会被误判为不支持的动作。
     */
    private fun buildGesture(step: ActionStep): GestureDescription? {
        val metrics = measureScreen(context)
        val maxX = metrics.widthPx.toFloat()
        val maxY = metrics.heightPx.toFloat()

        val builder = GestureDescription.Builder()
        when (step) {
            is TapStep -> {
                builder.addStroke(strokeOf(pointPath(step.x.clampToScreen(maxX), step.y.clampToScreen(maxY)), 0L, step.duration))
            }

            is LongPressStep -> {
                builder.addStroke(strokeOf(pointPath(step.x.clampToScreen(maxX), step.y.clampToScreen(maxY)), 0L, step.duration))
            }

            is SwipeStep -> {
                val path = Path().apply {
                    moveTo(step.fromX.clampToScreen(maxX), step.fromY.clampToScreen(maxY))
                    lineTo(step.toX.clampToScreen(maxX), step.toY.clampToScreen(maxY))
                }
                builder.addStroke(strokeOf(path, 0L, step.duration))
            }

            is MultiTouchStep -> {
                if (step.points.size > MAX_STROKES) return null
                step.points.forEach { point ->
                    val start = point.startOffset.coerceIn(0L, (step.duration - 1).coerceAtLeast(0L))
                    val duration = (step.duration - start).coerceAtLeast(MIN_STROKE_DURATION_MS)
                    builder.addStroke(
                        strokeOf(pointPath(point.x.clampToScreen(maxX), point.y.clampToScreen(maxY)), start, duration),
                    )
                }
            }

            is DelayStep, is GamepadStep, is KeyStep -> return null
        }
        return builder.build()
    }

    /** 将手势坐标保持在屏幕内，以便通过平台的非负边界检查。 */
    private fun Float.clampToScreen(max: Float): Float = coerceIn(0f, max)

    private fun pointPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    private fun strokeOf(path: Path, start: Long, duration: Long) = GestureDescription.StrokeDescription(
        path,
        start,
        duration.coerceAtLeast(MIN_STROKE_DURATION_MS),
    )

    companion object {
        /** Android 最多渲染 10 个同时进行的笔画。 */
        const val MAX_STROKES = 10

        /** 平台会拒绝零长度的笔画。 */
        const val MIN_STROKE_DURATION_MS = 20L

        /**
         * 当前窗口尺寸（像素）。
         *
         * `WindowMetrics`（API 30+）报告无障碍服务所覆盖窗口的真实边界，
         * 手势坐标正是相对于它而言；较旧的版本则回退到显示指标。
         */
        fun measureScreen(context: Context): ScreenMetrics {
            val resources = context.resources
            val density = resources.displayMetrics.density
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val manager = context.getSystemService(WindowManager::class.java)
                    val bounds = manager?.currentWindowMetrics?.bounds
                    if (bounds != null && !bounds.isEmpty) {
                        ScreenMetrics(bounds.width(), bounds.height(), density)
                    } else {
                        fallback(context, density)
                    }
                } else {
                    fallback(context, density)
                }
            }.getOrElse { fallback(context, density) }
        }

        @Suppress("DEPRECATION")
        private fun fallback(context: Context, density: Float): ScreenMetrics {
            val metrics = android.util.DisplayMetrics()
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            manager?.defaultDisplay?.getRealMetrics(metrics)
            return if (metrics.widthPixels > 0) {
                ScreenMetrics(metrics.widthPixels, metrics.heightPixels, density)
            } else {
                val dm = context.resources.displayMetrics
                ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density)
            }
        }
    }
}
