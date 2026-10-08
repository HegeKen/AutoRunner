package com.autorunner.core.storage

import java.io.File
import java.util.Properties

/** Properties file backed [KeyValueStore] for the desktop target. */
class DesktopKeyValueStore(private val file: File) : KeyValueStore {

    private val lock = Any()

    private val properties = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    override fun getString(key: String, default: String?): String? =
        synchronized(lock) { properties.getProperty(key) } ?: default

    override fun putString(key: String, value: String) = mutate { it.setProperty(key, value) }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        synchronized(lock) { properties.getProperty(key) }?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) = mutate { it.setProperty(key, value.toString()) }

    override fun getInt(key: String, default: Int): Int =
        synchronized(lock) { properties.getProperty(key) }?.toIntOrNull() ?: default

    override fun putInt(key: String, value: Int) = mutate { it.setProperty(key, value.toString()) }

    override fun getLong(key: String, default: Long): Long =
        synchronized(lock) { properties.getProperty(key) }?.toLongOrNull() ?: default

    override fun putLong(key: String, value: Long) = mutate { it.setProperty(key, value.toString()) }

    override fun getFloat(key: String, default: Float): Float =
        synchronized(lock) { properties.getProperty(key) }?.toFloatOrNull() ?: default

    override fun putFloat(key: String, value: Float) = mutate { it.setProperty(key, value.toString()) }

    override fun contains(key: String): Boolean = synchronized(lock) { properties.containsKey(key) }

    override fun remove(key: String) = mutate { it.remove(key) }

    override fun clear() = mutate { it.clear() }

    private inline fun mutate(block: (Properties) -> Unit) {
        synchronized(lock) {
            block(properties)
            file.parentFile?.mkdirs()
            try {
                file.outputStream().use { properties.store(it, "AutoRunner settings") }
            } catch (e: Exception) {
                System.err.println("AutoRunner: failed to persist settings to ${file.absolutePath}: $e")
            }
        }
    }
}

actual fun createKeyValueStore(name: String): KeyValueStore =
    DesktopKeyValueStore(File(System.getProperty("user.home"), ".autorunner/$name.properties"))
