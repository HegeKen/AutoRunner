package cn.helilab.autorunner.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.autorunner.core.BuildInfo
import com.autorunner.core.di.AppContainer
import com.autorunner.core.execution.ComboSlotProgress
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionReport
import com.autorunner.core.model.ExecutionState
import com.autorunner.ui.platform.WebStatusInfo
import com.autorunner.ui.platform.WebStatusProvider
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 只读网页状态页的局域网 HTTP 服务。
 *
 * 设计目标很小：不引入第三方 HTTP 框架，用 [ServerSocket] 提供两个 GET 接口：
 *
 * * `/`：内嵌的状态页（`assets/web/status.html`），空闲时每 [STATUS_LONG_POLL_MS] 校准一次，
 *   状态变化时立即更新；
 * * `/api/status`：运行状态、脚本库与运行环境的 JSON 快照；带 `wait=1&since=<版本>`
 *   时变成长轮询，状态版本变化会立刻返回，否则最多等待 [STATUS_LONG_POLL_MS]。
 *   快照里的 `longPollMs` 把这个上限告诉客户端，客户端据此设置读超时。
 *
 * 服务只读，不暴露任何控制接口；是否开启由
 * [com.autorunner.core.model.AppSettings.webStatusEnabled] 决定，本类只观察
 * 设置变化并启停监听。监听绑定 `0.0.0.0` 以便同一局域网内的其他设备访问。
 */
class StatusWebServer(
    private val context: Context,
    private val container: AppContainer,
) : WebStatusProvider {

    private val _info = MutableStateFlow(WebStatusInfo())

    override val info: StateFlow<WebStatusInfo> = _info.asStateFlow()

    private val startedAtElapsedMs = SystemClock.elapsedRealtime()

    /**
     * 状态版本与等待锁。
     *
     * 客户端靠本地时钟推进时长，不再需要 2 秒轮询；`/api/status?wait=1` 会挂起
     * 到版本变化或超时。脚本开始 / 暂停 / 停止、运行结束、悬浮球就绪态变化都会
     * 立刻提升版本并唤醒等待中的请求，其余情况每分钟自然醒来校准一次。
     */
    @Volatile
    private var stateVersion: Long = 1L

    private val changeLock = ReentrantLock()
    private val changed = changeLock.newCondition()

    private val clients = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "autorunner-web-client").apply { isDaemon = true }
    }

    @Volatile
    private var lastReport: ExecutionReport? = null

    /**
     * 最近一次观察到的单脚本进度快照与收到它的挂钟时间。
     *
     * 执行器对长间隔 / 首轮等待按分钟上报，页面若直接显示快照里的
     * `elapsedMs`，两次上报之间会一直停在 `00:00`。记录观察时间后就能按挂钟
     * 把这段空档补进去，客户端拿到锚点后也能继续本地推进。
     * 组合运行有 `updatedAtEpochMs`，走 [ComboSlotProgress] 自己的插值，不用这里。
     */
    @Volatile
    private var observedProgress: ExecutionProgress = ExecutionProgress()

    @Volatile
    private var observedProgressAtMs: Long = 0L

    @Volatile
    private var pageHtml: String? = null

    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    /** 运行时定期刷新局域网地址，Wi-Fi 在服务开启后才连上也能显示。 */
    private var addressJob: Job? = null

    /** 读取页面资源并开始观察设置开关；由 [cn.helilab.autorunner.AppGraph] 调用一次。 */
    fun attach() {
        pageHtml = runCatching {
            context.assets.open(PAGE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onFailure { Log.w(TAG, "status page asset unavailable", it) }.getOrNull()

        // 运行结束报告只存在于内存里，服务自己留一份供页面展示。
        container.scope.launch {
            container.executionController.reports.collect {
                lastReport = it
                bumpVersion()
            }
        }
        // 记住快照的到达时间，供单脚本的运行时长 / 间隔做挂钟插值。
        container.scope.launch {
            container.executionController.progress.collect {
                observedProgress = it
                observedProgressAtMs = System.currentTimeMillis()
            }
        }
        // 生命周期、悬浮球就绪态与组合状态变化时立即通知长轮询客户端。
        container.scope.launch {
            container.executionController.state.collect { bumpVersion() }
        }
        container.scope.launch {
            container.executionController.armed.collect { bumpVersion() }
        }
        container.scope.launch {
            container.executionController.comboProgress
                .map { it?.state }
                .distinctUntilChanged()
                .collect { bumpVersion() }
        }
        // 预热脚本库，避免刚打开页面时列表为空。
        container.scope.launch { runCatching { container.scriptRepository.refresh() } }

        container.scope.launch {
            container.settingsRepository.settings
                .map { it.webStatusEnabled }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) start() else stop() }
        }
    }

    @Synchronized
    private fun start() {
        if (serverSocket != null) return
        val socket = bind()
        if (socket == null) {
            _info.value = WebStatusInfo(
                running = false,
                port = DEFAULT_PORT,
                error = "端口 $DEFAULT_PORT-$PORT_END 均被占用",
            )
            Log.w(TAG, "no free port in $DEFAULT_PORT-$PORT_END")
            return
        }
        serverSocket = socket
        _info.value = WebStatusInfo(
            running = true,
            port = socket.localPort,
            addresses = lanAddresses(),
        )
        acceptThread = thread(name = "autorunner-web", isDaemon = true) { acceptLoop(socket) }
        addressJob = container.scope.launch {
            while (true) {
                delay(ADDRESS_REFRESH_MS)
                val addresses = lanAddresses()
                if (addresses != _info.value.addresses) {
                    _info.value = _info.value.copy(addresses = addresses)
                }
            }
        }
        Log.i(TAG, "status page listening on ${socket.localPort}, addresses=${_info.value.addresses}")
    }

    @Synchronized
    private fun stop() {
        val socket = serverSocket
        serverSocket = null
        if (socket != null) {
            runCatching { socket.close() }
            acceptThread?.interrupt()
        }
        acceptThread = null
        addressJob?.cancel()
        addressJob = null
        _info.value = WebStatusInfo(running = false, port = DEFAULT_PORT)
        // 先更新 info 再唤醒，确保长轮询拿到的是「已停止」的最终快照。
        changeLock.withLock {
            stateVersion++
            changed.signalAll()
        }
        Log.i(TAG, "status page stopped")
    }

    private fun bind(): ServerSocket? {
        for (port in DEFAULT_PORT..PORT_END) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            } catch (_: Exception) {
                // 端口被占用时继续尝试下一个。
            }
        }
        return null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                // 监听套接字被关掉（开关关闭）才是真的该退出；其余瞬时错误如果直接
                // break，服务会变成「设置页显示运行中、实际不再接受连接」的半死状态。
                if (server.isClosed) break
                Log.w(TAG, "accept failed: ${e.message}")
                runCatching { Thread.sleep(ACCEPT_RETRY_DELAY_MS) }
                continue
            }
            runCatching {
                clients.execute {
                    runCatching { handle(client) }.onFailure {
                        Log.w(TAG, "request failed: ${it.message}")
                    }
                }
            }.onFailure {
                Log.w(TAG, "dispatch failed: ${it.message}")
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            // 丢弃请求头直到空行；本服务不读取请求体。
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            if (method != "GET") {
                writeResponse(socket, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed")
                return
            }

            when (path) {
                "/", "/index.html" -> writeResponse(
                    socket,
                    200,
                    "OK",
                    "text/html; charset=utf-8",
                    pageHtml ?: FALLBACK_PAGE,
                )

                "/api/status" -> {
                    val since = queryParam(query, "since")?.toLongOrNull()
                    if (queryParam(query, "wait") == "1" && since != null && since > 0L) {
                        awaitChange(since, STATUS_LONG_POLL_MS)
                    }
                    writeResponse(
                        socket,
                        200,
                        "OK",
                        "application/json; charset=utf-8",
                        buildStatusJson(),
                    )
                }

                "/favicon.ico" -> writeResponse(socket, 204, "No Content", "text/plain", "")

                else -> writeResponse(socket, 404, "Not Found", "text/plain; charset=utf-8", "Not Found")
            }
        }
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        reason: String,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)

        BufferedOutputStream(socket.getOutputStream()).use { out ->
            out.write(header)
            out.write(bytes)
            out.flush()
        }
    }

    // ------------------------------------------------------------------ 状态快照

    private fun buildStatusJson(): String {
        val settings = container.settingsRepository.current
        val controller = container.executionController
        val state = controller.state.value
        val progress = controller.progress.value
        val combo = controller.comboProgress.value
        val overlay = container.overlayManager.state.value
        val scripts = container.scriptRepository.scripts.value
        val metrics = container.accessibilityController.screenMetrics
        val battery = batteryState()
        val nowMs = System.currentTimeMillis()
        val version = stateVersion

        // 执行器只在动作边界 / 每分钟上报进度；这里按挂钟补齐两次快照之间的时长，
        // 让网页上的「已运行 / 间隔 / 等待首轮」按秒走动，而不是停在 00:00。
        val intervalSlot = combo?.slots?.firstOrNull { it.isInLoopInterval }
        val waitingSlot = combo?.slots?.firstOrNull { it.isWaitingForFirstRound }
        val liveElapsedMs = combo?.liveElapsedMs(nowMs)
            ?: (progress.elapsedMs + liveExtraFor(progress, nowMs))
        val liveIntervalElapsedMs = when {
            intervalSlot != null -> intervalSlot.liveIntervalElapsedMs(nowMs)
            progress.intervalTotalMs > 0L -> (
                progress.intervalElapsedMs + liveExtraFor(progress, nowMs)
                ).coerceAtMost(progress.intervalTotalMs)

            else -> 0L
        }
        val liveStartDelayElapsedMs = when {
            waitingSlot != null -> waitingSlot.liveStartDelayElapsedMs(nowMs)
            progress.startDelayTotalMs > 0L -> (
                progress.startDelayElapsedMs + liveExtraFor(progress, nowMs)
                ).coerceAtMost(progress.startDelayTotalMs)

            else -> 0L
        }

        val app = jsonObject(
            jsonField("versionName", jsonString(BuildInfo.VERSION_NAME)),
            jsonField("versionCode", BuildInfo.VERSION_CODE.toString()),
            jsonField("packageName", jsonString(context.packageName)),
            jsonField(
                "device",
                jsonString(
                    listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                ),
            ),
            jsonField("android", jsonString(Build.VERSION.RELEASE)),
            jsonField("sdk", Build.VERSION.SDK_INT.toString()),
            // 网页跟随 App 的外观设置：SYSTEM 交给 prefers-color-scheme。
            jsonField("themeMode", jsonString(settings.themeMode.name)),
        )

        val execution = jsonObject(
            jsonField("state", jsonString(state.name)),
            jsonField("stateLabel", jsonString(state.displayLabel())),
            jsonField("scriptId", jsonString(controller.activeScriptId)),
            jsonField("scriptName", jsonString(controller.activeScriptName.ifBlank { progress.scriptName })),
            jsonField("armed", controller.armed.value.toString()),
            jsonField("completedLoops", progress.completedLoops.toString()),
            jsonField("totalLoops", progress.totalLoops.jsonLoops()),
            jsonField("infinite", progress.isInfinite.toString()),
            jsonField("loopFraction", progress.loopFraction?.toString() ?: "null"),
            jsonField("currentActionIndex", progress.currentActionIndex.toString()),
            jsonField("totalActions", progress.totalActions.toString()),
            jsonField("currentActionLabel", jsonString(progress.currentActionLabel)),
            jsonField("elapsedMs", liveElapsedMs.toString()),
            jsonField("elapsedLabel", jsonString(ExecutionProgress.formatDuration(liveElapsedMs))),
            jsonField("skippedActions", progress.skippedActions.toString()),
            jsonField("message", jsonString(progress.message)),
            jsonField("intervalElapsedMs", liveIntervalElapsedMs.toString()),
            jsonField("intervalTotalMs", progress.intervalTotalMs.toString()),
            jsonField("startDelayElapsedMs", liveStartDelayElapsedMs.toString()),
            jsonField("startDelayTotalMs", progress.startDelayTotalMs.toString()),
        )

        val comboJson = combo?.let { snapshot ->
            jsonObject(
                jsonField("state", jsonString(snapshot.state.name)),
                jsonField("stateLabel", jsonString(snapshot.state.displayLabel())),
                jsonField("elapsedMs", liveElapsedMs.toString()),
                jsonField("elapsedLabel", jsonString(ExecutionProgress.formatDuration(liveElapsedMs))),
                jsonField(
                    "slots",
                    jsonArray(
                        snapshot.slots.map { slot ->
                            val live = slot.liveView(nowMs)
                            jsonObject(
                                jsonField("role", jsonString(slot.role.name)),
                                jsonField("roleLabel", jsonString(slot.role.displayName)),
                                jsonField("scriptName", jsonString(slot.scriptName)),
                                jsonField("state", jsonString(slot.state.name)),
                                jsonField("stateLabel", jsonString(slot.state.displayLabel())),
                                jsonField("completedLoops", slot.progress.completedLoops.toString()),
                                jsonField("totalLoops", slot.progress.totalLoops.jsonLoops()),
                                jsonField("currentActionIndex", slot.progress.currentActionIndex.toString()),
                                jsonField("totalActions", slot.progress.totalActions.toString()),
                                jsonField("elapsedMs", live.elapsedMs.toString()),
                                jsonField("elapsedLabel", jsonString(ExecutionProgress.formatDuration(live.elapsedMs))),
                                jsonField("loopElapsedMs", slot.liveLoopElapsedMs(nowMs).toString()),
                                jsonField("intervalElapsedMs", live.intervalElapsedMs.toString()),
                                jsonField("intervalTotalMs", slot.progress.intervalTotalMs.toString()),
                                jsonField("startDelayElapsedMs", live.startDelayElapsedMs.toString()),
                                jsonField("startDelayTotalMs", slot.progress.startDelayTotalMs.toString()),
                                jsonField("description", jsonString(live.description)),
                                jsonField("cycleFraction", slot.cycleFractionAt(nowMs).toString()),
                            )
                        },
                    ),
                ),
            )
        } ?: "null"

        val reportJson = lastReport?.let { report ->
            jsonObject(
                jsonField("scriptName", jsonString(report.scriptName)),
                jsonField("outcome", jsonString(report.outcome.name)),
                jsonField("summary", jsonString(report.summary)),
                jsonField("completedLoops", report.completedLoops.toString()),
                jsonField("totalLoops", report.totalLoops.jsonLoops()),
                jsonField("elapsedLabel", jsonString(ExecutionProgress.formatDuration(report.elapsedMs))),
                jsonField("failureMessage", jsonString(report.failureMessage)),
            )
        } ?: "null"

        val scriptsJson = jsonArray(
            scripts.map { record ->
                jsonObject(
                    jsonField("id", jsonString(record.id)),
                    jsonField("name", jsonString(record.name)),
                    jsonField("description", jsonString(record.description)),
                    jsonField("steps", record.stepCount.toString()),
                    jsonField("mode", jsonString(record.execution.mode.name)),
                    jsonField("repeatCount", record.execution.repeatCount.toString()),
                    jsonField("totalLoops", record.execution.totalLoops.jsonLoops()),
                    jsonField("intervalMs", record.execution.intervalMs.toString()),
                    jsonField("usesGamepad", record.script.usesGamepad.toString()),
                    jsonField("updatedAt", jsonString(timeLabel(record.updatedAtMs))),
                    jsonField("sizeBytes", record.sizeBytes.toString()),
                )
            },
        )

        val environment = jsonObject(
            jsonField("inputMode", jsonString(settings.inputMode.name)),
            jsonField("inputModeLabel", jsonString(settings.inputMode.displayName)),
            jsonField("accessibilityConnected", container.accessibilityController.isConnected.toString()),
            jsonField("overlayPermission", overlay.permissionGranted.toString()),
            jsonField("overlayVisible", overlay.visible.toString()),
            jsonField("overlayError", jsonString(overlay.error)),
            jsonField("rootAvailable", (container.rootInputBackend?.isAvailable == true).toString()),
            jsonField("rootModuleInstalled", (container.rootInputBackend?.moduleInstalled == true).toString()),
            jsonField("gamepadEnabled", settings.gamepadEnabled.toString()),
            jsonField("notificationListener", notificationListenerGranted().toString()),
            jsonField("keepScreenOn", settings.keepScreenOnWhileRunning.toString()),
            jsonField("executionNotification", settings.executionNotification.toString()),
            jsonField("executionReminder", settings.executionReminder.toString()),
            jsonField("batteryPercent", battery.percent?.toString() ?: "null"),
            jsonField("batteryCharging", battery.charging?.toString() ?: "null"),
            jsonField("screenWidth", metrics.widthPx.toString()),
            jsonField("screenHeight", metrics.heightPx.toString()),
            jsonField("screenDensity", metrics.density.toString()),
        )

        val network = _info.value

        return jsonObject(
            jsonField("serverTimeMs", nowMs.toString()),
            jsonField("serverTime", jsonString(timeLabel(nowMs))),
            jsonField("serverUptimeMs", (SystemClock.elapsedRealtime() - startedAtElapsedMs).toString()),
            jsonField("stateVersion", version.toString()),
            // 长轮询挂起上限：客户端据此把读超时设得比它长一点，既不会误判超时，
            // 又能在连接被中间设备丢掉后尽快换一条新的。
            jsonField("longPollMs", STATUS_LONG_POLL_MS.toString()),
            jsonField("app", app),
            jsonField(
                "network",
                jsonObject(
                    jsonField("running", network.running.toString()),
                    jsonField("port", network.port.toString()),
                    jsonField("addresses", jsonArray(network.addresses.map(::jsonString))),
                ),
            ),
            jsonField("execution", execution),
            jsonField("combo", comboJson),
            jsonField("lastReport", reportJson),
            jsonField("scripts", scriptsJson),
            jsonField("environment", environment),
        )
    }

    private fun notificationListenerGranted(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    private fun batteryState(): BatteryState {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return BatteryState(null, null)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryState(percent, charging)
    }

    private fun lanAddresses(): List<String> {
        val found = mutableListOf<Pair<String, String>>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { network ->
                if (!network.isUp || network.isLoopback) return@forEach
                network.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        address.hostAddress?.let { found += network.name to it }
                    }
                }
            }
        }
        return found
            .sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.startsWith("swlan") -> 2
                    else -> 3
                }
            }
            .map { it.second }
            .distinct()
    }

    private data class BatteryState(val percent: Int?, val charging: Boolean?)

    // ------------------------------------------------------- 进度快照的挂钟插值

    private fun liveExtra(state: ExecutionState, updatedAtEpochMs: Long, nowMs: Long): Long =
        if (state == ExecutionState.RUNNING && updatedAtEpochMs > 0L) {
            (nowMs - updatedAtEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }

    /**
     * 单脚本快照的插值时长。
     *
     * 只有当前快照正是我们收到的那一份时才补时长，避免新 run 的第一帧
     * 借用上一份快照的观察时间。
     */
    private fun liveExtraFor(progress: ExecutionProgress, nowMs: Long): Long =
        liveExtra(
            state = progress.state,
            updatedAtEpochMs = if (progress == observedProgress) observedProgressAtMs else 0L,
            nowMs = nowMs,
        )

    /**
     * 当前这一轮已运行的时长的实时值（组合槽位用）。
     *
     * 轮内（正在派发动作）按挂钟推进；轮间等待 / 首轮等待时停在上一轮的时长上，
     * 不跟着总运行时长一起涨 —— 页面下方要展示的是「这一段本轮跑了多久」。
     */
    private fun ComboSlotProgress.liveLoopElapsedMs(nowMs: Long): Long =
        if (state == ExecutionState.RUNNING && progress.currentActionIndex >= 0 &&
            !isWaitingForFirstRound && !isInLoopInterval
        ) {
            progress.loopElapsedMs + liveExtra(state, updatedAtEpochMs, nowMs)
        } else {
            progress.loopElapsedMs
        }

    private fun ComboSlotProgress.liveIntervalElapsedMs(nowMs: Long): Long {
        val total = progress.intervalTotalMs
        if (total <= 0L) return 0L
        return (progress.intervalElapsedMs + liveExtra(state, updatedAtEpochMs, nowMs))
            .coerceAtMost(total)
    }

    private fun ComboSlotProgress.liveStartDelayElapsedMs(nowMs: Long): Long {
        val total = progress.startDelayTotalMs
        if (total <= 0L) return 0L
        return (progress.startDelayElapsedMs + liveExtra(state, updatedAtEpochMs, nowMs))
            .coerceAtMost(total)
    }

    /** 槽位的实时时长与状态文案：把快照之后过去的挂钟时间补进等待阶段。 */
    private fun ComboSlotProgress.liveView(nowMs: Long): LiveSlotView {
        val extra = liveExtra(state, updatedAtEpochMs, nowMs)
        val elapsed = progress.elapsedMs + extra
        val intervalElapsed = liveIntervalElapsedMs(nowMs)
        val startDelayElapsed = liveStartDelayElapsedMs(nowMs)
        val description = when {
            state == ExecutionState.IDLE -> "尚未开始"
            state == ExecutionState.COMPLETED -> "已完成"
            state == ExecutionState.STOPPED -> "已停止"
            state == ExecutionState.PAUSED -> "已暂停"
            progress.startDelayTotalMs > 0L && startDelayElapsed < progress.startDelayTotalMs ->
                "等待首轮 ${waitingProgressLabel(startDelayElapsed, progress.startDelayTotalMs)}"

            progress.intervalTotalMs > 0L && intervalElapsed < progress.intervalTotalMs ->
                "循环间隔 ${waitingProgressLabel(intervalElapsed, progress.intervalTotalMs)}"

            progress.currentActionIndex >= 0 ->
                "动作 ${progress.currentActionIndex + 1}/${progress.totalActions}"

            else -> "运行中"
        }
        return LiveSlotView(elapsed, intervalElapsed, startDelayElapsed, description)
    }

    private fun waitingProgressLabel(elapsedMs: Long, totalMs: Long): String =
        if (totalMs >= 60_000L) {
            "${elapsedMs / 60_000}/${totalMs / 60_000} 分钟"
        } else {
            "${elapsedMs / 1_000}/${totalMs / 1_000} 秒"
        }

    private data class LiveSlotView(
        val elapsedMs: Long,
        val intervalElapsedMs: Long,
        val startDelayElapsedMs: Long,
        val description: String,
    )

    private fun timeLabel(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return SimpleDateFormat(TIME_PATTERN, Locale.CHINA).format(Date(epochMs))
    }

    private fun ExecutionState.displayLabel(): String = ExecutionProgress(state = this).formattedState()

    /** 无限循环在 JSON 里统一写成 `null`，由页面显示为 `∞`。 */
    private fun Int.jsonLoops(): String =
        if (this >= ExecutionConfig.INFINITE_LOOPS) "null" else toString()

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { c ->
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun jsonField(name: String, value: String): String = "${jsonString(name)}:$value"

    private fun jsonObject(vararg fields: String): String =
        fields.joinToString(prefix = "{", postfix = "}", separator = ",")

    private fun jsonArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",")

    // ------------------------------------------------------------------ 长轮询

    private fun bumpVersion() {
        changeLock.withLock {
            stateVersion++
            changed.signalAll()
        }
    }

    /**
     * 等到版本变化或超时。
     *
     * 客户端把上次拿到的 `stateVersion` 带回来；版本一致说明没有新事件，
     * 挂起到下一次脚本状态变化，最长 [STATUS_LONG_POLL_MS] 后自然返回，
     * 用来做空闲时的时钟校准。
     */
    private fun awaitChange(since: Long, timeoutMs: Long) {
        // 单调时钟：等待时长不能被系统对时 / 时区调整拉长。用挂钟计时时，一次
        // 回拨就会让「最多 20 秒」变成一分多钟，客户端正好在它的读超时里看到
        // “Read timed out”，这也是手机端（会自己校时）比电脑端更容易踩到的坑。
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        changeLock.withLock {
            while (stateVersion == since) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    changed.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "AutoRunnerWeb"

        /** 默认端口；被占用时依次尝试后面的端口。 */
        const val DEFAULT_PORT = 8720
        private const val PORT_END = 8729

        private const val PAGE_ASSET = "web/status.html"
        private const val REQUEST_TIMEOUT_MS = 10_000
        /**
         * 长轮询最长挂起时间，同时通过 `/api/status` 的 `longPollMs` 告诉客户端。
         *
         * 不要调大：手机端的 Wi-Fi 省电、Doze 与运营商 / 路由器的空闲连接回收都在
         * 30–60 秒这个量级。挂起越久，一条被中间设备悄悄丢掉的连接就越晚才能被
         * 发现，手机端就会停在「连接中断 / Read timed out」上。20 秒既能把状态变化
         * 即时推给客户端，又让一条死连接最迟在下一次请求时被换掉。
         */
        private const val STATUS_LONG_POLL_MS = 20_000L
        private const val ACCEPT_RETRY_DELAY_MS = 200L
        private const val ADDRESS_REFRESH_MS = 5_000L
        private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

        private val FALLBACK_PAGE = """
            <!doctype html><html lang="zh-CN"><meta charset="utf-8">
            <title>AutoRunner</title>
            <body style="font-family:system-ui,sans-serif;background:#0f1115;color:#e5e7eb;padding:32px">
            <h2>页面资源缺失</h2>
            <p>请通过安装包重新安装应用，或直接访问 <code>/api/status</code> 获取 JSON 状态。</p>
            </body></html>
        """.trimIndent()
    }
}
