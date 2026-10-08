package com.autorunner.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.autorunner.core.model.ExecutionState
import com.autorunner.core.model.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** AutoRunner 固定配色。 */
object AutoRunnerColors {

    /** 浅色与深色方案共用的品牌主色。 */
    val Primary = Color(0xFF2655FF)

    /** 录制状态使用的强调色。 */
    val Recording = Color(0xFFFF4D4F)

    /** 脚本运行中使用的强调色。 */
    val Running = Color(0xFF16A34A)

    /** 运行暂停时使用的强调色。 */
    val Paused = Color(0xFFF59E0B)

    /** 品牌色的深色变体，用于按压 / 变体表面。 */
    val PrimaryVariant = Color(0xFF1B3FCC)

    /** 品牌色的浅色色调，用于容器背景。 */
    val PrimaryTint = Color(0xFFE8EDFF)
}

/**
 * 浅色方案。
 *
 * AutoRunner 不再提供 Monet（动态取色）或用户自选取色种子：
 * 品牌色 `#2655FF` 被固定，只保留浅色 / 深色两套方案，这样既保证了产品
 * 辨识度，也把 MIUIX 的 Material You 生成逻辑排除在关键路径之外。
 */
private val AutoRunnerLightColors: Colors = lightColorScheme(
    primary = AutoRunnerColors.Primary,
    onPrimary = Color.White,
    primaryVariant = AutoRunnerColors.PrimaryVariant,
    onPrimaryVariant = Color(0xFFD6E0FF),
    primaryContainer = Color(0xFF4C74FF),
    onPrimaryContainer = Color.White,
    disabledPrimary = Color(0xFFB9C6FF),
    disabledOnPrimary = Color(0xFFF2F5FF),
    disabledPrimaryButton = Color(0xFFB9C6FF),
    disabledOnPrimaryButton = Color.White,
    disabledPrimarySlider = Color(0xFFA9B8F2),
    tertiaryContainer = AutoRunnerColors.PrimaryTint,
    onTertiaryContainer = AutoRunnerColors.Primary,
    sliderKeyPointForeground = Color(0xFF6E8CFF),
)

/** 深色方案；品牌主色保持一致，以确保强调色在两套方案中统一。 */
private val AutoRunnerDarkColors: Colors = darkColorScheme(
    primary = AutoRunnerColors.Primary,
    onPrimary = Color.White,
    primaryVariant = AutoRunnerColors.PrimaryVariant,
    onPrimaryVariant = Color(0xFFD6E0FF),
    primaryContainer = Color(0xFF1B2E7A),
    onPrimaryContainer = Color(0xFFD6E0FF),
    disabledPrimary = Color(0xFF2A3A66),
    disabledOnPrimary = Color(0xFF9AA7CC),
    disabledPrimaryButton = Color(0xFF2A3A66),
    disabledOnPrimaryButton = Color(0xFF9AA7CC),
    disabledPrimarySlider = Color(0xFF33427A),
    tertiaryContainer = Color(0xFF16244F),
    onTertiaryContainer = Color(0xFFB9C6FF),
    sliderKeyPointForeground = Color(0xFF6E8CFF),
)

/** 将持久化的 [ThemeMode] 映射到 MIUIX 的 `ColorSchemeMode`。 */
fun ThemeMode.toColorSchemeMode(): ColorSchemeMode = when (this) {
    ThemeMode.SYSTEM -> ColorSchemeMode.System
    ThemeMode.LIGHT -> ColorSchemeMode.Light
    ThemeMode.DARK -> ColorSchemeMode.Dark
}

/**
 * AutoRunner 的 MIUIX 主题。
 *
 * 只有三种模式——跟随系统、始终浅色、始终深色——且两套方案共用
 * `#2655FF` 品牌主色。
 *
 * ## 文字色角色（避免同类文字在不同页面深浅不一）
 *
 * MIUIX 的 `onSurfaceSecondary`（黑色 80%）与 `onSurfaceVariantSummary`（黑色 60%）
 * 是**两级**弱化文本，不是同义词。项目统一按用途取值，新增页面请照此执行：
 *
 *  - 标题：页面 / 分区标题用 `onBackground`（直接坐在页面背景上），
 *    卡片正文标题用 `onSurface`（坐在卡片表面上）；两者不要互换。
 *  - 次级正文（`body1` / `body2` 段落、说明性句子）：`onSurfaceSecondary`。
 *  - 提示与脚注（`footnote1` / `footnote2` 的小字、单位、状态说明）：
 *    `onSurfaceVariantSummary`。
 *  - 禁用态：用组件自己的 `disabled*` 颜色，不要拿上面两种颜色顶替。
 */
@Composable
fun AutoRunnerTheme(
    themeMode: ThemeMode = ThemeMode.Default,
    content: @Composable () -> Unit,
) {
    val controller = remember(themeMode) {
        ThemeController(
            colorSchemeMode = themeMode.toColorSchemeMode(),
            lightColors = AutoRunnerLightColors,
            darkColors = AutoRunnerDarkColors,
        )
    }
    MiuixTheme(controller = controller, content = content)
}

/**
 * 代表 [ExecutionState] 的强调色——状态颜色的唯一事实来源，
 * 状态胶囊与悬浮球共用。
 */
@Composable
fun ExecutionState.accentColor(): Color = when (this) {
    ExecutionState.RUNNING -> AutoRunnerColors.Running
    ExecutionState.PAUSED -> AutoRunnerColors.Paused
    ExecutionState.STOPPED -> MiuixTheme.colorScheme.error
    // 成功结束与运行中共用绿色（终态成功）；悬浮球同样显示 ✓。
    ExecutionState.COMPLETED -> AutoRunnerColors.Running
    ExecutionState.IDLE -> MiuixTheme.colorScheme.primary
}
