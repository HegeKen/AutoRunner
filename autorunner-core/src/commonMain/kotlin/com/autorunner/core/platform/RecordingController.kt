package com.autorunner.core.platform

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 录制会话的生命周期。 */
enum class RecordingStatus {
    /** 没有会话在运行。 */
    IDLE,

    /** 透明采集层已附加并正在记录触摸。 */
    RECORDING,

    /** 已请求停止；正在冲刷挂起的手势。 */
    FINALISING,

    /** 采集层无法附加（缺少无障碍服务）。 */
    ERROR,
}

/** `stop()` 的结果。 */
data class RecordingResult(
    val success: Boolean,
    val script: ScriptModel? = null,
    val message: String? = null,
)

/**
 * 采集原始触摸输入并把它们转换为 [ActionStep]。
 *
 * Android 实现在 `AutoRunnerAccessibilityService` 内附加一个
 * `TYPE_ACCESSIBILITY_OVERLAY` 视图；手势分类本身是共享的
 * （`GestureAnalyzer`）。
 */
interface RecordingController {

    val status: StateFlow<RecordingStatus>

    /** 迄今已分类的动作，按发生顺序排列。 */
    val steps: StateFlow<List<ActionStep>>

    /** 迄今见到的原始触摸事件（诊断／事件馈送）。 */
    val eventCount: StateFlow<Int>

    /** 会话录制所针对的屏幕。 */
    val screenMetrics: ScreenMetrics

    /**
     * 启动一个会话。
     *
     * @return 无障碍服务未连接、或已有会话在运行时为 `false`。
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

    /** 停止会话并构建生成的脚本。 */
    fun stop(): RecordingResult

    /** 丢弃当前会话，不产出脚本。 */
    fun cancel()

    /** 在保持会话的前提下清空已录制的动作。 */
    fun clear()

    /** 不停止会话，基于迄今录制的动作构建一个脚本。 */
    fun snapshotScript(name: String = ""): ScriptModel

    /**
     * 采集屏幕上的单个点。
     *
     * 供手柄映射 UI 使用：用户点一下虚拟按键的位置，坐标即回传。
     * 第一次抬指后临时采集层就会被移除。
     *
     * @return 无法取点（没有无障碍服务，或已有录制会话在运行）时为 `false`。
     */
    fun pickPoint(onPicked: (x: Float, y: Float) -> Unit): Boolean = false

    /**
     * 采集一个完整手势（点击／长按／滑动／多点触控）并上报分类后的动作。
     *
     * 编辑器正是通过它“从真实操作”填入坐标，而不用让用户手动敲像素值。
     *
     * @return 无法采集（没有无障碍服务，或已有录制会话在运行）时为 `false`。
     */
    fun pickAction(onPicked: (com.autorunner.core.model.ActionStep) -> Unit): Boolean = false
}

/** 平台无法录制时使用的兜底实现。 */
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

    /** 由 [start] 填充，让 UI 能解释录制为何不可用。 */
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
