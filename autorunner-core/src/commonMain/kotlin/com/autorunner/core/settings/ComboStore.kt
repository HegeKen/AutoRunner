package com.autorunner.core.settings

import com.autorunner.core.model.ComboConfig
import com.autorunner.core.serialization.ArScriptJson
import com.autorunner.core.storage.KeyValueStore

/**
 * 「组合运行」配置的持久化。
 *
 * 只保存两个脚本的 id 与执行参数（循环次数 / 间隔 / 首轮延迟），不复制脚本
 * 内容；脚本本身仍以 `.arscript` 文件为准。配置损坏或缺失时回退到
 * [ComboConfig.Empty]，不会阻止应用启动。
 */
class ComboStore(
    private val store: KeyValueStore,
) {

    /** 读取已保存的组合配置；从未保存或解析失败时返回空配置。 */
    fun load(): ComboConfig {
        val raw = store.getString(KEY)?.takeIf { it.isNotBlank() } ?: return ComboConfig.Empty
        return runCatching { ArScriptJson.compact.decodeFromString(ComboConfig.serializer(), raw) }
            .getOrDefault(ComboConfig.Empty)
            .sanitized()
    }

    /** 写入组合配置；空配置也照常保存，便于用户清空选择。 */
    fun save(config: ComboConfig) {
        val encoded = runCatching {
            ArScriptJson.compact.encodeToString(ComboConfig.serializer(), config.sanitized())
        }.getOrNull() ?: return
        store.putString(KEY, encoded)
    }

    fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "combo.config"
    }
}
