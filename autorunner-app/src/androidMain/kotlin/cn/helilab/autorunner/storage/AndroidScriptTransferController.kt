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
 * Storage Access Framework bridge for importing and exporting `.arscript` files
 * (§6.5).
 *
 * The repository only ever deals with strings, so this class owns all URI work:
 * reading the picked document, writing the export document and the clipboard /
 * share fallbacks.
 *
 * `ActivityResultLauncher`s can only be registered during activity creation, so
 * [register] has to be called from `MainActivity.onCreate`.
 */
class AndroidScriptTransferController(
    private val context: Context,
) : ScriptTransferController {

    private var importLauncher: ActivityResultLauncher<Array<String>>? = null

    private var exportLauncher: ActivityResultLauncher<String>? = null

    /** Pending export payload, consumed by the create-document callback. */
    private var pendingExport: Pair<String, String>? = null

    override var onPicked: ((fileName: String?, content: String) -> Unit)? = null

    /** Registers the SAF launchers; must run during activity creation. */
    fun register(caller: ActivityResultCaller) {
        // HyperOS 3 / Android 16 file picker: `ACTION_OPEN_DOCUMENT` with a MIME
        // filter. `ActivityResultContracts.OpenDocument` sets `type = */*` and
        // forwards our array as EXTRA_MIME_TYPES, so a *narrow* array is what makes
        // the picker list only script documents. `.arscript` is not a registered
        // MIME type, so the picker reports it as generic binary data — hence both
        // `application/json` (JSON content) and `application/octet-stream`.
        // Never add `*/*` or the picker falls back to listing every file.
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
            // `*/*` keeps the suggested file name untouched, including our
            // `.arscript` extension: a concrete MIME would make the picker append
            // the extension it derives from it (application/json -> ".json").
            ActivityResultContracts.CreateDocument("*/*"),
        ) { uri: Uri? ->
            val payload = pendingExport
            pendingExport = null
            if (payload == null) return@registerForActivityResult
            if (uri == null) {
                // The user backed out of the picker: nothing to write, but keep the
                // content reachable through the clipboard.
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
        // The guide's `resolveActivity` probe is advisory only: MIUI's picker
        // declares a `*/*` filter, so resolving a concrete MIME type can return
        // null even though launching the intent works. Log it and launch anyway.
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
            // No activity registered the picker (or it is already gone): sharing the
            // file is a usable fallback instead of failing silently.
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
         * MIME types the import picker is allowed to show.
         *
         * `.arscript` files are JSON documents that most ROMs type as generic binary
         * data, so both types are required for the user's own scripts to be visible.
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
     * `true` when a system file picker can handle the import intent.
     *
     * Mirrors the compatibility check recommended by the HyperOS file picker guide
     * so an unsupported system fails with a message instead of an ActivityNotFound.
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
