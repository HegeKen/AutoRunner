package cn.helilab.autorunner.accessibility

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import cn.helilab.autorunner.AutoRunnerApplication
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.platform.AccessibilityController
import com.autorunner.core.platform.RecordingController
import com.autorunner.core.platform.RecordingResult
import com.autorunner.core.platform.RecordingStatus
import com.autorunner.core.recording.GestureAnalyzer
import com.autorunner.core.recording.RawTouchEvent
import com.autorunner.core.recording.RecordedScriptBuilder
import com.autorunner.core.util.currentIsoTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 通过 [AutoRunnerAccessibilityService] 录制触摸输入，并用共享的
 * [GestureAnalyzer] 将其分类为脚本动作（§6.1.1）。
 *
 * ## 触摸镜像
 *
 * 全屏的 `TYPE_ACCESSIBILITY_OVERLAY` 视图必然会消费它采集到的触摸，
 * 否则被测应用在录制期间将停止响应。因此 AutoRunner 在会话运行期间
 * 用 `dispatchGesture` 回放每一个已分类的手势，
 * 使录制的往返体验保持可用。
 */
class AndroidRecordingController(
    private val context: Context,
    private val settingsProvider: () -> AppSettings,
    private val accessibilityController: AccessibilityController,
    /**
     * 确保在采集层挡住屏幕其余部分时，用户仍有办法停止会话。
     * 若无法做到则返回 `false`。
     */
    private val ensureStopControl: () -> Boolean = { true },
) : RecordingController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow(RecordingStatus.IDLE)

    override val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private val _steps = MutableStateFlow<List<ActionStep>>(emptyList())

    override val steps: StateFlow<List<ActionStep>> = _steps.asStateFlow()

    private val _eventCount = MutableStateFlow(0)

    override val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

    /** 当前会话的原始帧，供录制事件流使用。 */
    private val _events = MutableStateFlow<List<RawTouchEvent>>(emptyList())

    val events: StateFlow<List<RawTouchEvent>> = _events.asStateFlow()

    private var analyzer: GestureAnalyzer? = null

    /** 会话的开始时间，用于 `description` 字段。 */
    private var startedAt: String = currentIsoTimestamp()

    /** 将录制的手势镜像回去，使下层应用仍能响应。 */
    private var mirrorTouches: Boolean = true

    /**
     * 当前正在派发的镜像手势数量。
     *
     * 全屏采集层同样会收到 AutoRunner 自己注入的事件，
     * 若没有这个计数，录制点击的镜像会被再次捕获为一次新的点击并再次镜像——
     * 形成无穷反馈回路（在真机上观察到：两次点击产生了 116 个动作）。
     */
    private var mirrorInFlight: Int = 0

    /** 来自真实触摸屏的 `InputDevice`；其余一切都是注入的。 */
    private val touchscreenDeviceIds: Set<Int> by lazy(::resolveTouchscreenDeviceIds)

    /** 在录制界面展示的诊断数据。 */
    private var droppedSelfInjected: Int = 0

    private var droppedForeignDevice: Int = 0

    /** 记录每一帧；通过调试命令通道开启。 */
    var verboseLogging: Boolean = false

    override val screenMetrics get() = accessibilityController.screenMetrics

    override fun arm(): Boolean {
        // 只显示悬浮控制（悬浮球/面板），不进入采集状态
        return ensureStopControl()
    }

    override fun goHome() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun bringToFront() {
        runCatching {
            context.startActivity(
                android.content.Intent(context, cn.helilab.autorunner.MainActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    .putExtra("destination", "record"),
            )
        }
    }

    override fun start(): Boolean {
        if (_status.value == RecordingStatus.RECORDING) return true
        val service = AccessibilityServiceHolder.current() ?: run {
            _status.value = RecordingStatus.ERROR
            return false
        }

        val config = settingsProvider().recording
        analyzer?.reset()
        analyzer = GestureAnalyzer(config)
        _steps.value = emptyList()
        _events.value = emptyList()
        _eventCount.value = 0
        startedAt = currentIsoTimestamp()
        mirrorTouches = true

        droppedSelfInjected = 0
        droppedForeignDevice = 0
        // 尽力而为：同时显示悬浮面板，让用户拥有惯常的控件。
        // 但它已*不*再是必需的——停止由绘制在采集层内部的控件保证，
        // 该控件不受窗口层级影响（在 MIUI 上，面板的点击会被采集层吞掉）。
        if (!ensureStopControl()) {
            Log.i(
                AutoRunnerApplication.TAG,
                "floating panel unavailable; the in-layer stop control is used instead",
            )
        }

        mirrorTouches = true
        val attached = service.attachTouchCapture(
            listener = ::onTouchEvent,
            onStopRequested = {
                Log.i(AutoRunnerApplication.TAG, "stop control pressed inside the capture layer")
                stop()
            },
            isSelfInjected = { mirrorInFlight > 0 },
        )
        _status.value = if (attached) RecordingStatus.RECORDING else RecordingStatus.ERROR
        Log.i(
            AutoRunnerApplication.TAG,
            "recording started via overlay: attached=$attached touchscreens=$touchscreenDeviceIds " +
                "mirroring=$mirrorTouches",
        )
        return attached
    }

    override fun stop(): RecordingResult {
        if (_status.value != RecordingStatus.RECORDING && _status.value != RecordingStatus.ERROR) {
            return RecordingResult(false, message = "当前没有正在进行的录制")
        }
        _status.value = RecordingStatus.FINALISING
        AccessibilityServiceHolder.current()?.detachTouchCapture()

        // 仍在执行中的手势以“现在”作为结束时间完成。
        analyzer?.let { active ->
            val flushed = active.flush(SystemClock.uptimeMillis())
            if (flushed.isNotEmpty()) _steps.value = _steps.value + flushed
        }

        val script = buildScript()
        analyzer = null
        _status.value = RecordingStatus.IDLE
        Log.i(
            AutoRunnerApplication.TAG,
            "recording stopped: steps=${_steps.value.size} rawEvents=${_eventCount.value} " +
                "droppedSelfInjected=$droppedSelfInjected droppedForeignDevice=$droppedForeignDevice",
        )
        return RecordingResult(
            success = script.flow.isNotEmpty(),
            script = script,
            message = if (script.flow.isEmpty()) "没有捕获到任何动作" else null,
        )
    }

    override fun cancel() {
        AccessibilityServiceHolder.current()?.detachTouchCapture()
        analyzer?.reset()
        analyzer = null
        _steps.value = emptyList()
        _events.value = emptyList()
        _eventCount.value = 0
        _status.value = RecordingStatus.IDLE
    }

    override fun clear() {
        analyzer?.reset()
        _steps.value = emptyList()
        _events.value = emptyList()
        _eventCount.value = 0
    }

    override fun snapshotScript(name: String): ScriptModel = buildScript(name)

    override fun pickPoint(onPicked: (Float, Float) -> Unit): Boolean {
        if (_status.value == RecordingStatus.RECORDING) return false
        val service = AccessibilityServiceHolder.current() ?: return false
        return service.captureNextPoint(onPicked)
    }

    override fun pickAction(onPicked: (ActionStep) -> Unit): Boolean {
        if (_status.value == RecordingStatus.RECORDING) return false
        val service = AccessibilityServiceHolder.current() ?: return false
        // 一次性的分析器只对一个手势做分类；一旦完成，
        // 采集层就会被卸载。
        val analyzer = GestureAnalyzer(settingsProvider().recording)
        return service.attachTouchCapture(
            listener = { event ->
                val steps = analyzer.onTouchEvent(event)
                if (steps.isNotEmpty()) {
                    service.detachTouchCapture()
                    onPicked(steps.first())
                }
            },
            onStopRequested = { service.detachTouchCapture() },
            isSelfInjected = { false },
            controlLabel = TouchCaptureView.CANCEL_LABEL,
        )
    }

    // ------------------------------------------------------------------ input

    private fun onTouchEvent(event: RawTouchEvent) {
        val active = analyzer ?: return

        // 有上限的原始流跟踪；除非调试通道开启，否则关闭。
        if (verboseLogging && _eventCount.value < TRACE_FRAMES) {
            Log.i(
                AutoRunnerApplication.TAG,
                "raw[${_eventCount.value}] ${event.phase} n=${event.samples.size} " +
                    "dev=${event.sourceDeviceId} t=${event.timestampMs}",
            )
        }

        // 守卫 1：我们此刻正在派发自己的镜像。
        if (mirrorInFlight > 0) {
            droppedSelfInjected++
            return
        }

        // 守卫 2：该帧并非来自真实触摸屏，因此只可能是注入的手势
        // （脚本回放或其他自动化应用）。
        if (event.sourceDeviceId >= 0 &&
            touchscreenDeviceIds.isNotEmpty() &&
            event.sourceDeviceId !in touchscreenDeviceIds
        ) {
            droppedForeignDevice++
            return
        }

        val finished = active.onTouchEvent(event)
        _eventCount.value = active.eventCount
        if (verboseLogging) {
            finished.forEach { Log.i(AutoRunnerApplication.TAG, "step> ${it.typeName} ${it.label}") }
        }
        // 保持有界的事件流，使长时间会话不会耗尽内存。
        val feed = _events.value
        _events.value = if (feed.size >= MAX_FEED) feed.drop(feed.size - MAX_FEED + 1) + event else feed + event

        if (finished.isEmpty()) return
        val maxSteps = settingsProvider().recording.maxSteps
        val current = _steps.value
        _steps.value = (current + finished).take(maxSteps)

        if (mirrorTouches) {
            finished.forEach { step ->
                mirrorInFlight++
                scope.launch {
                    // try/finally 保证任何异常路径下计数都归零：
                    // 否则 setCaptureTouchable 抛异常后 mirrorInFlight 永远 > 0，
                    // 后续所有帧都会被 Guard 1 当作自注入丢弃，录制"卡死"。
                    try {
                        val service = AccessibilityServiceHolder.current()
                        // 注入期间让采集层不可触摸：否则镜像会被我们自己吃掉，
                        // 用户的操作无法穿透到真实应用。
                        service?.setCaptureTouchable(false)
                        runCatching { accessibilityController.perform(step) }
                        service?.setCaptureTouchable(true)
                    } finally {
                        mirrorInFlight = (mirrorInFlight - 1).coerceAtLeast(0)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ build

    private fun buildScript(name: String = ""): ScriptModel {
        val settings = settingsProvider()
        val resolvedName = name.ifBlank {
            "录制脚本 ${startedAt.take(16).replace('T', ' ')}"
        }
        return RecordedScriptBuilder.build(
            name = resolvedName,
            steps = _steps.value,
            metrics = screenMetrics,
            execution = settings.defaultExecution,
            recordingConfig = settings.recording,
            createdAt = startedAt,
        )
    }

    /**
     * 列出来源包含触摸屏的 `InputDevice`。来自任何其他设备的帧都是以编程方式
     * 注入的（由 AutoRunner 自己的手势回放或其他自动化应用），
     * 绝不能被录制。
     */
    private fun resolveTouchscreenDeviceIds(): Set<Int> = runCatching {
        InputDevice.getDeviceIds()
            .filter { id ->
                val device = InputDevice.getDevice(id)
                device != null &&
                    (device.sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
            }
            .toSet()
    }.getOrDefault(emptySet())

    private companion object {
        /** 事件流保留的原始帧上限。 */
        const val MAX_FEED = 200

        /** 每个会话写入 logcat 的原始帧数量。 */
        const val TRACE_FRAMES = 80
    }
}
