package com.autorunner.core.platform

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of a recording session. */
enum class RecordingStatus {
    /** No session running. */
    IDLE,

    /** The transparent capture layer is attached and recording touches. */
    RECORDING,

    /** Stop was requested; pending gestures are being flushed. */
    FINALISING,

    /** The capture layer could not be attached (accessibility service missing). */
    ERROR,
}

/** Outcome of `stop()`. */
data class RecordingResult(
    val success: Boolean,
    val script: ScriptModel? = null,
    val message: String? = null,
)

/**
 * Captures raw touch input and turns it into [ActionStep]s.
 *
 * Android implementation attaches a `TYPE_ACCESSIBILITY_OVERLAY` view inside
 * `AutoRunnerAccessibilityService`; the gesture classification itself is shared
 * (`GestureAnalyzer`).
 */
interface RecordingController {

    val status: StateFlow<RecordingStatus>

    /** Actions classified so far, in the order they happened. */
    val steps: StateFlow<List<ActionStep>>

    /** Raw touch events seen so far (diagnostics / event feed). */
    val eventCount: StateFlow<Int>

    /** Screen the session is recording against. */
    val screenMetrics: ScreenMetrics

    /**
     * Starts a session.
     *
     * @return `false` when the accessibility service is not connected or a
     *   session is already running.
     */
    /**
     * 「准备录制」：只把悬浮控制显示出来（不开始采集），由用户在悬浮窗上点开始。
     *
     * @return `false` 表示悬浮控制无法显示（缺悬浮窗权限）。
     */
    fun arm(): Boolean = false

    /** 切到桌面，方便用户去目标应用操作。 */
    fun goHome() {}

    /** 录制结束后把应用带回前台（录制页会提示及时保存）。 */
    fun bringToFront() {}

    fun start(): Boolean

    /** Stops the session and builds the resulting script. */
    fun stop(): RecordingResult

    /** Discards the current session without producing a script. */
    fun cancel()

    /** Clears recorded actions while staying in the session. */
    fun clear()

    /** Builds a script from the actions recorded so far without stopping. */
    fun snapshotScript(name: String = ""): ScriptModel

    /**
     * Captures a single screen point.
     *
     * Used by the gamepad mapping UI: the user taps the position of a virtual
     * button and the coordinates are reported back. The temporary capture layer is
     * removed as soon as the first tap is released.
     *
     * @return `false` when picking is impossible (no accessibility service, or a
     *   recording session is already running).
     */
    fun pickPoint(onPicked: (x: Float, y: Float) -> Unit): Boolean = false

    /**
     * Captures one complete gesture (tap / long press / swipe / multi touch) and
     * reports the classified action.
     *
     * This is how the editor fills in coordinates "from a real operation" instead
     * of asking the user to type pixels.
     *
     * @return `false` when capturing is impossible (no accessibility service, or a
     *   recording session is already running).
     */
    fun pickAction(onPicked: (com.autorunner.core.model.ActionStep) -> Unit): Boolean = false
}

/** Fallback recording controller used when the platform cannot record. */
class UnavailableRecordingController(
    private val reason: String = "当前平台不支持录制",
) : RecordingController {

    private val _status = MutableStateFlow(RecordingStatus.IDLE)
    private val _steps = MutableStateFlow<List<ActionStep>>(emptyList())
    private val _eventCount = MutableStateFlow(0)

    override val status: StateFlow<RecordingStatus> = _status.asStateFlow()
    override val steps: StateFlow<List<ActionStep>> = _steps.asStateFlow()
    override val eventCount: StateFlow<Int> = _eventCount.asStateFlow()
    override val screenMetrics: ScreenMetrics = ScreenMetrics.Unknown

    /** Populated by [start] so the UI can explain why recording is unavailable. */
    var lastError: String? = reason
        private set

    override fun start(): Boolean {
        _status.value = RecordingStatus.ERROR
        return false
    }

    override fun stop(): RecordingResult = RecordingResult(false, message = reason)

    override fun cancel() {
        _status.value = RecordingStatus.IDLE
    }

    override fun clear() {
        _steps.value = emptyList()
        _eventCount.value = 0
    }

    override fun snapshotScript(name: String): ScriptModel = ScriptModel()
}
