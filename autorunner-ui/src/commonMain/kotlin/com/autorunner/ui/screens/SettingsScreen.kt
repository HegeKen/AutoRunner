/*
 * AutoRunner — 设置页面（共享 UI，commonMain）
 *
 * 覆盖设计文档中的以下章节：
 *  - §4.5.2 关键组件映射：设置页使用 `SwitchPreference` / `ArrowPreference` /
 *    `RadioButtonPreference` / `OverlayDropdownPreference` 等 MIUIX 偏好组件；
 *  - §6.4.2 悬浮窗权限适配：无障碍、悬浮窗、通知、蓝牙的授权入口，以及
 *    MIUI / ColorOS / OriginOS 等厂商 ROM 的差异化引导；
 *  - §5.1 / §5.2 多设备适配：以窗口尺寸类别驱动单列与双列布局；
 *  - §9 权限清单：逐项列出 AutoRunner 需要的权限及其用途。
 *
 * 页面本身不触碰任何平台 API：所有授权动作都通过
 * `com.autorunner.ui.platform.PermissionController` 转发到 Android 层。
 */
package com.autorunner.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.icon.extended.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autorunner.core.BuildInfo
import com.autorunner.core.model.AppSettings
import com.autorunner.core.model.GamepadButton
import com.autorunner.core.model.GamepadButtonMapping
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.ThemeMode
import com.autorunner.core.platform.DockEdge
import com.autorunner.ui.adaptive.AutoRunnerWindowSize
import com.autorunner.ui.adaptive.LayoutMode
import com.autorunner.ui.components.ExecutionConfigSection
import com.autorunner.ui.components.FormDialog
import com.autorunner.ui.components.GamepadCalibrationEntry
import com.autorunner.ui.components.LocalPageTitle
import com.autorunner.ui.components.MessageDialog
import com.autorunner.ui.components.NumberField
import com.autorunner.ui.components.PageScaffold
import com.autorunner.ui.components.PreferenceRow
import com.autorunner.ui.components.SectionCard
import com.autorunner.ui.components.SliderPreference
import com.autorunner.ui.components.StatusPill
import com.autorunner.ui.components.parseNumberInput
import com.autorunner.ui.platform.AutoRunnerPermission
import com.autorunner.ui.platform.OemGuidanceStep
import com.autorunner.ui.platform.PermissionStatus
import com.autorunner.ui.theme.AutoRunnerColors
import com.autorunner.ui.theme.Dimens
import com.autorunner.ui.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Theme
import com.autorunner.core.model.InputMode
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * AutoRunner 的“设置”页 —— 分类入口 + 二级页面。
 *
 * 设置项变多后单页滚动过长，因此这里做了一次信息架构拆分：落地页只渲染
 * [SettingsCategoryMenu]（权限与授权 / 外观 / 执行 / 悬浮窗 / 录制微调 / 手柄模拟 /
 * 存储与关于），点进分类才渲染该分类的控件，具体分派见 [SettingsPageContent]。
 * 所有设置项、ViewModel 调用与写入行为与拆分前完全一致。
 *
 * 布局：
 *  - `compact` / `medium`：单列滚动，内容宽度以 [Dimens.MaxContentWidth] 居中约束；
 *    进入二级页时顶栏显示分类名并带返回按钮。
 *  - `expanded`（平板 / 大屏）：左栏常驻分类菜单（320dp），右侧渲染所选分类，
 *    两个列表各自滚动。
 *
 * 顶栏统一交由 [AutoRunnerTopAppBar] 渲染（大标题 / 紧凑的切换细节见该组件）；
 * 页面自身 `Scaffold` 的 `contentWindowInsets` 置零，滚动连接挂在内容区不滚动的
 * 外层容器上，与录制页 / 脚本编辑器页保持一致。
 *
 * @param viewModel 设置页状态持有者，所有写入都直接落到
 *   `com.autorunner.core.settings.SettingsRepository`。
 * @param windowSize 当前窗口的尺寸类别，用于在单列与「左菜单 + 右详情」之间切换。
 * @param onOpenScriptsDirectoryInfo 打开“脚本目录说明”的回调；为 `null` 时不显示该入口。
 * @param modifier 由外层 Shell 传入的修饰符（一般用于处理内边距）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    windowSize: AutoRunnerWindowSize,
    onOpenScriptsDirectoryInfo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val permissions by viewModel.permissionStatus.collectAsState()
    val message by viewModel.message.collectAsState()

    // `null` 表示落地页（分类列表）。宽屏下左栏常驻，右侧默认展开第一个分类，
    // 因此详情栏不会出现空白。
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    val expanded = windowSize.layoutMode == LayoutMode.EXPANDED
    val detailPage = page ?: SettingsPage.PERMISSIONS
    val showingMenu = !expanded && page == null

    // 窄屏停留在二级分类页时拦截系统返回键：先回到设置分类列表，而不是把
    // 返回事件冒泡给外层 Shell 的全局 BackHandler（后者会直接退回脚本库首页）。
    // Compose 中内层 BackHandler 的回调优先级高于外层，无需手动协调。
    androidx.compose.ui.backhandler.BackHandler(enabled = !expanded && page != null) {
        page = null
    }

    // 与录制 / 编辑器共用 PageScaffold：insets 置零与滚动连接只有一份实现。
    PageScaffold(
        windowSize = windowSize,
        title = if (showingMenu) "设置" else detailPage.title,
        subtitle = if (showingMenu) "权限、外观与执行偏好" else detailPage.summary,
        modifier = modifier,
        navigationIcon = {
            // 宽屏是「菜单 + 详情」双栏常驻，此时 page == null 本就回到菜单，
            // 返回箭头点了没有任何效果，因此只在窄屏显示。
            if (!showingMenu && !expanded) {
                IconButton(onClick = { page = null }) {
                    Icon(
                        imageVector = MiuixIcons.ChevronBackward,
                        contentDescription = "返回设置分类",
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) {
        if (expanded) {
            // 宽屏：左栏分类 + 右侧详情，两个列表各自滚动，滚动事件统一冒泡到
            // 外层容器上的同一个 scrollBehavior。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = Dimens.ScreenPaddingWide,
                        vertical = Dimens.CardSpacing,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
            ) {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .overScrollVertical()
                        .verticalScroll(rememberScrollState()),
                ) {
                    SettingsCategoryMenu(
                        permissionStatus = permissions,
                        selected = detailPage,
                        onSelect = { page = it },
                        header = {
                            MissingPermissionsCard(
                                status = permissions,
                                viewModel = viewModel,
                                ignoreAccessibility = settings.inputMode == InputMode.ROOT &&
                                    viewModel.rootInputAvailable,
                            )
                        },
                    )

                    // 直接放在设置首页（不藏进二级页）：外置显示用到的框架与协作方。
                    TechStackSection()
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .overScrollVertical()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
                ) {
                    CompositionLocalProvider(LocalPageTitle provides detailPage.title) {
                        SettingsPageContent(
                            page = detailPage,
                            settings = settings,
                            permissions = permissions,
                            viewModel = viewModel,
                            onToggleOverlay = viewModel::toggleOverlay,
                            onOpenScriptsDirectoryInfo = onOpenScriptsDirectoryInfo,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = Dimens.MaxContentWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .overScrollVertical()
                        .verticalScroll(rememberScrollState())
                        // 底部留出与屏幕边距等宽的呼吸位：外层 Scaffold 的 padding
                        // 只让内容避开底部 tab，最后一项仍会紧贴 tab 上沿。
                        // 分类落地页与二级页共用本 Column，一次覆盖两种页面。
                        .padding(
                            start = Dimens.ScreenPadding,
                            end = Dimens.ScreenPadding,
                            bottom = Dimens.ScreenPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
                ) {
                    // 顶部留白由 Column 的 spacing 提供，避免与 Scaffold 的 insets 叠加。
                    if (showingMenu) {
                        SettingsCategoryMenu(
                            permissionStatus = permissions,
                            selected = null,
                            onSelect = { page = it },
                            header = {
                                MissingPermissionsCard(
                                    status = permissions,
                                    viewModel = viewModel,
                                    ignoreAccessibility = settings.inputMode == InputMode.ROOT &&
                                        viewModel.rootInputAvailable,
                                )
                            },
                        )

                        // 直接放在设置首页（不藏进二级页）：外置显示用到的框架与协作方。
                        TechStackSection()
                    } else {
                        CompositionLocalProvider(LocalPageTitle provides detailPage.title) {
                            SettingsPageContent(
                                page = detailPage,
                                settings = settings,
                                permissions = permissions,
                                viewModel = viewModel,
                                onToggleOverlay = viewModel::toggleOverlay,
                                onOpenScriptsDirectoryInfo = onOpenScriptsDirectoryInfo,
                            )
                        }
                    }
                }
            }
        }
    }

    MessageDialog(
        message = message,
        onDismiss = viewModel::dismissMessage,
    )
}

/**
 * 渲染一个二级分类的全部控件。
 *
 * 每个分支只是把原本内联在落地页上的区块搬过来，控件、调用与行为完全不变。
 */
@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    settings: AppSettings,
    permissions: PermissionStatus,
    viewModel: SettingsViewModel,
    onToggleOverlay: () -> Unit,
    onOpenScriptsDirectoryInfo: (() -> Unit)?,
) {
    when (page) {
        SettingsPage.PERMISSIONS -> PermissionSection(
            status = permissions,
            settings = settings,
            viewModel = viewModel,
        )

        SettingsPage.APPEARANCE -> AppearanceSection(
            settings = settings,
            onPickTheme = viewModel::setThemeMode,
        )

        SettingsPage.EXECUTION -> {
            RootInputSection(settings = settings, viewModel = viewModel)
            ExecutionDefaultsSection(settings = settings, viewModel = viewModel)
            ExecutionFeedbackSection(settings = settings, viewModel = viewModel)
            PowerSection(settings = settings, viewModel = viewModel)
        }

        SettingsPage.OVERLAY -> FloatingWindowSection(
            settings = settings,
            viewModel = viewModel,
            onToggleOverlay = onToggleOverlay,
        )

        SettingsPage.RECORDING -> RecordingTuningSection(settings = settings, viewModel = viewModel)

        SettingsPage.GAMEPAD -> GamepadSection(settings = settings, viewModel = viewModel)

        SettingsPage.STORAGE -> {
            StorageSection(
                storageLocation = viewModel.storageLocation,
                onOpenScriptsDirectoryInfo = onOpenScriptsDirectoryInfo,
            )
            ResetSection(onReset = viewModel::resetSettings)
            AboutSection()
        }
    }
}

// ---------------------------------------------------------------------------
// 权限与授权（§6.4.2 / §9）
// ---------------------------------------------------------------------------

/**
 * 权限与授权区块。
 *
 * `SYSTEM_ALERT_WINDOW` 属于特殊权限，无法通过运行时权限 API 申请，只能引导用户
 * 前往系统设置手动开启；`BIND_ACCESSIBILITY_SERVICE` 同理。这里为每一项提供入口，
 * 并在厂商 ROM 需要额外步骤时展示 `PermissionController.oemGuidance()` 的说明。
 */
/**
 * 模拟输入的实现方式。
 *
 * 默认走无障碍 `dispatchGesture`；打开开关后由 Magisk / KernelSU 模块里的守护进程以
 * root 执行 `input`，用于无障碍被 ROM 限制或用户不愿开启无障碍的场景。模块未安装时
 * 开关会明确提示「未检测到 Root 桥」，并自动继续使用无障碍。
 */
@Composable
private fun RootInputSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    // 导出结果提示（空串表示导出失败）
    val available = viewModel.rootInputAvailable
    val module = viewModel.rootModuleInstalled
    // 打开开关且未装模块时，自动把内置模块导出到下载目录并提示安装。
    var moduleExport by remember { mutableStateOf<String?>(null) }
    moduleExport?.let { path ->
        MessageDialog(
            message = if (path.isBlank()) {
                "模块导出失败：无法写入下载目录（Android 9 及以下需要存储权限）"
            } else {
                "模块已导出到 $path。请在 Magisk / KernelSU 管理器中安装该 zip 并重启，" +
                    "之后回到这里点「重新检测 Root 授权」。"
            },
            onDismiss = { moduleExport = null },
            title = "安装 Root 模块",
        )
    }
    val enabled = settings.inputMode == InputMode.ROOT
    SectionCard(
        title = "模拟输入方式",
        subtitle = when {
            module -> "已安装 Root Bridge 模块：文件桥注入，最快"
            available -> "已获得 Root 授权：直接调用 su -c input 注入（未装模块）"
            else -> "未检测到 Root：请授予本应用 root 权限，或刷入 Root Bridge 模块"
        },
    ) {
        SwitchPreference(
            title = "使用 Root 注入",
            summary = when {
                enabled && available -> "由 root 执行 input；不依赖无障碍服务"
                enabled && !available -> "已选择但 Root 未就绪，运行时会回落到无障碍"
                else -> "使用无障碍服务 dispatchGesture（默认）"
            },
            checked = enabled,
            endActions = {
                StatusPill(
                    text = if (available) "已就绪" else "未检测到",
                    color = if (available) {
                        AutoRunnerColors.Running
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            },
            onCheckedChange = { on ->
                if (on) {
                    viewModel.refreshRootState()
                    if (!viewModel.rootModuleInstalled) {
                        moduleExport = viewModel.exportRootModule() ?: ""
                    }
                }
                viewModel.setInputMode(if (on) InputMode.ROOT else InputMode.ACCESSIBILITY)
            },
        )
        TextButton(
            text = "导出模块到下载目录",
            onClick = { moduleExport = viewModel.exportRootModule() ?: "" },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            text = "重新检测 Root 授权",
            onClick = { viewModel.refreshRootState() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 缺失权限摘要。
 *
 * 直接显示在设置落地页顶部：缺权限时用户不必先点进「权限与授权」才知道缺什么，
 * 每一行本身就是「去开启」入口（点击即调用对应权限的申请/跳转逻辑）。
 */
@Composable
private fun MissingPermissionsCard(
    status: PermissionStatus,
    viewModel: SettingsViewModel,
    ignoreAccessibility: Boolean = false,
) {
    val missing = status.missingCore()
        .filterNot { ignoreAccessibility && it == AutoRunnerPermission.ACCESSIBILITY }
    if (missing.isEmpty()) return
    SectionCard(
        title = "还缺少必要的权限",
        subtitle = "点击任意一项直接前往开启（共 ${missing.size} 项未授予）",
    ) {
        missing.forEach { permission ->
            PreferenceRow(
                title = permission.title,
                summary = permission.summary,
                icon = permission.icon,
                iconTint = MiuixTheme.colorScheme.error,
                // 整行即入口，不再放一个会被 endActions 挤到截断的药丸。
                onClick = { openPermission(permission, viewModel) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun PermissionSection(
    status: PermissionStatus,
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionCard(
        title = "权限与授权",
        subtitle = if (status.corePermissionsGranted) {
            "核心权限已就绪，可以开始录制与回放"
        } else {
            "还有 ${status.missingCore().size} 项核心权限未授予"
        },
    ) {
        AutoRunnerPermission.entries.forEach { permission ->
            val granted = status.isGranted(permission)
            PreferenceRow(
                title = permission.title,
                summary = permission.summary,
                icon = permission.icon,
                iconTint = if (granted) {
                    AutoRunnerColors.Running
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                statusText = if (granted) "已授予" else "未授予",
                statusColor = if (granted) AutoRunnerColors.Running else MiuixTheme.colorScheme.error,
                onClick = { openPermission(permission, viewModel) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp),
            )
        }

        PreferenceRow(
            title = "忽略电池优化",
            summary = if (status.batteryOptimisationIgnored) {
                "已加入白名单，长时间重复执行不易被系统回收"
            } else {
                "建议加入白名单，避免后台执行被厂商 ROM 冻结"
            },
            statusText = if (status.batteryOptimisationIgnored) "已加入" else "未加入",
            statusColor = if (status.batteryOptimisationIgnored) {
                AutoRunnerColors.Running
            } else {
                AutoRunnerColors.Paused
            },
            onClick = viewModel::requestIgnoreBatteryOptimisations,
        )

        PreferenceRow(
            title = "打开系统应用详情",
            summary = "跳转到本应用的系统设置页；部分 ROM 的无障碍开关隐藏较深",
            onClick = viewModel::openAppDetailsSettings,
        )
    }

    if (status.requiresOemGuidance && !settings.skipOemGuidance) {
        OemGuidanceCard(
            guidance = viewModel.oemGuidance(),
            manufacturer = status.manufacturer,
            onSkip = { viewModel.setSkipOemGuidance(true) },
        )
    }

    if (status.requiresOemGuidance) {
        SwitchPreference(
            checked = settings.skipOemGuidance,
            onCheckedChange = viewModel::setSkipOemGuidance,
            title = "不再提示厂商适配说明",
            summary = "关闭后仍可在「权限与授权」中查看引导步骤",
        )
    }
}

/** 厂商 ROM（MIUI / ColorOS / OriginOS 等）的差异化解锁步骤。 */
@Composable
private fun OemGuidanceCard(
    guidance: List<OemGuidanceStep>,
    manufacturer: String,
    onSkip: () -> Unit,
) {
    SectionCard(
        title = "厂商适配说明",
        subtitle = "检测到 ${manufacturer.ifBlank { "当前" }} 设备，请按下列步骤操作",
    ) {
        if (guidance.isEmpty()) {
            PreferenceRow(
                title = "暂无额外说明",
                summary = "若权限反复被回收，请尝试锁定后台或允许自启动",
            )
        } else {
            guidance.forEachIndexed { index, step ->
                PreferenceRow(
                    title = "${index + 1}. ${step.title}",
                    summary = step.detail,
                )
            }
        }
        PreferenceRow(
            title = "知道了，不再提示",
            summary = "随时可在下方开关中恢复提示",
            onClick = onSkip,
        )
    }
}

/** 把权限点击事件分发到 [SettingsViewModel] 上的对应入口。 */
private fun openPermission(
    permission: AutoRunnerPermission,
    viewModel: SettingsViewModel,
) {
    when (permission) {
        AutoRunnerPermission.ACCESSIBILITY -> viewModel.openAccessibilitySettings()
        AutoRunnerPermission.OVERLAY -> viewModel.requestOverlayPermission()
        AutoRunnerPermission.NOTIFICATION -> viewModel.requestNotificationPermission()
    }
}

// ---------------------------------------------------------------------------
// 外观（§4.4）
// ---------------------------------------------------------------------------

/**
 * 外观：仅浅色 / 深色 / 跟随系统。
 *
 * Monet 动态取色模式与预置种子色已移除；品牌主色（#2655FF）
 * 直接固定在主题里。模式通过 `WindowDropdownPreference` 选择，它把选项列表
 * 渲染在自己的窗口中，因此在设置 Scaffold 内外行为完全一致。
 */
@Composable
private fun AppearanceSection(
    settings: AppSettings,
    onPickTheme: (ThemeMode) -> Unit,
) {
    SectionCard(
        title = "外观",
        subtitle = "仅提供浅色与深色两套主题",
    ) {
        WindowDropdownPreference(
            items = ThemeMode.entries.map { it.displayName },
            selectedIndex = ThemeMode.entries.indexOf(settings.themeMode).coerceAtLeast(0),
            title = "外观模式",
            summary = "跟随系统会在浅色与深色之间自动切换",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Theme,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            onSelectedIndexChange = { index ->
                ThemeMode.entries.getOrNull(index)?.let(onPickTheme)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 默认执行（§6.3）
// ---------------------------------------------------------------------------

/** 新建脚本时默认使用的执行模式、重复次数与循环间隔。 */
@Composable
private fun ExecutionDefaultsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    ExecutionConfigSection(
        config = settings.defaultExecution,
        onModeChange = viewModel::setDefaultMode,
        onRepeatCountChange = viewModel::setDefaultRepeatCount,
        onIntervalChange = viewModel::setDefaultIntervalMs,
        onFailureStrategyChange = viewModel::setFailureStrategy,
        title = "默认执行模式",
    )
}

// ---------------------------------------------------------------------------
// 悬浮窗（§6.4）
// ---------------------------------------------------------------------------

/**
 * 执行期间的面板与通知行为。
 *
 * 这两项原本挂在「悬浮窗」区块里，但它们描述的是“开始执行之后”的行为，因此随
 * 「执行」分类一起呈现（设置项本身与写入路径不变）。
 */
@Composable
private fun ExecutionFeedbackSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionCard(
        title = "执行反馈",
        subtitle = "开始执行后对悬浮面板与通知栏的控制",
    ) {
        SwitchPreference(
            checked = settings.autoCollapsePanel,
            onCheckedChange = viewModel::setAutoCollapsePanel,
            title = "运行后自动收起",
            summary = "开始执行脚本后收起为悬浮球，减少遮挡",
        )
        SwitchPreference(
            checked = settings.executionNotification,
            onCheckedChange = viewModel::setExecutionNotification,
            title = "执行进度通知",
            summary = "通过 autorunner_execution 渠道同步循环进度",
        )
        SwitchPreference(
            checked = settings.executionReminder,
            onCheckedChange = viewModel::setExecutionReminder,
            title = "运行状态提醒",
            summary = "开始、暂停、循环进度等以悬浮小提示展示，已自动限频，不会刷屏",
        )
    }
}

/** 悬浮控制面板的开关与球位置状态。 */
@Composable
private fun FloatingWindowSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onToggleOverlay: () -> Unit,
) {
    // 直接订阅 OverlayManager 的状态流：悬浮窗显隐 / 球所在半屏变化会自动触发重组。
    val overlayState by viewModel.overlayState.collectAsState()
    val visible = overlayState.visible
    val edge = overlayState.dockedEdge

    SectionCard(
        title = "悬浮窗",
        subtitle = "AutoRunnerOverlayService 以前台服务托管控制面板",
    ) {
        SwitchPreference(
            checked = settings.showFloatingBallOnStart,
            onCheckedChange = viewModel::setShowFloatingBallOnStart,
            title = "启动时显示悬浮球",
            summary = "应用启动后自动挂载可自由拖动的悬浮球",
        )
        SwitchPreference(
            checked = visible,
            onCheckedChange = { onToggleOverlay() },
            title = "显示悬浮窗",
            summary = "在其他应用上层显示悬浮控制面板，可拖动到任意位置",
            startAction = {
                Icon(
                    imageVector = if (visible) MiuixIcons.Show else MiuixIcons.Hide,
                    contentDescription = null,
                    tint = if (visible) {
                        AutoRunnerColors.Running
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            },
        )
        PreferenceRow(
            title = "球所在半屏",
            summary = "决定迷你菜单的展开方向，拖动到任意位置后自动更新",
            statusText = if (edge == DockEdge.LEFT) "左侧" else "右侧",
            statusColor = MiuixTheme.colorScheme.secondaryContainer,
            statusContentColor = MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ---------------------------------------------------------------------------
// 录制微调（§6.1.1）
// ---------------------------------------------------------------------------

/** 手势分类器参数：抖动阈值、长按判定、默认延迟与坐标存储方式。 */
@Composable
private fun RecordingTuningSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionCard(
        title = "录制微调",
        subtitle = "影响点击 / 长按 / 滑动的归类结果，改动后立即生效",
    ) {
        SliderPreference(
            title = "点击抖动阈值",
            value = settings.recording.tapSlopPx,
            onValueChange = viewModel::setTapSlop,
            summary = "位移小于该值的按压视为点击，超出则识别为滑动",
            valueRange = 1f..100f,
            valueLabel = { "${it.toInt()} px" },
        )
        SliderPreference(
            title = "长按判定阈值",
            value = settings.recording.longPressThresholdMs.toFloat(),
            onValueChange = { viewModel.setLongPressThreshold(it.toLong()) },
            summary = "按压超过该时长记录为 longPress",
            valueRange = 100f..2000f,
            valueLabel = { "${it.toLong()} ms" },
        )
        SliderPreference(
            title = "默认动作延迟",
            value = settings.recording.defaultDelayMs.toFloat(),
            onValueChange = { viewModel.setDefaultActionDelay(it.toLong()) },
            summary = "每个动作之后写入的等待时间，可在编辑器中单独调整",
            valueRange = 0f..2000f,
            valueLabel = { "${it.toLong()} ms" },
        )
        SwitchPreference(
            checked = settings.recording.captureMultiTouch,
            onCheckedChange = viewModel::setCaptureMultiTouch,
            title = "捕获多点触控",
            summary = "记录双指缩放、双指滚动等手势（multiTouch 动作）",
        )
        SwitchPreference(
            checked = settings.recording.normaliseCoordinates,
            onCheckedChange = viewModel::setNormaliseCoordinates,
            title = "使用百分比坐标",
            summary = "以 0.0~1.0 存储坐标，便于在不同分辨率设备间复用脚本",
        )
    }
}

// ---------------------------------------------------------------------------
// 执行与功耗（§6.3.4 / §11）
// ---------------------------------------------------------------------------

/** 长时间重复执行时的功耗与反馈选项。 */
@Composable
private fun PowerSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionCard(
        title = "执行与功耗",
        subtitle = "循环间隔使用协程 delay()，不会忙等待占用 CPU",
    ) {
        SwitchPreference(
            checked = settings.keepScreenOnWhileRunning,
            onCheckedChange = viewModel::setKeepScreenOn,
            title = "运行时保持屏幕常亮",
            summary = "避免息屏导致手势回放被系统拦截",
        )
        SwitchPreference(
            checked = settings.hapticFeedback,
            onCheckedChange = viewModel::setHapticFeedback,
            title = "动作震动反馈",
            summary = "每次动作边界触发轻微震动，方便调试脚本",
        )
    }
}

// ---------------------------------------------------------------------------
// 手柄模拟（§7）
// ---------------------------------------------------------------------------

/**
 * 手柄模拟（§7）——**本机输入**。
 *
 * AutoRunner 不会伪装成连接到另一台机器的蓝牙手柄：`gamepad` 动作是以触摸的
 * 形式注入到*本*设备上、落在用户为该按键映射的位置，这正是带虚拟手柄盘的
 * 手机游戏真正需要的。因此每个按键都存储一个屏幕坐标，用「拾取」动作获取
 * 或手动输入。
 */
@Composable
private fun GamepadSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    val mappings = settings.gamepadMappingsFor(settings.gamepadMode)
    val connected = viewModel.gamepadConnected
    var editingButton by remember { mutableStateOf<GamepadButton?>(null) }
    var mappingDialogVisible by remember { mutableStateOf(false) }
    var editingStick by remember { mutableStateOf(false) }

    SectionCard(
        title = "手柄模拟（本机输入）",
        subtitle = "把脚本中的 gamepad 按键映射为本机触摸，直接作用于当前设备上的游戏",
    ) {
        SwitchPreference(
            checked = settings.gamepadEnabled,
            onCheckedChange = viewModel::setGamepadEnabled,
            title = "启用手柄模拟",
            summary = if (connected) {
                "执行到 gamepad 动作时，在本机对应位置注入触摸"
            } else {
                "需要无障碍服务已连接（触摸注入依赖它）"
            },
        )

        // 与脚本编辑器共用同一个标定入口：入口行 + 确认弹窗只有一份实现。
        GamepadCalibrationEntry(
            mode = settings.gamepadMode,
            onGamepadCalibration = { viewModel.armGamepadCalibration() },
            summary = if (mappings.calibrated) {
                "「${settings.gamepadMode.displayName}」已在本设备完成标定；编辑 / 新增脚本时不再重复引导"
            } else {
                "退到后台后把 Dpad、ABXY 等按键拖到真实按钮上（每种手柄类型各自标定一次）"
            },
            statusText = if (mappings.calibrated) "已标定" else "未标定",
            statusColor = if (mappings.calibrated) AutoRunnerColors.Running else MiuixTheme.colorScheme.outline,
            iconTint = if (mappings.calibrated) {
                AutoRunnerColors.Running
            } else {
                MiuixTheme.colorScheme.primary
            },
        )

        PreferenceRow(
            title = "已映射按键",
            summary = "共 ${mappings.configuredCount} / ${GamepadButton.entries.size} 个按键已设置屏幕位置",
            statusText = if (mappings.configuredCount == 0) "未配置" else "${mappings.configuredCount} 个",
            statusColor = if (mappings.configuredCount == 0) {
                MiuixTheme.colorScheme.outline
            } else {
                AutoRunnerColors.Running
            },
        )

        OverlayDropdownPreference(
            items = GamepadMode.entries.map { it.displayName },
            selectedIndex = GamepadMode.entries.indexOf(settings.gamepadMode),
            title = "手柄类型",
            summary = "切换下方按键命名与标定数据；每种类型各自保存一份",
            onSelectedIndexChange = { index ->
                GamepadMode.entries.getOrNull(index)?.let(viewModel::setGamepadMode)
            },
        )

        // ------------------------------------------------------ 虚拟摇杆
        PreferenceRow(
            title = "虚拟摇杆中心",
            summary = if (mappings.stickCenterConfigured) {
                "(${mappings.stickCenterX.toInt()}, ${mappings.stickCenterY.toInt()})"
            } else {
                "未设置：脚本中的 stick 动作以它为拖动起点"
            },
            icon = MiuixIcons.Location,
            iconTint = if (mappings.stickCenterConfigured) {
                AutoRunnerColors.Running
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            endActions = {
                TextButton(
                    text = "拾取",
                    onClick = { viewModel.pickGamepadPoint(null) },
                )
            },
            onClick = { editingStick = true },
        )

        SliderPreference(
            title = "摇杆半径",
            value = mappings.stickRadius,
            onValueChange = viewModel::setStickRadius,
            summary = "摇杆满偏时拖动的像素距离",
            valueRange = 40f..600f,
            valueLabel = { "${it.toInt()} px" },
        )

        // ------------------------------------------------------------ 按键
        GamepadButton.entries.forEach { button ->
            val mapping = mappings.buttons.firstOrNull { it.button == button }
            val configured = mapping?.configured == true
            PreferenceRow(
                title = button.labelFor(settings.gamepadMode),
                summary = if (mapping != null && configured) {
                    "(${mapping.x.toInt()}, ${mapping.y.toInt()}) · ${mapping.durationMs}ms"
                } else {
                    "未映射：脚本中的该按键会被跳过"
                },
                icon = MiuixIcons.Location,
                iconTint = if (configured) {
                    AutoRunnerColors.Running
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                statusText = if (configured) "已映射" else "未映射",
                statusColor = if (configured) AutoRunnerColors.Running else MiuixTheme.colorScheme.outline,
                onClick = {
                    editingButton = button
                    mappingDialogVisible = true
                },
                insideMargin = Dimens.CompactRowPadding,
            )
        }

        if (mappings.configuredCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                TextButton(
                    text = "清空全部映射",
                    onClick = viewModel::clearAllGamepadMappings,
                )
            }
        }
    }

    GamepadButtonMappingDialog(
        show = mappingDialogVisible,
        button = editingButton,
        current = editingButton?.let { button -> mappings.buttons.firstOrNull { it.button == button } },
        label = editingButton?.labelFor(settings.gamepadMode).orEmpty(),
        onPick = { onPicked -> editingButton?.let { viewModel.pickGamepadPoint(it, onPicked) } },
        onSave = { x, y, duration ->
            editingButton?.let { viewModel.setGamepadMapping(it, x, y, duration) }
            mappingDialogVisible = false
        },
        onClear = {
            editingButton?.let(viewModel::clearGamepadMapping)
            mappingDialogVisible = false
        },
        onDismiss = { mappingDialogVisible = false },
        onDismissFinished = { editingButton = null },
    )

    StickCenterDialog(
        show = editingStick,
        currentX = mappings.stickCenterX,
        currentY = mappings.stickCenterY,
        configured = mappings.stickCenterConfigured,
        onPick = { onPicked -> viewModel.pickGamepadPoint(null, onPicked) },
        onSave = { x, y ->
            viewModel.setStickCenter(x, y)
            editingStick = false
        },
        onDismiss = { editingStick = false },
    )
}

/** 单个按键位置的编辑器；数值为本设备的绝对像素。 */
@Composable
private fun GamepadButtonMappingDialog(
    show: Boolean,
    button: GamepadButton?,
    current: GamepadButtonMapping?,
    label: String,
    onPick: ((Float, Float) -> Unit) -> Unit,
    onSave: (Float, Float, Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    // 按钮为 null 时（刚进入页面 / 退场动画已结束）不渲染任何内容。
    if (button == null) return
    // 每次打开都按该按键当前的值重新初始化输入框（关闭时不保留上次未保存的输入）。
    key(button, show) {
        var x by remember { mutableStateOf((current?.x ?: 0f).toInt().toString()) }
        var y by remember { mutableStateOf((current?.y ?: 0f).toInt().toString()) }
        var duration by remember { mutableStateOf((current?.durationMs ?: 60L).toString()) }

        val parsedX = parseNumberInput(x)
        val parsedY = parseNumberInput(y)
        val parsedDuration = parseNumberInput(duration)
        val valid = parsedX != null && parsedY != null && parsedDuration != null

        FormDialog(
            show = show,
            title = "映射按键：$label",
            summary = "使用「拾取」在屏幕上直接点击该按钮，或手动输入像素坐标",
            confirmEnabled = valid,
            onConfirm = {
                if (parsedX != null && parsedY != null && parsedDuration != null) {
                    onSave(parsedX, parsedY, parsedDuration.toLong())
                }
            },
            onDismiss = onDismiss,
            onDismissFinished = onDismissFinished,
        ) {
            NumberField(
                label = "X 坐标",
                value = x,
                onValueChange = { x = it },
                modifier = Modifier.fillMaxWidth(),
                isError = parsedX == null,
                errorMessage = if (parsedX == null) "请输入 X 坐标" else null,
            )
            NumberField(
                label = "Y 坐标",
                value = y,
                onValueChange = { y = it },
                modifier = Modifier.fillMaxWidth(),
                isError = parsedY == null,
                errorMessage = if (parsedY == null) "请输入 Y 坐标" else null,
            )
            NumberField(
                label = "按压时长 (ms)",
                value = duration,
                onValueChange = { duration = it },
                modifier = Modifier.fillMaxWidth(),
                isError = parsedDuration == null,
                errorMessage = if (parsedDuration == null) "请输入按压时长" else null,
            )
            TextButton(
                text = "拾取坐标（点击屏幕上的按钮位置）",
                onClick = {
                    onPick { px, py ->
                        x = px.toInt().toString()
                        y = py.toInt().toString()
                    }
                },
            )
            TextButton(text = "清除该按键映射", onClick = onClear)
        }
    }
}

/** 虚拟摇杆中心点的编辑器。 */
@Composable
private fun StickCenterDialog(
    show: Boolean,
    currentX: Float,
    currentY: Float,
    configured: Boolean,
    onPick: ((Float, Float) -> Unit) -> Unit,
    onSave: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每次打开都以当前设置重新初始化输入框，关闭时不保留未保存的输入。
    key(show) {
        var x by remember { mutableStateOf(currentX.toInt().toString()) }
        var y by remember { mutableStateOf(currentY.toInt().toString()) }

        val parsedX = parseNumberInput(x)
        val parsedY = parseNumberInput(y)

        FormDialog(
            show = show,
            title = "虚拟摇杆中心",
            summary = if (configured) "当前 (${currentX.toInt()}, ${currentY.toInt()})" else "尚未设置",
            confirmEnabled = parsedX != null && parsedY != null,
            onConfirm = {
                if (parsedX != null && parsedY != null) onSave(parsedX, parsedY)
            },
            onDismiss = onDismiss,
        ) {
            NumberField(
                label = "X 坐标",
                value = x,
                onValueChange = { x = it },
                modifier = Modifier.fillMaxWidth(),
                isError = parsedX == null,
                errorMessage = if (parsedX == null) "请输入 X 坐标" else null,
            )
            NumberField(
                label = "Y 坐标",
                value = y,
                onValueChange = { y = it },
                modifier = Modifier.fillMaxWidth(),
                isError = parsedY == null,
                errorMessage = if (parsedY == null) "请输入 Y 坐标" else null,
            )
            TextButton(
                text = "拾取坐标（点击摇杆中心）",
                onClick = {
                    onPick { px, py ->
                        x = px.toInt().toString()
                        y = py.toInt().toString()
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 存储（§6.2）
// ---------------------------------------------------------------------------

/** 脚本文件的存放位置与导出格式说明。 */
@Composable
private fun StorageSection(
    storageLocation: String,
    onOpenScriptsDirectoryInfo: (() -> Unit)?,
) {
    SectionCard(
        title = "存储",
        subtitle = "脚本以 JSON 形式保存为 .arscript 文件",
    ) {
        PreferenceRow(
            title = "脚本目录",
            summary = storageLocation,
            icon = MiuixIcons.Folder,
        )
        PreferenceRow(
            title = "文件格式",
            summary = "根级包含 info / execution / flow 三段，可读且易于扩展",
            statusText = ".arscript",
            statusColor = MiuixTheme.colorScheme.secondaryContainer,
            statusContentColor = MiuixTheme.colorScheme.onSecondaryContainer,
        )
        if (onOpenScriptsDirectoryInfo != null) {
            PreferenceRow(
                title = "导出与导入说明",
                summary = "如何把脚本迁移到另一台设备或备份到本地",
                onClick = onOpenScriptsDirectoryInfo,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 重置与关于
// ---------------------------------------------------------------------------

/** 一键恢复出厂设置。 */
@Composable
private fun ResetSection(onReset: () -> Unit) {
    SectionCard(title = "重置", subtitle = "仅清除本机偏好，不会删除任何脚本文件") {
        PreferenceRow(
            title = "恢复默认设置",
            summary = "外观、执行、录制与手柄选项全部回到初始值",
            icon = MiuixIcons.Refresh,
            iconTint = MiuixTheme.colorScheme.error,
            onClick = onReset,
        )
    }
}

/**
 * 技术栈与致谢。
 *
 * 把用到的框架与协作方外置显示在设置页里：便于排查问题时确认版本来源，
 * 也标注本项目的 AI 协作方。
 */
@Composable
private fun TechStackSection() {
    SectionCard(
        title = "技术栈与致谢",
        subtitle = "本项目使用到的框架，以及参与开发协作的 AI",
    ) {
        val entries = listOf(
            "Kotlin Multiplatform" to "跨平台核心：脚本模型、执行器、序列化与存储",
            "Compose Multiplatform" to "全部界面（Android 与桌面共用一套 UI 代码）",
            "MIUIX (top.yukonga.miuix.kmp 0.9.4)" to "控件与主题：偏好项、弹层、大标题顶栏、悬浮组件",
            "kotlinx.serialization / coroutines" to ".arscript JSON 编解码与协程调度",
            "AndroidX (activity / lifecycle / core-ktx)" to "SAF 文档选择、生命周期与状态收集",
            "Android AccessibilityService" to "触摸采集与手势回放（dispatchGesture）",
            "Magisk / KernelSU 模块" to "Root 场景：自动开启无障碍、默认授权与 root 输入注入",
        )
        entries.forEach { (name, usage) ->
            PreferenceRow(
                title = name,
                summary = usage,
                insideMargin = Dimens.CompactRowPadding,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        }
        PreferenceRow(
            title = "开发协作：DeepSeek",
            summary = "本项目由 DeepSeek（deepseek-flash 模型，运行于 DeepSeek Harness）参与实现、" +
                "调试与真机验证",
        )
        PreferenceRow(
            title = "作者：HegeKen",
            summary = "AutoRunner · 无障碍自动化录制与回放",
        )
    }
}

/** 应用信息：名称、版本、包名与关键组件类名。 */
@Composable
private fun AboutSection() {
    SectionCard(title = "关于") {
        PreferenceRow(
            title = "AutoRunner",
            summary = "基于 Kotlin Multiplatform 与 MIUIX 的原生安卓自动化录制工具",
            icon = MiuixIcons.Info,
            statusText = BuildInfo.VERSION_NAME,
            statusColor = MiuixTheme.colorScheme.secondaryContainer,
            statusContentColor = MiuixTheme.colorScheme.onSecondaryContainer,
        )
        AboutRow("应用 ID", "cn.helilab.autorunner")
        AboutRow("脚本扩展名", ".arscript")
        AboutRow("无障碍服务", "AutoRunnerAccessibilityService")
        AboutRow("悬浮窗服务", "AutoRunnerOverlayService")
        AboutRow("执行引擎", "AutoRunnerScriptExecutor")
        AboutRow("前台通知渠道", "autorunner_execution")
    }
}

/** “关于”区块中的一行只读键值对。 */
@Composable
private fun AboutRow(label: String, value: String) {
    PreferenceRow(
        title = label,
        summary = value,
        insideMargin = Dimens.CompactRowPadding,
    )
}
