package cn.helilab.autorunner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import cn.helilab.autorunner.permission.AndroidPermissionController
import cn.helilab.autorunner.storage.AndroidScriptTransferController
import com.autorunner.core.script.ImportResult
import com.autorunner.ui.screens.AutoRunnerApp
import com.autorunner.ui.state.Destination
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * AutoRunner Compose UI 的单 Activity 宿主。
 *
 * 该 Activity 可调整大小且从不锁定方向，因此同一套界面可以应对手机竖屏、
 * 手机横屏、展开态折叠屏和平板 —— 这是满足 Android 17 尺寸调整要求（§5.4）的前提。
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionController?.refresh()
    }

    private var permissionController: AndroidPermissionController? = null

    private var transferController: AndroidScriptTransferController? = null

    /** 通过 `destination` intent extra 请求的页面。 */
    private val requestedDestination =
        androidx.compose.runtime.mutableStateOf<Destination>(Destination.Scripts)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AppGraph.initialize(application)
        val container = AppGraph.requireContainer()

        val permissions = AndroidPermissionController(
            context = this,
            requestPermissionLauncher = { permissionsToRequest -> permissionLauncher.launch(permissionsToRequest) },
            // Root 模式：回前台检测到无障碍缺失时，经模块命令桥按需补回。
            rootInputBackend = container.rootInputBackend,
        )
        val transfers = AndroidScriptTransferController(this).apply { register(this@MainActivity) }
        permissionController = permissions
        transferController = transfers
        AppGraph.transfer = transfers

        requestedDestination.value = destinationFrom(intent)

        setContent {
            AutoRunnerApp(
                container = container,
                permissionController = permissions,
                transferController = transfers,
                gamepadStatusProvider = AppGraph.gamepad,
                initialDestination = requestedDestination.value,
            )
        }

        handleScriptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedDestination.value = destinationFrom(intent)
        handleScriptIntent(intent)
    }

    /**
     * 将 `--es destination scripts|editor|record|settings` 映射到对应页面。
     *
     * MIUI 拒绝 `adb shell input tap`，因此自动化 UI 测试正是通过这种方式
     * 到达应用的每个页面。
     */
    private fun destinationFrom(intent: Intent?): Destination = when (intent?.getStringExtra(EXTRA_DESTINATION)) {
        "editor" -> Destination.Editor(intent.getStringExtra(EXTRA_SCRIPT_ID))
        "record" -> Destination.Record
        "settings" -> Destination.Settings
        else -> Destination.Scripts
    }

    override fun onResume() {
        super.onResume()
        // 权限是在系统设置中授予的，因此每次用户返回前台都必须
        // 重新读取状态（§6.4.2）。
        permissionController?.refresh()
        AppGraph.containerOrNull?.overlayManager?.refreshPermissionState()
    }

    /**
     * 导入分享来的 `.arscript` 文档（例如从文件管理器打开）。
     */
    private fun handleScriptIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        val container = AppGraph.requireContainer()
        lifecycleScope.launch {
            val payload = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readText()
                }
            }.getOrNull()

            if (payload.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, "无法读取所选文件", Toast.LENGTH_SHORT).show()
                return@launch
            }

            when (val result = container.scriptRepository.importFromText(payload)) {
                is ImportResult.Success ->
                    Toast.makeText(
                        this@MainActivity,
                        "已导入「${result.record.name}」",
                        Toast.LENGTH_SHORT,
                    ).show()

                is ImportResult.Failure ->
                    Toast.makeText(this@MainActivity, "导入失败：${result.message}", Toast.LENGTH_LONG).show()
            }
        }
        Log.i(AutoRunnerApplication.TAG, "importing script from $uri")
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_SCRIPT_ID = "scriptId"
    }
}
