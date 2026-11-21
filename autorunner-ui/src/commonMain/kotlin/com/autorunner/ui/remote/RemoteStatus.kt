package com.autorunner.ui.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * 另一台设备 `/api/status` 的一次快照。
 *
 * 字段与 Android 层 `StatusWebServer.buildStatusJson` 一一对应；全部字段都带默认值，
 * 这样对方版本较旧（缺少新字段）时仍能解析，页面只展示拿到的信息。
 */
@Serializable
data class RemoteStatusSnapshot(
    val serverTimeMs: Long = 0,
    val serverTime: String = "",
    val serverUptimeMs: Long = 0,
    val stateVersion: Long = 0,
    /** 服务端长轮询的挂起上限；旧服务端没有这个字段，按 [LEGACY_LONG_POLL_MS] 兜底。 */
    val longPollMs: Long = 0,
    val app: RemoteAppInfo = RemoteAppInfo(),
    val network: RemoteNetworkInfo = RemoteNetworkInfo(),
    val execution: RemoteExecution = RemoteExecution(),
    val combo: RemoteCombo? = null,
    val lastReport: RemoteReport? = null,
    val scripts: List<RemoteScriptSummary> = emptyList(),
    val environment: RemoteEnvironment = RemoteEnvironment(),
)

@Serializable
data class RemoteAppInfo(
    val versionName: String = "",
    val versionCode: Int = 0,
    val packageName: String = "",
    val device: String = "",
    val android: String = "",
    val sdk: Int = 0,
    val themeMode: String = "",
)

@Serializable
data class RemoteNetworkInfo(
    val running: Boolean = false,
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
)

@Serializable
data class RemoteExecution(
    val state: String = "",
    val stateLabel: String = "",
    val scriptId: String = "",
    val scriptName: String = "",
    val armed: Boolean = false,
    val completedLoops: Int = 0,
    val totalLoops: Int? = null,
    val infinite: Boolean = false,
    val loopFraction: Double? = null,
    val currentActionIndex: Int = -1,
    val totalActions: Int = 0,
    val currentActionLabel: String = "",
    val elapsedMs: Long = 0,
    val elapsedLabel: String = "",
    val skippedActions: Int = 0,
    val message: String = "",
    val intervalElapsedMs: Long = 0,
    val intervalTotalMs: Long = 0,
    val startDelayElapsedMs: Long = 0,
    val startDelayTotalMs: Long = 0,
)

@Serializable
data class RemoteCombo(
    val state: String = "",
    val stateLabel: String = "",
    val elapsedMs: Long = 0,
    val elapsedLabel: String = "",
    val slots: List<RemoteComboSlot> = emptyList(),
)

@Serializable
data class RemoteComboSlot(
    val role: String = "",
    val roleLabel: String = "",
    val scriptName: String = "",
    val state: String = "",
    val stateLabel: String = "",
    val completedLoops: Int = 0,
    val totalLoops: Int? = null,
    val currentActionIndex: Int = -1,
    val totalActions: Int = 0,
    val elapsedMs: Long = 0,
    val elapsedLabel: String = "",
    /** 当前这一轮已运行的时长；旧服务端没有该字段时为 `null`。 */
    val loopElapsedMs: Long? = null,
    val intervalElapsedMs: Long = 0,
    val intervalTotalMs: Long = 0,
    val startDelayElapsedMs: Long = 0,
    val startDelayTotalMs: Long = 0,
    val description: String = "",
    val cycleFraction: Double? = null,
)

@Serializable
data class RemoteReport(
    val scriptName: String = "",
    val outcome: String = "",
    val summary: String = "",
    val completedLoops: Int = 0,
    val totalLoops: Int? = null,
    val elapsedLabel: String = "",
    val failureMessage: String = "",
)

@Serializable
data class RemoteScriptSummary(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val steps: Int = 0,
    val mode: String = "",
    val repeatCount: Int = 0,
    val totalLoops: Int? = null,
    val intervalMs: Long = 0,
    val usesGamepad: Boolean = false,
    val updatedAt: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
data class RemoteEnvironment(
    val inputMode: String = "",
    val inputModeLabel: String = "",
    val accessibilityConnected: Boolean = false,
    val overlayPermission: Boolean = false,
    val overlayVisible: Boolean = false,
    val overlayError: String = "",
    val rootAvailable: Boolean = false,
    val rootModuleInstalled: Boolean = false,
    val gamepadEnabled: Boolean = false,
    val notificationListener: Boolean = false,
    val keepScreenOn: Boolean = false,
    val executionNotification: Boolean = false,
    val executionReminder: Boolean = false,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val screenDensity: Double = 0.0,
)

private val remoteStatusJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    // 服务端把所有数字都写成 JSON 字符串（见 buildStatusJson 的 jsonField 用法），
    // lenient 模式才允许把 "8720" 解码进 Int。
    isLenient = true
}

internal fun parseRemoteStatus(payload: String): RemoteStatusSnapshot? =
    runCatching { remoteStatusJson.decodeFromString<RemoteStatusSnapshot>(payload) }.getOrNull()

/** 旧版服务端（v1.1.1 及更早）把长轮询固定挂起 55 秒，并且不上报 `longPollMs`。 */
internal const val LEGACY_LONG_POLL_MS = 55_000L

/** 读超时比服务端的挂起上限多留的余量，覆盖局域网抖动与手机端的调度延迟。 */
private const val LONG_POLL_READ_SLACK_MS = 15_000L

/**
 * 长轮询的读超时。
 *
 * 服务端在快照里上报自己的挂起上限，客户端只需要比它多一点余量：设得过大
 * （以前固定 70 秒）会让一条被 Wi-Fi 省电 / Doze 丢掉的连接拖住很久，用户看到
 * 的就是「连接中断 + Read timed out」。旧服务端没有 `longPollMs` 时按 55 秒兜底。
 */
internal fun remoteReadTimeoutMs(longPollMs: Long): Int {
    val cap = (if (longPollMs > 0L) longPollMs else LEGACY_LONG_POLL_MS)
        .coerceIn(1_000L, 60_000L)
    return (cap + LONG_POLL_READ_SLACK_MS).toInt()
}

/**
 * 把用户输入整理成状态页地址。
 *
 * 允许直接粘贴 `192.168.1.5:8720` / `http://192.168.1.5:8720/` 这类完整文本：
 * 去掉协议与路径，主机里若带端口则以它为准（覆盖端口输入框）。
 */
internal fun buildRemoteStatusUrl(hostInput: String, portInput: String): String? {
    var raw = hostInput.trim()
    if (raw.isEmpty()) return null
    raw = raw.removePrefix("https://").removePrefix("http://")
    raw = raw.substringBefore('/').substringBefore('?').trim()
    if (raw.isEmpty()) return null

    var host = raw
    var port = portInput.trim().toIntOrNull() ?: return null
    val colon = raw.lastIndexOf(':')
    if (colon in 1 until raw.lastIndex) {
        val embedded = raw.substring(colon + 1).toIntOrNull()
        if (embedded != null) {
            host = raw.substring(0, colon)
            port = embedded
        }
    }
    if (host.isBlank() || port !in 1..65_535) return null
    return "http://$host:$port/"
}

/** 进度环/进度条文案：无限循环只报已完成轮数，有限循环展示当前第几轮。 */
internal fun loopProgressText(completed: Int, total: Int?): String = when {
    total == null -> "已运行 $completed 轮 · 无限循环"
    total <= 0 -> "已运行 $completed 轮"
    completed >= total -> "已完成 $total 轮"
    else -> "第 ${completed + 1} / $total 轮"
}

/** 优先使用服务端算好的精确进度（含当前轮内动作进度），缺失时退回轮数比例。 */
internal fun loopFraction(completed: Int, total: Int?, precise: Double?): Float {
    precise?.let { return it.toFloat().coerceIn(0f, 1f) }
    if (total != null && total > 0) return (completed.toFloat() / total).coerceIn(0f, 1f)
    return 0f
}

internal fun scriptModeText(mode: String, totalLoops: Int?, intervalMs: Long): String {
    val base = when {
        mode == "ONCE" -> "单次执行"
        totalLoops == null -> "无限循环"
        else -> "循环 $totalLoops 轮"
    }
    return if (intervalMs > 0) "$base · 间隔 ${formatInterval(intervalMs)}" else base
}

internal fun formatInterval(ms: Long): String = when {
    ms <= 0 -> "0 秒"
    ms >= 60_000 -> "${trimDecimal(ms / 60_000.0)} 分"
    ms >= 1_000 -> "${trimDecimal(ms / 1_000.0)} 秒"
    else -> "$ms 毫秒"
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 秒"
    val totalSeconds = ms / 1_000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "$days 天 $hours 小时"
        hours > 0 -> "$hours 小时 $minutes 分"
        minutes > 0 -> "$minutes 分 $seconds 秒"
        else -> "$seconds 秒"
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_024L * 1_024L -> "${trimDecimal(bytes / (1_024.0 * 1_024.0))} MB"
    bytes >= 1_024L -> "${trimDecimal(bytes / 1_024.0)} KB"
    else -> "$bytes B"
}

private fun trimDecimal(value: Double): String {
    val rounded = (value * 10).roundToLong() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

/** 远程状态的语气，仅用于配色。 */
internal enum class RemoteTone { ACTIVE, PAUSED, SUCCESS, FAILED, STOPPED, NEUTRAL }

internal fun executionTone(state: String): RemoteTone = when (state) {
    "RUNNING" -> RemoteTone.ACTIVE
    "PAUSED" -> RemoteTone.PAUSED
    "COMPLETED" -> RemoteTone.SUCCESS
    "STOPPED" -> RemoteTone.STOPPED
    else -> RemoteTone.NEUTRAL
}

internal fun outcomeTone(outcome: String): RemoteTone = when (outcome) {
    "COMPLETED" -> RemoteTone.SUCCESS
    "FAILED" -> RemoteTone.FAILED
    "STOPPED" -> RemoteTone.STOPPED
    else -> RemoteTone.NEUTRAL
}

/**
 * 用本地挂钟把一次 `/api/status` 快照补到当前时刻。
 *
 * 服务端对长间隔按分钟上报，监控页如果直接展示快照值会整分钟不动。
 * 每次收到新响应都以它为锚点重新计算 [deltaMs]，既能让秒数平滑走动，
 * 又会在下一次轮询时自动纠正本地计时与服务端之间的偏移。
 */
internal fun RemoteStatusSnapshot.liveAfter(deltaMs: Long): RemoteStatusSnapshot {
    if (deltaMs <= 0L) return this
    return copy(
        serverUptimeMs = serverUptimeMs + deltaMs,
        execution = execution.liveAfter(deltaMs),
        combo = combo?.liveAfter(deltaMs),
    )
}

private fun RemoteExecution.liveAfter(deltaMs: Long): RemoteExecution {
    if (state != "RUNNING") return this
    val elapsed = elapsedMs + deltaMs
    return copy(
        elapsedMs = elapsed,
        elapsedLabel = formatClock(elapsed),
        intervalElapsedMs = intervalElapsedMs.liveWithin(intervalTotalMs, deltaMs),
        startDelayElapsedMs = startDelayElapsedMs.liveWithin(startDelayTotalMs, deltaMs),
    )
}

private fun RemoteCombo.liveAfter(deltaMs: Long): RemoteCombo {
    if (state != "RUNNING") return this
    val elapsed = elapsedMs + deltaMs
    return copy(
        elapsedMs = elapsed,
        elapsedLabel = formatClock(elapsed),
        slots = slots.map { it.liveAfter(deltaMs) },
    )
}

private fun RemoteComboSlot.liveAfter(deltaMs: Long): RemoteComboSlot {
    if (state != "RUNNING") return this
    val elapsed = elapsedMs + deltaMs
    val interval = intervalElapsedMs.liveWithin(intervalTotalMs, deltaMs)
    val startDelay = startDelayElapsedMs.liveWithin(startDelayTotalMs, deltaMs)
    return copy(
        elapsedMs = elapsed,
        elapsedLabel = formatClock(elapsed),
        // 只有真正在派发动作时才推进「本轮」；轮间等待时让它停在上一轮的时长上。
        loopElapsedMs = if (currentActionIndex >= 0) loopElapsedMs?.plus(deltaMs) else loopElapsedMs,
        intervalElapsedMs = interval,
        startDelayElapsedMs = startDelay,
        description = descriptionAt(interval, startDelay),
    )
}

private fun Long.liveWithin(total: Long, deltaMs: Long): Long =
    if (total > 0L) (this + deltaMs).coerceAtMost(total) else this

private fun RemoteComboSlot.descriptionAt(intervalElapsedMs: Long, startDelayElapsedMs: Long): String = when {
    state == "IDLE" -> "尚未开始"
    state == "COMPLETED" -> "已完成"
    state == "STOPPED" -> "已停止"
    state == "PAUSED" -> "已暂停"
    startDelayTotalMs > 0L && startDelayElapsedMs < startDelayTotalMs ->
        "等待首轮 ${waitingProgressText(startDelayElapsedMs, startDelayTotalMs)}"

    intervalTotalMs > 0L && intervalElapsedMs < intervalTotalMs ->
        "循环间隔 ${waitingProgressText(intervalElapsedMs, intervalTotalMs)}"

    currentActionIndex >= 0 -> "动作 ${currentActionIndex + 1}/$totalActions"
    description.isNotBlank() -> description
    else -> "运行中"
}

private fun waitingProgressText(elapsedMs: Long, totalMs: Long): String =
    if (totalMs >= 60_000L) {
        "${elapsedMs / 60_000}/${totalMs / 60_000} 分钟"
    } else {
        "${elapsedMs / 1_000}/${totalMs / 1_000} 秒"
    }

/**
 * 槽位当前阶段的文案：等待首轮 / 循环间隔 / 动作 x/y，终态直接用对方的描述。
 */
internal fun RemoteComboSlot.phaseText(): String = when {
    state != "RUNNING" -> description.ifBlank { stateLabel.ifBlank { state } }
    startDelayTotalMs > 0L && startDelayElapsedMs < startDelayTotalMs -> "等待首轮"
    intervalTotalMs > 0L && intervalElapsedMs < intervalTotalMs -> "循环间隔"
    currentActionIndex >= 0 && totalActions > 0 -> "动作 ${currentActionIndex + 1}/$totalActions"
    description.isNotBlank() -> description
    else -> "运行中"
}

/**
 * 该槽位「当前这一轮」的时长：等待阶段给已等待 / 总等待，动作阶段给本轮已运行。
 * 整段脚本的总运行时长已经在页面顶部大字展示，这里不再重复。
 */
internal fun RemoteComboSlot.roundTimeText(): String = when {
    state != "RUNNING" -> ""
    startDelayTotalMs > 0L && startDelayElapsedMs < startDelayTotalMs ->
        "已等待 ${formatClock(startDelayElapsedMs)} / ${formatClock(startDelayTotalMs)}"
    intervalTotalMs > 0L && intervalElapsedMs < intervalTotalMs ->
        "已等待 ${formatClock(intervalElapsedMs)} / ${formatClock(intervalTotalMs)}"
    // 旧服务端（v1.1.1）不提供 loopElapsedMs，宁可不显示也不把 0 当成真的跑了 0 秒。
    loopElapsedMs != null -> "本轮 ${formatClock(loopElapsedMs)}"
    else -> ""
}

/** 与服务端 `ExecutionProgress.formatDuration` 保持一致的计时格式。 */
internal fun formatClock(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    fun Long.pad(): String = if (this < 10) "0$this" else toString()
    return if (hours > 0) {
        "${hours.pad()}:${minutes.pad()}:${seconds.pad()}"
    } else {
        "${minutes.pad()}:${seconds.pad()}"
    }
}
