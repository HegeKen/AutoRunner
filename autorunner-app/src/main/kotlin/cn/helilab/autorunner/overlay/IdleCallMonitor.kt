package cn.helilab.autorunner.overlay

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.helilab.autorunner.AutoRunnerApplication
import cn.helilab.autorunner.notification.IdleInterruption

/**
 * 黑屏挂机期间的通话监听。
 *
 * 检测电话（含网络通话）进入 → 触发 interruption 开始回调；
 * 电话挂断、音频模式回到基线后 → 触发 resolved 回调，由上层自动恢复黑屏挂机。
 *
 * 读取音频模式不需要任何权限，因此在没有「电话状态」权限的设备上同样可用；
 * 第三方通话应用可能在响铃期间不把音频模式切到 call mode，此时由
 * [cn.helilab.autorunner.notification.AutoRunnerNotificationListener] 的
 * `CATEGORY_CALL` 通知作为补充。
 *
 * 进入挂机时的音频模式作为基线：游戏内语音或通话中再开挂机不会被立刻踢出，
 * 只有挂机期间**新进入**的通话模式才算特殊情况。resolved 检测同样以基线为准，
 * 回到基线（或 MODE_NORMAL）才算「电话结束」。
 */
internal class IdleCallMonitor(
    context: Context,
    private val onEvent: (IdleInterruption) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private var started = false

    /** 事件已触发过（防止短时间内多次 start / resolved）。 */
    private var eventFired = false

    /** 进入挂机时的音频模式；与它相同的模式不触发退出，resolved 时也回到它。 */
    private var baselineMode = AudioManager.MODE_NORMAL

    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            checkMode()
            if (started) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** 开始监听；重复调用是幂等的。 */
    fun start() {
        if (started) return
        started = true
        eventFired = false
        baselineMode = currentMode()
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /** 停止监听；挂机层永久收起时调用。 */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(poll)
    }

    private fun checkMode() {
        val mode = currentMode()
        if (!eventFired) {
            // 还没触发过 start：只关心 call mode 检测。
            if (mode == baselineMode || !isCallMode(mode)) return
            fireStart(mode)
            return
        }
        // 已触发过 start：继续轮询等待 resolved——回到基线或普通模式都算结束。
        if (mode == baselineMode || mode == AudioManager.MODE_NORMAL) {
            fireResolved()
        }
    }

    private fun fireStart(mode: Int) {
        eventFired = true
        Log.i(
            AutoRunnerApplication.TAG,
            "idle screen: audio mode $mode (baseline=$baselineMode) — interruption start",
        )
        onEvent(IdleInterruption(IdleInterruption.Kind.CALL, resolved = false))
    }

    private fun fireResolved() {
        Log.i(
            AutoRunnerApplication.TAG,
            "idle screen: audio mode back to baseline — interruption resolved",
        )
        started = false
        handler.removeCallbacks(poll)
        onEvent(IdleInterruption(IdleInterruption.Kind.CALL, resolved = true))
    }

    private fun currentMode(): Int =
        runCatching { audioManager?.mode }.getOrNull() ?: AudioManager.MODE_NORMAL

    private companion object {
        /** 500ms 足够让来电界面及时出现；轮询本身只是一次轻量的系统调用。 */
        const val POLL_INTERVAL_MS = 500L

        fun isCallMode(mode: Int): Boolean =
            mode == AudioManager.MODE_RINGTONE ||
                mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
