package cn.helilab.autorunner.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import cn.helilab.autorunner.AppGraph
import cn.helilab.autorunner.AutoRunnerApplication
import cn.helilab.autorunner.accessibility.AccessibilityServiceHolder
import cn.helilab.autorunner.accessibility.AndroidRecordingController
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.FailureStrategy
import kotlinx.coroutines.launch

/**
 * Debug only command channel.
 *
 * MIUI (and several other ROMs) reject `adb shell input tap` with
 * `SecurityException: Injecting input events requires INJECT_EVENTS`, which makes
 * manual UI verification impossible from a workstation. This receiver exposes the
 * same operations the UI triggers so a debug build can be driven end to end from
 * `adb`:
 *
 * ```bash
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command state
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command run
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command record_start
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command record_stop
 * adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND --es command overlay_show
 * ```
 *
 * The receiver is **disabled in release builds** through the
 * `debugReceiverEnabled` manifest placeholder and additionally refuses to run
 * unless the application is debuggable.
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
            "state" -> dumpState()

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
                    // Mirrors the library's ▶ button: go through the view model so the
                    // script's own execution block is applied.
                    val execution = com.autorunner.ui.viewmodel.ExecutionViewModel(container)
                    execution.refreshScripts()
                    execution.start(scriptId)
                    // start() resolves the script asynchronously; wait a moment so the
                    // log shows the plan that is actually running.
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

    /** Prints everything needed to judge whether the services are wired up. */
    private fun dumpState() {
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
