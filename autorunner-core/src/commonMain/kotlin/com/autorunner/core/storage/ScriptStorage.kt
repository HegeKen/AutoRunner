package com.autorunner.core.storage

import com.autorunner.core.util.currentTimeMillis

/**
 * 脚本仓库所基于的极简文件抽象。
 *
 * Android 上用 `Context.filesDir` 实现（应用私有存储，无需运行时权限），
 * JVM 上用用户主目录内的一个目录实现。保持为接口使仓库可完全测试。
 */
interface ScriptStorage {
    /** 展示给用户的目录（若平台有）。 */
    val location: String

    /** 当前已存储的文件名（不含目录），已排序。 */
    fun list(): List<String>

    fun read(name: String): String?

    fun write(name: String, content: String)

    fun delete(name: String): Boolean

    fun exists(name: String): Boolean

    /** 最后修改时间（纪元毫秒），未知时为 `0`。 */
    fun lastModified(name: String): Long = 0L

    /** 删除所有已存储的文件。 */
    fun clear()
}

/** 测试使用、并作为安全兜底的内存版 [ScriptStorage]。 */
class InMemoryScriptStorage(initial: Map<String, String> = emptyMap()) : ScriptStorage {

    private val files = LinkedHashMap<String, String>()
    private val timestamps = HashMap<String, Long>()

    init {
        files.putAll(initial)
    }

    override val location: String get() = "memory://autorunner/scripts"

    override fun list(): List<String> = files.keys.sorted()

    override fun read(name: String): String? = files[name]

    override fun write(name: String, content: String) {
        files[name] = content
        timestamps[name] = currentTimeMillis()
    }

    override fun delete(name: String): Boolean {
        timestamps.remove(name)
        return files.remove(name) != null
    }

    override fun exists(name: String): Boolean = files.containsKey(name)

    override fun lastModified(name: String): Long = timestamps[name] ?: 0L

    override fun clear() {
        files.clear()
        timestamps.clear()
    }
}

/** 创建平台默认的脚本存储。 */
expect fun createScriptStorage(): ScriptStorage
