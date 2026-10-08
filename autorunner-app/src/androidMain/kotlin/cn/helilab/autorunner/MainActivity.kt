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
 * Single activity host of the AutoRunner Compose UI.
 *
 * The activity is resizeable and never locks its orientation, so the same
 * screens handle phone portrait, phone landscape, unfolded foldables and
 * tablets — the prerequisite for the Android 17 resize requirement (§5.4).
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionController?.refresh()
    }

    private var permissionController: AndroidPermissionController? = null

    private var transferController: AndroidScriptTransferController? = null

    /** Screen requested through the `destination` intent extra. */
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
     * Maps `--es destination scripts|editor|record|settings` to a screen.
     *
     * MIUI refuses `adb shell input tap`, so this is how an automated UI pass
     * reaches every page of the app.
     */
    private fun destinationFrom(intent: Intent?): Destination = when (intent?.getStringExtra(EXTRA_DESTINATION)) {
        "editor" -> Destination.Editor(intent.getStringExtra(EXTRA_SCRIPT_ID))
        "record" -> Destination.Record
        "settings" -> Destination.Settings
        else -> Destination.Scripts
    }

    override fun onResume() {
        super.onResume()
        // Permissions are granted in system settings, so the state has to be
        // re-read every time the user comes back (§6.4.2).
        permissionController?.refresh()
        AppGraph.containerOrNull?.overlayManager?.refreshPermissionState()
    }

    /**
     * Imports a shared `.arscript` document (for example from a file manager).
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
