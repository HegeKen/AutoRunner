package cn.helilab.autorunner.notification

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.helilab.autorunner.AutoRunnerApplication

/**
 * 黑屏挂机层上展示的一条媒体播放信息。
 *
 * 只在本次挂机期间**播放过音乐**时存在；暂停后继续保留（按钮变成「播放」），
 * 会话彻底消失 / 进入挂机时本来就处于暂停状态则不存在。
 * 字段都在 [IdleMediaMonitor] 中从 [MediaController] 快照而来，视图层不再接触
 * `android.media` 类型，方便绘制与测试。
 */
data class IdleMediaState(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    val canTogglePlayPause: Boolean,
    /** 曲目总时长（毫秒）；0 表示播放器没有提供（直播 / 未知时长）。 */
    val durationMs: Long = 0L,
    /** 最近一次已知的播放位置（毫秒）。 */
    val positionMs: Long = 0L,
    /** [positionMs] 对应的 `SystemClock.elapsedRealtime()` 时间点，用于插值。 */
    val positionUpdatedAtElapsedMs: Long = 0L,
    /** 播放速度；暂停时为 0。 */
    val playbackSpeed: Float = 1f,
) {

    /** 滚动文字中的媒体行；标题缺失时退回应用名。 */
    val displayText: String
        get() {
            val name = title.ifBlank { appLabel }.ifBlank { "正在播放" }
            return if (artist.isBlank()) "♫ $name" else "♫ $name · $artist"
        }

    /** 是否具备可展示的播放进度（有确定时长）。 */
    val hasProgress: Boolean get() = durationMs > 0L

    /**
     * 按当前时刻估算的播放进度（0..1）。
     *
     * 播放器只在状态变化时更新 `PlaybackState.position`，播放过程中需要按
     * `lastPositionUpdateTime` 加上经过的时间插值，否则圆环会一直停在
     * 上一次回调的位置。
     */
    fun progressFractionAt(nowElapsedMs: Long): Float {
        if (durationMs <= 0L) return 0f
        val elapsed = if (isPlaying && positionUpdatedAtElapsedMs > 0L) {
            (nowElapsedMs - positionUpdatedAtElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val speed = if (isPlaying) {
            // 个别播放器在播放中把速度上报成 0，按 1x 处理，避免进度环停住。
            if (playbackSpeed > 0f) playbackSpeed else 1f
        } else {
            0f
        }
        val position = positionMs + (elapsed * speed).toLong()
        return (position.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

/**
 * 通过已启用的「通知使用权」读取当前媒体会话，为黑屏挂机层提供播放信息与
 * 上一首 / 下一首 / 暂停控制。
 *
 * 权限模型与 [AutoRunnerNotificationListener] 相同：`MediaSessionManager
 * .getActiveSessions(component)` 要求调用方是已启用的通知监听器。因此未授予
 * 「通知使用权」时本类只记录一次日志并保持静默，黑屏挂机其余功能不受影响。
 *
 * 会话变化、播放状态与元数据都由系统回调驱动；另外保留一个低频轮询，兜住
 * 个别播放器不更新回调的情况。全部回调都在主线程执行。
 */
class IdleMediaMonitor(
    private val context: Context,
    private val onStateChanged: (IdleMediaState?) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())

    private val sessionManager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    private val listenerComponent = ComponentName(context, AutoRunnerNotificationListener::class.java)

    /** 当前可用的全部媒体会话；用于注册回调，不直接代表正在播放。 */
    private val controllers = ArrayList<MediaController>()

    private var started = false

    /** 已经因为缺少通知使用权报过日志，避免每轮轮询刷屏。 */
    private var permissionDenied = false

    private var activeController: MediaController? = null

    /**
     * 本次黑屏挂机期间**真正播放过**的会话 token。
     *
     * 媒体按钮只在音乐真的播放过之后出现；暂停后**保留**按钮（图标变为播放），
     * 否则用户误触暂停就再也无法从黑屏界面恢复播放。进入挂机时本来就处于
     * 暂停状态的会话不会弹出按钮。按 token 记录而不是「见过播放」布尔值，
     * 这样轮询偶尔拿到空列表时不会把暂停中的按钮误撤掉，也不会把之后某个
     * 无关的暂停会话当成刚播放过的那个。
     */
    private val playedTokens = HashSet<MediaSession.Token>()

    private var lastPublished: IdleMediaState? = null

    private var hasPublished = false

    /** 同一条通知流里反复查 `PackageManager` 没有意义。 */
    private val appLabels = HashMap<String, String>()

    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            evaluate()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            evaluate()
        }

        override fun onSessionDestroyed() {
            refreshSessions()
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { active ->
            bind(active.orEmpty())
        }

    /**
     * 低频兜底：媒体会话没变但状态回调缺失时（部分 ROM / 播放器），仍能在一个
     * 轮询周期内发现「开始播放」或「切歌」。
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            refreshSessions()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** 开始监听；重复调用是幂等的。 */
    fun start() {
        if (started) return
        started = true
        hasPublished = false
        lastPublished = null
        playedTokens.clear()
        val manager = sessionManager ?: run {
            Log.i(AutoRunnerApplication.TAG, "media sessions unavailable: no MediaSessionManager")
            return
        }
        runCatching {
            manager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                listenerComponent,
                handler,
            )
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "media session listener registration failed", error)
        }
        refreshSessions()
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /** 停止监听并释放会话回调；黑屏层收起时调用。 */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        runCatching {
            sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        }
        controllers.forEach { controller ->
            runCatching { controller.unregisterCallback(sessionCallback) }
        }
        controllers.clear()
        activeController = null
        lastPublished = null
        hasPublished = false
        playedTokens.clear()
    }

    // ------------------------------------------------------------------ 控制

    fun skipPrevious() {
        val controller = activeController ?: return
        runCatching { controller.transportControls.skipToPrevious() }
            .onFailure { Log.w(AutoRunnerApplication.TAG, "media skip previous failed", it) }
    }

    fun skipNext() {
        val controller = activeController ?: return
        runCatching { controller.transportControls.skipToNext() }
            .onFailure { Log.w(AutoRunnerApplication.TAG, "media skip next failed", it) }
    }

    fun togglePlayPause() {
        val controller = activeController ?: return
        val playbackState = runCatching { controller.playbackState }.getOrNull() ?: return
        val controls = controller.transportControls
        runCatching {
            when (playbackState.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                -> controls.pause()

                else -> controls.play()
            }
        }.onFailure { Log.w(AutoRunnerApplication.TAG, "media play/pause failed", it) }
    }

    // -------------------------------------------------------------- 会话跟踪

    private fun refreshSessions() {
        if (!started) return
        val manager = sessionManager ?: return
        val active = try {
            manager.getActiveSessions(listenerComponent)
        } catch (error: SecurityException) {
            // 未授予「通知使用权」：媒体控制不可用，但不影响挂机本身。
            if (!permissionDenied) {
                permissionDenied = true
                Log.i(
                    AutoRunnerApplication.TAG,
                    "media sessions unavailable: notification access not granted",
                )
            }
            bind(emptyList())
            return
        } catch (error: RuntimeException) {
            Log.w(AutoRunnerApplication.TAG, "media sessions query failed", error)
            bind(emptyList())
            return
        }
        permissionDenied = false
        bind(active)
    }

    /**
     * 替换当前会话集合并重新注册回调。
     *
     * 对所有活动会话注册回调（而不只是正在播放的那个），这样用户在通知栏 /
     * 耳机上恢复播放时能立刻拿到 `onPlaybackStateChanged`，无需等轮询。
     */
    private fun bind(active: List<MediaController>) {
        if (!started) return
        // 轮询拿到的往往是同一批会话的新 MediaController 实例，按 sessionToken
        // 比较即可；没有变化时只重新评估状态，避免每 5 秒重复注册回调。
        val unchanged = active.size == controllers.size &&
            active.indices.all { active[it].sessionToken == controllers[it].sessionToken }
        if (!unchanged) {
            controllers.forEach { controller ->
                runCatching { controller.unregisterCallback(sessionCallback) }
            }
            controllers.clear()
            controllers.addAll(active)
            controllers.forEach { controller ->
                runCatching { controller.registerCallback(sessionCallback, handler) }
            }
        }
        // 所有会话都消失了也不清空 playedTokens：一次空结果可能只是系统 /
        // 播放器的瞬时状态，清掉会让暂停中的按钮永久消失。token 唯一，
        // 之后新出现的会话不会被误判成「播放过」。
        evaluate()
    }

    private fun evaluate() {
        if (!started) return
        val playing = controllers.firstOrNull { it.isPlayingNow() }
        if (playing != null) {
            playedTokens += playing.sessionToken
            activeController = playing
            publish(buildState(playing))
            return
        }
        // 暂停后继续保留按钮（播放图标可恢复）；进入挂机时本来就没在播放
        // 的会话不打扰用户。优先沿用刚刚在播的那个会话，避免多播放器时
        // 切到另一个恰好处于暂停状态的 App。
        val tracked = activeController
        val paused = if (tracked != null &&
            tracked.isPausedNow() &&
            tracked.sessionToken in playedTokens &&
            controllers.any { it.sessionToken == tracked.sessionToken }
        ) {
            tracked
        } else {
            controllers.firstOrNull { it.isPausedNow() && it.sessionToken in playedTokens }
        }
        if (paused == null) {
            activeController = null
            publish(null)
            return
        }
        activeController = paused
        publish(buildState(paused))
    }

    private fun buildState(controller: MediaController): IdleMediaState {
        val playbackState = runCatching { controller.playbackState }.getOrNull()
        val metadata = runCatching { controller.metadata }.getOrNull()
        val packageName = runCatching { controller.packageName }.getOrNull().orEmpty()
        val title = firstNonBlank(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        )
        val artist = firstNonBlank(
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        )
        val appLabel = appLabel(packageName)
        val actions = playbackState?.actions ?: 0L
        val durationMs = runCatching {
            metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        }.getOrDefault(0L)
        // 有些播放器把 actions 留空（0）却仍支持 transportControls。
        // 此时按「可用」处理，让按钮先展示出来，点下去由播放器自行决定是否响应。
        val unknownActions = actions == 0L
        return IdleMediaState(
            packageName = packageName,
            appLabel = appLabel,
            title = title.ifBlank { appLabel },
            artist = artist,
            isPlaying = playbackState?.isPlayingOrBuffering() == true,
            canSkipPrevious = unknownActions ||
                actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSkipNext = unknownActions ||
                actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canTogglePlayPause = unknownActions || actions and (
                PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE
                ) != 0L,
            durationMs = durationMs.coerceAtLeast(0L),
            positionMs = playbackState?.position?.coerceAtLeast(0L) ?: 0L,
            positionUpdatedAtElapsedMs = playbackState?.lastPositionUpdateTime ?: 0L,
            playbackSpeed = playbackState?.playbackSpeed ?: 1f,
        )
    }

    private fun publish(state: IdleMediaState?) {
        if (hasPublished && state == lastPublished) return
        hasPublished = true
        lastPublished = state
        onStateChanged(state)
    }

    private fun appLabel(packageName: String): String {
        if (packageName.isBlank()) return ""
        return appLabels.getOrPut(packageName) {
            runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName.substringAfterLast('.'))
        }
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    /** 调试通道用的一行状态。 */
    fun debugDescription(): String = buildString {
        append("started=").append(started)
        append(" permissionDenied=").append(permissionDenied)
        append(" sessions=").append(controllers.size)
        append(" media=").append(lastPublished?.packageName ?: "null")
        append(" playing=").append(lastPublished?.isPlaying == true)
        append(" title=").append(lastPublished?.title ?: "null")
    }

    private companion object {
        /** 状态回调兜底轮询周期；媒体控制不是高频交互，5 秒足够。 */
        const val POLL_INTERVAL_MS = 5_000L
    }
}

private fun MediaController.isPlayingNow(): Boolean =
    runCatching { playbackState?.isPlayingOrBuffering() == true }.getOrDefault(false)

private fun MediaController.isPausedNow(): Boolean =
    runCatching { playbackState?.state == PlaybackState.STATE_PAUSED }.getOrDefault(false)

private fun PlaybackState.isPlayingOrBuffering(): Boolean =
    state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
