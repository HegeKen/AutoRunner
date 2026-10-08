package com.autorunner.core.storage

import com.autorunner.core.platform.AndroidPlatform
import java.io.File

/**
 * 应用私有存储：`<filesDir>/scripts`（每个脚本一个 `.arscript` 文件）。
 *
 * 使用内部存储意味着导入或导出脚本始终无需存储权限；面向用户的导入/导出
 * 走应用模块中的存储访问框架（Storage Access Framework）。
 */
class AndroidScriptStorage(private val root: File) : ScriptStorage {

    override val location: String get() = root.absolutePath

    override fun list(): List<String> =
        root.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    override fun read(name: String): String? {
        val file = File(root, name)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    override fun write(name: String, content: String) {
        if (!root.exists()) root.mkdirs()
        // 先写入再重命名，可在进程中断或写入过程中磁盘写满时
        // 保持原文件完好无损。
        val target = File(root, name)
        val tmp = File(root, "$name.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    override fun delete(name: String): Boolean = File(root, name).delete()

    override fun exists(name: String): Boolean = File(root, name).isFile

    override fun lastModified(name: String): Long = File(root, name).lastModified()

    override fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}

actual fun createScriptStorage(): ScriptStorage {
    val context = AndroidPlatform.applicationContext ?: return InMemoryScriptStorage()
    val root = File(context.filesDir, "scripts")
    if (!root.exists()) root.mkdirs()
    return AndroidScriptStorage(root)
}
