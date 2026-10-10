package cn.helilab.autorunner.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.util.Log
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import cn.helilab.autorunner.AutoRunnerApplication
import com.autorunner.ui.platform.ScriptTransferController
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 用于导入和导出 `.arscript` 文件的存储访问框架（SAF）桥接（§6.5）。
 *
 * 仓库层只处理字符串，因此本类负责所有 URI 相关工作：
 * 读取选中的文档、写入导出文档，以及剪贴板 / 分享兜底方案。
 *
 * `ActivityResultLauncher` 只能在 Activity 创建期间注册，
 * 因此 [register] 必须在 `MainActivity.onCreate` 中调用。
 */
class AndroidScriptTransferController(
    private val context: Context,
) : ScriptTransferController {

    private var importLauncher: ActivityResultLauncher<Array<String>>? = null

    private var exportLauncher: ActivityResultLauncher<String>? = null

    /** 待处理的导出内容，由创建文档的回调消费。 */
    private var pendingExport: Pair<String, String>? = null

    override var onPicked: ((fileName: String?, content: String) -> Unit)? = null

    /** 注册 SAF 启动器；必须在 Activity 创建期间执行。 */
    fun register(caller: ActivityResultCaller) {
        // HyperOS 3 / Android 16 文件选择器：带 MIME 过滤的 `ACTION_OPEN_DOCUMENT`。
        // `ActivityResultContracts.OpenDocument` 会设置 `type = */*` 并把我们的数组
        // 转发为 EXTRA_MIME_TYPES，因此只有*更窄*的数组才能让选择器
        // 只列出脚本文档。`.arscript` 不是已注册的 MIME 类型，
        // 选择器会把它报告为通用二进制数据——所以两种类型都要提供：
        // `application/json`（JSON 内容）和 `application/octet-stream`。
        // 绝不要加 `*/*`，否则选择器会回退为列出所有文件。
        Log.i(
            AutoRunnerApplication.TAG,
            "import picker filter: ${ARSCRIPT_MIME_TYPES.joinToString()}",
        )
        importLauncher = caller.registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val payload = readText(uri) ?: return@registerForActivityResult
            onPicked?.invoke(displayName(uri), payload)
        }

        exportLauncher = caller.registerForActivityResult(
            // `*/*` 会让建议的文件名保持不变，包括我们的
            // `.arscript` 扩展名：具体的 MIME 会让选择器追加
            // 由它推导出的扩展名（application/json -> ".json"）。
            ActivityResultContracts.CreateDocument("*/*"),
        ) { uri: Uri? ->
            val payload = pendingExport
            pendingExport = null
            if (payload == null) return@registerForActivityResult
            if (uri == null) {
                // 用户从选择器退出了：无需写入，但内容仍可通过剪贴板获取。
                Log.i(AutoRunnerApplication.TAG, "export cancelled by the user")
                notify("已取消导出")
                return@registerForActivityResult
            }
            val written = writeText(uri, payload.second)
            Log.i(AutoRunnerApplication.TAG, "export '${payload.first}' written=$written")
            notify(if (written) "已导出到所选位置" else "导出失败，可改用「分享 / 复制」")
        }
        Log.i(AutoRunnerApplication.TAG, "document pickers registered")
    }

    override fun pickScriptFile(): Boolean {
        val launcher = importLauncher
        if (launcher == null) {
            notify("当前上下文无法打开文件选择器")
            return false
        }
        // 指南中的 `resolveActivity` 探测仅供参考：MIUI 的选择器
        // 声明的是 `*/*` 过滤，因此解析具体的 MIME 类型可能返回 null，
        // 即便启动该 Intent 实际上是可行的。记录日志后照常启动。
        Log.i(AutoRunnerApplication.TAG, "document picker resolves=${isFilePickerAvailable()}")
        return runCatching {
            launcher.launch(ARSCRIPT_MIME_TYPES)
            true
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "unable to open document picker", error)
            notify("无法打开文件选择器，请改用「分享 / 复制」导入脚本")
        }.getOrDefault(false)
    }

    override fun exportScript(fileName: String, content: String) {
        val launcher = exportLauncher
        if (launcher == null) {
            // 没有 Activity 注册过选择器（或它已经销毁）：改用分享文件
            // 作为可用的兜底方案，而不是静默失败。
            Log.w(AutoRunnerApplication.TAG, "export picker unavailable, falling back to share")
            shareScript(fileName, content)
            return
        }
        pendingExport = fileName to content
        runCatching { launcher.launch(fileName) }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "unable to open the create-document picker", error)
            pendingExport = null
            copyToClipboard(fileName, content)
            notify("无法打开文件选择器，已复制脚本内容到剪贴板")
        }
    }

    override fun shareScript(fileName: String, content: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享脚本").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            copyToClipboard(fileName, content)
            notify("已复制脚本内容到剪贴板")
        }
    }

    override fun copyToClipboard(label: String, text: String) {
        runCatching {
            val manager = context.getSystemService(ClipboardManager::class.java)
            manager?.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    override fun notify(message: String) {
        runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        /**
         * 导入选择器允许显示的 MIME 类型。
         *
         * `.arscript` 文件是 JSON 文档，但大多数 ROM 将其类型标为通用二进制数据，
         * 因此两种类型都必须提供，用户自己的脚本才可见。
         */
        val ARSCRIPT_MIME_TYPES = arrayOf("application/json", "application/octet-stream")
    }

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }
    }.getOrNull()

    private fun writeText(uri: Uri, content: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(content.toByteArray())
            stream.flush()
        }
        true
    }.getOrDefault(false)

    /**
     * 当系统文件选择器能够处理导入 Intent 时为 `true`。
     *
     * 与 HyperOS 文件选择器指南推荐的兼容性检查保持一致，
     * 使不受支持的系统以提示消息失败，而不是抛出 ActivityNotFound。
     */
    private fun isFilePickerAvailable(): Boolean = runCatching {
        val probe = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(ARSCRIPT_MIME_TYPES.first())
            .putExtra(Intent.EXTRA_MIME_TYPES, ARSCRIPT_MIME_TYPES)
        context.packageManager.resolveActivity(probe, 0) != null
    }.getOrDefault(false)

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
