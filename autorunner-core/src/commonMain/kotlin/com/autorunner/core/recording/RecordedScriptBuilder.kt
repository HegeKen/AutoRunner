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
 * Assembles a [ScriptModel] from classified actions.
 *
 * Used when a recording session stops and when the editor re-saves an edited
 * flow, so both paths produce byte-identical metadata.
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
