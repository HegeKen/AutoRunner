package com.autorunner.core.settings

import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.InputMode
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.FailureStrategy
import com.autorunner.core.model.GamepadMappings
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.RecordingConfig
import com.autorunner.core.model.ThemeMode
import com.autorunner.core.serialization.ArScriptJson
import com.autorunner.core.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 持久化 [AppSettings] 并以可观察的 [StateFlow] 暴露，
 * 使 Compose UI 与各服务都能立即对变更作出反应。
 */
class SettingsRepository(
    private val store: KeyValueStore,
    private val json: Json = ArScriptJson.compact,
) {

    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    /** 应用 [transform] 并持久化结果。 */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = transform(_settings.value).sanitized()
        _settings.value = updated
        write(updated)
        return updated
    }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDefaultExecution(config: ExecutionConfig) = update { it.copy(defaultExecution = config.sanitized()) }

    fun setDefaultRepeatCount(count: Int) = update {
        it.copy(
            defaultExecution = it.defaultExecution.copy(
                mode = if (count == 1) ExecutionMode.ONCE else ExecutionMode.REPEAT,
                repeatCount = count.coerceAtLeast(0),
            ),
        )
    }

    fun setFailureStrategy(strategy: FailureStrategy) = update { it.copy(failureStrategy = strategy) }

    fun setRecordingConfig(config: RecordingConfig) = update { it.copy(recording = config) }

    fun setShowFloatingBall(enabled: Boolean) = update { it.copy(showFloatingBallOnStart = enabled) }

    fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOnWhileRunning = enabled) }

    fun setExecutionNotification(enabled: Boolean) = update { it.copy(executionNotification = enabled) }

    fun setExecutionReminder(enabled: Boolean) = update { it.copy(executionReminder = enabled) }

    fun setHapticFeedback(enabled: Boolean) = update { it.copy(hapticFeedback = enabled) }

    fun setAutoCollapsePanel(enabled: Boolean) = update { it.copy(autoCollapsePanel = enabled) }

    fun setGamepadEnabled(enabled: Boolean) = update { it.copy(gamepadEnabled = enabled) }

    fun setGamepadMode(mode: GamepadMode) = update { it.copy(gamepadMode = mode) }

    /** 替换为 [mode] 存储的标定结果（每种模式各存一份）。 */
    fun setGamepadMappings(mode: GamepadMode, mappings: GamepadMappings) = update {
        it.copy(gamepadMappingsByMode = it.gamepadMappingsByMode + (mode to mappings))
    }

    /** 切换模拟输入方式（无障碍 / Root 注入）。 */
    fun setInputMode(mode: InputMode) = update { it.copy(inputMode = mode) }

    fun setSkipOemGuidance(skip: Boolean) = update { it.copy(skipOemGuidance = skip) }

    fun reset() = update { AppSettings.Default }

    private fun read(): AppSettings {
        val raw = store.getString(KEY_SETTINGS) ?: return AppSettings.Default
        decode(raw)?.let { return it.sanitized() }
        // 架构迁移：已移除选项的存量值（Monet 主题模式和种子色）
        // 会导致解码失败，并静默重置其他所有偏好，因此先丢弃这些键再重试一次。
        return migrate(raw)?.sanitized() ?: AppSettings.Default
    }

    private fun decode(raw: String): AppSettings? =
        runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrNull()

    private fun migrate(raw: String): AppSettings? = runCatching {
        val element = json.parseToJsonElement(raw).jsonObject.toMutableMap()
        REMOVED_KEYS.forEach(element::remove)
        json.decodeFromString(AppSettings.serializer(), JsonObject(element).toString())
    }.getOrNull()

    private fun write(settings: AppSettings) {
        store.putString(KEY_SETTINGS, json.encodeToString(AppSettings.serializer(), settings))
    }

    private companion object {
        const val KEY_SETTINGS = "app_settings_v1"

        /** 旧版本存在、现在必须忽略的字段。 */
        val REMOVED_KEYS = listOf("keyColor", "themeMode")
    }
}
