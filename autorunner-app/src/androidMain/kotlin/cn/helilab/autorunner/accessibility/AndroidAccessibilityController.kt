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
 * Holds the currently bound [AutoRunnerAccessibilityService].
 *
 * The controller is created before the service exists (the user has to enable
 * it in Settings), so it reads the holder lazily instead of capturing an
 * instance.
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
 * `AccessibilityController` implementation backed by `dispatchGesture`
 * (§6.1.2).
 *
 * Every gesture is dispatched on the main looper and the suspend function only
 * returns once the system reported completion or cancellation, which keeps the
 * executor's timing honest.
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
        // Pure waits and gamepad reports are handled outside the gesture API.
        is DelayStep -> ActionResult.Success
        is GamepadStep -> ActionResult.Unsupported("手柄动作由手柄模块处理")
        // 文本输入走 `ACTION_SET_TEXT`，不是手势。
        is KeyStep -> performKeyInput(step)
        else -> dispatch(step)
    }

    /**
     * Types [KeyStep.text] into the focused input field via `ACTION_SET_TEXT`.
     *
     * There is no gesture equivalent, so this bypasses [dispatch]: it looks up the
     * node that currently holds input focus and sets its text in one action.
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
        // `dispatchGesture` returns false for new gestures until the current one
        // finishes; asking the service to detach the capture layer also aborts
        // an in-flight recording gesture.
        AccessibilityServiceHolder.current()?.abortCurrentGesture()
    }

    private suspend fun dispatch(step: ActionStep): ActionResult {
        val service = AccessibilityServiceHolder.current()
            ?: return ActionResult.Failure("无障碍服务未连接", recoverable = true)

        val gesture = buildGesture(step)
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

    /** Translates an [ActionStep] into a [GestureDescription]. */
    private fun buildGesture(step: ActionStep): GestureDescription? = runCatching {
        val builder = GestureDescription.Builder()
        when (step) {
            is TapStep -> {
                builder.addStroke(strokeOf(pointPath(step.x, step.y), 0L, step.duration))
            }

            is LongPressStep -> {
                builder.addStroke(strokeOf(pointPath(step.x, step.y), 0L, step.duration))
            }

            is SwipeStep -> {
                val path = Path().apply {
                    moveTo(step.fromX, step.fromY)
                    lineTo(step.toX, step.toY)
                }
                builder.addStroke(strokeOf(path, 0L, step.duration))
            }

            is MultiTouchStep -> {
                if (step.points.size > MAX_STROKES) return@runCatching null
                step.points.forEach { point ->
                    val start = point.startOffset.coerceIn(0L, (step.duration - 1).coerceAtLeast(0L))
                    val duration = (step.duration - start).coerceAtLeast(MIN_STROKE_DURATION_MS)
                    builder.addStroke(
                        strokeOf(pointPath(point.x, point.y), start, duration),
                    )
                }
            }

            is DelayStep, is GamepadStep, is KeyStep -> return@runCatching null
        }
        builder.build()
    }.getOrNull()

    private fun pointPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    private fun strokeOf(path: Path, start: Long, duration: Long) = GestureDescription.StrokeDescription(
        path,
        start,
        duration.coerceAtLeast(MIN_STROKE_DURATION_MS),
    )

    companion object {
        /** Android renders at most 10 simultaneous strokes. */
        const val MAX_STROKES = 10

        /** The platform rejects zero length strokes. */
        const val MIN_STROKE_DURATION_MS = 20L

        /**
         * Current window size in pixels.
         *
         * `WindowMetrics` (API 30+) reports the real bounds of the window the
         * accessibility service covers, which is what gesture coordinates are
         * relative to; older releases fall back to the display metrics.
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
