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

/** Fixed AutoRunner palette. */
object AutoRunnerColors {

    /** Brand primary used by both the light and the dark scheme. */
    val Primary = Color(0xFF2655FF)

    /** Accent used for the recording state. */
    val Recording = Color(0xFFFF4D4F)

    /** Accent used while a script runs. */
    val Running = Color(0xFF16A34A)

    /** Accent used when a run is paused. */
    val Paused = Color(0xFFF59E0B)

    /** Darker shade of the brand colour, used for pressed / variant surfaces. */
    val PrimaryVariant = Color(0xFF1B3FCC)

    /** Light tint of the brand colour, used for containers. */
    val PrimaryTint = Color(0xFFE8EDFF)
}

/**
 * Light scheme.
 *
 * AutoRunner no longer offers Monet (dynamic colour) or a user picked seed: the
 * brand colour `#2655FF` is pinned and only the light / dark pair remains, which
 * keeps the product recognisable and MIUIX' Material You generation out of the
 * critical path.
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

/** Dark scheme; the brand primary is kept identical so accents stay consistent. */
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

/** Maps the persisted [ThemeMode] onto MIUIX' `ColorSchemeMode`. */
fun ThemeMode.toColorSchemeMode(): ColorSchemeMode = when (this) {
    ThemeMode.SYSTEM -> ColorSchemeMode.System
    ThemeMode.LIGHT -> ColorSchemeMode.Light
    ThemeMode.DARK -> ColorSchemeMode.Dark
}

/**
 * AutoRunner's MIUIX theme.
 *
 * Only three modes exist — follow the system, always light, always dark — and both
 * schemes share the `#2655FF` brand primary.
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
 * Accent colour representing an [ExecutionState] — the single source of truth
 * for state colours, shared by the status pill and the floating ball.
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
