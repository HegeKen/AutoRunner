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
 * Records touch input through [AutoRunnerAccessibilityService] and classifies it
 * into script actions with the shared [GestureAnalyzer] (§6.1.1).
 *
 * ## Touch mirroring
 *
 * A full screen `TYPE_ACCESSIBILITY_OVERLAY` view necessarily consumes the
 * touches it captures, so the app under test would otherwise stop reacting
 * during recording. AutoRunner therefore replays every classified gesture with
 * `dispatchGesture` while the session is running, which keeps the recording
 * round trip usable.
 */
class AndroidRecordingController(
    private val context: Context,
    private val settingsProvider: () -> AppSettings,
    private val accessibilityController: AccessibilityController,
    /**
     * Makes sure the user has a way to stop the session while the capture layer
     * blocks the rest of the screen. Returns `false` when that is impossible.
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

    /** Raw frames of the current session, used by the recording event feed. */
    private val _events = MutableStateFlow<List<RawTouchEvent>>(emptyList())

    val events: StateFlow<List<RawTouchEvent>> = _events.asStateFlow()

    private var analyzer: GestureAnalyzer? = null

    /** Start time of the session, used for the `description` field. */
    private var startedAt: String = currentIsoTimestamp()

    /** Mirrors recorded gestures back so the underlying app still reacts. */
    private var mirrorTouches: Boolean = true

    /**
     * Number of mirror gestures currently being dispatched.
     *
     * A full screen capture layer also receives the events AutoRunner itself
     * injects, so without this the mirror of a recorded tap would be captured as
     * another tap and re-mirrored — an endless feedback loop (observed on a real
     * device: two taps produced 116 actions).
     */
    private var mirrorInFlight: Int = 0

    /** `InputDevice`s that are real touchscreens; everything else is injected. */
    private val touchscreenDeviceIds: Set<Int> by lazy(::resolveTouchscreenDeviceIds)

    /** Diagnostics surfaced in the recording screen. */
    private var droppedSelfInjected: Int = 0

    private var droppedForeignDevice: Int = 0

    /** Logs every captured frame; enabled through the debug command channel. */
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
        // Best effort: also show the floating panel so the user has the usual
        // controls. It is *not* required any more — stopping is guaranteed by the
        // control painted inside the capture layer, which is immune to window
        // layering (on MIUI the panel's taps were swallowed by the capture layer).
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

        // Any gesture still in flight is completed with "now" as its end time.
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
        // A throwaway analyzer classifies exactly one gesture; the capture layer is
        // detached as soon as it is complete.
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

        // Bounded raw stream trace; off unless the debug channel enables it.
        if (verboseLogging && _eventCount.value < TRACE_FRAMES) {
            Log.i(
                AutoRunnerApplication.TAG,
                "raw[${_eventCount.value}] ${event.phase} n=${event.samples.size} " +
                    "dev=${event.sourceDeviceId} t=${event.timestampMs}",
            )
        }

        // Guard 1: we are dispatching our own mirror right now.
        if (mirrorInFlight > 0) {
            droppedSelfInjected++
            return
        }

        // Guard 2: the frame did not come from a real touchscreen, so it can
        // only be an injected gesture (script replay or another automation app).
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
        // Keep a bounded feed so a long session cannot exhaust memory.
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
                    val service = AccessibilityServiceHolder.current()
                    // 注入期间让采集层不可触摸：否则镜像会被我们自己吃掉，
                    // 用户的操作无法穿透到真实应用。
                    service?.setCaptureTouchable(false)
                    runCatching { accessibilityController.perform(step) }
                    service?.setCaptureTouchable(true)
                    mirrorInFlight = (mirrorInFlight - 1).coerceAtLeast(0)
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
     * Lists the `InputDevice`s whose sources include a touchscreen. Frames from
     * any other device were injected programmatically (by AutoRunner's own
     * gesture replay or by another automation app) and must not be recorded.
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
        /** Maximum number of raw frames retained for the event feed. */
        const val MAX_FEED = 200

        /** How many raw frames are written to logcat per session. */
        const val TRACE_FRAMES = 80
    }
}
