package com.autorunner.core.storage

import com.autorunner.core.util.currentTimeMillis

/**
 * Minimal file abstraction the script repository is built on.
 *
 * Implemented on Android with `Context.filesDir` (app private storage, no
 * runtime permission required) and on the JVM with a directory inside the user
 * home. Keeping it an interface makes the repository fully testable.
 */
interface ScriptStorage {
    /** Directory shown to the user, if the platform has one. */
    val location: String

    /** File names (without directory) currently stored, sorted. */
    fun list(): List<String>

    fun read(name: String): String?

    fun write(name: String, content: String)

    fun delete(name: String): Boolean

    fun exists(name: String): Boolean

    /** Last modification time in epoch milliseconds, `0` when unknown. */
    fun lastModified(name: String): Long = 0L

    /** Removes every stored file. */
    fun clear()
}

/** In-memory [ScriptStorage] used by tests and as a safe fallback. */
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

/** Creates the platform default script storage. */
expect fun createScriptStorage(): ScriptStorage
