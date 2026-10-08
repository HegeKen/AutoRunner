package com.autorunner.core.recording

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.CoordinateResolver
import com.autorunner.core.model.CoordinateSpace
import com.autorunner.core.model.DeviceInfo
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.RecordingConfig
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptInfo
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.util.currentIsoTimestamp
import com.autorunner.core.util.isoDatePart

/**
 * 由分类后的动作组装出 [ScriptModel]。
 *
 * 录制会话停止时与编辑器重新保存已编辑流程时都会用到它，
 * 因此两条路径产出的元数据完全一致。
 */
object RecordedScriptBuilder {

    fun build(
        name: String,
        steps: List<ActionStep>,
        metrics: ScreenMetrics,
        execution: ExecutionConfig = ExecutionConfig(mode = com.autorunner.core.model.ExecutionMode.ONCE),
        recordingConfig: RecordingConfig = RecordingConfig.Default,
        description: String = "",
        tags: List<String> = emptyList(),
        createdAt: String = currentIsoTimestamp(),
    ): ScriptModel {
        val safeName = name.ifBlank { "录制脚本 ${isoDatePart(createdAt)}" }
        val space = if (recordingConfig.normaliseCoordinates) CoordinateSpace.NORMALIZED else CoordinateSpace.ABSOLUTE
        val base = ScriptModel(
            info = ScriptInfo(
                name = safeName,
                description = description.ifBlank { "录制于 ${isoDatePart(createdAt)}" },
                device = DeviceInfo.of(metrics),
                createdAt = createdAt,
                coordinateSpace = CoordinateSpace.ABSOLUTE,
                tags = tags,
            ),
            execution = execution.sanitized(),
            flow = steps,
        )
        return if (space == CoordinateSpace.NORMALIZED && metrics.isValid) {
            CoordinateResolver.normaliseScript(base, metrics)
        } else {
            base
        }
    }
}
