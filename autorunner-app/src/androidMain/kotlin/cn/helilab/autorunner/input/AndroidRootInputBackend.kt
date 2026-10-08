package cn.helilab.autorunner.input

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import cn.helilab.autorunner.AutoRunnerApplication
import com.autorunner.core.BuildInfo
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep
import com.autorunner.core.platform.ActionResult
import com.autorunner.core.platform.RootInputBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Root 模式输入注入。
 *
 * 指令写进 AutoRunner 私有目录下的命令文件（0700，只有本应用与 root 可写），
 * Magisk / KernelSU 模块里的守护进程以 root 读取后调用 `input` 执行，因此绕过了
 * MIUI 对 `INJECT_EVENTS` 与无障碍开关的限制。
 *
 * 模块是否就绪以 `/data/local/tmp/autorunner_root/mode` 为准：该目录由模块以 root
 * 创建（0755），普通应用只能读、无法伪造。
 */
class AndroidRootInputBackend(
    private val context: Context,
    private val metrics: () -> Pair<Int, Int>,
    private val normalised: () -> Boolean,
) : RootInputBackend {

    private val marker = File(MARKER_DIR, "mode")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** root 管理器（Magisk/KernelSU）已授权本应用时为 true。 */
    @Volatile
    private var suGranted = false

    /** 可用的 su 路径。 */
    @Volatile
    private var suPath: String? = null

    init {
        probe()
    }

    override val moduleInstalled: Boolean
        get() = marker.isFile

    override val isAvailable: Boolean
        get() = moduleInstalled || suGranted

    /**
     * 探测 root：直接执行 `su -c id`。Magisk / KernelSU 会弹一次授权框，
     * 允许之后本应用即可直接调用 `su -c input ...` 注入，无需安装模块。
     */
    override fun probe() {
        scope.launch {
            val candidates = listOf(
                "su",
                "/system/bin/su",
                // KernelSU
                "/data/adb/ksu/bin/su",
                // Magisk
                "/debug_ramdisk/su",
                "/sbin/su",
            )
            for (path in candidates) {
                val granted = runCatching {
                    val process = ProcessBuilder(path, "-c", "id")
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText()
                    val code = process.waitFor()
                    diagnose("$path -> exit=$code out=${output.trim().take(120)}")
                    output.contains("uid=0")
                }.getOrElse { error ->
                    diagnose("$path -> ${error.javaClass.simpleName}: ${error.message}")
                    false
                }
                if (granted) {
                    suPath = path
                    suGranted = true
                    diagnose("GRANTED via $path")
                    Log.i(AutoRunnerApplication.TAG, "root granted via '$path'")
                    return@launch
                }
            }
            diagnose("NOT GRANTED (tried ${candidates.joinToString()})")
            Log.i(AutoRunnerApplication.TAG, "root not granted (tried ${candidates.joinToString()})")
        }
    }

    override suspend fun perform(step: ActionStep): ActionResult {
        if (!isAvailable) return ActionResult.Failure("Root 桥未就绪", recoverable = true)
        val line = when (step) {
            is TapStep -> "tap ${px(step.x, true)} ${px(step.y, false)}"
            is LongPressStep -> "long ${px(step.x, true)} ${px(step.y, false)} ${step.duration}"
            is SwipeStep ->
                "swipe ${px(step.fromX, true)} ${px(step.fromY, false)} " +
                    "${px(step.toX, true)} ${px(step.toY, false)} ${step.duration}"
            is MultiTouchStep -> return ActionResult.Unsupported("Root 注入暂不支持多点触控")
            else -> return ActionResult.Unsupported("Root 注入不支持该动作")
        }
        // 模块在 → 走文件桥（最快，无 su 开销）；否则直接 su 调 input。
        return if (moduleInstalled) write(line) else execSu(line)
    }

    /** 把协议行转换成 `input` 的子命令。 */
    private fun toInputArgs(line: String): String {
        val parts = line.split(" ")
        return if (parts.size >= 4 && parts[0] == "long") {
            "swipe ${parts[1]} ${parts[2]} ${parts[1]} ${parts[2]} ${parts[3]}"
        } else {
            line
        }
    }

    private suspend fun execSu(line: String): ActionResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val su = suPath ?: return@withContext ActionResult.Failure(
                "未获得 root 授权",
                recoverable = true,
            )
            runCatching {
                val process = ProcessBuilder(su, "-c", "input ${toInputArgs(line)}")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                if (code == 0) {
                    Log.i(AutoRunnerApplication.TAG, "root input: input ${toInputArgs(line)}")
                    ActionResult.Success
                } else {
                    ActionResult.Failure("root 注入失败($code) ${output.take(120)}")
                }
            }.getOrElse { error ->
                Log.w(AutoRunnerApplication.TAG, "root input failed", error)
                ActionResult.Failure(error.message ?: "root 注入异常")
            }
        }

    /** 自检：写入一条无害按键，确认命令文件可写（设置页用来显示"已连通"）。 */
    fun selfCheck(): Boolean = write("key KEYCODE_WAKEUP") == ActionResult.Success

    /**
     * 按需补回无障碍条目：模块在时写一行 `a11y`，由守护进程幂等地重新写入
     * `enabled_accessibility_services`。未装模块时设备上没有对应的 enable 脚本，
     * 返回 false 交由用户手动开启。
     */
    override fun repairAccessibility(): Boolean {
        if (!moduleInstalled) return false
        return write("a11y") == ActionResult.Success
    }

    private fun write(line: String): ActionResult = runCatching {
        FileOutputStream(File(context.filesDir, COMMAND_FILE), true).use { out ->
            out.write((line + "\n").toByteArray())
            out.flush()
        }
        Log.i(AutoRunnerApplication.TAG, "root input dispatched: $line")
        ActionResult.Success
    }.getOrElse { error ->
        Log.w(AutoRunnerApplication.TAG, "root input failed", error)
        ActionResult.Failure(error.message ?: "Root 注入写入失败")
    }

    private fun px(value: Float, horizontal: Boolean): Int {
        if (!normalised()) return value.roundToInt()
        val (w, h) = metrics()
        return (value * (if (horizontal) w else h)).roundToInt()
    }

    /**
     * 导出内置模块 zip 到下载目录。
     *
     * Android 10+ 用 MediaStore 写入公共下载目录（无需存储权限）；旧版本退回
     * 公共 Downloads 路径（需要 WRITE_EXTERNAL_STORAGE，失败时返回 null）。
     */
    override fun exportModule(): String? = runCatching {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MODULE_FILE)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { out ->
                context.assets.open(MODULE_ASSET).copyTo(out)
            } ?: return@runCatching null
            "Downloads/$MODULE_FILE"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) return@runCatching null
            val file = File(dir, MODULE_FILE)
            FileOutputStream(file).use { out -> context.assets.open(MODULE_ASSET).copyTo(out) }
            file.absolutePath
        }
    }.getOrElse { error ->
        Log.w(AutoRunnerApplication.TAG, "export module failed", error)
        null
    }

    /** 把探测结果写到 files/root_probe.txt：部分 ROM 会屏蔽第三方日志。 */
    private fun diagnose(line: String) {
        runCatching {
            FileOutputStream(File(context.filesDir, "root_probe.txt"), true).use { out ->
                out.write((line + "\n").toByteArray())
            }
        }
    }

    companion object {
        const val MARKER_DIR = "/data/local/tmp/autorunner_root"
        const val COMMAND_FILE = "root_input.cmd"

        /** 内置模块（assets）与导出后的文件名；版本与内置模块一致。 */
        const val MODULE_ASSET = "autorunner_root.zip"
        val MODULE_FILE = "autorunner_root-v${BuildInfo.VERSION_NAME}.zip"
    }
}
