package cn.helilab.autorunner.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import cn.helilab.autorunner.AppGraph
import cn.helilab.autorunner.AutoRunnerApplication
import cn.helilab.autorunner.accessibility.AccessibilityServiceHolder
import cn.helilab.autorunner.accessibility.AndroidRecordingController
import cn.helilab.autorunner.notification.ExecutionFocusIsland
import cn.helilab.autorunner.notification.FocusNotification
import cn.helilab.autorunner.notification.FocusNotificationFeed
import cn.helilab.autorunner.notification.IdleNotification
import cn.helilab.autorunner.notification.IdleNotificationFeed
import com.autorunner.core.execution.ComboExecution
import com.autorunner.core.execution.ComboScriptSlot
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.FailureStrategy
import kotlinx.coroutines.launch

/**
 * 仅供调试的命令通道。
 *
 * MIUI（以及若干其他 ROM）会以 `SecurityException: Injecting input events requires INJECT_EVENTS`
 * 拒绝 `adb shell input tap`，导致无法从工作站手动验证 UI。本接收器暴露与 UI
 * 触发的相同操作，使 debug 构建可以从 `adb` 全流程驱动：
 *
 * ```bash
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command state
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command run
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command run_combo --es scriptA a --es scriptB b --el intervalA 840000 --el intervalB 3000000 --el delayB 600000
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command record_start
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command record_stop
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command overlay_show
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command black_screen --ez enabled true
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command hang_state
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command fake_notification
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command fake_focus
 * ```
 *
 * 该接收器通过 `debugReceiverEnabled` 清单占位符在 release 构建中**禁用**，
 * 并且在应用不可调试时额外拒绝运行。
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isDebuggable(context)) {
            Log.w(AutoRunnerApplication.TAG, "debug command ignored: not a debuggable build")
            return
        }

        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val container = AppGraph.requireContainer()
        Log.i(AutoRunnerApplication.TAG, "debug command: $command")

        when (command) {
            "state" -> dumpState(context)

            "run" -> {
                val requested = intent.getStringExtra(EXTRA_SCRIPT_ID)
                val config = ExecutionConfig(
                    mode = ExecutionMode.ONCE,
                    failureStrategy = FailureStrategy.SKIP_ACTION,
                )
                container.scope.launch {
                    val scriptId = requested
                        ?: container.scriptRepository.refresh().firstOrNull()?.id
                    if (scriptId == null) {
                        Log.w(AutoRunnerApplication.TAG, "debug run: no script stored")
                        return@launch
                    }
                    // 镜像脚本库的 ▶ 按钮：走 view model，以便套用
                    // 脚本自身的执行配置块。
                    val execution = com.autorunner.ui.viewmodel.ExecutionViewModel(container)
                    execution.refreshScripts()
                    execution.start(scriptId)
                    // start() 会异步解析脚本；稍等片刻，让日志
                    // 打出实际正在运行的计划。
                    kotlinx.coroutines.delay(400)
                    Log.i(
                        AutoRunnerApplication.TAG,
                        "debug run: connected=${container.accessibilityController.isConnected}" +
                            " plan=${execution.buildConfig().describe()}" +
                            " progress=${execution.progress.value.loopLabel}" +
                            " message=${execution.message.value}",
                    )
                }
            }

            "run_combo" -> {
                // 组合运行的调试入口：绕过悬浮球直接启动两段脚本，便于在真机上
                // 验证并行调度与首轮延迟。间隔 / 延迟单位为毫秒。
                val intervalA = intent.getLongExtra("intervalA", 14 * 60_000L)
                val intervalB = intent.getLongExtra("intervalB", 50 * 60_000L)
                val delayB = intent.getLongExtra("delayB", 0L)
                val repeatA = intent.getIntExtra("repeatA", 0)
                val repeatB = intent.getIntExtra("repeatB", 0)
                val idA = intent.getStringExtra("scriptA")
                val idB = intent.getStringExtra("scriptB")
                container.scope.launch {
                    val records = container.scriptRepository.refresh()
                    val recordA = records.firstOrNull { it.id == idA } ?: records.getOrNull(0)
                    val recordB = records.firstOrNull { it.id == idB } ?: records.getOrNull(1)
                    if (recordA == null || recordB == null) {
                        Log.w(AutoRunnerApplication.TAG, "debug run_combo: need two stored scripts")
                        return@launch
                    }
                    val configA = ExecutionConfig(
                        mode = ExecutionMode.REPEAT,
                        repeatCount = repeatA,
                        intervalMs = intervalA,
                        failureStrategy = FailureStrategy.SKIP_ACTION,
                    )
                    val configB = configA.copy(
                        repeatCount = repeatB,
                        intervalMs = intervalB,
                        startDelayMs = delayB,
                    )
                    val started = container.executionController.startCombo(
                        ComboExecution(
                            slotA = ComboScriptSlot(ComboSlotRole.A, recordA.id, recordA.script, configA),
                            slotB = ComboScriptSlot(ComboSlotRole.B, recordB.id, recordB.script, configB),
                        ),
                    )
                    Log.i(
                        AutoRunnerApplication.TAG,
                        "debug run_combo -> started=$started A='${recordA.name}'@${intervalA}ms " +
                            "B='${recordB.name}'@${intervalB}ms delayB=${delayB}ms",
                    )
                }
            }

            "focus_permission" -> {
                val extras = Bundle().apply { putString("package", context.packageName) }
                val result = runCatching {
                    context.contentResolver.call(
                        Uri.parse("content://miui.statusbar.notification.public"),
                        "canShowFocus",
                        null,
                        extras,
                    )
                }.onFailure { Log.w(AutoRunnerApplication.TAG, "focus_permission call failed", it) }.getOrNull()
                val appInfo = runCatching {
                    context.packageManager.getApplicationInfo(
                        context.packageName,
                        PackageManager.GET_META_DATA,
                    )
                }.getOrNull()
                val xmsAppId = appInfo?.metaData?.getString(ExecutionFocusIsland.XMS_APP_ID_META)
                val xmsDebug = appInfo?.metaData?.getBoolean("com.xiaomi.xms.BUILD_TYPE_DEBUG")
                Log.i(
                    AutoRunnerApplication.TAG,
                    "debug focus_permission: canShowFocus=${result?.getBoolean("canShowFocus")} " +
                        "protocol=${ExecutionFocusIsland.focusProtocol(context)} " +
                        "xmsAppId=${xmsAppId?.ifBlank { "<empty>" } ?: "<missing>"} " +
                        "xmsDebug=$xmsDebug " +
                        "supportsIsland=${ExecutionFocusIsland.supportsIsland(context)} " +
                        "rootUnlock=${ExecutionFocusIsland.hasRootFocusUnlock()} " +
                        "focusNotifs=${Settings.Secure.getString(context.contentResolver, "focus_notifs")} " +
                        "updatable=${Settings.Secure.getString(context.contentResolver, "updatable_focus_notifs")}",
                )
            }

            "focus_payload" -> {
                val container = AppGraph.requireContainer()
                val combo = container.executionController.comboProgress.value
                val progress = container.executionController.progress.value
                val payload = when {
                    combo != null && combo.isActive ->
                        ExecutionFocusIsland.buildPayload(progress, combo)

                    progress.state.isActive ->
                        ExecutionFocusIsland.buildSinglePayload(progress)

                    else -> null
                }
                if (payload == null) {
                    Log.w(
                        AutoRunnerApplication.TAG,
                        "debug focus_payload: nothing running " +
                            "(protocol=${ExecutionFocusIsland.focusProtocol(context)}, " +
                            "island=${ExecutionFocusIsland.supportsIsland(context)})",
                    )
                } else {
                    Log.i(AutoRunnerApplication.TAG, "debug focus_payload: $payload")
                }
            }

            "tap" -> {
                val x = intent.getStringExtra("x")?.toFloatOrNull() ?: 0f
                val y = intent.getStringExtra("y")?.toFloatOrNull() ?: 0f
                container.scope.launch {
                    val result = container.accessibilityController.perform(
                        com.autorunner.core.model.TapStep(x = x, y = y),
                    )
                    Log.i(AutoRunnerApplication.TAG, "debug tap ($x, $y) -> $result")
                }
            }

            "pick_action" -> {
                val started = container.recordingController.pickAction { step ->
                    Log.i(AutoRunnerApplication.TAG, "pick_action captured: ${step.typeName} ${step.label}")
                }
                Log.i(AutoRunnerApplication.TAG, "debug pick_action -> started=$started")
            }

            "import" -> {
                val started = AppGraph.transfer?.pickScriptFile() ?: false
                Log.i(AutoRunnerApplication.TAG, "debug import picker requested (launched=$started)")
            }

            "pause" -> container.executionController.togglePause()

            "stop" -> container.executionController.stop()

            "record_start" -> {
                (container.recordingController as? AndroidRecordingController)?.let { controller ->
                    controller.verboseLogging = intent.getBooleanExtra("trace", false)
                }
                val started = container.recordingController.start()
                Log.i(
                    AutoRunnerApplication.TAG,
                    "debug record_start -> $started",
                )
            }

            "record_stop" -> {
                val result = container.recordingController.stop()
                Log.i(
                    AutoRunnerApplication.TAG,
                    "debug record_stop -> success=${result.success} " +
                        "steps=${result.script?.flow?.size} message=${result.message}",
                )
            }

            "overlay_show" -> {
                val shown = container.overlayManager.show()
                Log.i(AutoRunnerApplication.TAG, "debug overlay_show -> $shown")
            }

            "overlay_hide" -> container.overlayManager.hide()

            "overlay_expand" -> container.overlayManager.expand()

            "overlay_collapse" -> container.overlayManager.collapse()

            "black_screen" -> {
                val enabled = intent.getBooleanExtra("enabled", true)
                cn.helilab.autorunner.overlay.AutoRunnerOverlayService
                    .setBlackScreen(context, enabled)
                Log.i(AutoRunnerApplication.TAG, "debug black_screen -> $enabled")
            }

            "hang_state" -> {
                val settings = container.settingsRepository.current
                val listenerGranted = runCatching {
                    NotificationManagerCompat.getEnabledListenerPackages(context)
                        .contains(context.packageName)
                }.getOrDefault(false)
                // Android 15+ 未授予该 appop 时，短信 / 验证码等敏感通知的正文会被系统清空。
                // unsafeCheckOpNoThrow 是 API 29 才有的方法，旧系统上直接返回 null。
                val sensitiveAllowed = if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.Q
                ) {
                    runCatching {
                        context.getSystemService(android.app.AppOpsManager::class.java)
                            ?.unsafeCheckOpNoThrow(
                                "android:receive_sensitive_notifications",
                                android.os.Process.myUid(),
                                context.packageName,
                            ) == android.app.AppOpsManager.MODE_ALLOWED
                    }.getOrNull()
                } else {
                    null
                }
                val accessibility = AccessibilityServiceHolder.current()
                    ?.debugHangGuardState()
                    ?: "accessibility=disconnected"
                Log.i(
                    AutoRunnerApplication.TAG,
                    "HANG_STATE listenerGranted=$listenerGranted " +
                        "sensitiveNotifications=$sensitiveAllowed " +
                        "notificationsObserved=${IdleNotificationFeed.isObserved} " +
                        "showNotifications=${settings.idleNotificationDisplay} " +
                        "focusObserved=${FocusNotificationFeed.isObserved} " +
                        "showFocus=${settings.idleFocusDisplay} " +
                        "focusCount=${FocusNotificationFeed.snapshot().size} " +
                        "dialogGuard=${settings.hangDialogGuard} $accessibility",
                )
            }

            "fake_notification" -> {
                IdleNotificationFeed.publish(
                    IdleNotification(
                        packageName = "com.example.debug",
                        appLabel = "测试应用",
                        title = "挂机通知测试",
                        text = "这是一条用于验证黑屏挂机通知卡片的测试通知",
                        postedAtMs = SystemClock.elapsedRealtime(),
                    ),
                )
                Log.i(
                    AutoRunnerApplication.TAG,
                    "fake_notification -> observed=${IdleNotificationFeed.isObserved} " +
                        "（需要黑屏挂机层可见）",
                )
            }

            "fake_focus" -> {
                // 不依赖真实外卖 / 打车订单：直接往焦点通知集合里塞一条岛状态，
                // 用于验证黑屏面板的排版、进度条与自动消失（收到 removed 时清理）。
                FocusNotificationFeed.upsert(
                    FocusNotification(
                        key = "debug|focus",
                        packageName = "com.example.debug",
                        appLabel = intent.getStringExtra("app").orEmpty().ifEmpty { "美团外卖" },
                        title = intent.getStringExtra("title").orEmpty().ifEmpty { "预计 21:07 送达" },
                        detail = intent.getStringExtra("detail").orEmpty()
                            .ifEmpty { "商家已出餐，骑手已到店 · 川味特色面馆" },
                        progress = intent.getStringExtra("progress")?.toIntOrNull() ?: 50,
                        accentColor = android.graphics.Color.parseColor("#FFCC33"),
                        postedAtMs = SystemClock.elapsedRealtime(),
                    ),
                )
                Log.i(
                    AutoRunnerApplication.TAG,
                    "fake_focus -> observed=${FocusNotificationFeed.isObserved} " +
                        "count=${FocusNotificationFeed.snapshot().size}（需要黑屏挂机层可见）",
                )
            }

            "clear_focus" -> {
                // 只清掉 fake_focus 注入的那条，真实岛状态保持不动。
                FocusNotificationFeed.remove("debug|focus")
                Log.i(
                    AutoRunnerApplication.TAG,
                    "clear_focus -> count=${FocusNotificationFeed.snapshot().size}",
                )
            }

            "run_repeat" -> {
                val requested = intent.getStringExtra(EXTRA_SCRIPT_ID)
                val count = intent.getStringExtra("count")?.toIntOrNull() ?: 3
                val interval = intent.getStringExtra("interval")?.toLongOrNull() ?: 1000L
                val config = ExecutionConfig(
                    mode = ExecutionMode.REPEAT,
                    repeatCount = count,
                    intervalMs = interval,
                    failureStrategy = FailureStrategy.SKIP_ACTION,
                    reconnectTimeoutMs = 3_000L,
                )
                container.scope.launch {
                    val scriptId = requested
                        ?: container.scriptRepository.refresh().firstOrNull()?.id
                    if (scriptId == null) {
                        Log.w(AutoRunnerApplication.TAG, "debug run_repeat: no script stored")
                        return@launch
                    }
                    val record = container.scriptRepository.load(scriptId) ?: return@launch
                    Log.i(
                        AutoRunnerApplication.TAG,
                        "debug run_repeat: '${record.name}' x$count interval=${interval}ms",
                    )
                    val report = container.executor.execute(record.script, config, scriptId)
                    Log.i(AutoRunnerApplication.TAG, "debug run_repeat finished: ${report.summary}")
                }
            }

            "flush" -> container.scope.launch {
                val scripts = container.scriptRepository.refresh()
                Log.i(AutoRunnerApplication.TAG, "debug flush -> ${scripts.size} scripts")
            }

            else -> Log.w(AutoRunnerApplication.TAG, "unknown debug command '$command'")
        }
    }

    /** 打印判断各服务是否正确接线所需的全部信息。 */
    private fun dumpState(context: Context) {
        val container = AppGraph.requireContainer()
        Log.i(
            AutoRunnerApplication.TAG,
            buildString {
                append("STATE accessibility=")
                append(container.accessibilityController.javaClass.simpleName)
                append(" connected=")
                append(container.accessibilityController.isConnected)
                append(" bound=")
                append(AccessibilityServiceHolder.isConnected)
                append(" metrics=")
                append(container.accessibilityController.refreshScreenMetrics())
                append(" recording=")
                append(container.recordingController.javaClass.simpleName)
                append('/')
                append(container.recordingController.status.value)
                append(" overlay=")
                append(container.overlayManager.javaClass.simpleName)
                append(" granted=")
                append(container.overlayManager.isPermissionGranted())
                append(" visible=")
                append(container.overlayManager.state.value.visible)
                append(" scripts=")
                append(container.scriptRepository.scripts.value.size)
                append(" execState=")
                append(container.executionController.state.value)
                append(" progress=")
                append(container.executionController.progress.value.loopLabel)
                append(" focusProtocol=")
                append(ExecutionFocusIsland.focusProtocol(context))
                append(" island=")
                append(ExecutionFocusIsland.supportsIsland(context))
                append(" combo=")
                append(
                    container.executionController.comboProgress.value?.let { combo ->
                        combo.slots.joinToString("/") {
                            "${it.role.name}:${it.progress.completedLoops}/${it.progress.totalLoops}"
                        }
                    } ?: "none",
                )
                append(" gamepad=")
                append(container.gamepadGateway?.javaClass?.simpleName ?: "none")
            },
        )
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    companion object {
        const val ACTION = "cn.helilab.autorunner.DEBUG_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SCRIPT_ID = "scriptId"
    }
}
