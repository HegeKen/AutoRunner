package com.autorunner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autorunner.ui.adaptive.AutoRunnerWindowSize
import com.autorunner.ui.components.AppButton
import com.autorunner.ui.components.AppButtonTone
import com.autorunner.ui.components.NumberField
import com.autorunner.ui.components.PageScaffold
import com.autorunner.ui.components.PreferenceRow
import com.autorunner.ui.components.SectionCard
import com.autorunner.ui.components.StatusPill
import com.autorunner.ui.components.contentColor
import com.autorunner.ui.remote.RemoteCombo
import com.autorunner.ui.remote.RemoteEnvironment
import com.autorunner.ui.remote.RemoteExecution
import com.autorunner.ui.remote.RemoteReport
import com.autorunner.ui.remote.RemoteScriptSummary
import com.autorunner.ui.remote.RemoteStatusSnapshot
import com.autorunner.ui.remote.RemoteTone
import com.autorunner.ui.remote.buildRemoteStatusUrl
import com.autorunner.ui.remote.executionTone
import com.autorunner.ui.remote.fetchRemoteStatus
import com.autorunner.ui.remote.formatBytes
import com.autorunner.ui.remote.formatDuration
import com.autorunner.ui.remote.formatInterval
import com.autorunner.ui.remote.liveAfter
import com.autorunner.ui.remote.loopFraction
import com.autorunner.ui.remote.loopProgressText
import com.autorunner.ui.remote.outcomeTone
import com.autorunner.ui.remote.phaseText
import com.autorunner.ui.remote.remoteReadTimeoutMs
import com.autorunner.ui.remote.roundTimeText
import com.autorunner.ui.remote.scriptModeText
import com.autorunner.ui.theme.AutoRunnerColors
import com.autorunner.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 另一台设备的运行状态监控页。
 *
 * 不做网页内嵌：直接读取对方的 `/api/status`（与网页状态页同一份 JSON），
 * 解析后以原生列表渲染。地址只保留在输入框和最近一次成功连接的目标里。
 * 使用带 `stateVersion` 的长轮询：脚本状态变化时立即返回，空闲时按对方上报的
 * `longPollMs`（旧服务端为 55 秒）等待一次；运行时长在两次响应之间由本地时钟推进。
 * 页面不在前台时停止轮询、恢复时立刻重连，避免手机端把一条被系统挂起的请求
 * 拖到读超时才显示「连接中断」。
 *
 * @param initialHost / [initialPort] 上次连接过的目标，用于预填输入框。
 * @param onRememberTarget 解析出有效地址后回写设置，下次打开时预填。
 */
@Composable
fun RemoteMonitorScreen(
    windowSize: AutoRunnerWindowSize,
    onBack: () -> Unit,
    initialHost: String = "",
    initialPort: Int = 8720,
    onRememberTarget: (host: String, port: Int) -> Unit = { _, _ -> },
) {
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var port by rememberSaveable { mutableStateOf(initialPort.toString()) }
    var connectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<RemoteStatusSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // 连续失败次数：一次失败只显示「重连中」，别把单次读超时渲染成「连接中断」。
    var failures by remember { mutableIntStateOf(0) }
    // 快照到达的本地单调时间，作为两次轮询之间的计时锚点。
    var receivedAt by remember { mutableStateOf<TimeMark?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    // 只在页面可见时轮询。切到后台 / 息屏后，长轮询会被系统挂起、Wi-Fi 也会
    // 进入省电，等回到前台时那条请求只剩读超时，用户看到的就是「连接中断 +
    // Read timed out」。这里在不可见时直接取消（客户端实现会断开底层连接），
    // 重新可见时立刻用 since=0 取一份新快照：服务端会立即返回，感知上无缝。
    // 用 STARTED 而不是 RESUMED：分屏里另一侧有焦点时监控页仍然可见，继续刷新。
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(connectedUrl, refreshTick, foreground) {
        val base = connectedUrl ?: return@LaunchedEffect
        if (!foreground) return@LaunchedEffect
        var since = 0L
        // 每次重新进入页面都从保守的旧服务端超时起步：第一帧 since=0 会立即返回，
        // 随后按对方上报的 longPollMs 收紧。
        var readTimeoutMs = remoteReadTimeoutMs(0L)
        while (true) {
            try {
                val requestedSince = since
                val started = TimeSource.Monotonic.markNow()
                val next = fetchRemoteStatus(
                    url = "${base}api/status?wait=1&since=$requestedSince",
                    readTimeoutMs = readTimeoutMs,
                )
                readTimeoutMs = remoteReadTimeoutMs(next.longPollMs)
                snapshot = next
                receivedAt = TimeSource.Monotonic.markNow()
                error = null
                failures = 0
                since = next.stateVersion
                // 旧服务端没有 stateVersion 时退回每分钟刷新，避免空转。
                if (next.stateVersion <= 0L) {
                    delay(60_000)
                } else if (
                    next.stateVersion == requestedSince &&
                    started.elapsedNow().inWholeMilliseconds < 1_000L
                ) {
                    delay(5_000)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                failures++
                error = t.message?.takeIf { it.isNotBlank() } ?: "无法连接对方设备"
                // 单次失败（切后台、换 Wi-Fi、路由器短暂抖动）先快重试一次，
                // 连续失败再退到 5 秒，避免网络真的断了时空转。
                delay(if (failures <= 1) 1_000L else 5_000L)
            }
        }
    }

    // 每秒触发一次重组，让时长在两次 /api/status 之间也能本地推进。
    LaunchedEffect(foreground) {
        if (!foreground) return@LaunchedEffect
        while (true) {
            delay(1_000)
            tick++
        }
    }

    val liveSnapshot = remember(snapshot, receivedAt, tick, error, foreground) {
        val snap = snapshot
        val mark = receivedAt
        if (snap == null || mark == null || error != null || !foreground) {
            snap
        } else {
            snap.liveAfter(mark.elapsedNow().inWholeMilliseconds)
        }
    }

    PageScaffold(
        windowSize = windowSize,
        title = "设备监控",
        subtitle = "输入另一台设备的状态页地址，查看它的运行进度",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.ChevronBackward,
                    contentDescription = "返回设置",
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sidePadding = if (maxWidth > Dimens.MaxContentWidth) {
                (maxWidth - Dimens.MaxContentWidth) / 2
            } else {
                Dimens.ScreenPadding
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    end = sidePadding,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
            ) {
                item(key = "connection") {
                    ConnectionCard(
                        host = host,
                        port = port,
                        connectedUrl = connectedUrl,
                        error = error,
                        failures = failures,
                        onHostChange = { host = it },
                        onPortChange = { port = it },
                        onConnect = {
                            val url = buildRemoteStatusUrl(host, port)
                            if (url == null) {
                                connectedUrl = null
                                snapshot = null
                                receivedAt = null
                                failures = 0
                                error = "请输入有效的 IP / 主机名与端口"
                            } else {
                                error = null
                                snapshot = null
                                receivedAt = null
                                failures = 0
                                connectedUrl = url
                                onRememberTarget(host.trim(), port.trim().toIntOrNull() ?: initialPort)
                            }
                        },
                        onDisconnect = {
                            connectedUrl = null
                            snapshot = null
                            receivedAt = null
                            error = null
                            failures = 0
                        },
                        onRefresh = { refreshTick++ },
                    )
                }

                val current = liveSnapshot
                if (current == null) {
                    item(key = "hint") {
                        SectionCard(
                            title = "使用说明",
                            subtitle = "数据直接来自对方的只读状态接口",
                        ) {
                            PreferenceRow(
                                title = "先开启对方的网页状态页",
                                summary = "在对方设备「设置 → 网页状态页」开启后，这里输入它的局域网地址即可",
                                insideMargin = Dimens.CompactRowPadding,
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            PreferenceRow(
                                title = "地址格式",
                                summary = "支持直接粘贴 192.168.1.5:8720 或 http://192.168.1.5:8720/",
                                insideMargin = Dimens.CompactRowPadding,
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            PreferenceRow(
                                title = "刷新频率",
                                summary = "脚本状态变化时即时刷新，回到前台会立刻重连；空闲时约每 20 秒校准一次，只读展示",
                                insideMargin = Dimens.CompactRowPadding,
                            )
                        }
                    }
                } else {
                    item(key = "execution") { ExecutionCard(current.execution) }
                    current.combo?.let { combo ->
                        item(key = "combo") { ComboCard(combo) }
                    }
                    current.lastReport?.let { report ->
                        item(key = "report") { ReportCard(report) }
                    }
                    item(key = "scripts") { ScriptsCard(current.scripts) }
                    item(key = "environment") { EnvironmentCard(current.environment) }
                    item(key = "device") { DeviceCard(current) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 连接
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionCard(
    host: String,
    port: String,
    connectedUrl: String?,
    error: String?,
    failures: Int,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard(
        title = "连接设备",
        subtitle = "填写对方设备的 IP 与端口（默认 8720）",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = host,
                onValueChange = onHostChange,
                label = "IP / 主机名",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            NumberField(
                value = port,
                onValueChange = onPortChange,
                label = "端口",
                maxDigits = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(
                    onClick = onConnect,
                    enabled = host.isNotBlank(),
                ) {
                    Text(
                        text = "连接",
                        color = AppButtonTone.Primary.contentColor(),
                        style = MiuixTheme.textStyles.button,
                    )
                }
                if (connectedUrl != null) {
                    AppButton(
                        onClick = onDisconnect,
                        tone = AppButtonTone.Neutral,
                    ) {
                        Text(
                            text = "断开",
                            color = AppButtonTone.Neutral.contentColor(),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    TextButton(text = "立即刷新", onClick = onRefresh)
                }
            }
        }

        when {
            connectedUrl == null && error != null -> PreferenceRow(
                title = "无法解析地址",
                summary = error,
                statusText = "未连接",
                statusColor = MiuixTheme.colorScheme.errorContainer,
                statusContentColor = MiuixTheme.colorScheme.onErrorContainer,
                insideMargin = Dimens.CompactRowPadding,
            )

            connectedUrl == null -> PreferenceRow(
                title = "未连接",
                summary = "输入地址后点击「连接」开始监控",
                insideMargin = Dimens.CompactRowPadding,
            )

            // 第一次失败通常只是切换前后台 / 换网造成的单次读超时，先提示「重连中」，
            // 连续失败才判定为连接中断，避免闪一下红色状态就恢复。
            error != null && failures < 2 -> PreferenceRow(
                title = "重连中",
                summary = error,
                statusText = "正在重试",
                statusColor = AutoRunnerColors.Paused.copy(alpha = 0.18f),
                statusContentColor = AutoRunnerColors.Paused,
                insideMargin = Dimens.CompactRowPadding,
            )

            error != null -> PreferenceRow(
                title = "连接中断",
                summary = error,
                statusText = "重试中",
                statusColor = MiuixTheme.colorScheme.errorContainer,
                statusContentColor = MiuixTheme.colorScheme.onErrorContainer,
                insideMargin = Dimens.CompactRowPadding,
            )

            else -> PreferenceRow(
                title = "已连接",
                summary = connectedUrl,
                statusText = "实时",
                statusColor = AutoRunnerColors.Running,
                insideMargin = Dimens.CompactRowPadding,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 运行状态
// ---------------------------------------------------------------------------

@Composable
private fun ExecutionCard(execution: RemoteExecution) {
    val tone = executionTone(execution.state)
    val label = execution.stateLabel.ifBlank { execution.state.ifBlank { "空闲" } }

    SectionCard(
        title = "运行状态",
        subtitle = execution.scriptName.ifBlank { "当前没有运行中的脚本" },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(text = label, color = tone.toneColor())
                Text(
                    text = loopProgressText(execution.completedLoops, execution.totalLoops),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            ProgressTrack(
                fraction = loopFraction(
                    execution.completedLoops,
                    execution.totalLoops,
                    execution.loopFraction,
                ),
                color = tone.toneColor(),
            )
            InfoRow("已运行", execution.elapsedLabel)
            if (execution.totalActions > 0 && execution.currentActionIndex >= 0) {
                InfoRow(
                    "当前动作",
                    execution.currentActionLabel.ifBlank {
                        "${execution.currentActionIndex + 1} / ${execution.totalActions}"
                    },
                )
            }
            if (execution.intervalTotalMs > 0) {
                InfoRow(
                    "循环间隔",
                    "${formatInterval(execution.intervalElapsedMs)} / ${formatInterval(execution.intervalTotalMs)}",
                )
            }
            if (execution.startDelayTotalMs > 0) {
                InfoRow(
                    "等待首轮",
                    "${formatInterval(execution.startDelayElapsedMs)} / ${formatInterval(execution.startDelayTotalMs)}",
                )
            }
            if (execution.skippedActions > 0) {
                InfoRow("已跳过", "${execution.skippedActions} 个动作")
            }
            if (execution.message.isNotBlank()) {
                Text(
                    text = execution.message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ComboCard(combo: RemoteCombo) {
    SectionCard(
        title = "组合执行",
        subtitle = combo.stateLabel.ifBlank { combo.state },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoRow("已运行", combo.elapsedLabel)
            combo.slots.forEach { slot ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = slot.roleLabel.ifBlank { slot.role },
                            color = executionTone(slot.state).toneColor(),
                        )
                        Text(
                            text = slot.scriptName.ifBlank { "未选择脚本" },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = slot.stateLabel.ifBlank { slot.state },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    ProgressTrack(
                        fraction = loopFraction(
                            slot.completedLoops,
                            slot.totalLoops,
                            slot.cycleFraction,
                        ),
                        color = executionTone(slot.state).toneColor(),
                    )
                    // 总运行时长在上面的卡片里已经有大字，这里给这一段自己的节奏：
                    // 动作阶段是「本轮 x:xx」，等待阶段是「已等待 x:xx / 总时长」。
                    Text(
                        text = buildString {
                            append(loopProgressText(slot.completedLoops, slot.totalLoops))
                            append(" · ")
                            append(slot.phaseText())
                            slot.roundTimeText().takeIf { it.isNotBlank() }?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MiuixTheme.textStyles.footnote2.merge(TabularNumbers),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportCard(report: RemoteReport) {
    val tone = outcomeTone(report.outcome)
    SectionCard(
        title = "最近一次运行",
        subtitle = report.scriptName.ifBlank { "未知脚本" },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(text = report.outcome.ifBlank { "结束" }, color = tone.toneColor())
                Text(
                    text = report.summary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            InfoRow("完成轮次", loopProgressText(report.completedLoops, report.totalLoops))
            if (report.elapsedLabel.isNotBlank()) InfoRow("耗时", report.elapsedLabel)
            if (report.failureMessage.isNotBlank()) {
                Text(
                    text = report.failureMessage,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 脚本库与环境
// ---------------------------------------------------------------------------

@Composable
private fun ScriptsCard(scripts: List<RemoteScriptSummary>) {
    SectionCard(
        title = "脚本库",
        subtitle = if (scripts.isEmpty()) "对方设备上还没有脚本" else "共 ${scripts.size} 个脚本",
    ) {
        if (scripts.isEmpty()) return@SectionCard
        scripts.take(8).forEachIndexed { index, script ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            PreferenceRow(
                title = script.name.ifBlank { "未命名脚本" },
                summary = buildString {
                    append("${script.steps} 个动作 · ")
                    append(scriptModeText(script.mode, script.totalLoops, script.intervalMs))
                    if (script.usesGamepad) append(" · 手柄")
                    append(" · ")
                    append(formatBytes(script.sizeBytes))
                },
                insideMargin = Dimens.CompactRowPadding,
            )
        }
        if (scripts.size > 8) {
            PreferenceRow(
                title = "其余 ${scripts.size - 8} 个脚本",
                summary = "网页状态页可以查看完整列表",
                insideMargin = Dimens.CompactRowPadding,
            )
        }
    }
}

@Composable
private fun EnvironmentCard(environment: RemoteEnvironment) {
    SectionCard(
        title = "运行环境",
        subtitle = environment.inputModeLabel.ifBlank { environment.inputMode },
    ) {
        PreferenceRow(
            title = "无障碍服务",
            summary = if (environment.accessibilityConnected) "已连接" else "未连接",
            statusText = if (environment.accessibilityConnected) "正常" else "未开启",
            statusColor = if (environment.accessibilityConnected) {
                AutoRunnerColors.Running
            } else {
                MiuixTheme.colorScheme.errorContainer
            },
            statusContentColor = if (environment.accessibilityConnected) {
                Color.White
            } else {
                MiuixTheme.colorScheme.onErrorContainer
            },
            insideMargin = Dimens.CompactRowPadding,
        )
        environment.overlayError.takeIf { it.isNotBlank() }?.let { overlayError ->
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            PreferenceRow(
                title = "悬浮窗异常",
                summary = overlayError,
                insideMargin = Dimens.CompactRowPadding,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        PreferenceRow(
            title = "悬浮面板",
            summary = when {
                !environment.overlayPermission -> "未授予悬浮窗权限"
                environment.overlayVisible -> "已显示在屏幕上"
                else -> "已授权，当前隐藏"
            },
            insideMargin = Dimens.CompactRowPadding,
        )
        if (environment.rootAvailable || environment.rootModuleInstalled) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            PreferenceRow(
                title = "Root 注入",
                summary = when {
                    environment.rootModuleInstalled -> "已安装 Root Bridge 模块"
                    else -> "已获得 root 授权"
                },
                insideMargin = Dimens.CompactRowPadding,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        PreferenceRow(
            title = "手柄模拟",
            summary = if (environment.gamepadEnabled) "已开启" else "未开启",
            insideMargin = Dimens.CompactRowPadding,
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        PreferenceRow(
            title = "黑屏挂机",
            summary = buildString {
                append(if (environment.keepScreenOn) "保持亮屏" else "允许熄屏")
                if (environment.executionNotification) append(" · 执行通知")
                if (environment.executionReminder) append(" · 执行提醒")
                if (environment.notificationListener) append(" · 通知使用权已授权")
            },
            insideMargin = Dimens.CompactRowPadding,
        )
        val battery = environment.batteryPercent
        if (battery != null) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            PreferenceRow(
                title = "电量",
                summary = when (environment.batteryCharging) {
                    true -> "$battery% · 充电中"
                    false -> "$battery% · 未充电"
                    null -> "$battery%"
                },
                insideMargin = Dimens.CompactRowPadding,
            )
        }
    }
}

@Composable
private fun DeviceCard(snapshot: RemoteStatusSnapshot) {
    val app = snapshot.app
    val network = snapshot.network
    SectionCard(
        title = "设备信息",
        subtitle = app.device.ifBlank { app.packageName },
    ) {
        PreferenceRow(
            title = "AutoRunner",
            summary = buildString {
                append(app.versionName.ifBlank { "未知版本" })
                if (app.versionCode > 0) append("（${app.versionCode}）")
                if (app.device.isNotBlank()) append(" · ${app.device}")
            },
            insideMargin = Dimens.CompactRowPadding,
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        PreferenceRow(
            title = "Android",
            summary = buildString {
                append(app.android.ifBlank { "未知" })
                if (app.sdk > 0) append("（API ${app.sdk}）")
                if (snapshot.environment.screenWidth > 0) {
                    append(" · ${snapshot.environment.screenWidth}×${snapshot.environment.screenHeight}")
                }
            },
            insideMargin = Dimens.CompactRowPadding,
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        PreferenceRow(
            title = "状态接口",
            summary = if (network.running) {
                "端口 ${network.port} · ${network.addresses.joinToString(" / ")}"
            } else {
                "服务未运行"
            },
            insideMargin = Dimens.CompactRowPadding,
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        PreferenceRow(
            title = "运行时长",
            summary = formatDuration(snapshot.serverUptimeMs),
            statusText = snapshot.serverTime.takeIf { it.isNotBlank() },
            statusColor = MiuixTheme.colorScheme.secondaryContainer,
            statusContentColor = MiuixTheme.colorScheme.onSecondaryContainer,
            insideMargin = Dimens.CompactRowPadding,
        )
    }
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

/**
 * 表格数字（等宽数字）。
 *
 * 监控页的时长每秒刷新。默认字体里「1」比「8」窄，秒数一跳，数字后面的单位、
 * 分隔符（「/ 14:00」这类）就会左右抖一下。熄屏挂机的时钟用等宽字体 + 固定槽位
 * 解决同一个问题，这里用 OpenType 的 `tnum` 特性：数字等宽，中文单位仍保持原字体。
 */
private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1.merge(TabularNumbers),
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProgressTrack(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
    }
}

@Composable
private fun RemoteTone.toneColor(): Color = when (this) {
    RemoteTone.ACTIVE -> AutoRunnerColors.Running
    RemoteTone.PAUSED -> AutoRunnerColors.Paused
    RemoteTone.SUCCESS -> AutoRunnerColors.Running
    RemoteTone.FAILED -> MiuixTheme.colorScheme.error
    RemoteTone.STOPPED, RemoteTone.NEUTRAL -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
