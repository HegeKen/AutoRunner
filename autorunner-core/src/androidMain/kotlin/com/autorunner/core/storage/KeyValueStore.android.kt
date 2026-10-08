package com.autorunner.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.autorunner.core.platform.AndroidPlatform

/** `SharedPreferences` backed [KeyValueStore]. */
class AndroidKeyValueStore(private val preferences: SharedPreferences) : KeyValueStore {

    override fun getString(key: String, default: String?): String? = preferences.getString(key, default)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = preferences.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = preferences.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    override fun getFloat(key: String, default: Float): Float = preferences.getFloat(key, default)

    override fun putFloat(key: String, value: Float) {
        preferences.edit().putFloat(key, value).apply()
    }

    override fun contains(key: String): Boolean = preferences.contains(key)

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}

actual fun createKeyValueStore(name: String): KeyValueStore {
    val context = AndroidPlatform.applicationContext ?: return InMemoryKeyValueStore()
    return AndroidKeyValueStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
}
