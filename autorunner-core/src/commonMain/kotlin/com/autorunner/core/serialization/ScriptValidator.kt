package com.autorunner.core.serialization

import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ArScriptConventions
import com.autorunner.core.model.CoordinateSpace
import com.autorunner.core.model.DelayStep
import com.autorunner.core.model.ExecutionConfig
import com.autorunner.core.model.GamepadAction
import com.autorunner.core.model.GamepadStep
import com.autorunner.core.model.KeyStep
import com.autorunner.core.model.LongPressStep
import com.autorunner.core.model.MultiTouchStep
import com.autorunner.core.model.ScreenMetrics
import com.autorunner.core.model.ScriptModel
import com.autorunner.core.model.SwipeStep
import com.autorunner.core.model.TapStep

/** [ValidationIssue] 的严重级别。 */
enum class ValidationSeverity { ERROR, WARNING }

/** [ScriptValidator] 发现的单个问题。 */
data class ValidationIssue(
    val code: String,
    val message: String,
    val severity: ValidationSeverity,
    /** 在 `flow` 中的下标；脚本级问题为 `null`。 */
    val stepIndex: Int? = null,
)

/** 脚本在存储或执行前校验的结果。 */
data class ValidationResult(val issues: List<ValidationIssue> = emptyList()) {

    val errors: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == ValidationSeverity.WARNING }

    val isValid: Boolean get() = errors.isEmpty()
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    fun summary(): String = when {
        issues.isEmpty() -> "校验通过"
        else -> buildString {
            if (errors.isNotEmpty()) append("${errors.size} 个错误")
            if (errors.isNotEmpty() && warnings.isNotEmpty()) append("，")
            if (warnings.isNotEmpty()) append("${warnings.size} 个警告")
        }
    }

    companion object {
        val Valid = ValidationResult()
    }
}

/**
 * 对 [ScriptModel] 的纯校验。编辑器（行内提示）、导入器（拒绝损坏文件）
 * 和执行器（快速失败，而不是分发乱七八糟的手势）都会用到。
 */
object ScriptValidator {

    /** 合理的手势时长上限，防止损坏文件。 */
    private const val MAX_ACTION_DURATION_MS = 120_000L

    private const val MAX_DELAY_MS = 3_600_000L

    /**
     * 键盘输入文本上限。`ACTION_SET_TEXT` 派发超长文本（如损坏/恶意文件里的
     * MB 级字符串）可能拖死无障碍服务导致 ANR，正常输入远达不到该量级。
     */
    private const val MAX_KEY_TEXT_LENGTH = 10_000

    fun validate(script: ScriptModel, metrics: ScreenMetrics? = null): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        if (script.version !in ArScriptConventions.SUPPORTED_VERSIONS) {
            issues += ValidationIssue(
                code = "unsupported_version",
                message = "未知的脚本版本 ${script.version}，当前支持 ${ArScriptConventions.SUPPORTED_VERSIONS.joinToString()}",
                severity = ValidationSeverity.ERROR,
            )
        }

        if (script.flow.isEmpty()) {
            issues += ValidationIssue(
                code = "empty_flow",
                message = "脚本不包含任何动作",
                severity = ValidationSeverity.ERROR,
            )
        }

        if (script.info.name.isBlank()) {
            issues += ValidationIssue(
                code = "blank_name",
                message = "脚本名称为空，将使用默认名称",
                severity = ValidationSeverity.WARNING,
            )
        }

        val device = script.info.device
        val bounds = when {
            metrics != null && metrics.isValid -> metrics
            device.width > 0 && device.height > 0 -> ScreenMetrics(device.width, device.height, device.density)
            else -> null
        }
        if (bounds == null) {
            issues += ValidationIssue(
                code = "missing_device_info",
                message = "脚本未记录录制设备分辨率，无法换算绝对坐标",
                severity = ValidationSeverity.WARNING,
            )
        }

        validateExecution(script.execution, issues)

        script.flow.forEachIndexed { index, step ->
            validateStep(step, index, script.info.coordinateSpace, bounds, issues)
        }

        return ValidationResult(issues)
    }

    private fun validateExecution(config: ExecutionConfig, issues: MutableList<ValidationIssue>) {
        if (config.intervalMs < 0) {
            issues += ValidationIssue(
                code = "negative_interval",
                message = "循环间隔不能为负数",
                severity = ValidationSeverity.ERROR,
            )
        }
        if (config.repeatCount < 0) {
            issues += ValidationIssue(
                code = "negative_repeat",
                message = "重复次数不能为负数",
                severity = ValidationSeverity.ERROR,
            )
        }
        if (config.mode == com.autorunner.core.model.ExecutionMode.ONCE && config.repeatCount > 1) {
            issues += ValidationIssue(
                code = "repeat_count_ignored",
                message = "单次执行模式下重复次数不会生效",
                severity = ValidationSeverity.WARNING,
            )
        }
        if (config.reconnectTimeoutMs < 0) {
            issues += ValidationIssue(
                code = "negative_reconnect_timeout",
                message = "服务重连超时不能为负数",
                severity = ValidationSeverity.ERROR,
            )
        }
    }

    private fun validateStep(
        step: ActionStep,
        index: Int,
        space: CoordinateSpace,
        bounds: ScreenMetrics?,
        issues: MutableList<ValidationIssue>,
    ) {
        if (step.delay < 0) {
            issues += ValidationIssue("negative_delay", "延迟不能为负数", ValidationSeverity.ERROR, index)
        } else if (step.delay > MAX_DELAY_MS) {
            issues += ValidationIssue("excessive_delay", "延迟超过 1 小时", ValidationSeverity.WARNING, index)
        }

        when (step) {
            is TapStep -> {
                durationIssue(step.duration, index, "点击", issues)
                coordinateIssue(step.x, step.y, index, space, bounds, issues)
            }

            is LongPressStep -> {
                durationIssue(step.duration, index, "长按", issues)
                coordinateIssue(step.x, step.y, index, space, bounds, issues)
            }

            is SwipeStep -> {
                durationIssue(step.duration, index, "滑动", issues)
                coordinateIssue(step.fromX, step.fromY, index, space, bounds, issues)
                coordinateIssue(step.toX, step.toY, index, space, bounds, issues)
                if (step.fromX == step.toX && step.fromY == step.toY) {
                    issues += ValidationIssue(
                        "degenerate_swipe",
                        "滑动的起点与终点相同",
                        ValidationSeverity.WARNING,
                        index,
                    )
                }
            }

            is MultiTouchStep -> {
                durationIssue(step.duration, index, "多点触控", issues)
                if (step.points.size < 2) {
                    issues += ValidationIssue(
                        "insufficient_pointers",
                        "多点触控至少需要 2 个触点",
                        ValidationSeverity.ERROR,
                        index,
                    )
                }
                step.points.forEach { coordinateIssue(it.x, it.y, index, space, bounds, issues) }
            }

            is GamepadStep -> {
                if (step.value < 0f || step.value > 1f) {
                    issues += ValidationIssue(
                        "invalid_analogue_value",
                        "手柄模拟量必须在 0.0 ~ 1.0 之间",
                        ValidationSeverity.ERROR,
                        index,
                    )
                }
                if (step.action == GamepadAction.STICK && (step.x < -1f || step.x > 1f || step.y < -1f || step.y > 1f)) {
                    issues += ValidationIssue(
                        "invalid_stick_range",
                        "摇杆坐标必须在 -1.0 ~ 1.0 之间",
                        ValidationSeverity.ERROR,
                        index,
                    )
                }
            }

            is DelayStep -> {
                if (step.duration <= 0) {
                    issues += ValidationIssue("non_positive_duration", "等待时间必须大于 0", ValidationSeverity.ERROR, index)
                }
            }

            is KeyStep -> {
                if (step.text.isEmpty()) {
                    issues += ValidationIssue(
                        "empty_key_text",
                        "键盘输入内容不能为空",
                        ValidationSeverity.ERROR,
                        index,
                    )
                } else if (step.text.length > MAX_KEY_TEXT_LENGTH) {
                    issues += ValidationIssue(
                        "excessive_key_text",
                        "键盘输入内容超过 $MAX_KEY_TEXT_LENGTH 字符上限",
                        ValidationSeverity.ERROR,
                        index,
                    )
                }
            }
        }
    }

    private fun durationIssue(duration: Long, index: Int, name: String, issues: MutableList<ValidationIssue>) {
        when {
            duration <= 0 -> issues += ValidationIssue(
                "non_positive_duration",
                "$name 时长必须大于 0",
                ValidationSeverity.ERROR,
                index,
            )

            duration > MAX_ACTION_DURATION_MS -> issues += ValidationIssue(
                "excessive_duration",
                "$name 时长超过 2 分钟",
                ValidationSeverity.WARNING,
                index,
            )
        }
    }

    private fun coordinateIssue(
        x: Float,
        y: Float,
        index: Int,
        space: CoordinateSpace,
        bounds: ScreenMetrics?,
        issues: MutableList<ValidationIssue>,
    ) {
        when (space) {
            CoordinateSpace.ABSOLUTE -> {
                if (x < 0f || y < 0f) {
                    issues += ValidationIssue("negative_coordinate", "坐标不能为负数", ValidationSeverity.ERROR, index)
                    return
                }
                if (bounds != null && (x > bounds.widthPx || y > bounds.heightPx)) {
                    issues += ValidationIssue(
                        "coordinate_out_of_bounds",
                        "坐标 ($x, $y) 超出录制设备分辨率 ${bounds.widthPx}×${bounds.heightPx}",
                        ValidationSeverity.WARNING,
                        index,
                    )
                }
            }

            CoordinateSpace.NORMALIZED -> {
                if (x !in 0f..1f || y !in 0f..1f) {
                    issues += ValidationIssue(
                        "normalised_out_of_range",
                        "百分比坐标必须位于 0.0 ~ 1.0 之间",
                        ValidationSeverity.ERROR,
                        index,
                    )
                }
            }
        }
    }
}
