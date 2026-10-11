package com.autorunner.ui.viewmodel

import com.autorunner.core.di.AppContainer
import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.InputMode
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.FailureStrategy
import com.autorunner.core.model.GamepadButton
import com.autorunner.core.model.GamepadButtonMapping
import com.autorunner.core.model.GamepadKeyLabels
import com.autorunner.core.model.GamepadMappings
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.labelFor
import com.autorunner.core.model.RecordingConfig
import com.autorunner.core.model.ThemeMode
import com.autorunner.core.platform.DockEdge
import com.autorunner.ui.platform.GamepadStatusProvider
import com.autorunner.ui.platform.OemGuidanceStep
import com.autorunner.ui.platform.PermissionController
import com.autorunner.ui.platform.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置页面的状态持有者。
 *
 * 所有修改都直接写入 [com.autorunner.core.settings.SettingsRepository]，
 * 以便各服务能立即读到新值。
 */
class SettingsViewModel(
    private val container: AppContainer,
    private val permissions: PermissionController? = null,
    private val gamepad: GamepadStatusProvider? = null,
    /** 本机是否为平板（smallestScreenWidthDp >= 600）；键盘映射仅平板可见。 */
    val isTabletDevice: Boolean = false,
) {

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    /** 存储目录确定后即可用。 */
    val storageLocation: String =
        (container.scriptRepository as? com.autorunner.core.script.FileScriptRepository)?.location
            ?: "应用私有目录"

    val permissionStatus: StateFlow<PermissionStatus> =
        permissions?.status ?: MutableStateFlow(PermissionStatus()).asStateFlow()

    val gamepadSupported: Boolean get() = gamepad?.supported ?: false

    val gamepadConnected: Boolean get() = gamepad?.connected ?: false

    val gamepadError: String? get() = gamepad?.lastError

    /** 可观察的悬浮层状态，让设置行保持响应式。 */
    val overlayState: StateFlow<com.autorunner.core.platform.OverlayState> = container.overlayManager.state

    val overlayVisible: Boolean get() = overlayState.value.visible

    val overlayDockEdge: DockEdge get() = overlayState.value.dockedEdge

    // ---------------------------------------------------------------- 主题

    fun setThemeMode(mode: ThemeMode) = container.settingsRepository.setThemeMode(mode)

    // ------------------------------------------------------------ 执行

    fun setDefaultMode(mode: ExecutionMode) = container.settingsRepository.update {
        it.copy(defaultExecution = it.defaultExecution.copy(mode = mode))
    }

    fun setDefaultRepeatCount(count: Int) = container.settingsRepository.setDefaultRepeatCount(count)

    fun setDefaultIntervalMs(intervalMs: Long) = container.settingsRepository.update {
        it.copy(defaultExecution = it.defaultExecution.copy(intervalMs = intervalMs.coerceAtLeast(0)))
    }

    /**
     * 失败策略有意存储了两份：[AppSettings.failureStrategy] 是悬浮面板发起运行时
     * 采用的全局默认值，而 `defaultExecution.failureStrategy` 会嵌入新录制的脚本，
     * 因此两者必须保持同步。
     */
    fun setFailureStrategy(strategy: FailureStrategy) = container.settingsRepository.update {
        it.copy(
            failureStrategy = strategy,
            defaultExecution = it.defaultExecution.copy(failureStrategy = strategy),
        )
    }

    fun setKeepScreenOn(enabled: Boolean) = container.settingsRepository.setKeepScreenOn(enabled)

    fun setExecutionNotification(enabled: Boolean) =
        container.settingsRepository.setExecutionNotification(enabled)

    fun setExecutionReminder(enabled: Boolean) =
        container.settingsRepository.setExecutionReminder(enabled)

    fun setAutoCollapsePanel(enabled: Boolean) = container.settingsRepository.setAutoCollapsePanel(enabled)

    fun setHapticFeedback(enabled: Boolean) = container.settingsRepository.setHapticFeedback(enabled)

    // ------------------------------------------------------------ 录制

    fun setRecordingConfig(config: RecordingConfig) = container.settingsRepository.setRecordingConfig(config)

    fun setTapSlop(px: Float) = container.settingsRepository.update {
        it.copy(recording = it.recording.copy(tapSlopPx = px))
    }

    fun setLongPressThreshold(ms: Long) = container.settingsRepository.update {
        it.copy(recording = it.recording.copy(longPressThresholdMs = ms))
    }

    fun setCaptureMultiTouch(enabled: Boolean) = container.settingsRepository.update {
        it.copy(recording = it.recording.copy(captureMultiTouch = enabled))
    }

    fun setNormaliseCoordinates(enabled: Boolean) = container.settingsRepository.update {
        it.copy(recording = it.recording.copy(normaliseCoordinates = enabled))
    }

    fun setDefaultActionDelay(ms: Long) = container.settingsRepository.update {
        it.copy(recording = it.recording.copy(defaultDelayMs = ms))
    }

    // ------------------------------------------------------------- 悬浮层

    fun setShowFloatingBallOnStart(enabled: Boolean) =
        container.settingsRepository.setShowFloatingBall(enabled)

    fun toggleOverlay() {
        val manager = container.overlayManager
        if (!manager.isPermissionGranted()) {
            _message.value = "请先授予悬浮窗权限"
            permissions?.requestOverlayPermission()
            return
        }
        manager.toggle()
    }

    // ------------------------------------------------------------- 手柄
    //
    // 手柄模拟是把按键注入到*本*设备，因此唯一的配置工作就是
    // 告诉 AutoRunner 每个虚拟按键在屏幕上的位置。

    fun setGamepadEnabled(enabled: Boolean) {
        container.settingsRepository.setGamepadEnabled(enabled)
        if (enabled && container.settingsRepository.current.currentGamepadMappings.configuredCount == 0) {
            _message.value = "请先为要使用的手柄按键拾取屏幕位置，否则脚本中的 gamepad 动作会被跳过"
        }
    }

    fun setGamepadMode(mode: GamepadMode) = container.settingsRepository.setGamepadMode(mode)

    /** 平板键盘映射总开关。 */
    fun setGamepadKeyboardEnabled(enabled: Boolean) {
        container.settingsRepository.setGamepadKeyboardEnabled(enabled)
        if (enabled) {
            val count = container.settingsRepository.current
                .currentGamepadMappings.buttons.count { it.configured && it.keyCode != null }
            if (count == 0) {
                _message.value = "请先在下方按键里绑定键盘键位，否则按下键盘不会触发点击"
            }
        }
    }

    /** WASD 控制左摇杆开关（独立开关，需已标定的虚拟摇杆中心）。 */
    fun setGamepadKeyboardStickEnabled(enabled: Boolean) {
        container.settingsRepository.setGamepadKeyboardStickEnabled(enabled)
        if (enabled && !container.settingsRepository.current.currentGamepadMappings.stickCenterConfigured) {
            _message.value = "请先在下方标定虚拟摇杆中心，WASD 需要按中心与半径计算方向位置"
        }
    }

    /**
     * 拉起全屏手柄标定悬浮层：App 退到后台，用户在真实游戏页面把每个按键拖到
     * 实际按钮上。标定结果按当前 [AppSettings.gamepadMode] 分开保存，编辑 /
     * 新增脚本时对应类型便不再引导用户重复标定。
     *
     * @return `false` 表示无法开始（缺少悬浮窗权限）；调用方
     *   （`GamepadCalibrationEntry`）据此提示用户，这里不再写 message，
     *   避免和调用方的弹窗叠成两层。
     */
    fun armGamepadCalibration(): Boolean = container.gamepadCalibrationController.arm()

    /** 把 [transform] 的结果写回当前手柄类型对应的那一份标定。 */
    private fun updateCurrentGamepadMappings(transform: (GamepadMappings) -> GamepadMappings) {
        val repository = container.settingsRepository
        val mode = repository.current.gamepadMode
        repository.setGamepadMappings(mode, transform(repository.current.gamepadMappingsFor(mode)))
    }

    /** 保存一个按键位置（手动编辑器和拾取器都会用到）。 */
    fun setGamepadMapping(
        button: GamepadButton,
        x: Float,
        y: Float,
        durationMs: Long = container.settingsRepository.current
            .currentGamepadMappings.buttons.firstOrNull { it.button == button }?.durationMs ?: 60L,
        keyCode: Int? = container.settingsRepository.current
            .currentGamepadMappings.buttons.firstOrNull { it.button == button }?.keyCode,
        holdMode: Boolean = container.settingsRepository.current
            .currentGamepadMappings.buttons.firstOrNull { it.button == button }?.holdMode ?: false,
    ) = updateCurrentGamepadMappings { mappings ->
        mappings.withMapping(
            GamepadButtonMapping(
                button = button,
                x = x,
                y = y,
                durationMs = durationMs,
                configured = true,
                keyCode = keyCode,
                holdMode = holdMode,
            ),
        )
    }

    /**
     * 把物理键盘键位绑定到 [button]（平板键盘映射）。
     *
     * 同一键位不允许绑定两个按键；键位不支持（系统键）时拒绝。
     * 按键尚未拾取屏幕位置时也允许先绑定，但该键在位置配置完成前
     * 不会触发点击。
     */
    fun bindGamepadKey(button: GamepadButton, keyCode: Int) {
        if (!GamepadKeyLabels.isBindable(keyCode)) {
            _message.value = "该按键为系统按键，不支持绑定"
            return
        }
        updateCurrentGamepadMappings { mappings ->
            // 已绑键但尚未拾取屏幕位置（configured=false）的占位映射同样参与
            // 查重，避免同一个键位在两个按键上各留一份。
            val owner = mappings.buttons
                .firstOrNull { it.keyCode == keyCode && it.button != button }
            if (owner != null) {
                _message.value =
                    "「${GamepadKeyLabels.labelFor(keyCode)}」" +
                        "已绑定给「${owner.button.labelFor(container.settingsRepository.current.gamepadMode)}」"
                mappings
            } else {
                val existing = mappings.buttons.firstOrNull { it.button == button }
                mappings.withMapping(
                    (existing ?: GamepadButtonMapping(button = button))
                        .copy(keyCode = keyCode),
                )
            }
        }
    }

    /** 清除 [button] 已绑定的键盘键位。 */
    fun clearGamepadKey(button: GamepadButton) = updateCurrentGamepadMappings { mappings ->
        // 占位映射（仅有键位、未拾取坐标）也要能被清除，不能用 mappingFor。
        val existing = mappings.buttons.firstOrNull { it.button == button }
            ?: return@updateCurrentGamepadMappings mappings
        mappings.withMapping(existing.copy(keyCode = null))
    }

    fun clearGamepadMapping(button: GamepadButton) = updateCurrentGamepadMappings { it.withoutMapping(button) }

    /**
     * 一键把当前手柄类型的全部按键按压时长统一为 [durationMs]。
     *
     * 不同设备 / 游戏对按压的识别阈值不同（有的 60ms 即可触发，有的需要 200ms
     * 以上），逐个按键改太繁琐，因此在「设置 → 手柄模拟」里提供整体调整；
     * 范围与 AppSettings 持久化时的夹取一致（20..10000 毫秒）。
     */
    fun setAllGamepadDurations(durationMs: Long) {
        if (container.settingsRepository.current.currentGamepadMappings.configuredCount == 0) {
            _message.value = "还没有已映射的按键，请先完成手柄标定"
            return
        }
        val coerced = durationMs.coerceIn(20L, 10_000L)
        updateCurrentGamepadMappings { mappings ->
            mappings.copy(buttons = mappings.buttons.map { it.copy(durationMs = coerced) })
        }
    }

    fun clearAllGamepadMappings() = updateCurrentGamepadMappings { GamepadMappings.Empty }

    fun setStickCenter(x: Float, y: Float) = updateCurrentGamepadMappings {
        it.copy(stickCenterX = x, stickCenterY = y, stickCenterConfigured = true)
    }

    /** 保存右摇杆中心（「鼠标 / 触控板控制右摇杆」注入手势的起点）。 */
    fun setRightStickCenter(x: Float, y: Float) = updateCurrentGamepadMappings {
        it.copy(rightStickCenterX = x, rightStickCenterY = y, rightStickCenterConfigured = true)
    }

    /** 鼠标 / 触控板控制右摇杆开关（需 Android 14+ 且已标定右摇杆中心）。 */
    fun setGamepadMouseStickEnabled(enabled: Boolean) {
        container.settingsRepository.setGamepadMouseStickEnabled(enabled)
        if (enabled &&
            !container.settingsRepository.current.currentGamepadMappings.rightStickCenterConfigured
        ) {
            _message.value = "请先在下方标定右摇杆中心，鼠标位移需要按中心与半径计算推杆位置"
        }
    }

    fun setStickRadius(radius: Float) = updateCurrentGamepadMappings { it.copy(stickRadius = radius) }

    /**
     * 请求无障碍层捕获下一次点击的坐标。
     *
     * @param button 为 `null` 时拾取虚拟摇杆中心而不是某个按键。
     * @param rightStickCenter `button == null` 且为 `true` 时拾取的是**右**摇杆中心，
     *   否则为左摇杆中心。
     * @param onPicked 提供时（映射对话框），捕获的坐标会回传给对话框，
     *   由它填充输入框并让用户点「保存」确认；为 `null` 时坐标直接写入设置，
     *   即摇杆行上快速「拾取」操作所需的行为。
     */
    fun pickGamepadPoint(
        button: GamepadButton?,
        onPicked: ((Float, Float) -> Unit)? = null,
        rightStickCenter: Boolean = false,
    ) {
        if (!container.accessibilityController.isConnected) {
            _message.value = "需要无障碍服务已连接才能拾取屏幕位置"
            return
        }
        val picked = button != null || rightStickCenter
        val started = container.recordingController.pickPoint { x, y ->
            if (onPicked != null) {
                onPicked(x, y)
                _message.value = "已拾取坐标 (${x.toInt()}, ${y.toInt()})，确认后点「保存」"
            } else {
                when {
                    button != null -> {
                        setGamepadMapping(button, x, y)
                        _message.value = "已将「${button.labelFor(container.settingsRepository.current.gamepadMode)}」" +
                            "映射到 (${x.toInt()}, ${y.toInt()})"
                    }
                    rightStickCenter -> {
                        setRightStickCenter(x, y)
                        _message.value = "已将右摇杆中心设为 (${x.toInt()}, ${y.toInt()})"
                    }
                    else -> {
                        setStickCenter(x, y)
                        _message.value = "已将虚拟摇杆中心设为 (${x.toInt()}, ${y.toInt()})"
                    }
                }
            }
        }
        // 这里的引导文案是同步写入的，会覆盖上面的结果文案；但 pickPoint 回调要等用户
        // 真正点屏幕后才触发，届时会再次覆盖为结果文案，因此最终展示的仍是结果。
        _message.value = if (started) {
            if (!picked) {
                "请在屏幕上点击虚拟摇杆的中心位置"
            } else if (button == null) {
                "请在屏幕上点击右摇杆的中心位置"
            } else {
                "请在屏幕上点击「${button.labelFor(container.settingsRepository.current.gamepadMode)}」对应的按钮位置"
            }
        } else {
            "无法进入坐标拾取，请确认无障碍服务已连接且未在录制中"
        }
    }

    // --------------------------------------------------------- 权限

    fun refreshPermissions() = permissions?.refresh()

    fun openAccessibilitySettings() = permissions?.openAccessibilitySettings()

    fun requestOverlayPermission() = permissions?.requestOverlayPermission()

    fun requestNotificationPermission() = permissions?.requestNotificationPermission()


    fun requestIgnoreBatteryOptimisations() = permissions?.requestIgnoreBatteryOptimisations()

    fun openAppDetailsSettings() = permissions?.openAppDetailsSettings()

    fun oemGuidance(): List<OemGuidanceStep> = permissions?.oemGuidance().orEmpty()

    /** Root 桥（模块）是否就绪。 */
    val rootInputAvailable: Boolean get() = container.rootInputBackend?.isAvailable == true

    /** 是否已安装 Root Bridge 模块（文件桥）。 */
    val rootModuleInstalled: Boolean get() = container.rootInputBackend?.moduleInstalled == true

    /** 重新探测 root 授权（用户刚在 root 管理器里点过允许时用）。 */
    fun refreshRootState() = container.rootInputBackend?.probe()

    /** 把内置 Root Bridge 模块导出到下载目录，返回展示路径（失败为 null）。 */
    fun exportRootModule(): String? = container.rootInputBackend?.exportModule()

    fun setInputMode(mode: InputMode) = container.settingsRepository.setInputMode(mode)

    fun setSkipOemGuidance(skip: Boolean) = container.settingsRepository.setSkipOemGuidance(skip)

    fun resetSettings() {
        container.settingsRepository.reset()
        _message.value = "已恢复默认设置"
    }

    fun dismissMessage() {
        _message.value = null
    }
}
