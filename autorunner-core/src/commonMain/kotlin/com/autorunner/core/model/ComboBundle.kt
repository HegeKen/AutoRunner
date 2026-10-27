package com.autorunner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 可导入 / 导出的「组合任务」文件（`.arcombo`）。
 *
 * 组合配置本身只引用脚本 id，离开本机后这些 id 没有意义；因此导出时把两段
 * 脚本的完整内容一起嵌入，导入端先保存脚本、再把槽位映射到新脚本 id，保证
 * 组合任务可以跨设备 / 备份恢复后直接使用。
 */
@Serializable
data class ComboBundle(
    @SerialName("format") val format: String = FORMAT,
    @SerialName("version") val version: Int = VERSION,
    /** ISO-8601 导出时间，便于用户识别文件。 */
    @SerialName("exportedAt") val exportedAt: String = "",
    /** 两个槽位的脚本 id 与执行参数。 */
    @SerialName("combo") val combo: ComboConfig = ComboConfig.Empty,
    /** 被引用的脚本原文；导入端会重新落盘并重映射 id。 */
    @SerialName("scripts") val scripts: List<ComboBundleScript> = emptyList(),
) {
    companion object {
        /** 文件内的格式标识，避免把普通脚本误读成组合任务。 */
        const val FORMAT = "autorunner.combo"

        /** 当前文件格式版本。 */
        const val VERSION = 1

        /** 不带前导点的扩展名。 */
        const val FILE_EXTENSION = "arcombo"

        const val DOT_EXTENSION = ".$FILE_EXTENSION"
    }
}

/** 组合任务中嵌入的一段脚本，以及导出前的原始 id。 */
@Serializable
data class ComboBundleScript(
    @SerialName("originalId") val originalId: String = "",
    @SerialName("script") val script: ScriptModel = ScriptModel(),
)
