package cn.helilab.autorunner.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import cn.helilab.autorunner.AutoRunnerApplication
import kotlin.math.roundToInt

/**
 * 黑屏挂机层右上角显示的电量快照。
 *
 * @param levelPercent 0..100；系统未提供时用 -1 表示未知。
 * @param charging 是否正在充电（含已充满）。
 */
internal data class IdleBatteryState(
    val levelPercent: Int,
    val charging: Boolean,
) {

    /** 右上角的百分比文案；未知电量时显示 `--%`。 */
    val label: String get() = if (levelPercent in 0..100) "$levelPercent%" else "--%"
}

/**
 * 监听系统电量与充电状态，把变化推给黑屏挂机层。
 *
 * `ACTION_BATTERY_CHANGED` 是受保护的粘性广播：注册时立即返回当前状态，
 * 之后仅在电量 / 插拔变化时回调，不需要额外权限，也不会常驻后台。
 */
internal class IdleBatteryMonitor(
    private val context: Context,
    private val onChanged: (IdleBatteryState) -> Unit,
) {

    private var registered = false

    private var lastState: IdleBatteryState? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val value = intent ?: return
            publish(parse(value))
        }
    }

    /** 开始监听；重复调用是幂等的。 */
    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess { sticky ->
            registered = true
            sticky?.let { publish(parse(it)) }
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "battery status listener registration failed", error)
        }
    }

    /** 停止监听；没有注册过时调用也是安全的。 */
    fun stop() {
        if (!registered) return
        registered = false
        lastState = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun publish(state: IdleBatteryState) {
        if (state == lastState) return
        lastState = state
        onChanged(state)
    }

    private fun parse(intent: Intent): IdleBatteryState {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).roundToInt().coerceIn(0, 100)
        } else {
            -1
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        // FULL 也算充电完成（例如整夜插着充电器）；插着电源但系统报
        // NOT_CHARGING（电池保护 / 限充）时不画闪电，避免误导。
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return IdleBatteryState(levelPercent = percent, charging = charging)
    }
}
