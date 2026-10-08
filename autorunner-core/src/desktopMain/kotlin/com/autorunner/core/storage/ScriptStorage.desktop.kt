package com.autorunner.core.storage

import java.io.File

/**
 * JVM storage: `~/.autorunner/scripts` (one `.arscript` file per script).
 *
 * Useful for the desktop preview build and for integration tests, which pass an
 * explicit root directory.
 */
class DesktopScriptStorage(private val root: File) : ScriptStorage {

    override val location: String get() = root.absolutePath

    override fun list(): List<String> =
        root.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    override fun read(name: String): String? {
        val file = File(root, name)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    override fun write(name: String, content: String) {
        if (!root.exists()) root.mkdirs()
        // Write-then-rename keeps the previous file intact when the process dies
        // or the disk fills up mid-write.
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

actual fun createScriptStorage(): ScriptStorage =
    DesktopScriptStorage(File(System.getProperty("user.home"), ".autorunner/scripts"))
