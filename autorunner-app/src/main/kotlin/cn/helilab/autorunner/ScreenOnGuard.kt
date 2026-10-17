package cn.helilab.autorunner

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 脚本运行期间的屏幕常亮保护。
 *
 * 用进程级 WakeLock 而不是某个窗口的 `FLAG_KEEP_SCREEN_ON`：脚本可能在
 * 没有任何 Activity / 悬浮窗的场景下运行（通知栏、Root 注入、后台唤起），
 * 只有 WakeLock 能覆盖全部入口。
 *
 * display 一旦熄灭，InputDispatcher 会直接丢弃注入的触摸，脚本就会「空跑」，
 * 因此脚本运行（含暂停）期间必须让屏幕保持点亮。
 */
object ScreenOnGuard {

    private var wakeLock: PowerManager.WakeLock? = null

    /** 开始保持屏幕常亮；重复调用是幂等的。 */
    fun acquire(context: Context) {
        if (wakeLock?.isHeld == true) return
        val manager = context.getSystemService(PowerManager::class.java) ?: return
        runCatching {
            @Suppress("DEPRECATION")
            val lock = manager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "AutoRunner:scriptRunning",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            wakeLock = lock
            Log.i(AutoRunnerApplication.TAG, "screen-on lock acquired")
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "unable to acquire screen-on lock", error)
        }
    }

    /** 结束常亮保护；未持有时调用也是安全的。 */
    fun release() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
        Log.i(AutoRunnerApplication.TAG, "screen-on lock released")
    }
}
