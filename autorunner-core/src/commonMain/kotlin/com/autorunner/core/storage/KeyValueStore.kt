package com.autorunner.core.storage

/**
 * Tiny key/value persistence used for user settings.
 *
 * Android implementation is backed by `SharedPreferences`, the JVM one by a
 * properties file. Values are stored as strings; callers use
 * [SettingsCodec] helpers to (de)serialise richer objects.
 */
interface KeyValueStore {
    fun getString(key: String, default: String? = null): String?

    fun putString(key: String, value: String)

    fun getBoolean(key: String, default: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun getInt(key: String, default: Int): Int

    fun putInt(key: String, value: Int)

    fun getLong(key: String, default: Long): Long

    fun putLong(key: String, value: Long)

    fun getFloat(key: String, default: Float): Float

    fun putFloat(key: String, value: Float)

    fun contains(key: String): Boolean

    fun remove(key: String)

    fun clear()
}

/** In-memory [KeyValueStore], used by tests. */
class InMemoryKeyValueStore(
    initial: Map<String, String> = emptyMap(),
) : KeyValueStore {

    private val values = LinkedHashMap<String, String>()

    init {
        values.putAll(initial)
    }

    override fun getString(key: String, default: String?): String? = values[key] ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value.toString()
    }

    override fun getInt(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default

    override fun putInt(key: String, value: Int) {
        values[key] = value.toString()
    }

    override fun getLong(key: String, default: Long): Long = values[key]?.toLongOrNull() ?: default

    override fun putLong(key: String, value: Long) {
        values[key] = value.toString()
    }

    override fun getFloat(key: String, default: Float): Float = values[key]?.toFloatOrNull() ?: default

    override fun putFloat(key: String, value: Float) {
        values[key] = value.toString()
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}

/** Creates the platform default settings store. */
expect fun createKeyValueStore(name: String = "autorunner_settings"): KeyValueStore
